package com.n8tech.donutfabric.gui;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.auctionhouse.AuctionHouseManager;
import com.n8tech.donutfabric.auctionhouse.AuctionListing;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import com.n8tech.donutfabric.utils.TimeFormatter;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auction House GUI.
 *
 * Layout (6-row chest):
 *  Row 0: border + TITLE header
 *  Rows 1-4: listing slots (7 per row = 28 per page)
 *  Row 5: ◀ PREV | [SORT] | [MY LISTINGS] | [SELL HAND] | NEXT ▶
 *
 * Left-click a listing → buy instantly.
 */
public class AuctionHouseGUI extends BaseGUI {

    public record AHGUIState(int page, AuctionHouseManager.SortMode sort, boolean showMine) {}

    private static final Map<UUID, AHGUIState> states = new ConcurrentHashMap<>();

    private static final int ITEMS_PER_PAGE = 28;
    private static final int[] LISTING_SLOTS = buildListingSlots();

    // Bottom bar
    private static final int BTN_PREV    = 46;
    private static final int BTN_SORT    = 47;
    private static final int BTN_MINE    = 48;
    private static final int BTN_SELL    = 49;
    private static final int BTN_INFO    = 50;
    private static final int BTN_NEXT    = 52;

    public static void open(ServerPlayerEntity player) {
        states.putIfAbsent(player.getUuid(),
                new AHGUIState(0, AuctionHouseManager.SortMode.PRICE_ASC, false));
        buildAndOpen(player);
    }

    private static void buildAndOpen(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        AHGUIState st = states.getOrDefault(uuid,
                new AHGUIState(0, AuctionHouseManager.SortMode.PRICE_ASC, false));

        AuctionHouseManager ah  = DonutFabric.getInstance().getAuctionHouseManager();
        EconomyManager      eco = DonutFabric.getInstance().getEconomyManager();

        List<AuctionListing> listings = st.showMine()
                ? ah.getPlayerListings(uuid)
                : ah.getActiveListings(null, st.sort());

        int totalPages = Math.max(1, (int) Math.ceil((double) listings.size() / ITEMS_PER_PAGE));
        int page       = Math.min(st.page(), totalPages - 1);

        SimpleInventory inv = new SimpleInventory(54) {
            @Override
            public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity p) { return true; }
        };

        // Border
        ItemStack border = filler(Items.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++)  inv.setStack(i, border.copy());
        for (int i = 45; i < 54; i++) inv.setStack(i, border.copy());
        for (int row = 1; row <= 4; row++) {
            inv.setStack(row * 9 + 7, border.copy());
            inv.setStack(row * 9 + 8, border.copy());
        }

        // Listing items
        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && (start + i) < listings.size(); i++) {
            inv.setStack(LISTING_SLOTS[i], buildListingItem(listings.get(start + i), eco));
        }

        // Navigation
        if (page > 0) inv.setStack(BTN_PREV, prevPage(page + 1));
        else          inv.setStack(BTN_PREV, filler(Items.RED_STAINED_GLASS_PANE));

        if (page < totalPages - 1) inv.setStack(BTN_NEXT, nextPage(page + 1, totalPages));
        else                       inv.setStack(BTN_NEXT, filler(Items.RED_STAINED_GLASS_PANE));

        // Sort button
        String sortLabel = switch (st.sort()) {
            case PRICE_ASC  -> "&aSorted: Cheapest";
            case PRICE_DESC -> "&aSorted: Most Expensive";
            case NEWEST     -> "&aSorted: Newest";
            case OLDEST     -> "&aSorted: Oldest";
        };
        inv.setStack(BTN_SORT, makeItem(Items.COMPARATOR, sortLabel,
            "&7Click to change sort order"));

        // My listings toggle
        inv.setStack(BTN_MINE, makeItem(
            st.showMine() ? Items.LIME_DYE : Items.GRAY_DYE,
            st.showMine() ? "&aYour Listings" : "&7All Listings",
            "&7Click to toggle"));

        // Sell hand button
        inv.setStack(BTN_SELL, makeItem(Items.GOLD_INGOT,
            "&6Sell Held Item",
            "&7Close GUI then use:",
            "&e/ah sell <price>"));

        // Info
        inv.setStack(BTN_INFO, makeItem(Items.PAPER,
            "&6Auction House &8| &7" + listings.size() + " listing(s)",
            "&7Tax on listing: &e" + (int)(DonutFabric.getInstance()
                .getConfigManager().getDouble("auction-house.listing-tax", 0.05) * 100) + "%",
            "",
            "&7Page: &e" + (page + 1) + "/" + totalPages));

        // Open with click handler
        final int finalPage = page;
        final int finalTotal = totalPages;
        final List<AuctionListing> finalListings = listings;

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInv, p) -> new GenericContainerScreenHandler(
                    ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6) {

                @Override
                public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                                        net.minecraft.entity.player.PlayerEntity pl) {
                    UUID uid = pl.getUuid();
                    AHGUIState s = states.getOrDefault(uid,
                            new AHGUIState(0, AuctionHouseManager.SortMode.PRICE_ASC, false));

                    if (slotIndex == BTN_PREV && finalPage > 0) {
                        states.put(uid, new AHGUIState(finalPage - 1, s.sort(), s.showMine()));
                        DonutFabric.getServer().execute(() -> buildAndOpen((ServerPlayerEntity) pl));
                        return;
                    }
                    if (slotIndex == BTN_NEXT && finalPage < finalTotal - 1) {
                        states.put(uid, new AHGUIState(finalPage + 1, s.sort(), s.showMine()));
                        DonutFabric.getServer().execute(() -> buildAndOpen((ServerPlayerEntity) pl));
                        return;
                    }
                    if (slotIndex == BTN_SORT) {
                        AuctionHouseManager.SortMode next = nextSort(s.sort());
                        states.put(uid, new AHGUIState(0, next, s.showMine()));
                        DonutFabric.getServer().execute(() -> buildAndOpen((ServerPlayerEntity) pl));
                        return;
                    }
                    if (slotIndex == BTN_MINE) {
                        states.put(uid, new AHGUIState(0, s.sort(), !s.showMine()));
                        DonutFabric.getServer().execute(() -> buildAndOpen((ServerPlayerEntity) pl));
                        return;
                    }

                    // Listing click
                    for (int i = 0; i < LISTING_SLOTS.length; i++) {
                        if (slotIndex == LISTING_SLOTS[i]) {
                            int idx = finalPage * ITEMS_PER_PAGE + i;
                            if (idx >= finalListings.size()) return;
                            AuctionListing listing = finalListings.get(idx);
                            handleBuy((ServerPlayerEntity) pl, listing, ah);
                            return;
                        }
                    }
                }

                @Override
                public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
                    return false;
                }
            },
            Text.literal(ChatUtil.color("&8&lAuction House &8| &6Listings"))
        ));
    }

    private static void handleBuy(ServerPlayerEntity player, AuctionListing listing,
                                    AuctionHouseManager ah) {
        if (!listing.isActive()) {
            player.sendMessage(Text.literal(ChatUtil.color("&cListing no longer available.")));
            DonutFabric.getServer().execute(() -> buildAndOpen(player));
            return;
        }

        ah.buyListing(listing.getId(), player.getUuid(), player.getName().getString())
          .thenAccept(result -> {
              if (result.success() && !result.item().isEmpty()) {
                  DonutFabric.getServer().execute(() -> {
                      player.getInventory().insertStack(result.item().copy());
                      player.sendMessage(Text.literal(ChatUtil.color("&a" + result.message())));
                      buildAndOpen(player);
                  });
              } else {
                  player.sendMessage(Text.literal(ChatUtil.color("&c" + result.message())));
              }
          });
    }

    private static ItemStack buildListingItem(AuctionListing listing, EconomyManager eco) {
        // Try to reconstruct item icon
        ItemStack icon = tryDeserialize(listing.getItemNbt());
        if (icon.isEmpty()) {
            // Fallback icon
            var item = Registries.ITEM.get(Identifier.of("minecraft",
                    listing.getItemName().replace(" ", "_").toLowerCase()));
            icon = new ItemStack(item == Items.AIR ? Items.PAPER : item);
        }

        long expSecs = (listing.getExpiresAt() - System.currentTimeMillis()) / 1000;

        setName(icon, "&e" + listing.getItemName().replace("_", " ").toUpperCase()
                + " &8x" + listing.getQuantity());
        setLore(icon, List.of(
            "&8──────────────",
            "&7Seller: &f"  + listing.getSellerName(),
            "&7Price:  &a"  + eco.format(listing.getPrice()),
            "&7Qty:    &e"  + listing.getQuantity(),
            "&8──────────────",
            "&7Expires: &c" + TimeFormatter.formatSeconds(expSecs),
            "",
            "&eLeft-click &7to purchase"
        ));
        return icon;
    }

    private static ItemStack tryDeserialize(byte[] nbt) {
        if (nbt == null) return ItemStack.EMPTY;
        try {
            NbtCompound compound = NbtIo.readCompound((java.io.DataInput) new java.io.DataInputStream(new java.io.ByteArrayInputStream(nbt)));
            String id  = compound.getString("id");
            int count  = compound.getInt("count");
            var item = Registries.ITEM.get(Identifier.of(id));
            return new ItemStack(item, count);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static AuctionHouseManager.SortMode nextSort(AuctionHouseManager.SortMode current) {
        return switch (current) {
            case PRICE_ASC  -> AuctionHouseManager.SortMode.PRICE_DESC;
            case PRICE_DESC -> AuctionHouseManager.SortMode.NEWEST;
            case NEWEST     -> AuctionHouseManager.SortMode.OLDEST;
            case OLDEST     -> AuctionHouseManager.SortMode.PRICE_ASC;
        };
    }

    private static int[] buildListingSlots() {
        int[] slots = new int[ITEMS_PER_PAGE];
        int idx = 0;
        for (int row = 1; row <= 4; row++) {
            for (int col = 0; col < 7; col++) {
                slots[idx++] = row * 9 + col;
            }
        }
        return slots;
    }
}
