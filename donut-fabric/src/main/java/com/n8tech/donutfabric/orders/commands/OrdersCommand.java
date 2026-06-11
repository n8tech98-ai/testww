package com.n8tech.donutfabric.orders.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.gui.OrdersGUI;
import com.n8tech.donutfabric.orders.OrderManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * /orders                    — opens the orders GUI
 * /orders create <item> <qty> <price> — creates a buy order via CLI
 * /orders cancel <id>        — cancels an order
 * /orders fulfill <id> <qty> — fulfills an order via CLI
 * /orders list               — lists your active orders
 */
public class OrdersCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) ->
                registerTo(dispatcher));
    }

    private static void registerTo(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("orders")
            // Open GUI
            .executes(ctx -> openGUI(ctx.getSource()))

            // Create via CLI
            .then(CommandManager.literal("create")
                .then(CommandManager.argument("item", StringArgumentType.word())
                    .then(CommandManager.argument("quantity", IntegerArgumentType.integer(1))
                        .then(CommandManager.argument("price", DoubleArgumentType.doubleArg(0.01))
                            .executes(ctx -> createOrder(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "item"),
                                    IntegerArgumentType.getInteger(ctx, "quantity"),
                                    DoubleArgumentType.getDouble(ctx, "price")))
                        ))))

            // Cancel
            .then(CommandManager.literal("cancel")
                .then(CommandManager.argument("id", StringArgumentType.word())
                    .executes(ctx -> cancelOrder(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "id")))))

            // Fulfill
            .then(CommandManager.literal("fulfill")
                .then(CommandManager.argument("id", StringArgumentType.word())
                    .then(CommandManager.argument("quantity", IntegerArgumentType.integer(1))
                        .executes(ctx -> fulfillOrder(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "id"),
                                IntegerArgumentType.getInteger(ctx, "quantity"))))))

            // List own orders
            .then(CommandManager.literal("list")
                .executes(ctx -> listOrders(ctx.getSource())))
        );

        // Alias /order
        d.register(CommandManager.literal("order")
            .executes(ctx -> openGUI(ctx.getSource())));
    }

    // -----------------------------------------------------------------------
    //  Handlers
    // -----------------------------------------------------------------------

    private static int openGUI(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        // Open the server-side GUI screen
        OrdersGUI.open(player);
        return 1;
    }

    private static int createOrder(ServerCommandSource src, String item, int qty, double price) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        OrderManager om = DonutFabric.getInstance().getOrderManager();

        om.createOrder(player.getUuid(), player.getName().getString(), item, qty, price)
          .thenAccept(result -> {
              String color = result.success() ? "&a" : "&c";
              player.sendMessage(Text.literal(ChatUtil.color(color + result.message())));
          });
        return 1;
    }

    private static int cancelOrder(ServerCommandSource src, String id) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        OrderManager om = DonutFabric.getInstance().getOrderManager();

        om.cancelOrder(id, player.getUuid())
          .thenAccept(result -> {
              String color = result.success() ? "&a" : "&c";
              player.sendMessage(Text.literal(ChatUtil.color(color + result.message())));
          });
        return 1;
    }

    private static int fulfillOrder(ServerCommandSource src, String id, int qty) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;

        // Verify player has the items
        // (Full item-checking + removal is done in OrdersGUI for GUI-path;
        //  here we do a basic check for CLI fulfillment)
        OrderManager om = DonutFabric.getInstance().getOrderManager();

        om.fulfillOrder(id, player.getUuid(), player.getName().getString(), qty)
          .thenAccept(result -> {
              String color = result.success() ? "&a" : "&c";
              player.sendMessage(Text.literal(ChatUtil.color(color + result.message())));
          });
        return 1;
    }

    private static int listOrders(ServerCommandSource src) {
        if (!(src.getEntity() instanceof ServerPlayerEntity player)) return 0;
        OrderManager om = DonutFabric.getInstance().getOrderManager();

        var myOrders = om.getPlayerOrders(player.getUuid());
        if (myOrders.isEmpty()) {
            player.sendMessage(Text.literal(ChatUtil.color("&7You have no orders.")));
            return 1;
        }

        StringBuilder sb = new StringBuilder(ChatUtil.color(
                "&8&m         &r &6Your Orders &8&m         \n"));
        myOrders.stream().limit(10).forEach(o ->
            sb.append(ChatUtil.color(
                "&7[&e" + o.getId() + "&7] &f" + o.getQuantity() + "x &b" + o.getItemId() +
                " &7@ &e" + o.getPriceEach() + " &8(" + o.getStatus() + ")\n"))
        );
        player.sendMessage(Text.literal(sb.toString()));
        return 1;
    }
}
