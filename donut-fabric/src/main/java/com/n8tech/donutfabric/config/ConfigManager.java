package com.n8tech.donutfabric.config;

import com.n8tech.donutfabric.utils.ModLogger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads and provides access to all YAML configuration files.
 *
 * Files are stored in ./config/donutfabric/ relative to the server root.
 * Missing files are created from built-in defaults on first load.
 */
public class ConfigManager {

    private static final Path CONFIG_DIR = Path.of("config", "donutfabric");

    private Map<String, Object> mainConfig;
    private Map<String, Object> worthConfig;
    private Map<String, Object> messagesConfig;
    private Map<String, Object> shardsConfig;
    private Map<String, Object> rtpConfig;

    private final Yaml yaml;

    public ConfigManager() {
        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);
        this.yaml = new Yaml(opts);
    }

    // -----------------------------------------------------------------------
    //  Initialisation
    // -----------------------------------------------------------------------

    public void loadAll() {
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            ModLogger.warn("Could not create config directory: " + e.getMessage());
        }

        mainConfig     = loadOrCreate("config.yml",   defaultMainConfig());
        worthConfig    = loadOrCreate("worth.yml",    defaultWorthConfig());
        messagesConfig = loadOrCreate("messages.yml", defaultMessagesConfig());
        shardsConfig   = loadOrCreate("shards.yml",   defaultShardsConfig());
        rtpConfig      = loadOrCreate("rtp.yml",      defaultRTPConfig());

        ModLogger.info("Configuration loaded from " + CONFIG_DIR);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadOrCreate(String fileName, Map<String, Object> defaults) {
        Path file = CONFIG_DIR.resolve(fileName);
        if (!Files.exists(file)) {
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
                yaml.dump(defaults, w);
                ModLogger.info("Created default " + fileName);
            } catch (IOException e) {
                ModLogger.warn("Could not write " + fileName + ": " + e.getMessage());
            }
            return defaults;
        }
        try (Reader r = new InputStreamReader(
                new FileInputStream(file.toFile()), StandardCharsets.UTF_8)) {
            Map<String, Object> loaded = yaml.load(r);
            if (loaded == null) return defaults;
            // Back-fill any keys that exist in defaults but not in the file
            defaults.forEach(loaded::putIfAbsent);
            return loaded;
        } catch (IOException e) {
            ModLogger.warn("Could not read " + fileName + ": " + e.getMessage());
            return defaults;
        }
    }

    // -----------------------------------------------------------------------
    //  Typed getters (dot-path navigation)
    // -----------------------------------------------------------------------

    public String getString(Map<String, Object> cfg, String path, String def) {
        Object v = resolve(cfg, path);
        return v != null ? v.toString() : def;
    }

    public int getInt(Map<String, Object> cfg, String path, int def) {
        Object v = resolve(cfg, path);
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    public long getLong(Map<String, Object> cfg, String path, long def) {
        Object v = resolve(cfg, path);
        if (v instanceof Number n) return n.longValue();
        return def;
    }

    public double getDouble(Map<String, Object> cfg, String path, double def) {
        Object v = resolve(cfg, path);
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }

    public boolean getBoolean(Map<String, Object> cfg, String path, boolean def) {
        Object v = resolve(cfg, path);
        if (v instanceof Boolean b) return b;
        return def;
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Map<String, Object> cfg, String path) {
        String[] parts = path.split("\\.");
        Object cur = cfg;
        for (String p : parts) {
            if (!(cur instanceof Map<?, ?> map)) return null;
            cur = ((Map<String, Object>) map).get(p);
        }
        return cur;
    }

    // -----------------------------------------------------------------------
    //  Public config accessors
    // -----------------------------------------------------------------------

    public Map<String, Object> getMainConfig()     { return mainConfig; }
    public Map<String, Object> getWorthConfig()    { return worthConfig; }
    public Map<String, Object> getMessagesConfig() { return messagesConfig; }
    public Map<String, Object> getShardsConfig()   { return shardsConfig; }
    public Map<String, Object> getRTPConfig()      { return rtpConfig; }

    // -----------------------------------------------------------------------
    //  Shorthand helpers (reads from main config)
    // -----------------------------------------------------------------------

    public String getString(String path)           { return getString(mainConfig, path, ""); }
    public int getInt(String path, int def)        { return getInt(mainConfig, path, def); }
    public double getDouble(String path, double d) { return getDouble(mainConfig, path, d); }
    public boolean getBoolean(String path)         { return getBoolean(mainConfig, path, false); }

    /** Alias for {@link #getMainConfig()} — used by PlayerJoinListener and others. */
    public Map<String, Object> getConfig()          { return mainConfig; }

    /**
     * Retrieves a message string from messages.yml by key.
     * Falls back to {@code defaultValue} if the key is absent.
     */
    public String getMessage(String key, String defaultValue) {
        Object val = messagesConfig.get(key);
        return val != null ? val.toString() : defaultValue;
    }

    // -----------------------------------------------------------------------
    //  Default configs
    // -----------------------------------------------------------------------

    private Map<String, Object> defaultMainConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("database-type", "sqlite");             // sqlite | mongodb
        m.put("mongodb-uri",   "mongodb://localhost:27017/donut");
        m.put("economy", Map.of(
            "starting-balance", 500.0,
            "max-balance",      1_000_000_000.0,
            "currency-symbol",  "$",
            "currency-name",    "Dollars",
            "sell-tax",         0.0,
            "pay-tax",          0.05,
            "multiplier-enabled", true,
            "multiplier-max",   3.0,
            "multiplier-step",  0.1,
            "multiplier-threshold", 100000.0
        ));
        m.put("orders", Map.of(
            "max-per-player",     10,
            "tax-rate",           0.08,
            "expiry-hours",       72,
            "min-price",          1.0,
            "max-price",          10_000_000.0
        ));
        m.put("auction-house", Map.of(
            "max-per-player",     15,
            "listing-tax",        0.05,
            "expiry-hours",       48,
            "min-price",          1.0,
            "max-price",          100_000_000.0
        ));
        m.put("combat", Map.of(
            "tag-duration",       15,
            "logout-punish",      true,
            "pearl-cooldown",     12,
            "safezone-worlds",    java.util.List.of("spawn_world")
        ));
        return m;
    }

    private Map<String, Object> defaultWorthConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        // Sell prices per item (vanilla item IDs)
        // These match approximate DonutSMP 2026 sell values
        m.put("diamond",             45.0);
        m.put("emerald",             12.0);
        m.put("gold_ingot",          6.0);
        m.put("iron_ingot",          2.0);
        m.put("coal",                0.5);
        m.put("redstone",            1.0);
        m.put("lapis_lazuli",        1.5);
        m.put("quartz",              0.8);
        m.put("amethyst_shard",      3.0);
        m.put("nether_star",         2500.0);
        m.put("wither_skeleton_skull", 120.0);
        m.put("blaze_rod",           4.0);
        m.put("ghast_tear",          8.0);
        m.put("magma_cream",         3.0);
        m.put("ender_pearl",         5.0);
        m.put("eye_of_ender",        20.0);
        m.put("shulker_shell",       180.0);
        m.put("elytra",              8000.0);
        m.put("totem_of_undying",    450.0);
        m.put("end_crystal",         15.0);
        m.put("obsidian",            0.5);
        m.put("crying_obsidian",     2.0);
        m.put("respawn_anchor",      50.0);
        m.put("ancient_debris",      250.0);
        m.put("netherite_ingot",     800.0);
        m.put("netherite_scrap",     200.0);
        m.put("wheat",               0.3);
        m.put("carrot",              0.4);
        m.put("potato",              0.3);
        m.put("sugar_cane",          0.2);
        m.put("cactus",              0.15);
        m.put("kelp",                0.1);
        m.put("bamboo",              0.1);
        m.put("melon_slice",         0.2);
        m.put("pumpkin",             0.5);
        m.put("chorus_fruit",        1.5);
        m.put("bone_meal",           0.4);
        m.put("gunpowder",           2.0);
        m.put("slime_ball",          2.5);
        m.put("spider_eye",          1.0);
        m.put("string",              0.5);
        m.put("feather",             0.3);
        m.put("leather",             1.5);
        m.put("rabbit_hide",         0.8);
        m.put("ink_sac",             0.5);
        m.put("glow_ink_sac",        3.0);
        m.put("honeycomb",           2.0);
        m.put("honey_bottle",        1.5);
        m.put("book",                1.0);
        m.put("paper",               0.2);
        m.put("glass",               0.2);
        m.put("sand",                0.1);
        m.put("gravel",              0.1);
        m.put("clay",                0.3);
        m.put("terracotta",          0.2);
        m.put("prismarine_shard",    1.5);
        m.put("prismarine_crystals", 2.0);
        m.put("heart_of_the_sea",    3500.0);
        m.put("nautilus_shell",      25.0);
        m.put("phantom_membrane",    15.0);
        m.put("turtle_egg",          20.0);
        m.put("axolotl_bucket",      30.0);
        m.put("rabbit_foot",         3.0);
        m.put("golden_apple",        25.0);
        m.put("enchanted_golden_apple", 1200.0);
        m.put("experience_bottle",   5.0);
        m.put("trident",             600.0);
        m.put("music_disc_13",       80.0);
        m.put("music_disc_cat",      80.0);
        m.put("music_disc_otherside",150.0);
        return m;
    }

    private Map<String, Object> defaultMessagesConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefix",              "&8[&6DonutSMP&8]&r ");
        m.put("balance",             "&aBal: &e{balance}");
        m.put("balance-other",       "&a{player}&7's bal: &e{balance}");
        m.put("pay-sent",            "&aSent &e{amount}&a to &e{target}");
        m.put("pay-received",        "&e{sender}&a sent you &e{amount}");
        m.put("sell-success",        "&aSold &e{count}x {item}&a for &e{total}");
        m.put("sell-nothing",        "&cNothing sellable in hand.");
        m.put("insufficient-funds",  "&cInsufficient balance.");
        m.put("combat-tagged",       "&cYou are combat tagged! ({seconds}s)");
        m.put("combat-untag",        "&aCombat tag expired.");
        m.put("logout-punish",       "&c{player} tried to combat log and was killed.");
        m.put("rtp-searching",       "&7Finding a safe location...");
        m.put("rtp-success",         "&aTeleported to &e{x}&a, &e{z}");
        m.put("rtp-cooldown",        "&cRTP cooldown: &e{seconds}s");
        m.put("no-permission",       "&cYou don't have permission.");
        return m;
    }

    private Map<String, Object> defaultShardsConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("starting-shards",  0);
        m.put("kill-reward",      10);
        m.put("afk-rate-per-min", 1);
        m.put("shop", Map.of(
            "spawner-cost", 1500,
            "items", java.util.List.of(
                Map.of("id","pig_spawner",    "cost",1500,"display","&6Pig Spawner",    "material","pig_spawn_egg"),
                Map.of("id","cow_spawner",    "cost",1500,"display","&6Cow Spawner",    "material","cow_spawn_egg"),
                Map.of("id","sheep_spawner",  "cost",1500,"display","&6Sheep Spawner",  "material","sheep_spawn_egg"),
                Map.of("id","chicken_spawner","cost",1500,"display","&6Chicken Spawner","material","chicken_spawn_egg"),
                Map.of("id","zombie_spawner", "cost",1500,"display","&6Zombie Spawner", "material","zombie_spawn_egg"),
                Map.of("id","skeleton_spawner","cost",1500,"display","&6Skeleton Spawner","material","skeleton_spawn_egg"),
                Map.of("id","spider_spawner", "cost",1500,"display","&6Spider Spawner", "material","spider_spawn_egg"),
                Map.of("id","blaze_spawner",  "cost",2000,"display","&cBlaze Spawner",  "material","blaze_spawn_egg"),
                Map.of("id","enderman_spawner","cost",2500,"display","&5Enderman Spawner","material","enderman_spawn_egg"),
                Map.of("id","wither_skeleton_spawner","cost",3000,"display","&8Wither Skeleton Spawner","material","wither_skeleton_spawn_egg")
            )
        ));
        return m;
    }

    private Map<String, Object> defaultRTPConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",           true);
        m.put("min-radius",        500);
        m.put("max-radius",        8000);
        m.put("cooldown-seconds",  180);
        m.put("max-attempts",      30);
        m.put("safe-worlds",       java.util.List.of("world", "world_nether", "world_the_end"));
        m.put("blacklist-biomes",  java.util.List.of("ocean","deep_ocean","frozen_ocean","warm_ocean",
                "lukewarm_ocean","cold_ocean","deep_lukewarm_ocean","deep_cold_ocean",
                "deep_frozen_ocean","mushroom_fields","lava_ocean"));
        return m;
    }
}
