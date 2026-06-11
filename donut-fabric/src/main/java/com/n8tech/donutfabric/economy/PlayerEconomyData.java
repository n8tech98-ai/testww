package com.n8tech.donutfabric.economy;

import java.util.UUID;

/**
 * In-memory economy record for a single player.
 * Mutable — accessed only under EconomyManager's locks or from the async executor.
 */
public class PlayerEconomyData {

    private final UUID uuid;
    private String username;
    private double balance;
    private double multiplier;    // 1.0 – 3.0
    private double totalSold;     // cumulative sell revenue (used to compute multiplier tier)
    private boolean dirty;        // true when data needs flushing to DB

    public PlayerEconomyData(UUID uuid, String username, double balance,
                              double multiplier, double totalSold) {
        this.uuid       = uuid;
        this.username   = username;
        this.balance    = balance;
        this.multiplier = multiplier;
        this.totalSold  = totalSold;
        this.dirty      = false;
    }

    /** Factory for brand-new players. */
    public static PlayerEconomyData fresh(UUID uuid, String username, double startingBalance) {
        return new PlayerEconomyData(uuid, username, startingBalance, 1.0, 0.0);
    }

    // ---------- getters ----------

    public UUID   getUuid()       { return uuid; }
    public String getUsername()   { return username; }
    public double getBalance()    { return balance; }
    public double getMultiplier() { return multiplier; }
    public double getTotalSold()  { return totalSold; }
    public boolean isDirty()      { return dirty; }

    // ---------- setters ----------

    public void setUsername(String username)    { this.username = username; markDirty(); }
    public void setBalance(double balance)      { this.balance  = balance;  markDirty(); }
    public void setMultiplier(double mult)      { this.multiplier = mult;   markDirty(); }
    public void setTotalSold(double ts)         { this.totalSold  = ts;     markDirty(); }
    public void markDirty()                     { this.dirty = true; }
    public void markClean()                     { this.dirty = false; }

    @Override
    public String toString() {
        return "EcoData{%s bal=%.2f mult=%.2fx sold=%.2f}"
                .formatted(username, balance, multiplier, totalSold);
    }
}
