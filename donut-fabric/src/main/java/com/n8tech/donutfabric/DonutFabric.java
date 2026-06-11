package com.n8tech.donutfabric;

import com.n8tech.donutfabric.api.EconomyAPI;
import com.n8tech.donutfabric.auctionhouse.AuctionHouseManager;
import com.n8tech.donutfabric.auctionhouse.commands.AHCommand;
import com.n8tech.donutfabric.combat.CombatManager;
import com.n8tech.donutfabric.combat.listeners.CombatListener;
import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.database.DatabaseManager;
import com.n8tech.donutfabric.economy.EconomyManager;
import com.n8tech.donutfabric.economy.commands.*;
import com.n8tech.donutfabric.listeners.PlayerJoinListener;
import com.n8tech.donutfabric.listeners.PlayerLeaveListener;
import com.n8tech.donutfabric.orders.OrderManager;
import com.n8tech.donutfabric.orders.commands.OrdersCommand;
import com.n8tech.donutfabric.rtp.RTPManager;
import com.n8tech.donutfabric.rtp.commands.RTPCommand;
import com.n8tech.donutfabric.shards.ShardManager;
import com.n8tech.donutfabric.shards.commands.ShardsCommand;
import com.n8tech.donutfabric.utils.ModLogger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DonutFabric - Main mod entry point.
 * Initialises all managers, registers events, and starts background tasks.
 *
 * Architecture: every system lives in its own manager.
 * Managers are initialised in dependency order here and exposed via static accessors.
 * Background work is dispatched to the shared async executor — never block the server thread.
 */
public class DonutFabric implements ModInitializer {

    public static final String MOD_ID = "donutfabric";
    public static final String VERSION  = "1.0.0";

    // Shared thread pool for all async work (DB I/O, RTP chunk searching, etc.)
    public static final ScheduledExecutorService ASYNC_EXECUTOR =
            Executors.newScheduledThreadPool(
                    Math.max(4, Runtime.getRuntime().availableProcessors()),
                    r -> {
                        Thread t = new Thread(r, "DonutFabric-Async");
                        t.setDaemon(true);
                        return t;
                    });

    // ---------- singletons ----------
    private static DonutFabric instance;
    private static MinecraftServer server;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private EconomyManager economyManager;
    private OrderManager orderManager;
    private AuctionHouseManager auctionHouseManager;
    private ShardManager shardManager;
    private CombatManager combatManager;
    private RTPManager rtpManager;

    @Override
    public void onInitialize() {
        instance = this;
        ModLogger.info("DonutFabric " + VERSION + " loading...");

        // Config first — everything else reads from it
        configManager = new ConfigManager();
        configManager.loadAll();

        // Commands must be registered during onInitialize via CommandRegistrationCallback
        registerCommands();

        // Register lifecycle hooks so we get the server reference
        ServerLifecycleEvents.SERVER_STARTING.register(srv -> {
            server = srv;
            onServerStarting(srv);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> onServerStopping(srv));

        // Per-tick tasks (combat timer actionbar, etc.)
        ServerTickEvents.END_SERVER_TICK.register(srv -> onServerTick(srv));

        ModLogger.info("DonutFabric loaded successfully.");
    }

    // -----------------------------------------------------------------------
    //  Server lifecycle
    // -----------------------------------------------------------------------

    private void onServerStarting(MinecraftServer srv) {
        ModLogger.info("Initialising systems...");

        // 1. Database (blocking init is acceptable at startup)
        databaseManager = new DatabaseManager(configManager);
        databaseManager.init();

        // 2. Economy
        economyManager = new EconomyManager(databaseManager, configManager);
        economyManager.init();

        // 3. Orders
        orderManager = new OrderManager(databaseManager, configManager, economyManager);
        orderManager.init();

        // 4. Auction House
        auctionHouseManager = new AuctionHouseManager(databaseManager, configManager, economyManager);
        auctionHouseManager.init();

        // 5. Shards
        shardManager = new ShardManager(databaseManager, configManager);
        shardManager.init();

        // 6. Combat
        combatManager = new CombatManager(configManager);

        // 7. RTP
        rtpManager = new RTPManager(configManager);

        // Register event listeners
        registerListeners();

        // Background save task every 5 min
        ASYNC_EXECUTOR.scheduleAtFixedRate(
                this::asyncAutoSave, 5, 5, TimeUnit.MINUTES);

        // Order expiry sweep every 2 min
        ASYNC_EXECUTOR.scheduleAtFixedRate(
                orderManager::expireOldOrders, 2, 2, TimeUnit.MINUTES);

        // AH expiry sweep every 3 min
        ASYNC_EXECUTOR.scheduleAtFixedRate(
                auctionHouseManager::expireOldListings, 3, 3, TimeUnit.MINUTES);

        ModLogger.info("All systems online.");
    }

    private void onServerStopping(MinecraftServer srv) {
        ModLogger.info("Shutting down DonutFabric...");
        asyncAutoSave();
        ASYNC_EXECUTOR.shutdown();
        try {
            if (!ASYNC_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                ASYNC_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            ASYNC_EXECUTOR.shutdownNow();
        }
        databaseManager.close();
        ModLogger.info("DonutFabric shut down cleanly.");
    }

    private void onServerTick(MinecraftServer srv) {
        // Delegate per-tick logic to combat manager (actionbar updates, tag expiry)
        if (combatManager != null) combatManager.onTick(srv);
    }

    // -----------------------------------------------------------------------
    //  Registration helpers
    // -----------------------------------------------------------------------

    private void registerCommands() {
        // Economy
        BalCommand.register();
        PayCommand.register();
        SellCommand.register();

        // Orders
        OrdersCommand.register();

        // Auction House
        AHCommand.register();

        // Shards
        ShardsCommand.register();

        // RTP
        RTPCommand.register();
    }

    private void registerListeners() {
        new PlayerJoinListener(economyManager, shardManager, configManager).register();
        new PlayerLeaveListener(economyManager, shardManager, combatManager).register();
        new CombatListener(combatManager).register();
    }

    // -----------------------------------------------------------------------
    //  Async auto-save
    // -----------------------------------------------------------------------

    private void asyncAutoSave() {
        try {
            economyManager.saveAll();
            shardManager.saveAll();
            orderManager.saveAll();
            auctionHouseManager.saveAll();
        } catch (Exception e) {
            ModLogger.warn("Auto-save encountered an error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Static accessors (used by commands / GUIs)
    // -----------------------------------------------------------------------

    public static DonutFabric getInstance()            { return instance; }
    public static MinecraftServer getServer()          { return server; }
    public ConfigManager getConfigManager()            { return configManager; }
    public DatabaseManager getDatabaseManager()        { return databaseManager; }
    public EconomyManager getEconomyManager()          { return economyManager; }
    public OrderManager getOrderManager()              { return orderManager; }
    public AuctionHouseManager getAuctionHouseManager(){ return auctionHouseManager; }
    public ShardManager getShardManager()              { return shardManager; }
    public CombatManager getCombatManager()            { return combatManager; }
    public RTPManager getRTPManager()                  { return rtpManager; }

    /** Convenience: public economy API surface for external mods / scripts. */
    public static EconomyAPI economy() {
        return instance.economyManager;
    }
}
