package com.n8tech.donutfabric.auctionhouse;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.database.DatabaseManager;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.utils.ModLogger;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * DonutSMP Auction House manager.
 *
 * Sellers list items for a fixed price + listing tax.
 * Buyers click and instantly purchase — money goes to seller.
 * Expired items are returned to seller's inbox (implemented as chat notification here;
 * full mailbox system can be added as extension).
 */
public class AuctionHouseManager {

    private final DatabaseManager db;
    private final ConfigManager   config;
    private final EconomyManager  economy;

    private final Map<String, AuctionListing> listings = new ConcurrentHashMap<>();

    // Config
    private int    maxPerPlayer;
    private double listingTax;
    private long   expiryMillis;
    private double minPrice;
    private double maxPrice;

    public AuctionHouseManager(DatabaseManager db, ConfigManager config, EconomyManager economy) {
        this.db      = db;
        this.config  = config;
        this.economy = economy;
    }

    // -----------------------------------------------------------------------
    //  Initialisation
    // -----------------------------------------------------------------------

    public void init() {
        maxPerPlayer = config.getInt("auction-house.max-per-player", 15);
        listingTax   = config.getDouble("auction-house.listing-tax", 0.05);
        expiryMillis = config.getInt("auction-house.expiry-hours",   48) * 3600_000L;
        minPrice     = config.getDouble("auction-house.min-price",   1.0);
        maxPrice     = config.getDouble("auction-house.max-price",   100_000_000.0);

        DonutFabric.ASYNC_EXECUTOR.submit(this::loadFromDB);
        ModLogger.info("AuctionHouseManager ready.");
    }

    // -----------------------------------------------------------------------
    //  List item
    // -----------------------------------------------------------------------

    public CompletableFuture<AHResult> listItem(UUID sellerUuid, String sellerName,
                                                  ItemStack stack, double price) {
        return CompletableFuture.supplyAsync(() -> {
            if (price < minPrice) return AHResult.fail("Minimum price: " + economy.format(minPrice));
            if (price > maxPrice) return AHResult.fail("Maximum price: " + economy.format(maxPrice));
            if (stack.isEmpty())  return AHResult.fail("Cannot list empty item.");

            long active = listings.values().stream()
                    .filter(l -> l.getSellerUuid().equals(sellerUuid) && l.isActive())
                    .count();
            if (active >= maxPerPlayer) {
                return AHResult.fail("Listing limit reached (" + maxPerPlayer + ").");
            }

            double tax = price * listingTax;
            if (!economy.has(sellerUuid, tax)) {
                return AHResult.fail("Need " + economy.format(tax) + " for listing tax.");
            }
            economy.withdraw(sellerUuid, tax);

            String itemName = getItemDisplayName(stack);
            byte[] nbt      = serializeItem(stack);
            if (nbt == null) return AHResult.fail("Failed to serialize item.");

            String id  = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            long   now = System.currentTimeMillis();

            AuctionListing listing = new AuctionListing(
                    id, sellerUuid, sellerName, stack, nbt,
                    itemName, stack.getCount(), price, now, now + expiryMillis);

            listings.put(id, listing);
            persistListing(listing);

            db.logTransaction(sellerUuid, null, "AH_LIST", tax,
                    "Listed " + stack.getCount() + "x " + itemName + " for " + economy.format(price));

            return AHResult.success("Listed! ID: " + id + " | Tax paid: " + economy.format(tax));
        }, DonutFabric.ASYNC_EXECUTOR);
    }

    // -----------------------------------------------------------------------
    //  Buy item
    // -----------------------------------------------------------------------

    public CompletableFuture<AHResult> buyListing(String listingId, UUID buyerUuid,
                                                    String buyerName) {
        return CompletableFuture.supplyAsync(() -> {
            AuctionListing listing = listings.get(listingId);
            if (listing == null)           return AHResult.fail("Listing not found.");
            if (!listing.isActive())       return AHResult.fail("Listing is no longer active.");
            if (listing.isExpired()) {
                expireListing(listing);
                return AHResult.fail("Listing has expired.");
            }
            if (listing.getSellerUuid().equals(buyerUuid)) {
                return AHResult.fail("You cannot buy your own listing.");
            }
            if (!economy.has(buyerUuid, listing.getPrice())) {
                return AHResult.fail("Insufficient funds. Need: " + economy.format(listing.getPrice()));
            }

            // Transfer money
            economy.withdraw(buyerUuid, listing.getPrice());
            economy.deposit(listing.getSellerUuid(), listing.getPrice());

            // Mark sold
            listing.setStatus(AuctionListing.Status.SOLD);
            listing.setBuyerUuid(buyerUuid);
            listing.setBuyerName(buyerName);
            persistListing(listing);

            db.logTransaction(buyerUuid, listing.getSellerUuid(), "AH_BUY",
                    listing.getPrice(), "Bought listing " + listingId);

            // Return item stack (caller must give to player)
            ItemStack item = deserializeItem(listing.getItemNbt());
            listing.setItem(item != null ? item : ItemStack.EMPTY);

            return AHResult.success("Purchased " + listing.getItemName() +
                    " for " + economy.format(listing.getPrice()),
                    item != null ? item : ItemStack.EMPTY);
        }, DonutFabric.ASYNC_EXECUTOR);
    }

    // -----------------------------------------------------------------------
    //  Queries
    // -----------------------------------------------------------------------

    public List<AuctionListing> getActiveListings(String search, SortMode sort) {
        return listings.values().stream()
                .filter(AuctionListing::isActive)
                .filter(l -> search == null || l.getItemName().toLowerCase()
                                 .contains(search.toLowerCase()))
                .sorted(getComparator(sort))
                .collect(Collectors.toList());
    }

    public List<AuctionListing> getPlayerListings(UUID uuid) {
        return listings.values().stream()
                .filter(l -> l.getSellerUuid().equals(uuid))
                .sorted(Comparator.comparingLong(AuctionListing::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    public AuctionListing getListing(String id) { return listings.get(id); }

    public enum SortMode { PRICE_ASC, PRICE_DESC, NEWEST, OLDEST }

    private Comparator<AuctionListing> getComparator(SortMode mode) {
        if (mode == null) mode = SortMode.PRICE_ASC;
        return switch (mode) {
            case PRICE_ASC  -> Comparator.comparingDouble(AuctionListing::getPrice);
            case PRICE_DESC -> Comparator.comparingDouble(AuctionListing::getPrice).reversed();
            case OLDEST     -> Comparator.comparingLong(AuctionListing::getCreatedAt);
            default         -> Comparator.comparingLong(AuctionListing::getCreatedAt).reversed();
        };
    }

    // -----------------------------------------------------------------------
    //  Expiry sweep
    // -----------------------------------------------------------------------

    public void expireOldListings() {
        listings.values().stream()
                .filter(l -> l.isActive() && l.isExpired())
                .forEach(this::expireListing);
    }

    private void expireListing(AuctionListing listing) {
        listing.setStatus(AuctionListing.Status.EXPIRED);
        persistListing(listing);
        // Notify seller if online
        var srv = DonutFabric.getServer();
        if (srv != null) {
            var seller = srv.getPlayerManager().getPlayer(listing.getSellerUuid());
            if (seller != null) {
                seller.sendMessage(net.minecraft.text.Text.literal(
                    com.n8tech.donutfabric.utils.ChatUtil.color(
                        "&7[AH] &cListing " + listing.getId() +
                        " (" + listing.getItemName() + ") expired.")));
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Persistence
    // -----------------------------------------------------------------------

    public void saveAll() {
        listings.values().forEach(this::persistListing);
    }

    private void persistListing(AuctionListing l) {
        String sql = """
            INSERT INTO auction_listings
                (id, seller_uuid, seller_name, item_nbt, item_name, quantity, price,
                 created_at, expires_at, status, buyer_uuid, buyer_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                status = excluded.status,
                buyer_uuid = excluded.buyer_uuid,
                buyer_name = excluded.buyer_name
            """;
        try (PreparedStatement ps = db.getSQLite().prepareStatement(sql)) {
            ps.setString(1,  l.getId());
            ps.setString(2,  l.getSellerUuid().toString());
            ps.setString(3,  l.getSellerName());
            ps.setBytes(4,   l.getItemNbt());
            ps.setString(5,  l.getItemName());
            ps.setInt(6,     l.getQuantity());
            ps.setDouble(7,  l.getPrice());
            ps.setLong(8,    l.getCreatedAt());
            ps.setLong(9,    l.getExpiresAt());
            ps.setString(10, l.getStatus().name());
            ps.setString(11, l.getBuyerUuid() != null ? l.getBuyerUuid().toString() : null);
            ps.setString(12, l.getBuyerName());
            ps.executeUpdate();
        } catch (SQLException e) {
            ModLogger.warn("persistListing error: " + e.getMessage());
        }
    }

    private void loadFromDB() {
        String sql = """
            SELECT id, seller_uuid, seller_name, item_nbt, item_name, quantity, price,
                   created_at, expires_at, status, buyer_uuid, buyer_name
            FROM auction_listings WHERE status = 'ACTIVE'
            """;
        try (PreparedStatement ps = db.getSQLite().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                UUID buyerUuid = null;
                String buyerUuidStr = rs.getString("buyer_uuid");
                if (buyerUuidStr != null) buyerUuid = UUID.fromString(buyerUuidStr);

                AuctionListing l = new AuctionListing(
                        rs.getString("id"),
                        UUID.fromString(rs.getString("seller_uuid")),
                        rs.getString("seller_name"),
                        rs.getBytes("item_nbt"),
                        rs.getString("item_name"),
                        rs.getInt("quantity"),
                        rs.getDouble("price"),
                        rs.getLong("created_at"),
                        rs.getLong("expires_at"),
                        AuctionListing.Status.valueOf(rs.getString("status")),
                        buyerUuid,
                        rs.getString("buyer_name")
                );
                listings.put(l.getId(), l);
                count++;
            }
            ModLogger.info("Loaded " + count + " active AH listings from DB.");
        } catch (SQLException e) {
            ModLogger.warn("AH loadFromDB error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Item serialisation helpers
    // -----------------------------------------------------------------------

    private byte[] serializeItem(ItemStack stack) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtCompound nbt = new NbtCompound();
            // Use the stack's codec for serialization (MC 1.21)
            // Simplified: store item id + count
            nbt.putString("id",    Registries.ITEM.getId(stack.getItem()).toString());
            nbt.putInt("count",    stack.getCount());
            // Enchantments / custom NBT would need full codec serialization
            // For production, use stack.encode(registryLookup) with DynamicOps
            NbtIo.writeCompound(nbt, (DataOutput) new DataOutputStream(baos));
            return baos.toByteArray();
        } catch (Exception e) {
            ModLogger.warn("serialize item error: " + e.getMessage());
            return null;
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        if (data == null) return ItemStack.EMPTY;
        try {
            NbtCompound nbt = NbtIo.readCompound((DataInput) new DataInputStream(new ByteArrayInputStream(data)));
            String  idStr   = nbt.getString("id");
            int     count   = nbt.getInt("count");
            var item = Registries.ITEM.get(Identifier.of(idStr));
            return new ItemStack(item, count);
        } catch (Exception e) {
            ModLogger.warn("deserialize item error: " + e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    private String getItemDisplayName(ItemStack stack) {
        if (stack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME)) return stack.getName().getString();
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id.getPath().replace("_", " ");
    }

    // -----------------------------------------------------------------------
    //  Result wrapper
    // -----------------------------------------------------------------------

    public record AHResult(boolean success, String message, ItemStack item) {
        static AHResult success(String msg)              { return new AHResult(true,  msg, ItemStack.EMPTY); }
        static AHResult success(String msg, ItemStack i) { return new AHResult(true,  msg, i); }
        static AHResult fail   (String msg)              { return new AHResult(false, msg, ItemStack.EMPTY); }
    }
}
