package com.n8tech.donutfabric.shards;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.database.DatabaseManager;
import com.n8tech.donutfabric.utils.ModLogger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shard system — DonutSMP's secondary currency.
 *
 * Earning sources (June 2026):
 *  - AFK zone: 1 shard/minute  (tracked by AFK timer, applied per-minute)
 *  - Player kills: 10 shards per kill
 *  - Donut+ perk: earn shards anywhere (subscription bonus, admin-toggle)
 *
 * Spending: Shard Shop GUI — every spawner costs 1500 shards (flat pricing since April 2026).
 */
public class ShardManager {

    private final DatabaseManager db;
    private final ConfigManager   config;

    // UUID → shard balance (in-memory)
    private final Map<UUID, Integer> balances = new ConcurrentHashMap<>();

    // Config
    private int killReward;
    private int afkRatePerMin;
    private int spawnerCost;

    public ShardManager(DatabaseManager db, ConfigManager config) {
        this.db     = db;
        this.config = config;
    }

    // -----------------------------------------------------------------------
    //  Init
    // -----------------------------------------------------------------------

    public void init() {
        killReward    = config.getInt(config.getShardsConfig(), "kill-reward",     10);
        afkRatePerMin = config.getInt(config.getShardsConfig(), "afk-rate-per-min", 1);
        spawnerCost   = config.getInt(config.getShardsConfig(), "shop.spawner-cost", 1500);
        ModLogger.info("ShardManager ready (kill=" + killReward + ", afk=" + afkRatePerMin + "/min)");
    }

    // -----------------------------------------------------------------------
    //  Balance operations
    // -----------------------------------------------------------------------

    public int getShards(UUID uuid) {
        return balances.getOrDefault(uuid, 0);
    }

    public void addShards(UUID uuid, int amount) {
        balances.merge(uuid, amount, Integer::sum);
    }

    public boolean spendShards(UUID uuid, int amount) {
        int current = getShards(uuid);
        if (current < amount) return false;
        balances.put(uuid, current - amount);
        return true;
    }

    public boolean hasShards(UUID uuid, int amount) {
        return getShards(uuid) >= amount;
    }

    // -----------------------------------------------------------------------
    //  Event hooks (called from listeners)
    // -----------------------------------------------------------------------

    public void onPlayerKill(UUID killer) {
        addShards(killer, killReward);
    }

    /** Called every minute per AFK player. */
    public void grantAFKShards(UUID uuid) {
        addShards(uuid, afkRatePerMin);
    }

    // -----------------------------------------------------------------------
    //  Player lifecycle
    // -----------------------------------------------------------------------

    public void loadPlayer(UUID uuid) {
        DonutFabric.ASYNC_EXECUTOR.submit(() -> {
            int balance = loadFromDB(uuid);
            balances.put(uuid, balance);
        });
    }

    public void unloadPlayer(UUID uuid) {
        Integer balance = balances.remove(uuid);
        if (balance != null) {
            final int bal = balance;
            DonutFabric.ASYNC_EXECUTOR.submit(() ->
                    db.upsertShards(uuid, bal));
        }
    }

    public void saveAll() {
        balances.forEach((uuid, bal) ->
                db.upsertShards(uuid, bal));
    }

    private int loadFromDB(UUID uuid) {
        String sql = "SELECT balance FROM shards WHERE uuid = ?";
        try (PreparedStatement ps = db.getSQLite().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("balance");
            }
        } catch (SQLException e) {
            ModLogger.warn("shards loadFromDB: " + e.getMessage());
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    //  Admin commands
    // -----------------------------------------------------------------------

    public void setShards(UUID uuid, int amount) {
        balances.put(uuid, Math.max(0, amount));
    }

    public void giveShards(UUID uuid, int amount) {
        addShards(uuid, amount);
    }

    // -----------------------------------------------------------------------
    //  Getters for config values (used by ShardShopGUI)
    // -----------------------------------------------------------------------

    public int getSpawnerCost()   { return spawnerCost; }
    public int getKillReward()    { return killReward; }
    public int getAfkRatePerMin() { return afkRatePerMin; }
}
