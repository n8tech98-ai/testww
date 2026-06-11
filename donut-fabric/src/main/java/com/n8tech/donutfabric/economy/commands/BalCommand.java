package com.n8tech.donutfabric.economy.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.economy.PlayerEconomyData;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.UUID;

/**
 * /bal [player]
 * Shows your own balance, or another player's.
 */
public class BalCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("bal")
            .executes(ctx -> executeSelf(ctx.getSource()))
            .then(CommandManager.argument("player", StringArgumentType.word())
                .executes(ctx -> executeOther(ctx.getSource(),
                        StringArgumentType.getString(ctx, "player"))))
        );

        // /balance alias
        d.register(CommandManager.literal("balance")
            .executes(ctx -> executeSelf(ctx.getSource()))
            .then(CommandManager.argument("player", StringArgumentType.word())
                .executes(ctx -> executeOther(ctx.getSource(),
                        StringArgumentType.getString(ctx, "player"))))
        );
    }

    private static int executeSelf(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) {
            src.sendFeedback(() -> Text.literal("Must be a player."), false);
            return 0;
        }
        EconomyManager eco = DonutFabric.getInstance().getEconomyManager();
        UUID uuid   = player.getUuid();
        double bal  = eco.getBalance(uuid);
        double mult = eco.getMultiplier(uuid);

        String msg = ChatUtil.color(
            "&8━━━━━━━━━━━━━━━━━━━━━━━━\n" +
            "&6  Balance  &8|  &e" + eco.format(bal) + "\n" +
            "&6  Multiplier &8|  &a" + mult + "x\n" +
            "&8━━━━━━━━━━━━━━━━━━━━━━━━");
        player.sendMessage(Text.literal(msg));
        return 1;
    }

    private static int executeOther(ServerCommandSource src, String targetName) {
        EconomyManager eco = DonutFabric.getInstance().getEconomyManager();

        // Try online player first
        ServerPlayerEntity target = DonutFabric.getServer()
                .getPlayerManager().getPlayer(targetName);

        if (target != null) {
            double bal  = eco.getBalance(target.getUuid());
            double mult = eco.getMultiplier(target.getUuid());
            String msg  = ChatUtil.color(
                "&8━━━━━━━━━━━━━━━━━━━━━━━━\n" +
                "&6  " + targetName + " &8|\n" +
                "&e  " + eco.format(bal) + "  &7(mult: &a" + mult + "x&7)\n" +
                "&8━━━━━━━━━━━━━━━━━━━━━━━━");
            src.sendFeedback(() -> Text.literal(msg), false);
            return 1;
        }

        // Offline lookup — async so we don't block the server thread
        DonutFabric.ASYNC_EXECUTOR.submit(() -> {
            UUID offlineUuid = getOfflineUUID(targetName);
            if (offlineUuid == null) {
                src.sendFeedback(() ->
                        Text.literal(ChatUtil.color("&cPlayer not found: " + targetName)), false);
                return;
            }
            PlayerEconomyData data = eco.getOrLoadBlocking(offlineUuid, targetName);
            String msg = ChatUtil.color(
                "&7" + targetName + " (offline) — bal: &e" + eco.format(data.getBalance()));
            src.sendFeedback(() -> Text.literal(msg), false);
        });
        return 1;
    }

    /** Resolve a UUID from an online name or return null. Extend for offline DB lookup. */
    private static UUID getOfflineUUID(String name) {
        ServerPlayerEntity p = DonutFabric.getServer()
                .getPlayerManager().getPlayer(name);
        return p != null ? p.getUuid() : null;
    }
}
