package com.n8tech.donutfabric.orders;

import java.util.UUID;

/**
 * Represents a buy order posted by a player.
 * Players post how many of item X they want at price Y each.
 * Other players fulfill the order by delivering the items and receiving payment.
 */
public class Order {

    public enum Status { OPEN, PARTIAL, FILLED, EXPIRED, CANCELLED }

    private final String id;
    private final UUID   ownerUuid;
    private final String ownerName;
    private final String itemId;       // vanilla registry path e.g. "diamond"
    private int          quantity;     // total requested
    private int          filled;       // how many have been delivered
    private final double priceEach;    // price per item the buyer will pay
    private final double taxPaid;      // tax deducted from buyer on creation
    private final long   createdAt;    // epoch millis
    private final long   expiresAt;
    private Status       status;

    public Order(String id, UUID ownerUuid, String ownerName,
                 String itemId, int quantity, double priceEach, double taxPaid,
                 long createdAt, long expiresAt) {
        this.id        = id;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.itemId    = itemId;
        this.quantity  = quantity;
        this.filled    = 0;
        this.priceEach = priceEach;
        this.taxPaid   = taxPaid;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status    = Status.OPEN;
    }

    // Full constructor (for DB loading)
    public Order(String id, UUID ownerUuid, String ownerName,
                 String itemId, int quantity, int filled, double priceEach, double taxPaid,
                 long createdAt, long expiresAt, Status status) {
        this(id, ownerUuid, ownerName, itemId, quantity, priceEach, taxPaid, createdAt, expiresAt);
        this.filled = filled;
        this.status = status;
    }

    // ---- Computed ----

    public int getRemaining()     { return quantity - filled; }
    public boolean isExpired()    { return System.currentTimeMillis() > expiresAt; }
    public boolean isActive()     { return status == Status.OPEN || status == Status.PARTIAL; }
    public double getTotalValue() { return priceEach * quantity; }

    // ---- Getters ----

    public String getId()        { return id; }
    public UUID   getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public String getItemId()    { return itemId; }
    public int    getQuantity()  { return quantity; }
    public int    getFilled()    { return filled; }
    public double getPriceEach() { return priceEach; }
    public double getTaxPaid()   { return taxPaid; }
    public long   getCreatedAt() { return createdAt; }
    public long   getExpiresAt() { return expiresAt; }
    public Status getStatus()    { return status; }

    // ---- Setters ----

    public void setFilled(int filled) {
        this.filled = filled;
        if (this.filled >= this.quantity) this.status = Status.FILLED;
        else if (this.filled > 0)         this.status = Status.PARTIAL;
    }

    public void setStatus(Status status) { this.status = status; }

    @Override
    public String toString() {
        return "Order{%s %dx%s @%.2f [%s %d/%d]}"
                .formatted(id, quantity, itemId, priceEach, status, filled, quantity);
    }
}
