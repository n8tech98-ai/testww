package com.n8tech.donutfabric.economy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.api.EconomyAPI;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.database.DatabaseManager;
import com.n8tech.donutfabric.utils.ModLogger;
import com.n8tech.donutfabric.utils.NumberFormatter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central economy manager.
 *
 * DonutSMP economy features reproduced:
 *  - Dual-currency (Money + Shards handled in ShardManager)
 *  - Sell multiplier 1.0x → 3.0x based on cumulative /sell revenue
 *  - Worth lookup via worth.yml
 *  - Pay with configurable tax
 *  - Transaction logging
 *  - Caffeine cache with 30-min idle eviction; dirty records flushed to SQLite/Mongo asynchronously
 */
public class EconomyManager implements EconomyAPI {

    // ---- Config knobs (loaded from config.yml) ----
    private double startingBalance;
    private double maxBalance;
    private double payTax;
    private boolean multiplierEnabled;
    private double multiplierMax;
    private double multiplierStep;
    private double multiplierThreshold; // sell every N dollars unlocks next step
    private String currencySymbol;

    // ---- Worth table (item id → base sell price) ----
    private final Map<String, Double> worthTable = new ConcurrentHashMap<>();

    // ---- Player data cache ----
    // Key: UUID, Value: PlayerEconomyData
    private final Cache<UUID, PlayerEconomyData> cache;

    private final DatabaseManager db;
    private final ConfigManager config;

    public EconomyManager(DatabaseManager db, ConfigManager config) {
        this.db = db;
        this.config = config;
        this.cache = Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .removalListener((k, v, cause) -> {
                    // Flush on eviction so offline player data isn't lost
                    if (v instanceof PlayerEconomyData data && data.isDirty()) {
                        flushToDB(data);
                    }
                })
                .build();
    }

    // -----------------------------------------------------------------------
    //  Initialisation
    // -----------------------------------------------------------------------

    public void init() {
        // Load config values
        startingBalance     = config.getDouble("economy.starting-balance", 500.0);
        maxBalance          = config.getDouble("economy.max-balance",       1_000_000_000.0);
        payTax              = config.getDouble("economy.pay-tax",           0.05);
        multiplierEnabled   = config.getBoolean("economy.multiplier-enabled");
        multiplierMax       = config.getDouble("economy.multiplier-max",    3.0);
        multiplierStep      = config.getDouble("economy.multiplier-step",   0.1);
        multiplierThreshold = config.getDouble("economy.multiplier-threshold", 100_000.0);
        currencySymbol      = config.getString("economy.currency-symbol");
        if (currencySymbol == null || currencySymbol.isBlank()) currencySymbol = "$";

        // Load worth table from worth.yml
        loadWorthTable();

        ModLogger.info("EconomyManager ready. Starting balance: " + format(startingBalance));
    }

    @SuppressWarnings("unchecked")
    private void loadWorthTable() {
        worthTable.clear();
        config.getWorthConfig().forEach((key, val) -> {
            if (val instanceof Number n) {
                worthTable.put(key.toLowerCase(), n.doubleValue());
            }
        });
        ModLogger.info("Worth table loaded: " + worthTable.size() + " items");
    }

    // -----------------------------------------------------------------------
    //  Player data lifecycle
    // -----------------------------------------------------------------------

    /**
     * Load a player's data into the cache asynchronously.
     * Called on PlayerJoinEvent — resolves before the player interacts with economy.
     */
    public CompletableFuture<PlayerEconomyData> loadPlayer(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerEconomyData existing = cache.getIfPresent(uuid);
            if (existing != null) {
                existing.setUsername(username); // keep name fresh
                return existing;
            }
            PlayerEconomyData loaded = loadFromDB(uuid, username);
            cache.put(uuid, loaded);
            return loaded;
        }, DonutFabric.ASYNC_EXECUTOR);
    }

    private PlayerEconomyData loadFromDB(UUID uuid, String username) {
        if (db.getType() == DatabaseManager.DatabaseType.SQLITE) {
            String sql = "SELECT balance, multiplier, total_sold FROM economy WHERE uuid = ?";
            try (PreparedStatement ps = db.getSQLite().prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerEconomyData(
                                uuid, username,
                                rs.getDouble("balance"),
                                rs.getDouble("multiplier"),
                                rs.getDouble("total_sold"));
                    }
                }
            } catch (SQLException e) {
                ModLogger.warn("loadFromDB error: " + e.getMessage());
            }
        }
        // New player
        PlayerEconomyData fresh = PlayerEconomyData.fresh(uuid, username, startingBalance);
        fresh.markDirty();
        return fresh;
    }

    /** Unload a player's data, flushing if dirty. */
    public void unloadPlayer(UUID uuid) {
        PlayerEconomyData data = cache.getIfPresent(uuid);
        if (data != null && data.isDirty()) {
            flushToDB(data);
        }
        cache.invalidate(uuid);
    }

    /** Called on auto-save task — flushes all dirty entries. */
    public void saveAll() {
        cache.asMap().values().stream()
                .filter(PlayerEconomyData::isDirty)
                .forEach(this::flushToDB);
    }

    private void flushToDB(PlayerEconomyData data) {
        db.upsertEconomy(
                data.getUuid(), data.getUsername(),
                data.getBalance(), data.getMultiplier(), data.getTotalSold());
        data.markClean();
    }

    // -----------------------------------------------------------------------
    //  EconomyAPI implementation
    // -----------------------------------------------------------------------

    @Override
    public double getBalance(UUID uuid) {
        PlayerEconomyData d = getOrNull(uuid);
        return d != null ? d.getBalance() : 0.0;
    }

    @Override
    public double getMultiplier(UUID uuid) {
        PlayerEconomyData d = getOrNull(uuid);
        return d != null ? d.getMultiplier() : 1.0;
    }

    @Override
    public boolean deposit(UUID uuid, double amount) {
        if (amount <= 0) return false;
        PlayerEconomyData d = getOrNull(uuid);
        if (d == null) return false;
        double newBal = Math.min(d.getBalance() + amount, maxBalance);
        d.setBalance(newBal);
        return true;
    }

    @Override
    public boolean withdraw(UUID uuid, double amount) {
        if (amount <= 0) return false;
        PlayerEconomyData d = getOrNull(uuid);
        if (d == null) return false;
        if (d.getBalance() < amount) return false;
        d.setBalance(d.getBalance() - amount);
        return true;
    }

    @Override
    public boolean transfer(UUID from, UUID to, double amount) {
        if (amount <= 0) return false;
        double tax    = amount * payTax;
        double total  = amount + tax;
        if (!has(from, total)) return false;
        withdraw(from, total);
        deposit(to, amount);
        db.logTransaction(from, to, "PAY", amount, "Transfer (tax=" + format(tax) + ")");
        return true;
    }

    @Override
    public boolean has(UUID uuid, double amount) {
        return getBalance(uuid) >= amount;
    }

    @Override
    public String format(double amount) {
        return currencySymbol + NumberFormatter.format(amount);
    }

    @Override
    public CompletableFuture<Double> getBalanceAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> getBalance(uuid), DonutFabric.ASYNC_EXECUTOR);
    }

    // -----------------------------------------------------------------------
    //  Sell logic (used by /sell command)
    // -----------------------------------------------------------------------

    /**
     * Calculates the effective sell price for {@code count} of {@code itemId},
     * applies the player's multiplier, credits their account, and updates the multiplier.
     *
     * @return the total credited (after multiplier, before any sell tax). 0 if item not in worth table.
     */
    public double sellItems(UUID uuid, String itemId, int count) {
        String key   = itemId.toLowerCase().replace("minecraft:", "");
        Double worth = worthTable.get(key);
        if (worth == null || worth <= 0) return 0.0;

        double mult  = multiplierEnabled ? getMultiplier(uuid) : 1.0;
        double total = worth * count * mult;

        deposit(uuid, total);
        accumulateSellRevenue(uuid, total);
        db.logTransaction(null, uuid, "SELL", total,
                count + "x " + key + " @" + format(worth) + " x" + mult);
        return total;
    }

    /** Look up the worth of one item (base price, before multiplier). */
    public double getWorth(String itemId) {
        String key = itemId.toLowerCase().replace("minecraft:", "");
        return worthTable.getOrDefault(key, 0.0);
    }

    public Map<String, Double> getWorthTable() {
        return Collections.unmodifiableMap(worthTable);
    }

    /**
     * Accumulates sell revenue and upgrades the player's multiplier tier.
     * Multiplier increments every {@code multiplierThreshold} dollars sold.
     */
    private void accumulateSellRevenue(UUID uuid, double revenue) {
        PlayerEconomyData d = getOrNull(uuid);
        if (d == null) return;
        double newTotal = d.getTotalSold() + revenue;
        d.setTotalSold(newTotal);

        if (multiplierEnabled) {
            // Each threshold crossed earns +multiplierStep, capped at multiplierMax
            double earned  = Math.floor(newTotal / multiplierThreshold) * multiplierStep + 1.0;
            double newMult = Math.min(earned, multiplierMax);
            // Round to avoid floating point drift
            newMult = Math.round(newMult * 100.0) / 100.0;
            if (newMult != d.getMultiplier()) {
                d.setMultiplier(newMult);
            }
        }
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private PlayerEconomyData getOrNull(UUID uuid) {
        return cache.getIfPresent(uuid);
    }

    /** Called by commands when a player may not be online (e.g. /pay offline target). */
    public PlayerEconomyData getOrLoadBlocking(UUID uuid, String username) {
        PlayerEconomyData d = cache.getIfPresent(uuid);
        if (d != null) return d;
        d = loadFromDB(uuid, username);
        cache.put(uuid, d);
        return d;
    }

    public Cache<UUID, PlayerEconomyData> getCache() { return cache; }
}
