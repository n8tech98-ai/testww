package com.n8tech.donutfabric.gui;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.orders.Order;
import com.n8tech.donutfabric.orders.OrderManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import com.n8tech.donutfabric.utils.TimeFormatter;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orders GUI — the most important feature.
 *
 * Layout (6-row chest, dark theme):
 *
 *  Row 0: ██ TITLE ██ ██ ██ ██ ██ ██ ██
 *  Row 1: [Order] [Order] [Order] [Order] [Order] [Order] [Order] ██
 *  Row 2: [Order] [Order] [Order] [Order] [Order] [Order] [Order] ██
 *  Row 3: [Order] [Order] [Order] [Order] [Order] [Order] [Order] ██
 *  Row 4: [Order] [Order] [Order] [Order] [Order] [Order] [Order] ██
 *  Row 5: ██ ◀ PREV ██ [SEARCH] [MY ORDERS] [CREATE] ██ NEXT ▶ ██
 *
 * 28 order slots per page (7 per row × 4 rows).
 * Click order → fulfill it (takes items from inventory).
 * Shift-click → cancel own order.
 */
public class OrdersGUI extends BaseGUI {

    // GUI state per player (page, search filter)
    public record GUIState(int page, String filter, boolean showMine) {}

    private static final Map<UUID, GUIState> playerStates = new ConcurrentHashMap<>();

    // Orders fill slots 9-44 (rows 1-4, cols 0-6)
    private static final int ITEMS_PER_PAGE = 28;
    private static final int[] ORDER_SLOTS  = buildOrderSlots();

    // Bottom bar fixed slots
    private static final int BTN_PREV      = 46;
    private static final int BTN_MINE      = 48;
    private static final int BTN_CREATE    = 50;
    private static final int BTN_NEXT      = 52;
    private static final int LABEL_INFO    = 49;

    // -----------------------------------------------------------------------
    //  Entry point
    // -----------------------------------------------------------------------

    public static void open(ServerPlayerEntity player) {
        playerStates.putIfAbsent(player.getUuid(), new GUIState(0, null, false));
        buildAndOpen(player);
    }

    private static void buildAndOpen(ServerPlayerEntity player) {
        UUID uuid   = player.getUuid();
        GUIState st = playerStates.getOrDefault(uuid, new GUIState(0, null, false));

        OrderManager  om  = DonutFabric.getInstance().getOrderManager();
        EconomyManager eco = DonutFabric.getInstance().getEconomyManager();

        List<Order> orders = st.showMine()
                ? om.getPlayerOrders(uuid)
                : om.getActiveOrders(st.filter());

        int totalPages = Math.max(1, (int) Math.ceil((double) orders.size() / ITEMS_PER_PAGE));
        int page       = Math.min(st.page(), totalPages - 1);

        // Build inventory
        SimpleInventory inv = new SimpleInventory(54) {
            @Override
            public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity p) { return true; }
        };

        // Fill border
        ItemStack border = filler(Items.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++)  inv.setStack(i, border.copy());
        for (int i = 45; i < 54; i++) inv.setStack(i, border.copy());
        for (int row = 1; row <= 4; row++) {
            inv.setStack(row * 9 + 7, border.copy());
            inv.setStack(row * 9 + 8, border.copy());
        }

        // Place orders into slots
        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && (start + i) < orders.size(); i++) {
            Order order = orders.get(start + i);
            inv.setStack(ORDER_SLOTS[i], buildOrderItem(order, eco));
        }

        // Navigation buttons
        if (page > 0) inv.setStack(BTN_PREV, prevPage(page + 1));
        else          inv.setStack(BTN_PREV, filler(Items.RED_STAINED_GLASS_PANE));

        if (page < totalPages - 1) inv.setStack(BTN_NEXT, nextPage(page + 1, totalPages));
        else                       inv.setStack(BTN_NEXT, filler(Items.RED_STAINED_GLASS_PANE));

        // Info
        inv.setStack(LABEL_INFO, makeItem(Items.PAPER,
            "&6Orders &8| &7Page " + (page + 1) + "/" + totalPages,
            "&7Total active orders: &e" + orders.size(),
            "&7Tax: &e" + (int)(DonutFabric.getInstance()
                .getConfigManager().getDouble("orders.tax-rate", 0.08) * 100) + "%",
            "",
            st.showMine() ? "&aShowing: &eYour Orders" : "&aShowing: &eAll Orders",
            "&7Click &e[My Orders] &7to toggle."));

        // My orders toggle
        inv.setStack(BTN_MINE, makeItem(st.showMine() ? Items.LIME_DYE : Items.GRAY_DYE,
            st.showMine() ? "&aYour Orders" : "&7All Orders",
            "&7Click to toggle",
            st.showMine() ? "&aCurrently: &eYour orders" : "&7Currently: &eAll orders"));

        // Create button
        inv.setStack(BTN_CREATE, makeItem(Items.EMERALD,
            "&aCreate Order",
            "&7Use &e/orders create <item> <qty> <price>",
            "&7to post a buy order."));

        // Attach click handler
        openWithHandler(player, inv, "&8&lOrders &8| &6Buy Orders", page, totalPages, orders, eco, om);
    }

    // -----------------------------------------------------------------------
    //  Click-aware open
    // -----------------------------------------------------------------------

    private static void openWithHandler(ServerPlayerEntity player, SimpleInventory inv,
                                          String title, int page, int totalPages,
                                          List<Order> orders, EconomyManager eco, OrderManager om) {

        int totalPage = totalPages; // effectively final for lambda
        int curPage   = page;

        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInv, p) -> new GenericContainerScreenHandler(
                    ScreenHandlerType.GENERIC_9X6, syncId, playerInv, inv, 6) {

                @Override
                public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                                        net.minecraft.entity.player.PlayerEntity pl) {
                    if (slotIndex < 0 || slotIndex >= inv.size()) {
                        super.onSlotClick(slotIndex, button, actionType, pl);
                        return;
                    }

                    UUID uid = pl.getUuid();
                    GUIState st = playerStates.getOrDefault(uid, new GUIState(0, null, false));

                    // Navigation
                    if (slotIndex == BTN_PREV && curPage > 0) {
                        playerStates.put(uid, new GUIState(curPage - 1, st.filter(), st.showMine()));
                        DonutFabric.getServer().execute(() -> buildAndOpen((ServerPlayerEntity) pl));
                        return;
                    }
                    if (slotIndex == BTN_NEXT && curPage < totalPage - 1) {
                        playerStates.put(uid, new GUIState(curPage + 1, st.filter(), st.showMine()));
                        DonutFabric.getServer().execute(() -> buildAndOpen((ServerPlayerEntity) pl));
                        return;
                    }

                    // My orders toggle
                    if (slotIndex == BTN_MINE) {
                        playerStates.put(uid, new GUIState(0, st.filter(), !st.showMine()));
                        DonutFabric.getServer().execute(() -> buildAndOpen((ServerPlayerEntity) pl));
                        return;
                    }

                    // Create button — just print hint (actual creation is CLI)
                    if (slotIndex == BTN_CREATE) {
                        pl.sendMessage(Text.literal(ChatUtil.color(
                            "&7Use &e/orders create <item> <qty> <price> &7to create an order.")));
                        return;
                    }

                    // Order click
                    for (int i = 0; i < ORDER_SLOTS.length; i++) {
                        if (slotIndex == ORDER_SLOTS[i]) {
                            int idx = curPage * ITEMS_PER_PAGE + i;
                            if (idx >= orders.size()) return;
                            Order order = orders.get(idx);

                            if (actionType == SlotActionType.THROW) {
                                // Shift+click or Q — cancel own order
                                if (order.getOwnerUuid().equals(uid)) {
                                    om.cancelOrder(order.getId(), uid).thenAccept(res -> {
                                        pl.sendMessage(Text.literal(ChatUtil.color(
                                            (res.success() ? "&a" : "&c") + res.message())));
                                        DonutFabric.getServer().execute(() ->
                                            buildAndOpen((ServerPlayerEntity) pl));
                                    });
                                }
                            } else {
                                // Left-click → fulfill
                                handleFulfill((ServerPlayerEntity) pl, order, om);
                            }
                            return;
                        }
                    }
                    // Block taking items from border slots
                }

                @Override
                public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
                    return false;
                }
            },
            Text.literal(ChatUtil.color(title))
        ));
    }

    // -----------------------------------------------------------------------
    //  Fulfill logic (removes items from player inventory)
    // -----------------------------------------------------------------------

    private static void handleFulfill(ServerPlayerEntity player, Order order, OrderManager om) {
        if (!order.isActive()) {
            player.sendMessage(Text.literal(ChatUtil.color("&cOrder is no longer active.")));
            return;
        }

        // Count how many of this item the player has
        String itemId = order.getItemId();
        int playerCount = countItemInInventory(player, itemId);

        if (playerCount == 0) {
            player.sendMessage(Text.literal(ChatUtil.color(
                "&cYou don't have any &e" + itemId + " &cto fulfill this order.")));
            return;
        }

        int toFulfill = Math.min(playerCount, order.getRemaining());

        // Remove items from inventory
        removeItemsFromInventory(player, itemId, toFulfill);

        // Process fulfillment async
        om.fulfillOrder(order.getId(), player.getUuid(),
                player.getName().getString(), toFulfill)
          .thenAccept(result -> {
              player.sendMessage(Text.literal(ChatUtil.color(
                  (result.success() ? "&a" : "&c") + result.message())));
              DonutFabric.getServer().execute(() -> buildAndOpen(player));
          });
    }

    private static int countItemInInventory(ServerPlayerEntity player, String itemId) {
        var item = Registries.ITEM.get(Identifier.of("minecraft", itemId));
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    private static void removeItemsFromInventory(ServerPlayerEntity player,
                                                   String itemId, int amount) {
        var item = Registries.ITEM.get(Identifier.of("minecraft", itemId));
        int remaining = amount;
        for (int i = 0; i < player.getInventory().size() && remaining > 0; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty() || s.getItem() != item) continue;
            int remove = Math.min(remaining, s.getCount());
            s.decrement(remove);
            if (s.isEmpty()) player.getInventory().setStack(i, ItemStack.EMPTY);
            remaining -= remove;
        }
    }

    // -----------------------------------------------------------------------
    //  Order item builder
    // -----------------------------------------------------------------------

    private static ItemStack buildOrderItem(Order order, EconomyManager eco) {
        // Try to find the item material for the display icon
        var iconItem = Registries.ITEM.get(Identifier.of("minecraft", order.getItemId()));
        ItemStack icon = new ItemStack(iconItem == Items.AIR ? Items.PAPER : iconItem);

        String statusColor = switch (order.getStatus()) {
            case OPEN    -> "&a";
            case PARTIAL -> "&e";
            default      -> "&7";
        };

        long expiresIn = (order.getExpiresAt() - System.currentTimeMillis()) / 1000;
        String expStr  = TimeFormatter.formatSeconds(expiresIn);

        setName(icon, "&e" + order.getItemId().replace("_", " ").toUpperCase());
        setLore(icon, List.of(
            "&8─────────────────",
            "&7Buyer: &f"  + order.getOwnerName(),
            "&7Wants: &e"  + order.getRemaining() + " &7/ &e" + order.getQuantity(),
            "&7Price: &a"  + eco.format(order.getPriceEach()) + " &7each",
            "&7Total: &a"  + eco.format(order.getPriceEach() * order.getRemaining()),
            "&8─────────────────",
            statusColor + "● " + order.getStatus().name(),
            "&7Expires: &c" + expStr,
            "",
            "&eLeft-click &7to fulfill",
            "&7(needs items in inventory)",
            "&cQ / Shift-click &7to cancel (own only)"
        ));
        return icon;
    }

    // -----------------------------------------------------------------------
    //  Slot map builder
    // -----------------------------------------------------------------------

    private static int[] buildOrderSlots() {
        // Rows 1-4 (indices 9-44), cols 0-6 (skip cols 7 and 8 which are border)
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
