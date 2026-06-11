package com.n8tech.donutfabric.economy.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /sell hand  — sells item currently held
 * /sell all   — sells every sellable item in inventory
 * /sell       — shows usage
 */
public class SellCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("sell")
            .executes(ctx -> showUsage(ctx.getSource()))
            .then(CommandManager.literal("hand")
                .executes(ctx -> executeSellHand(ctx.getSource())))
            .then(CommandManager.literal("all")
                .executes(ctx -> executeSellAll(ctx.getSource())))
        );
    }

    // -----------------------------------------------------------------------
    //  /sell hand
    // -----------------------------------------------------------------------

    private static int executeSellHand(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;

        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) {
            player.sendMessage(Text.literal(ChatUtil.color("&cNothing in hand.")));
            return 0;
        }

        EconomyManager eco = DonutFabric.getInstance().getEconomyManager();
        String itemId = getItemId(held);
        int count = held.getCount();
        double worth = eco.getWorth(itemId);

        if (worth <= 0) {
            player.sendMessage(Text.literal(ChatUtil.color("&c" + formatName(itemId) + " has no sell value.")));
            return 0;
        }

        double total = eco.sellItems(player.getUuid(), itemId, count);
        player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);

        double mult = eco.getMultiplier(player.getUuid());
        player.sendMessage(Text.literal(ChatUtil.color(
            "&aSold &e" + count + "x " + formatName(itemId) +
            " &afor &e" + eco.format(total) +
            " &7(&a" + mult + "x&7 mult)")));
        return 1;
    }

    // -----------------------------------------------------------------------
    //  /sell all
    // -----------------------------------------------------------------------

    private static int executeSellAll(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;

        EconomyManager eco = DonutFabric.getInstance().getEconomyManager();
        UUID uuid = player.getUuid();

        double totalEarned  = 0.0;
        int    totalItems   = 0;
        int    uniqueItems  = 0;

        // Collect all non-empty slots to avoid ConcurrentModificationException
        List<int[]> toRemove = new ArrayList<>(); // [slot, count]

        var inv = player.getInventory();
        for (int slot = 0; slot < inv.size(); slot++) {
            ItemStack stack = inv.getStack(slot);
            if (stack.isEmpty()) continue;

            String itemId = getItemId(stack);
            double worth  = eco.getWorth(itemId);
            if (worth <= 0) continue;

            int    count = stack.getCount();
            double total = eco.sellItems(uuid, itemId, count);
            totalEarned += total;
            totalItems  += count;
            uniqueItems++;
            toRemove.add(new int[]{slot, count});
        }

        if (totalItems == 0) {
            player.sendMessage(Text.literal(ChatUtil.color("&cNo sellable items in inventory.")));
            return 0;
        }

        // Remove items from inventory AFTER calculating (safe modification)
        for (int[] entry : toRemove) {
            inv.removeStack(entry[0]);
        }

        double mult = eco.getMultiplier(uuid);
        final double finalEarned = totalEarned;
        final int    finalItems  = totalItems;
        final int    finalUnique = uniqueItems;
        player.sendMessage(Text.literal(ChatUtil.color(
            "&aSold &e" + finalUnique + " &aitem type(s) &7(" + finalItems + " total) " +
            "&afor &e" + eco.format(finalEarned) +
            " &7(&a" + mult + "x&7)")));
        return 1;
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static int showUsage(ServerCommandSource src) {
        src.sendFeedback(() -> Text.literal(ChatUtil.color(
            "&e/sell hand &8— &7sell held item\n" +
            "&e/sell all  &8— &7sell all sellable items")), false);
        return 1;
    }

    /** Extracts the raw item registry ID (e.g. "minecraft:diamond" → "diamond"). */
    private static String getItemId(ItemStack stack) {
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id.getPath(); // strips namespace for worth table lookup
    }

    /** Formats "ancient_debris" → "Ancient Debris". */
    private static String formatName(String id) {
        String[] words = id.replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            sb.append(Character.toUpperCase(w.charAt(0)))
              .append(w.substring(1))
              .append(" ");
        }
        return sb.toString().trim();
    }
}
