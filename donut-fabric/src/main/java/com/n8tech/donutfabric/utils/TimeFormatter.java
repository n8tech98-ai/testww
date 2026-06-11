package com.n8tech.donutfabric.utils;

/**
 * Formats time durations for display in chat and GUIs.
 */
public final class TimeFormatter {

    private TimeFormatter() {}

    /**
     * Formats seconds into a compact human-readable string.
     * Examples: "5s", "2m 30s", "1h 5m 0s"
     */
    public static String formatSeconds(long totalSeconds) {
        if (totalSeconds <= 0) return "0s";

        long hours   = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0)   return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    /**
     * Formats milliseconds into a compact human-readable string.
     */
    public static String formatMillis(long millis) {
        return formatSeconds(millis / 1000L);
    }

    /**
     * Formats remaining time from an epoch-millisecond deadline.
     * Returns "Expired" if the deadline is in the past.
     */
    public static String formatRemaining(long deadlineEpochMillis) {
        long remaining = deadlineEpochMillis - System.currentTimeMillis();
        if (remaining <= 0) return "Expired";
        return formatMillis(remaining);
    }

    /**
     * Formats expiration time for order / AH listings.
     * e.g. "23h 59m" — hours and minutes only for longer durations.
     */
    public static String formatExpiry(long deadlineEpochMillis) {
        long remaining = deadlineEpochMillis - System.currentTimeMillis();
        if (remaining <= 0) return "Expired";
        long totalMins = remaining / 60_000L;
        if (totalMins < 1) return "< 1m";
        long hours = totalMins / 60;
        long mins  = totalMins % 60;
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}
