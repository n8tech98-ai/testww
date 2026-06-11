package com.n8tech.donutfabric.economy.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * /pay <player> <amount>
 * Transfers money from the sender to a target player (with configured tax).
 */
public class PayCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("pay")
            .then(CommandManager.argument("player", StringArgumentType.word())
                .then(CommandManager.argument("amount", DoubleArgumentType.doubleArg(0.01))
                    .executes(ctx -> execute(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "player"),
                            DoubleArgumentType.getDouble(ctx, "amount")
                    ))
                )
            )
        );
    }

    private static int execute(ServerCommandSource src, String targetName, double amount) {
        if (!(src.getEntity() instanceof ServerPlayerEntity sender)) {
            src.sendFeedback(() -> Text.literal("Players only."), false);
            return 0;
        }

        ServerPlayerEntity target = DonutFabric.getServer()
                .getPlayerManager().getPlayer(targetName);

        if (target == null || target == sender) {
            sender.sendMessage(Text.literal(ChatUtil.color("&cPlayer not found or invalid.")));
            return 0;
        }

        EconomyManager eco = DonutFabric.getInstance().getEconomyManager();

        // Transfer includes tax (sender pays amount + tax)
        boolean success = eco.transfer(sender.getUuid(), target.getUuid(), amount);

        if (!success) {
            double tax   = amount * 0.05; // approximate for display
            double total = amount + tax;
            sender.sendMessage(Text.literal(ChatUtil.color(
                "&cInsufficient balance. Need: " + eco.format(total))));
            return 0;
        }

        // Notify sender
        sender.sendMessage(Text.literal(ChatUtil.color(
            "&aSent &e" + eco.format(amount) + "&a to &e" + targetName)));

        // Notify target
        target.sendMessage(Text.literal(ChatUtil.color(
            "&e" + sender.getName().getString() + "&a paid you &e" + eco.format(amount))));

        return 1;
    }
}
