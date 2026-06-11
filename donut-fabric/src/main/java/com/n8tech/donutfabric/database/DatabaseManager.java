package com.n8tech.donutfabric.database;

import com.n8tech.donutfabric.config.ConfigManager;
import com.n8tech.donutfabric.utils.ModLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

/**
 * DatabaseManager wraps SQLite (default) or MongoDB.
 * All callers receive a thin DAO via getDAO(), which hides the backend.
 *
 * SQLite is the default; MongoDB is enabled by setting database-type=mongodb in config.yml.
 * All blocking DB operations must be called from the ASYNC_EXECUTOR — never the server thread.
 */
public class DatabaseManager {

    private final ConfigManager config;
    private DatabaseType type;

    // SQLite
    private Connection sqliteConn;

    // MongoDB (lazy; only constructed if type == MONGODB)
    private MongoBackend mongoBackend;

    public enum DatabaseType { SQLITE, MONGODB }

    public DatabaseManager(ConfigManager config) {
        this.config = config;
    }

    // -----------------------------------------------------------------------
    //  Initialisation
    // -----------------------------------------------------------------------

    public void init() {
        String dbType = config.getString("database-type");
        if ("mongodb".equalsIgnoreCase(dbType)) {
            type = DatabaseType.MONGODB;
            initMongo();
        } else {
            type = DatabaseType.SQLITE;
            initSQLite();
        }
        createTables();
        ModLogger.info("Database initialised (" + type + ")");
    }

    private void initSQLite() {
        try {
            Path dbPath = Path.of("config", "donutfabric", "data.db");
            Files.createDirectories(dbPath.getParent());
            Class.forName("org.sqlite.JDBC");
            sqliteConn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            // Enable WAL for concurrent reads
            try (Statement s = sqliteConn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
                s.execute("PRAGMA cache_size=10000");
            }
        } catch (Exception e) {
            throw new RuntimeException("SQLite init failed: " + e.getMessage(), e);
        }
    }

    private void initMongo() {
        String uri = config.getString("mongodb-uri");
        mongoBackend = new MongoBackend(uri);
        mongoBackend.init();
    }

    // -----------------------------------------------------------------------
    //  Table / index creation
    // -----------------------------------------------------------------------

    private void createTables() {
        if (type == DatabaseType.SQLITE) {
            createSQLiteTables();
        }
        // MongoDB creates collections lazily
    }

    private void createSQLiteTables() {
        String[] ddl = {
            // Economy balances
            """
            CREATE TABLE IF NOT EXISTS economy (
                uuid        TEXT PRIMARY KEY,
                username    TEXT NOT NULL,
                balance     REAL NOT NULL DEFAULT 0,
                multiplier  REAL NOT NULL DEFAULT 1.0,
                total_sold  REAL NOT NULL DEFAULT 0,
                last_seen   INTEGER NOT NULL DEFAULT 0
            )
            """,
            // Shard balances
            """
            CREATE TABLE IF NOT EXISTS shards (
                uuid        TEXT PRIMARY KEY,
                balance     INTEGER NOT NULL DEFAULT 0
            )
            """,
            // Orders (buy orders posted by players)
            """
            CREATE TABLE IF NOT EXISTS orders (
                id          TEXT PRIMARY KEY,
                owner_uuid  TEXT NOT NULL,
                owner_name  TEXT NOT NULL,
                item_id     TEXT NOT NULL,
                quantity    INTEGER NOT NULL,
                filled      INTEGER NOT NULL DEFAULT 0,
                price_each  REAL NOT NULL,
                tax_paid    REAL NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL,
                expires_at  INTEGER NOT NULL,
                status      TEXT NOT NULL DEFAULT 'OPEN'
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_orders_item ON orders(item_id, status)",
            "CREATE INDEX IF NOT EXISTS idx_orders_owner ON orders(owner_uuid)",
            // Auction House listings
            """
            CREATE TABLE IF NOT EXISTS auction_listings (
                id          TEXT PRIMARY KEY,
                seller_uuid TEXT NOT NULL,
                seller_name TEXT NOT NULL,
                item_nbt    BLOB NOT NULL,
                item_name   TEXT NOT NULL,
                quantity    INTEGER NOT NULL,
                price       REAL NOT NULL,
                created_at  INTEGER NOT NULL,
                expires_at  INTEGER NOT NULL,
                status      TEXT NOT NULL DEFAULT 'ACTIVE',
                buyer_uuid  TEXT,
                buyer_name  TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_ah_item ON auction_listings(item_name, status)",
            "CREATE INDEX IF NOT EXISTS idx_ah_seller ON auction_listings(seller_uuid)",
            // Transaction log
            """
            CREATE TABLE IF NOT EXISTS transactions (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp   INTEGER NOT NULL,
                from_uuid   TEXT,
                to_uuid     TEXT,
                type        TEXT NOT NULL,
                amount      REAL NOT NULL,
                description TEXT
            )
            """
        };

        try (Statement s = sqliteConn.createStatement()) {
            for (String sql : ddl) {
                s.execute(sql.trim());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Table creation failed: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    //  Connection / query helpers
    // -----------------------------------------------------------------------

    /** Returns a live SQLite connection. Thread-safety is the caller's concern. */
    public synchronized Connection getSQLite() {
        try {
            if (sqliteConn == null || sqliteConn.isClosed()) {
                initSQLite();
            }
        } catch (SQLException e) {
            ModLogger.warn("SQLite connection check failed: " + e.getMessage());
        }
        return sqliteConn;
    }

    public MongoBackend getMongo() { return mongoBackend; }
    public DatabaseType getType()  { return type; }

    // -----------------------------------------------------------------------
    //  Generic upsert helper (SQLite)
    // -----------------------------------------------------------------------

    public synchronized void upsertEconomy(UUID uuid, String name, double balance,
                                            double multiplier, double totalSold) {
        String sql = """
            INSERT INTO economy (uuid, username, balance, multiplier, total_sold, last_seen)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                username   = excluded.username,
                balance    = excluded.balance,
                multiplier = excluded.multiplier,
                total_sold = excluded.total_sold,
                last_seen  = excluded.last_seen
            """;
        try (PreparedStatement ps = getSQLite().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setDouble(3, balance);
            ps.setDouble(4, multiplier);
            ps.setDouble(5, totalSold);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            ModLogger.warn("upsertEconomy error: " + e.getMessage());
        }
    }

    public synchronized void upsertShards(UUID uuid, int balance) {
        String sql = """
            INSERT INTO shards (uuid, balance) VALUES (?, ?)
            ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance
            """;
        try (PreparedStatement ps = getSQLite().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, balance);
            ps.executeUpdate();
        } catch (SQLException e) {
            ModLogger.warn("upsertShards error: " + e.getMessage());
        }
    }

    public synchronized void logTransaction(UUID from, UUID to, String type,
                                             double amount, String description) {
        String sql = """
            INSERT INTO transactions (timestamp, from_uuid, to_uuid, type, amount, description)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = getSQLite().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, from != null ? from.toString() : null);
            ps.setString(3, to   != null ? to.toString()   : null);
            ps.setString(4, type);
            ps.setDouble(5, amount);
            ps.setString(6, description);
            ps.executeUpdate();
        } catch (SQLException e) {
            ModLogger.warn("logTransaction error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    //  Lifecycle
    // -----------------------------------------------------------------------

    public synchronized void close() {
        try {
            if (sqliteConn != null && !sqliteConn.isClosed()) {
                sqliteConn.close();
                ModLogger.info("SQLite connection closed.");
            }
        } catch (SQLException e) {
            ModLogger.warn("Error closing SQLite: " + e.getMessage());
        }
        if (mongoBackend != null) mongoBackend.close();
    }
}
