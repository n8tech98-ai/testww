package com.n8tech.donutfabric.utils;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Formats numbers for display in economy contexts.
 * Produces clean, human-readable strings like "1,234.50" or "2.5K".
 */
public final class NumberFormatter {

    private NumberFormatter() {}

    private static final DecimalFormat MONEY_FORMAT;
    private static final DecimalFormat INT_FORMAT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        MONEY_FORMAT = new DecimalFormat("#,##0.00", symbols);
        INT_FORMAT   = new DecimalFormat("#,###", symbols);
    }

    /** Format a money double with 2 decimal places and thousand separators. */
    public static String format(double value) {
        return MONEY_FORMAT.format(value);
    }

    /** Format a long (shard count, item count) with thousand separators. */
    public static String formatLong(long value) {
        return INT_FORMAT.format(value);
    }

    /** Format a long with compact suffix (1K, 2.5M, etc.) for GUIs. */
    public static String compact(long value) {
        if (value >= 1_000_000_000L) return String.format("%.1fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L)     return String.format("%.1fM", value / 1_000_000.0);
        if (value >= 1_000L)         return String.format("%.1fK", value / 1_000.0);
        return String.valueOf(value);
    }

    /** Same but for doubles (balances etc.) */
    public static String compact(double value) {
        if (value >= 1_000_000_000.0) return String.format("%.1fB", value / 1_000_000_000.0);
        if (value >= 1_000_000.0)     return String.format("%.1fM", value / 1_000_000.0);
        if (value >= 1_000.0)         return String.format("%.1fK", value / 1_000.0);
        return MONEY_FORMAT.format(value);
    }

    /**
     * Parse a user-supplied number string, supporting K/M/B suffixes.
     * Returns -1 if parsing fails.
     */
    public static double parseInput(String input) {
        if (input == null || input.isBlank()) return -1;
        try {
            String cleaned = input.trim().replace(",", "");
            char last = Character.toLowerCase(cleaned.charAt(cleaned.length() - 1));
            if (last == 'k') return Double.parseDouble(cleaned.substring(0, cleaned.length() - 1)) * 1_000;
            if (last == 'm') return Double.parseDouble(cleaned.substring(0, cleaned.length() - 1)) * 1_000_000;
            if (last == 'b') return Double.parseDouble(cleaned.substring(0, cleaned.length() - 1)) * 1_000_000_000;
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
