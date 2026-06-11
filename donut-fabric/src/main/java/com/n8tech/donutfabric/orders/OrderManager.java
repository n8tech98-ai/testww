package com.n8tech.donutfabric.orders;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.database.DatabaseManager;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.utils.ModLogger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * OrderManager handles DonutSMP-style buy orders.
 *
 * Flow:
 *   1. Buyer creates an order via /orders → GUI.  Their balance is locked (deducted + tax held).
 *   2. Any player can "fulfill" the order by delivering items.
 *      They receive the locked payment instantly.
 *   3. Orders expire after configurable hours; remaining funds are refunded to the buyer.
 *
 * All DB reads/writes run on DonutFabric.ASYNC_EXECUTOR.
 * The in-memory map is the source of truth while the server is running.
 */
public class OrderManager {

    private final DatabaseManager  db;
    private final ConfigManager    config;
    private final EconomyManager   economy;

    // In-memory store: order id → Order
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    // Config
    private int    maxPerPlayer;
    private double taxRate;
    private long   expiryMillis;
    private double minPrice;
    private double maxPrice;

    public OrderManager(DatabaseManager db, ConfigManager config, EconomyManager economy) {
        this.db      = db;
        this.config  = config;
        this.economy = economy;
    }

    // -----------------------------------------------------------------------
    //  Initialisation
    // -----------------------------------------------------------------------

    public void init() {
        maxPerPlayer  = config.getInt("orders.max-per-player", 10);
        taxRate       = config.getDouble("orders.tax-rate",   0.08);
        expiryMillis  = config.getInt("orders.expiry-hours",  72) * 3600_000L;
        minPrice      = config.getDouble("orders.min-price",  1.0);
        maxPrice      = config.getDouble("orders.max-price",  10_000_000.0);

        // Load existing orders from DB asynchronously
        DonutFabric.ASYNC_EXECUTOR.submit(this::loadAllFromDB);
        ModLogger.info("OrderManager ready (max/player=" + maxPerPlayer + ", tax=" + (taxRate*100) + "%)");
    }

    // -----------------------------------------------------------------------
    //  CRUD
    // -----------------------------------------------------------------------

    /**
     * Creates a new buy order.
     * @return CompletableFuture<String> — order id on success, null on failure (with reason)
     */
    public CompletableFuture<OrderResult> createOrder(UUID buyerUuid, String buyerName,
                                                       String itemId, int quantity, double priceEach) {
        return CompletableFuture.supplyAsync(() -> {
            // Validation
            if (priceEach < minPrice)  return OrderResult.fail("Price too low (min " + minPrice + ")");
            if (priceEach > maxPrice)  return OrderResult.fail("Price too high (max " + maxPrice + ")");
            if (quantity  <= 0)        return OrderResult.fail("Quantity must be positive.");
            if (quantity  > 65536)     return OrderResult.fail("Max 65536 quantity per order.");

            long activeCount = orders.values().stream()
                    .filter(o -> o.getOwnerUuid().equals(buyerUuid) && o.isActive())
                    .count();
            if (activeCount >= maxPerPlayer) {
                return OrderResult.fail("You already have " + maxPerPlayer + " active orders.");
            }

            double totalCost = priceEach * quantity;
            double tax       = totalCost * taxRate;
            double required  = totalCost + tax;

            if (!economy.has(buyerUuid, required)) {
                return OrderResult.fail("Insufficient balance. Need: " + economy.format(required));
            }

            // Deduct from buyer immediately (escrow)
            economy.withdraw(buyerUuid, required);

            // Create order
            String id  = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            long   now = System.currentTimeMillis();
            Order order = new Order(id, buyerUuid, buyerName, itemId.toLowerCase(),
                    quantity, priceEach, tax, now, now + expiryMillis);

            orders.put(id, order);
            persistOrder(order);

            db.logTransaction(buyerUuid, null, "ORDER_CREATE", required,
                    "Order " + id + " for " + quantity + "x " + itemId);

            return OrderResult.success(id);
        }, DonutFabric.ASYNC_EXECUTOR);
    }

    /**
     * Fulfills an order (or part of it) by delivering {@code deliveredQty} items.
     * The fulfiller receives payment immediately.
     */
    public CompletableFuture<OrderResult> fulfillOrder(String orderId, UUID fulfillerUuid,
                                                        String fulfillerName, int deliveredQty) {
        return CompletableFuture.supplyAsync(() -> {
            Order order = orders.get(orderId);
            if (order == null)     return OrderResult.fail("Order not found.");
            if (!order.isActive()) return OrderResult.fail("Order is not active.");
            if (order.isExpired()) {
                expireOrder(order);
                return OrderResult.fail("Order has expired.");
            }
            if (order.getOwnerUuid().equals(fulfillerUuid)) {
                return OrderResult.fail("You cannot fulfill your own order.");
            }
            if (deliveredQty <= 0 || deliveredQty > order.getRemaining()) {
                return OrderResult.fail("Invalid quantity. Remaining: " + order.getRemaining());
            }

            double payment = order.getPriceEach() * deliveredQty;

            // Pay the fulfiller
            economy.deposit(fulfillerUuid, payment);

            // Update order fill count
            order.setFilled(order.getFilled() + deliveredQty);

            persistOrder(order);

            db.logTransaction(order.getOwnerUuid(), fulfillerUuid, "ORDER_FILL",
                    payment, "Order " + orderId + " filled " + deliveredQty + "x");

            String statusMsg = order.getStatus() == Order.Status.FILLED
                    ? "Order fully filled!"
                    : "Filled " + deliveredQty + "x. Remaining: " + order.getRemaining();

            return OrderResult.success(statusMsg + " | Earned: " + economy.format(payment));
        }, DonutFabric.ASYNC_EXECUTOR);
    }

    /**
     * Cancels an order and refunds the remaining locked funds (minus tax already paid).
     */
    public CompletableFuture<OrderResult> cancelOrder(String orderId, UUID requesterUuid) {
        return CompletableFuture.supplyAsync(() -> {
            Order order = orders.get(orderId);
            if (order == null) return OrderResult.fail("Order not found.");
            if (!order.getOwnerUuid().equals(requesterUuid)) {
                return OrderResult.fail("That is not your order.");
            }
            if (!order.isActive()) return OrderResult.fail("Order cannot be cancelled.");

            // Refund remaining escrow (no refund on tax)
            double refund = order.getPriceEach() * order.getRemaining();
            economy.deposit(requesterUuid, refund);
            order.setStatus(Order.Status.CANCELLED);
            persistOrder(order);

            db.logTransaction(null, requesterUuid, "ORDER_CANCEL", refund,
                    "Cancelled order " + orderId);

            return OrderResult.success("Order cancelled. Refunded: " + economy.format(refund));
        }, DonutFabric.ASYNC_EXECUTOR);
    }

    // -----------------------------------------------------------------------
    //  Queries (used by GUI)
    // -----------------------------------------------------------------------

    /** Returns all active orders, optionally filtered by item id. */
    public List<Order> getActiveOrders(String itemFilter) {
        return orders.values().stream()
                .filter(Order::isActive)
                .filter(o -> itemFilter == null || o.getItemId().contains(itemFilter.toLowerCase()))
                .sorted(Comparator.comparingDouble(Order::getPriceEach).reversed())
                .collect(Collectors.toList());
    }

    public List<Order> getPlayerOrders(UUID uuid) {
        return orders.values().stream()
                .filter(o -> o.getOwnerUuid().equals(uuid))
                .sorted(Comparator.comparingLong(Order::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    public Order getOrder(String id) {
        return orders.get(id);
    }

    // -----------------------------------------------------------------------
    //  Expiry sweep (called by scheduled task)
    // -----------------------------------------------------------------------

    public void expireOldOrders() {
        orders.values().stream()
                .filter(o -> o.isActive() && o.isExpired())
                .forEach(this::expireOrder);
    }

    private void expireOrder(Order order) {
        double refund = order.getPriceEach() * order.getRemaining();
        economy.deposit(order.getOwnerUuid(), refund);
        order.setStatus(Order.Status.EXPIRED);
        persistOrder(order);
        ModLogger.info("Expired order " + order.getId() + ", refunded " + economy.format(refund));
    }

    // -----------------------------------------------------------------------
    //  Persistence
    // -----------------------------------------------------------------------

    public void saveAll() {
        orders.values().forEach(this::persistOrder);
    }

    private void persistOrder(Order o) {
        String sql = """
            INSERT INTO orders
                (id, owner_uuid, owner_name, item_id, quantity, filled, price_each, tax_paid,
                 created_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                filled = excluded.filled, status = excluded.status
            """;
        try (PreparedStatement ps = db.getSQLite().prepareStatement(sql)) {
            ps.setString(1,  o.getId());
            ps.setString(2,  o.getOwnerUuid().toString());
            ps.setString(3,  o.getOwnerName());
            ps.setString(4,  o.getItemId());
            ps.setInt(5,     o.getQuantity());
            ps.setInt(6,     o.getFilled());
            ps.setDouble(7,  o.getPriceEach());
            ps.setDouble(8,  o.getTaxPaid());
            ps.setLong(9,    o.getCreatedAt());
            ps.setLong(10,   o.getExpiresAt());
            ps.setString(11, o.getStatus().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            ModLogger.warn("persistOrder error: " + e.getMessage());
        }
    }

    private void loadAllFromDB() {
        String sql = """
            SELECT id, owner_uuid, owner_name, item_id, quantity, filled,
                   price_each, tax_paid, created_at, expires_at, status
            FROM orders WHERE status IN ('OPEN','PARTIAL')
            """;
        try (PreparedStatement ps = db.getSQLite().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int loaded = 0;
            while (rs.next()) {
                Order o = new Order(
                        rs.getString("id"),
                        UUID.fromString(rs.getString("owner_uuid")),
                        rs.getString("owner_name"),
                        rs.getString("item_id"),
                        rs.getInt("quantity"),
                        rs.getInt("filled"),
                        rs.getDouble("price_each"),
                        rs.getDouble("tax_paid"),
                        rs.getLong("created_at"),
                        rs.getLong("expires_at"),
                        Order.Status.valueOf(rs.getString("status"))
                );
                orders.put(o.getId(), o);
                loaded++;
            }
            ModLogger.info("Loaded " + loaded + " active orders from DB.");
        } catch (SQLException e) {
            ModLogger.warn("loadAllFromDB error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Result wrapper
    // -----------------------------------------------------------------------

    public record OrderResult(boolean success, String message) {
        static OrderResult success(String msg) { return new OrderResult(true,  msg); }
        static OrderResult fail   (String msg) { return new OrderResult(false, msg); }
    }
}
