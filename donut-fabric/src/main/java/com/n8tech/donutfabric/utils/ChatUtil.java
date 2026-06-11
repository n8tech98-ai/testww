package com.n8tech.donutfabric.utils;

import net.minecraft.text.Text;

/**
 * Utility for translating legacy Minecraft color codes (&a, &c, etc.)
 * and building Text components from config strings.
 */
public final class ChatUtil {

    private ChatUtil() {}

    /**
     * Alias for {@link #translate(String)} — translates &amp; color codes to § codes.
     * Named {@code color()} to match usage across all command/GUI classes.
     */
    public static String color(String message) {
        return translate(message);
    }

    /**
     * Translates & color codes to § codes and returns a Text component.
     * Supports all 16 color codes (&0-&9, &a-&f) and formatters (&l, &o, &n, &m, &r, &k).
     */
    public static Text colorize(String message) {
        return Text.literal(translate(message));
    }

    /**
     * Returns the raw § string (for use in item names / lore where Text isn't needed).
     */
    public static String translate(String message) {
        if (message == null) return "";
        return message.replace('&', '§');
    }

    /**
     * Strips all § color codes from a string.
     */
    public static String stripColor(String message) {
        if (message == null) return "";
        return message.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    /**
     * Builds a Text component with a standard DonutSMP prefix.
     * Prefix is dark gray [D] in gold/gray color style.
     */
    public static Text prefixed(String message) {
        return Text.literal(translate("&8[&6Donut&8] &7" + message));
    }

    public static Text error(String message) {
        return Text.literal(translate("&8[&cDonut&8] &c" + message));
    }

    public static Text success(String message) {
        return Text.literal(translate("&8[&aDonut&8] &a" + message));
    }

    public static Text info(String message) {
        return Text.literal(translate("&8[&eDonut&8] &e" + message));
    }

    /**
     * Formats a money value with color: green if positive, red if zero.
     * e.g. "$1,234.50"
     */
    public static String moneyColored(double amount) {
        if (amount > 0) return "&a$" + NumberFormatter.format(amount);
        return "&c$" + NumberFormatter.format(amount);
    }

    /**
     * Formats a shard value with color.
     */
    public static String shardsColored(long amount) {
        return "&b" + NumberFormatter.formatLong(amount) + " ✦";
    }
}
