package com.n8tech.donutfabric.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public economy API.
 * External mods or scripts should interact with the economy only through this interface.
 */
public interface EconomyAPI {

    /** Returns the player's current balance. */
    double getBalance(UUID uuid);

    /** Returns the player's sell multiplier (1.0 – 3.0). */
    double getMultiplier(UUID uuid);

    /**
     * Deposits {@code amount} into the player's account.
     * @return true if successful
     */
    boolean deposit(UUID uuid, double amount);

    /**
     * Withdraws {@code amount} from the player's account.
     * @return true if they had sufficient funds and the withdrawal succeeded
     */
    boolean withdraw(UUID uuid, double amount);

    /**
     * Transfers {@code amount} from {@code from} to {@code to} after applying the pay tax.
     * @return true on success
     */
    boolean transfer(UUID from, UUID to, double amount);

    /** Returns true if the player has at least {@code amount}. */
    boolean has(UUID uuid, double amount);

    /** Formats a monetary value for display (e.g. "$1,234.50"). */
    String format(double amount);

    /** Async version of getBalance — resolves off the server thread. */
    CompletableFuture<Double> getBalanceAsync(UUID uuid);
}
