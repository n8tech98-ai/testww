package com.n8tech.donutfabric.auctionhouse.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.auctionhouse.AuctionHouseManager;
import com.n8tech.donutfabric.auctionhouse.AuctionListing;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.gui.AuctionHouseGUI;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

/**
 * /ah                    — open auction house GUI
 * /ah sell <price>       — list held item
 * /ah buy  <id>          — buy listing by ID
 * /ah search <term>      — search (chat output)
 * /ah mine               — list your active listings
 */
public class AHCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("ah")
            .executes(ctx -> openGUI(ctx.getSource()))

            .then(CommandManager.literal("sell")
                .then(CommandManager.argument("price", DoubleArgumentType.doubleArg(0.01))
                    .executes(ctx -> sellItem(ctx.getSource(),
                            DoubleArgumentType.getDouble(ctx, "price")))))

            .then(CommandManager.literal("buy")
                .then(CommandManager.argument("id", StringArgumentType.word())
                    .executes(ctx -> buyItem(ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

            .then(CommandManager.literal("search")
                .then(CommandManager.argument("term", StringArgumentType.greedyString())
                    .executes(ctx -> search(ctx.getSource(),
                            StringArgumentType.getString(ctx, "term")))))

            .then(CommandManager.literal("mine")
                .executes(ctx -> listMine(ctx.getSource())))
        );
    }

    // -----------------------------------------------------------------------

    private static int openGUI(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        AuctionHouseGUI.open(player);
        return 1;
    }

    private static int sellItem(ServerCommandSource src, double price) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;

        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) {
            player.sendMessage(Text.literal(ChatUtil.color("&cNothing in hand to sell.")));
            return 0;
        }

        AuctionHouseManager ah = DonutFabric.getInstance().getAuctionHouseManager();
        ItemStack toList = held.copy();

        ah.listItem(player.getUuid(), player.getName().getString(), toList, price)
          .thenAccept(result -> {
              if (result.success()) {
                  player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
              }
              String color = result.success() ? "&a" : "&c";
              player.sendMessage(Text.literal(ChatUtil.color(color + result.message())));
          });
        return 1;
    }

    private static int buyItem(ServerCommandSource src, String id) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        AuctionHouseManager ah = DonutFabric.getInstance().getAuctionHouseManager();

        ah.buyListing(id, player.getUuid(), player.getName().getString())
          .thenAccept(result -> {
              if (result.success() && !result.item().isEmpty()) {
                  // Give item to player (must run on server thread)
                  DonutFabric.getServer().execute(() ->
                      player.getInventory().insertStack(result.item().copy()));
              }
              String color = result.success() ? "&a" : "&c";
              player.sendMessage(Text.literal(ChatUtil.color(color + result.message())));
          });
        return 1;
    }

    private static int search(ServerCommandSource src, String term) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        AuctionHouseManager ah  = DonutFabric.getInstance().getAuctionHouseManager();
        EconomyManager      eco = DonutFabric.getInstance().getEconomyManager();

        List<AuctionListing> results = ah.getActiveListings(term, null);
        if (results.isEmpty()) {
            player.sendMessage(Text.literal(ChatUtil.color("&7No listings found for: &e" + term)));
            return 1;
        }

        StringBuilder sb = new StringBuilder(ChatUtil.color(
                "&8━━━ &6AH Search: &e" + term + " &8━━━\n"));
        results.stream().limit(10).forEach(l ->
            sb.append(ChatUtil.color(
                "&7[&b" + l.getId() + "&7] &f" + l.getQuantity() + "x &e" +
                l.getItemName() + " &8| &a" + eco.format(l.getPrice()) +
                " &7by &7" + l.getSellerName() + "\n"))
        );
        if (results.size() > 10) {
            sb.append(ChatUtil.color("&7...and " + (results.size() - 10) + " more. Use /ah to see all."));
        }
        player.sendMessage(Text.literal(sb.toString()));
        return 1;
    }

    private static int listMine(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        AuctionHouseManager ah  = DonutFabric.getInstance().getAuctionHouseManager();
        EconomyManager      eco = DonutFabric.getInstance().getEconomyManager();

        var mine = ah.getPlayerListings(player.getUuid());
        if (mine.isEmpty()) {
            player.sendMessage(Text.literal(ChatUtil.color("&7No active listings.")));
            return 1;
        }

        StringBuilder sb = new StringBuilder(ChatUtil.color("&8━━━ &6Your AH Listings &8━━━\n"));
        mine.stream().limit(15).forEach(l ->
            sb.append(ChatUtil.color(
                "&7[&b" + l.getId() + "&7] &f" + l.getQuantity() + "x &e" +
                l.getItemName() + " &8| &a" + eco.format(l.getPrice()) +
                " &8[&7" + l.getStatus() + "&8]\n"))
        );
        player.sendMessage(Text.literal(sb.toString()));
        return 1;
    }
}
