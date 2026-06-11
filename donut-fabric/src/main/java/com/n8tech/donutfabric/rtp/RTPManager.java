package com.n8tech.donutfabric.rtp;

import com.n8tech.donutfabric.DonutFabric;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.utils.ModLogger;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RTP (Random Teleport) system.
 *
 * Key requirements for large SMPs:
 * - Async candidate search (no sync chunk loading on server thread)
 * - Cooldown tracking per player
 * - Biome blacklist (no ocean, lava ocean spawning)
 * - Safe-block check (solid ground, air above, not lava/fire)
 * - High-performance chunk requests via CompletableFuture
 */
public class RTPManager {

    private final ConfigManager config;

    // UUID → last successful RTP timestamp
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    // Config values
    private int    minRadius;
    private int    maxRadius;
    private long   cooldownMillis;
    private int    maxAttempts;
    private Set<String> blacklistBiomes;

    // Blocks that are safe to land on
    private static final Set<Block> SAFE_SURFACE = Set.of(
        Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.SAND, Blocks.GRAVEL,
        Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.MYCELIUM,
        Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.ICE,
        Blocks.NETHERRACK, Blocks.NETHER_BRICKS, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
        Blocks.BASALT, Blocks.BLACKSTONE, Blocks.END_STONE,
        Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.JUNGLE_LOG
    );

    // Blocks that are dangerous and should never land on
    private static final Set<Block> UNSAFE_BLOCKS = Set.of(
        Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
        Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH,
        Blocks.WITHER_ROSE, Blocks.VOID_AIR
    );

    private final Random rng = new Random();

    public RTPManager(ConfigManager config) {
        this.config = config;
        loadConfig();
    }

    private void loadConfig() {
        var rtpCfg = config.getRTPConfig();
        minRadius      = config.getInt(rtpCfg, "min-radius",       500);
        maxRadius      = config.getInt(rtpCfg, "max-radius",       8000);
        cooldownMillis = config.getInt(rtpCfg, "cooldown-seconds",  180) * 1000L;
        maxAttempts    = config.getInt(rtpCfg, "max-attempts",       30);

        @SuppressWarnings("unchecked")
        List<String> biomes = (List<String>) rtpCfg.getOrDefault("blacklist-biomes", List.of());
        blacklistBiomes = new HashSet<>(biomes);
    }

    // -----------------------------------------------------------------------
    //  Cooldown
    // -----------------------------------------------------------------------

    public boolean hasCooldown(UUID uuid) {
        Long last = cooldowns.get(uuid);
        return last != null && (System.currentTimeMillis() - last) < cooldownMillis;
    }

    public long getCooldownRemainingSeconds(UUID uuid) {
        Long last = cooldowns.get(uuid);
        if (last == null) return 0;
        long remaining = cooldownMillis - (System.currentTimeMillis() - last);
        return remaining > 0 ? (int) Math.ceil(remaining / 1000.0) : 0;
    }

    public void setCooldown(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis());
    }

    public void clearCooldown(UUID uuid) {
        cooldowns.remove(uuid);
    }

    // -----------------------------------------------------------------------
    //  Async teleport search
    // -----------------------------------------------------------------------

    /**
     * Searches for a safe RTP location asynchronously.
     * Never blocks the server thread — chunk loading is done via CompletableFuture.
     *
     * @return CompletableFuture<BlockPos> resolved when a safe spot is found, or null if exhausted.
     */
    public CompletableFuture<BlockPos> findSafeLocation(ServerWorld world) {
        return CompletableFuture.supplyAsync(() -> {
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                int x = getRandomCoord();
                int z = getRandomCoord();

                // Check if the chunk is already loaded to avoid async loading dangers
                // On Paper/Folia you'd use async chunk loading; on Fabric we request and wait
                ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);
                WorldChunk chunk = world.getChunk(chunkPos.x, chunkPos.z);

                if (chunk.isEmpty()) continue;

                // Find safe Y
                int y = findSafeY(world, x, z);
                if (y < 0) continue;

                BlockPos pos = new BlockPos(x, y, z);

                // Biome check
                if (isBiomeBlacklisted(world, pos)) continue;

                // Final safety checks
                if (!isPositionSafe(world, pos)) continue;

                return pos;
            }
            return null; // Exhausted attempts
        }, DonutFabric.ASYNC_EXECUTOR);
    }

    private int getRandomCoord() {
        int range = maxRadius - minRadius;
        int offset = rng.nextInt(range + 1) + minRadius;
        return rng.nextBoolean() ? offset : -offset;
    }

    /**
     * Finds the topmost solid, safe Y at (x, z).
     * Returns -1 if no safe Y found.
     */
    private int findSafeY(ServerWorld world, int x, int z) {
        int topY = world.getTopY(); // world height
        int minY = world.getBottomY();

        for (int y = topY - 1; y > minY + 1; y--) {
            BlockPos pos   = new BlockPos(x, y, z);
            BlockPos above = new BlockPos(x, y + 1, z);
            BlockPos feet  = new BlockPos(x, y + 2, z);

            Block surface = world.getBlockState(pos).getBlock();
            Block airAbove = world.getBlockState(above).getBlock();
            Block airFeet  = world.getBlockState(feet).getBlock();

            if (UNSAFE_BLOCKS.contains(surface)) continue;
            if (!isSolid(world, pos))             continue;
            if (!isAir(world, above))             continue;
            if (!isAir(world, feet))              continue;

            return y + 1; // Stand on top of surface
        }
        return -1;
    }

    private boolean isSolid(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isSolidBlock(world, pos);
    }

    private boolean isAir(ServerWorld world, BlockPos pos) {
        Block b = world.getBlockState(pos).getBlock();
        return b == Blocks.AIR || b == Blocks.CAVE_AIR;
    }

    private boolean isPositionSafe(ServerWorld world, BlockPos pos) {
        // Check feet and head positions
        BlockPos feet = pos;
        BlockPos head = pos.up();

        if (UNSAFE_BLOCKS.contains(world.getBlockState(feet).getBlock())) return false;
        if (UNSAFE_BLOCKS.contains(world.getBlockState(head).getBlock())) return false;

        // Check block below is actually solid (not air, water, lava)
        BlockPos below = pos.down();
        Block ground = world.getBlockState(below).getBlock();
        if (ground == Blocks.AIR || ground == Blocks.CAVE_AIR
            || ground == Blocks.WATER || ground == Blocks.LAVA) return false;

        return true;
    }

    private boolean isBiomeBlacklisted(ServerWorld world, BlockPos pos) {
        if (blacklistBiomes.isEmpty()) return false;
        // Get biome path (e.g. "ocean", "deep_ocean")
        var biomeEntry = world.getBiome(pos);
        String biomePath = biomeEntry.getKey()
                .map(k -> k.getValue().getPath())
                .orElse("");
        return blacklistBiomes.stream().anyMatch(biomePath::contains);
    }

    // -----------------------------------------------------------------------
    //  Execute teleport (must run on server thread)
    // -----------------------------------------------------------------------

    /**
     * Teleports the player to the found position.
     * This method is safe to call from the server thread.
     */
    public void teleportPlayer(ServerPlayerEntity player, BlockPos pos) {
        player.teleport(
            (ServerWorld) player.getWorld(),
            pos.getX() + 0.5,
            pos.getY(),
            pos.getZ() + 0.5,
            player.getYaw(),
            player.getPitch()
        );
        setCooldown(player.getUuid());
    }
}
