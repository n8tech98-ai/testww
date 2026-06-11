package com.n8tech.donutfabric.listeners;

import com.n8tech.donutfabric.combat.CombatManager;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.shards.ShardManager;
import com.n8tech.donutfabric.utils.ModLogger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Handles player disconnect events:
 *  - Flushes and evicts economy data (unloadPlayer)
 *  - Flushes and evicts shard balance (unloadPlayer)
 *  - Delegates combat logout punishment to CombatManager
 *  - Clears all combat state for the player
 */
public class PlayerLeaveListener {

    private final EconomyManager economyManager;
    private final ShardManager shardManager;
    private final CombatManager combatManager;

    public PlayerLeaveListener(EconomyManager economyManager,
                               ShardManager shardManager,
                               CombatManager combatManager) {
        this.economyManager = economyManager;
        this.shardManager   = shardManager;
        this.combatManager  = combatManager;
    }

    public void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID uuid = player.getUuid();
            String name = player.getName().getString();

            ModLogger.debug("Player disconnected: " + name + " — saving data");

            // Combat logout punishment (must run on server thread — we're already here via DISCONNECT)
            combatManager.handleLogout(player);

            // Flush economy + evict from cache (handles its own async flush if dirty)
            economyManager.unloadPlayer(uuid);

            // Flush shards + evict (handles its own async flush)
            shardManager.unloadPlayer(uuid);

            // Clear pearl cooldowns, combat tags, etc.
            combatManager.clearPlayer(uuid);
        });
    }
}
