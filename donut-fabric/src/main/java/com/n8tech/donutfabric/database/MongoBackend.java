package com.n8tech.donutfabric.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.n8tech.donutfabric.utils.ModLogger;

/**
 * Thin wrapper around the MongoDB sync driver.
 * Collections are accessed by name; documents use BSON Documents.
 */
public class MongoBackend {

    private final String uri;
    private MongoClient client;
    private MongoDatabase db;

    public MongoBackend(String uri) {
        this.uri = uri;
    }

    public void init() {
        try {
            client = MongoClients.create(uri);
            // Extract DB name from URI or default to "donut"
            String dbName = "donut";
            if (uri.contains("/")) {
                String path = uri.substring(uri.lastIndexOf('/') + 1);
                if (!path.isEmpty() && !path.contains("?")) dbName = path;
                else if (path.contains("?")) dbName = path.substring(0, path.indexOf('?'));
            }
            db = client.getDatabase(dbName);
            ModLogger.info("MongoDB connected to database: " + dbName);
        } catch (Exception e) {
            throw new RuntimeException("MongoDB init failed: " + e.getMessage(), e);
        }
    }

    public MongoDatabase getDb() { return db; }

    public void close() {
        if (client != null) {
            client.close();
            ModLogger.info("MongoDB connection closed.");
        }
    }
}
