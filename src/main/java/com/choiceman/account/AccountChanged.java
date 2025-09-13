package com.choiceman.account;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Immutable event payload published when the active account context changes.
 */
@Getter
@EqualsAndHashCode
@ToString
public final class AccountChanged {
    /**
     * Client-provided stable account hash, or {@code -1} when no account is active.
     */
    private final long hash;

    /**
     * Current local player's display name, or {@code null} if unknown at the time of emission.
     */
    private final String playerName;

    /**
     * Convenience flag indicating whether an account is considered logged in.
     * True iff {@link #hash} != -1.
     */
    private final boolean loggedIn;

    public AccountChanged(long hash, String playerName) {
        this.hash = hash;
        this.playerName = playerName;
        this.loggedIn = hash != -1;
    }
}
