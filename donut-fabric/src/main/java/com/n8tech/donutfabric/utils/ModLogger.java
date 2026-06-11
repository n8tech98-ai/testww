package com.n8tech.donutfabric.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized logger for DonutFabric.
 * Uses SLF4J (provided by Fabric) so output routes through the server log.
 */
public final class ModLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("DonutFabric");

    private ModLogger() {}

    public static void info(String msg) {
        LOGGER.info("[DonutFabric] " + msg);
    }

    public static void warn(String msg) {
        LOGGER.warn("[DonutFabric] " + msg);
    }

    public static void error(String msg) {
        LOGGER.error("[DonutFabric] " + msg);
    }

    public static void error(String msg, Throwable t) {
        LOGGER.error("[DonutFabric] " + msg, t);
    }

    public static void debug(String msg) {
        // Gated on system property to avoid log spam in production
        if (Boolean.getBoolean("donutfabric.debug")) {
            LOGGER.info("[DonutFabric][DEBUG] " + msg);
        }
    }
}
