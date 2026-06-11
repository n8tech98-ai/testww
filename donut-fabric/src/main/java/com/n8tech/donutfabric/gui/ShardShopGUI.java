package com.n8tech.donutfabric.gui;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.shards.ShardManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.*;
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

/**
 * Shard Shop GUI.
 *
 * Layout (4-row chest, dark themed):
 * Row 0: border
 * Row 1-2: spawner items for purchase
 * Row 3: border + balance display + close
 *
 * Every spawner costs the configured shard cost (default 1500 shards).
 */
public class ShardShopGUI extends BaseGUI {

    // Spawner shop entries (item id, display name, cost override -1 = use default)
    private record ShopEntry(String itemId, String displayName, int costOverride) {}

    private static final List<ShopEntry> ENTRIES = List.of(
        new ShopEntry("pig_spawn_egg",              "&6Pig Spawner",              -1),
        new ShopEntry("cow_spawn_egg",              "&6Cow Spawner",              -1),
        new ShopEntry("sheep_spawn_egg",            "&6Sheep Spawner",            -1),
        new ShopEntry("chicken_spawn_egg",          "&6Chicken Spawner",          -1),
        new ShopEntry("zombie_spawn_egg",           "&cZombie Spawner",           -1),
        new ShopEntry("skeleton_spawn_egg",         "&7Skeleton Spawner",         -1),
        new ShopEntry("spider_spawn_egg",           "&7Spider Spawner",           -1),
        new ShopEntry("blaze_spawn_egg",            "&cBlaze Spawner",            2000),
        new ShopEntry("enderman_spawn_egg",         "&5Enderman Spawner",         2500),
        new ShopEntry("wither_skeleton_spawn_egg",  "&8Wither Skeleton Spawner",  3000),
        new ShopEntry("guardian_spawn_egg",         "&bGuardian Spawner",         2000),
        new ShopEntry("iron_golem_spawn_egg",       "&7Iron Golem Spawner",       3500),
        new ShopEntry("piglin_brute_spawn_egg",     "&6Piglin Brute Spawner",     2500),
        new ShopEntry("hoglin_spawn_egg",           "&cHoglin Spawner",           2000)
    );

    // Slots for shop items: rows 1-2, cols 1-7
    private static final int[] SHOP_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25
    };

    private static final int BALANCE_SLOT = 31;
    private static final int CLOSE_SLOT   = 35;

    public static void open(ServerPlayerEntity player) {
        ShardManager sm = DonutFabric.getInstance().getShardManager();
        int balance = sm.getShards(player.getUuid());
        int defCost = sm.getSpawnerCost();

        SimpleInventory inv = new SimpleInventory(36) {
            @Override
            public boolean canPlayerUse(net.minecraft.entity.player.PlayerEntity p) { return true; }
        };

        // Border
        ItemStack border = filler(Items.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++)  inv.setStack(i, border.copy());
        for (int i = 27; i < 36; i++) inv.setStack(i, border.copy());
        // Side borders
        for (int row = 1; row <= 2; row++) {
            inv.setStack(row * 9,     border.copy());
            inv.setStack(row * 9 + 8, border.copy());
        }

        // Shop items
        for (int i = 0; i < Math.min(ENTRIES.size(), SHOP_SLOTS.length); i++) {
            ShopEntry entry = ENTRIES.get(i);
            int cost = entry.costOverride() < 0 ? defCost : entry.costOverride();
            boolean canAfford = balance >= cost;

            var item = Registries.ITEM.get(Identifier.of("minecraft", entry.itemId()));
            ItemStack icon = new ItemStack(item == Items.AIR ? Items.SPAWNER : item);

            setName(icon, entry.displayName());
            setLore(icon, List.of(
                "&8──────────────",
                "&7Cost: &d" + cost + " ✦",
                canAfford ? "&aYou can afford this!" : "&cInsufficient shards",
                "&7Your balance: &d" + balance + " ✦",
                "&8──────────────",
                canAfford ? "&eClick &7to purchase" : "&cNeed &d" + (cost - balance) + "✦ &cmore"
            ));

            inv.setStack(SHOP_SLOTS[i], icon);
        }

        // Balance display
        inv.setStack(BALANCE_SLOT, makeItem(Items.AMETHYST_SHARD,
            "&5Your Shard Balance",
            "&d" + balance + " ✦",
            "",
            "&7Kill reward: &d+" + sm.getKillReward() + "✦",
            "&7AFK rate:    &d+" + sm.getAfkRatePerMin() + "✦/min"));

        // Close
        inv.setStack(CLOSE_SLOT, makeItem(Items.BARRIER, "&cClose", "&7Click to close the shop."));

        // Header label
        inv.setStack(4, makeItem(Items.NETHER_STAR,
            "&5✦ &dShard Shop &5✦",
            "&7Spend your shards to buy spawners.",
            "&7Every spawner costs the same price.",
            "&7Win PvP or AFK to earn shards."));

        // Open with handler
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInv, p) -> new GenericContainerScreenHandler(
                    ScreenHandlerType.GENERIC_9X4, syncId, playerInv, inv, 4) {

                @Override
                public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                                        net.minecraft.entity.player.PlayerEntity pl) {
                    if (slotIndex == CLOSE_SLOT) {
                        ((net.minecraft.server.network.ServerPlayerEntity) pl).closeHandledScreen();
                        return;
                    }

                    // Shop item click
                    for (int i = 0; i < Math.min(ENTRIES.size(), SHOP_SLOTS.length); i++) {
                        if (slotIndex == SHOP_SLOTS[i]) {
                            ShopEntry entry = ENTRIES.get(i);
                            int cost = entry.costOverride() < 0 ? defCost : entry.costOverride();
                            handlePurchase((ServerPlayerEntity) pl, entry, cost);
                            return;
                        }
                    }
                }

                @Override
                public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
                    return false;
                }
            },
            Text.literal(ChatUtil.color("&5✦ &dShard Shop &5✦"))
        ));
    }

    private static void handlePurchase(ServerPlayerEntity player, ShopEntry entry, int cost) {
        ShardManager sm = DonutFabric.getInstance().getShardManager();

        if (!sm.spendShards(player.getUuid(), cost)) {
            player.sendMessage(Text.literal(ChatUtil.color(
                "&cInsufficient shards! Need: &d" + cost + "✦")));
            return;
        }

        // Give the spawner egg
        var item = Registries.ITEM.get(Identifier.of("minecraft", entry.itemId()));
        ItemStack reward = new ItemStack(item == Items.AIR ? Items.SPAWNER : item, 1);
        setName(reward, entry.displayName());

        if (!player.getInventory().insertStack(reward)) {
            // Inventory full — drop at feet
            player.dropItem(reward, false);
        }

        player.sendMessage(Text.literal(ChatUtil.color(
            "&aPurchased &5" + ChatUtil.stripColor(entry.displayName()) +
            " &afor &d" + cost + "✦. &7Remaining: &d" + sm.getShards(player.getUuid()) + "✦")));

        // Refresh GUI
        DonutFabric.getServer().execute(() -> open(player));
    }
}
