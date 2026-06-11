package com.n8tech.donutfabric.listeners;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.shards.ShardManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import com.n8tech.donutfabric.utils.ModLogger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Handles player join events:
 *  - Async-loads economy data from DB into Caffeine cache
 *  - Async-loads shard balance from DB
 *  - Sends configurable join MOTD if enabled
 */
public class PlayerJoinListener {

    private final EconomyManager economyManager;
    private final ShardManager shardManager;
    private final ConfigManager configManager;

    public PlayerJoinListener(EconomyManager economyManager,
                              ShardManager shardManager,
                              ConfigManager configManager) {
        this.economyManager = economyManager;
        this.shardManager   = shardManager;
        this.configManager  = configManager;
    }

    public void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String name = player.getName().getString();

            ModLogger.debug("Player joined: " + name + " — loading data");

            // Preload economy data asynchronously (loadPlayer handles its own async dispatch)
            DonutFabric.ASYNC_EXECUTOR.submit(() -> {
                try {
                    economyManager.loadPlayer(player.getUuid(), name).join();
                } catch (Exception e) {
                    ModLogger.error("Failed to load economy data for " + name, e);
                }
            });

            // Preload shard data — ShardManager.loadPlayer dispatches async internally
            shardManager.loadPlayer(player.getUuid());

            // Send MOTD if configured
            boolean motdEnabled = "true".equalsIgnoreCase(
                    configManager.getConfig().getOrDefault("motd.enabled", "true").toString());

            if (motdEnabled) {
                // Small delay via server execute so the join message appears first
                server.execute(() -> {
                    if (player.isDisconnected()) return;
                    String motd = configManager.getMessage("join_motd",
                            "&8[&6Donut&8] &7Welcome back, &e{player}&7!");
                    player.sendMessage(
                            net.minecraft.text.Text.literal(
                                    ChatUtil.color(motd.replace("{player}", name))),
                            false);
                });
            }
        });
    }
}
