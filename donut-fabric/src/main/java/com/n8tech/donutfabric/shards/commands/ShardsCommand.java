package com.n8tech.donutfabric.shards.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.gui.ShardShopGUI;
import com.n8tech.donutfabric.shards.ShardManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * /shards          — show your shard balance + open shop
 * /shards shop     — open shard shop GUI
 * /shards give <player> <amount>   — admin
 * /shards set  <player> <amount>   — admin
 */
public class ShardsCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("shards")
            .executes(ctx -> showBalance(ctx.getSource()))

            .then(CommandManager.literal("shop")
                .executes(ctx -> openShop(ctx.getSource())))

            .then(CommandManager.literal("give")
                .requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> adminGive(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"),
                                IntegerArgumentType.getInteger(ctx, "amount"))))))

            .then(CommandManager.literal("set")
                .requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.argument("player", StringArgumentType.word())
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(0))
                        .executes(ctx -> adminSet(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "player"),
                                IntegerArgumentType.getInteger(ctx, "amount"))))))
        );
    }

    private static int showBalance(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        ShardManager sm = DonutFabric.getInstance().getShardManager();
        int shards = sm.getShards(player.getUuid());
        player.sendMessage(Text.literal(ChatUtil.color(
            "&8━━━━━━━━━━━━━━━━━━━━━━━━\n" +
            "&5  Shard Balance  &8|  &d" + shards + " ✦\n" +
            "&7  Kill reward:  &d" + sm.getKillReward() + " ✦\n" +
            "&7  AFK rate:     &d" + sm.getAfkRatePerMin() + " ✦/min\n" +
            "&8━━━━━━━━━━━━━━━━━━━━━━━━\n" +
            "&7Use &5/shards shop &7to spend shards."
        )));
        return 1;
    }

    private static int openShop(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        ShardShopGUI.open(player);
        return 1;
    }

    private static int adminGive(ServerCommandSource src, String playerName, int amount) {
        var target = DonutFabric.getServer().getPlayerManager().getPlayer(playerName);
        if (target == null) {
            src.sendFeedback(() -> Text.literal(ChatUtil.color("&cPlayer not found.")), false);
            return 0;
        }
        DonutFabric.getInstance().getShardManager().giveShards(target.getUuid(), amount);
        src.sendFeedback(() -> Text.literal(ChatUtil.color(
                "&aGave &d" + amount + "✦ &ashards to &e" + playerName)), true);
        target.sendMessage(Text.literal(ChatUtil.color(
                "&aYou received &d" + amount + "✦ &ashards from an admin.")));
        return 1;
    }

    private static int adminSet(ServerCommandSource src, String playerName, int amount) {
        var target = DonutFabric.getServer().getPlayerManager().getPlayer(playerName);
        if (target == null) {
            src.sendFeedback(() -> Text.literal(ChatUtil.color("&cPlayer not found.")), false);
            return 0;
        }
        DonutFabric.getInstance().getShardManager().setShards(target.getUuid(), amount);
        src.sendFeedback(() -> Text.literal(ChatUtil.color(
                "&aSet &e" + playerName + "&a's shards to &d" + amount + "✦")), true);
        return 1;
    }
}
