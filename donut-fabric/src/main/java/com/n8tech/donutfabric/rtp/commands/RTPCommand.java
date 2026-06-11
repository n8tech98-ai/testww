package com.n8tech.donutfabric.rtp.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.rtp.RTPManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * /rtp — random teleport
 * Finds a safe location asynchronously, then teleports on the server thread.
 */
public class RTPCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("rtp")
            .executes(ctx -> execute(ctx.getSource())));

        d.register(CommandManager.literal("wild")
            .executes(ctx -> execute(ctx.getSource())));
    }

    private static int execute(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendFeedback(() -> Text.literal("Players only."), false);
            return 0;
        }

        RTPManager rtp = DonutFabric.getInstance().getRTPManager();

        // Cooldown check
        if (rtp.hasCooldown(player.getUuid())) {
            long remaining = rtp.getCooldownRemainingSeconds(player.getUuid());
            player.sendMessage(Text.literal(ChatUtil.color(
                "&cRTP on cooldown. Try again in &e" + remaining + "s")));
            return 0;
        }

        // Combat check
        var combat = DonutFabric.getInstance().getCombatManager();
        if (combat.isTagged(player.getUuid())) {
            player.sendMessage(Text.literal(ChatUtil.color(
                "&cCannot RTP while in combat!")));
            return 0;
        }

        player.sendMessage(Text.literal(ChatUtil.color(
            "&7Finding a safe location, please wait...")));

        ServerWorld world = player.getServerWorld();

        // Search asynchronously
        rtp.findSafeLocation(world).thenAccept(pos -> {
            if (pos == null) {
                // Must send message on server thread
                DonutFabric.getServer().execute(() ->
                    player.sendMessage(Text.literal(ChatUtil.color(
                        "&cCould not find a safe location. Try again."))));
                return;
            }

            // Teleport must happen on server thread
            DonutFabric.getServer().execute(() -> {
                if (!player.isAlive() || player.isRemoved()) return;
                rtp.teleportPlayer(player, pos);
                player.sendMessage(Text.literal(ChatUtil.color(
                    "&aTeleported to &e" + pos.getX() + "&a, &e" + pos.getZ())));
            });
        }).exceptionally(ex -> {
            DonutFabric.getServer().execute(() ->
                player.sendMessage(Text.literal(ChatUtil.color(
                    "&cRTP failed: " + ex.getMessage()))));
            return null;
        });

        return 1;
    }
}
