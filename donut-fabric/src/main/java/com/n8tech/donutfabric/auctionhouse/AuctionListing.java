package com.n8tech.donutfabric.auctionhouse;

import net.minecraft.item.ItemStack;

import java.util.UUID;

/**
 * A single auction house listing: seller posts an item at a fixed price.
 * Any player can instantly buy it.
 */
public class AuctionListing {

    public enum Status { ACTIVE, SOLD, EXPIRED, CANCELLED }

    private final String    id;
    private final UUID      sellerUuid;
    private final String    sellerName;
    private       ItemStack item;         // the actual item (deserialized on load)
    private final byte[]    itemNbt;      // serialised bytes stored in DB
    private final String    itemName;     // display name for search/filter
    private final int       quantity;
    private final double    price;
    private final long      createdAt;
    private final long      expiresAt;
    private Status          status;
    private UUID            buyerUuid;
    private String          buyerName;

    public AuctionListing(String id, UUID sellerUuid, String sellerName,
                          ItemStack item, byte[] itemNbt, String itemName,
                          int quantity, double price,
                          long createdAt, long expiresAt) {
        this.id         = id;
        this.sellerUuid = sellerUuid;
        this.sellerName = sellerName;
        this.item       = item;
        this.itemNbt    = itemNbt;
        this.itemName   = itemName;
        this.quantity   = quantity;
        this.price      = price;
        this.createdAt  = createdAt;
        this.expiresAt  = expiresAt;
        this.status     = Status.ACTIVE;
    }

    // Full constructor for DB loading
    public AuctionListing(String id, UUID sellerUuid, String sellerName,
                          byte[] itemNbt, String itemName,
                          int quantity, double price,
                          long createdAt, long expiresAt,
                          Status status, UUID buyerUuid, String buyerName) {
        this(id, sellerUuid, sellerName, ItemStack.EMPTY, itemNbt, itemName,
             quantity, price, createdAt, expiresAt);
        this.status    = status;
        this.buyerUuid = buyerUuid;
        this.buyerName = buyerName;
    }

    public boolean isExpired()  { return System.currentTimeMillis() > expiresAt; }
    public boolean isActive()   { return status == Status.ACTIVE; }

    // Getters
    public String    getId()          { return id; }
    public UUID      getSellerUuid()  { return sellerUuid; }
    public String    getSellerName()  { return sellerName; }
    public ItemStack getItem()        { return item; }
    public byte[]    getItemNbt()     { return itemNbt; }
    public String    getItemName()    { return itemName; }
    public int       getQuantity()    { return quantity; }
    public double    getPrice()       { return price; }
    public long      getCreatedAt()   { return createdAt; }
    public long      getExpiresAt()   { return expiresAt; }
    public Status    getStatus()      { return status; }
    public UUID      getBuyerUuid()   { return buyerUuid; }
    public String    getBuyerName()   { return buyerName; }

    // Setters
    public void setStatus(Status s)       { this.status    = s; }
    public void setBuyerUuid(UUID u)      { this.buyerUuid = u; }
    public void setBuyerName(String n)    { this.buyerName = n; }
    public void setItem(ItemStack stack)  { this.item      = stack; }
}
