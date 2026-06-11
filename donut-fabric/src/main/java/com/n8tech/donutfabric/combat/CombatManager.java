package com.n8tech.donutfabric.combat;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.utils.ChatUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CombatManager implements DonutSMP-style Crystal PvP combat mechanics:
 *
 *  - Combat tagging: hit/being hit tags you for {@code tagDuration} seconds
 *  - Logout punishment: tagged players who disconnect are killed in-place
 *  - Pearl cooldown: ender pearl throw cooldown (anti-spam)
 *  - Actionbar combat timer: shows remaining tag time on the HUD
 *  - Anti-safezone abuse: combat tag cannot be reset by entering certain worlds
 *
 * All state is in-memory only (resets on server restart — intended).
 */
public class CombatManager {

    // UUID → millisecond timestamp when tag expires
    private final Map<UUID, Long> combatTags = new ConcurrentHashMap<>();

    // UUID → millisecond timestamp when pearl cooldown expires
    private final Map<UUID, Long> pearlCooldowns = new ConcurrentHashMap<>();

    // Config
    private final int  tagDurationSeconds;
    private final boolean logoutPunish;
    private final int  pearlCooldownSeconds;
    private final Set<String> safezoneWorlds;

    // Tick counter for actionbar updates (update every 10 ticks = 0.5s)
    private int tickCounter = 0;

    public CombatManager(ConfigManager config) {
        tagDurationSeconds  = config.getInt("combat.tag-duration",     15);
        logoutPunish        = config.getBoolean("combat.logout-punish");
        pearlCooldownSeconds= config.getInt("combat.pearl-cooldown",   12);

        @SuppressWarnings("unchecked")
        var worlds = (List<String>) config.getMainConfig()
                .getOrDefault("combat.safezone-worlds", List.of("spawn_world"));
        safezoneWorlds = new HashSet<>(worlds);
    }

    // -----------------------------------------------------------------------
    //  Combat tagging
    // -----------------------------------------------------------------------

    /**
     * Tags both the attacker and the victim for combat.
     * Should be called from a DamageSource listener when player→player damage occurs.
     */
    public void tag(UUID attacker, UUID victim) {
        long expiry = System.currentTimeMillis() + (tagDurationSeconds * 1000L);
        combatTags.put(attacker, expiry);
        combatTags.put(victim,   expiry);
    }

    public boolean isTagged(UUID uuid) {
        Long expiry = combatTags.get(uuid);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            combatTags.remove(uuid);
            return false;
        }
        return true;
    }

    public int getRemainingTagSeconds(UUID uuid) {
        Long expiry = combatTags.get(uuid);
        if (expiry == null) return 0;
        long remaining = expiry - System.currentTimeMillis();
        return remaining > 0 ? (int) Math.ceil(remaining / 1000.0) : 0;
    }

    public void untag(UUID uuid) {
        combatTags.remove(uuid);
    }

    // -----------------------------------------------------------------------
    //  Logout punishment
    // -----------------------------------------------------------------------

    /**
     * Called on PlayerDisconnect event.
     * If the player is combat-tagged and logout punishment is enabled, kills them.
     */
    public void onPlayerDisconnect(ServerPlayerEntity player) {
        if (!logoutPunish) return;
        if (!isTagged(player.getUuid())) return;

        // Kill the player (they already disconnected, so this damages their entity which is still loaded)
        // MC keeps the entity briefly during disconnect; we schedule the kill
        DonutFabric.getServer().execute(() -> {
            if (!player.isRemoved()) {
                player.damage(
                    player.getServerWorld().getDamageSources().generic(),
                    Float.MAX_VALUE);
                // Broadcast
                DonutFabric.getServer().getPlayerManager().broadcast(
                    Text.literal(ChatUtil.color(
                        "&c" + player.getName().getString() +
                        " &7tried to combat log and was punished.")),
                    false);
            }
        });

        untag(player.getUuid());
    }

    // -----------------------------------------------------------------------
    //  Pearl cooldown
    // -----------------------------------------------------------------------

    public boolean canThrowPearl(UUID uuid) {
        Long expiry = pearlCooldowns.get(uuid);
        return expiry == null || System.currentTimeMillis() > expiry;
    }

    public void setPearlCooldown(UUID uuid) {
        pearlCooldowns.put(uuid, System.currentTimeMillis() + (pearlCooldownSeconds * 1000L));
    }

    public int getPearlCooldownRemaining(UUID uuid) {
        Long expiry = pearlCooldowns.get(uuid);
        if (expiry == null) return 0;
        long remaining = expiry - System.currentTimeMillis();
        return remaining > 0 ? (int) Math.ceil(remaining / 1000.0) : 0;
    }

    // -----------------------------------------------------------------------
    //  Safezone logic
    // -----------------------------------------------------------------------

    /** Returns true if the player is in a safezone world. */
    public boolean isInSafezone(ServerPlayerEntity player) {
        String worldKey = player.getServerWorld().getRegistryKey().getValue().getPath();
        return safezoneWorlds.contains(worldKey);
    }

    /**
     * Crystal PvP optimisation: when a player is in a safezone,
     * they cannot be tagged. This prevents safezone-PvP griefing patterns.
     */
    public void tryTag(ServerPlayerEntity attacker, ServerPlayerEntity victim) {
        // Don't tag if either player is in a safezone
        if (isInSafezone(attacker) || isInSafezone(victim)) return;
        tag(attacker.getUuid(), victim.getUuid());
    }

    // -----------------------------------------------------------------------
    //  Per-tick loop (called from DonutFabric.onServerTick)
    // -----------------------------------------------------------------------

    public void onTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 10 != 0) return; // only every 10 ticks (0.5s)
        tickCounter = 0;

        // Update actionbar for all combat-tagged players
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            if (!isTagged(uuid)) continue;

            int secs = getRemainingTagSeconds(uuid);
            String bar = ChatUtil.color(
                "&c⚔ Combat Tagged &8| &e" + secs + "s &7remaining");
            player.sendMessage(Text.literal(bar), true); // true = actionbar
        }

        // Clean up expired tags
        long now = System.currentTimeMillis();
        combatTags.entrySet().removeIf(e -> now > e.getValue());
        pearlCooldowns.entrySet().removeIf(e -> now > e.getValue());
    }

    // -----------------------------------------------------------------------
    //  Accessors
    // -----------------------------------------------------------------------

    public boolean isLogoutPunishEnabled() { return logoutPunish; }
    public int getTagDuration()            { return tagDurationSeconds; }
    public int getPearlCooldown()          { return pearlCooldownSeconds; }

    /**
     * Alias for {@link #onPlayerDisconnect(ServerPlayerEntity)}.
     * Called by PlayerLeaveListener on disconnect.
     */
    public void handleLogout(ServerPlayerEntity player) {
        onPlayerDisconnect(player);
    }

    /**
     * Clears all in-memory combat state for a player (tags, cooldowns).
     * Called after save on player leave so no stale data lingers.
     */
    public void clearPlayer(UUID uuid) {
        combatTags.remove(uuid);
        pearlCooldowns.remove(uuid);
    }
}
