package com.choiceman.account;

import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.AccountHashChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks the current account context (account hash + display name) and emits {@link AccountChanged}
 * when it becomes known or changes.
 */
@Singleton
public class AccountManager {
    private final Client client;
    private final EventBus eventBus;

    /**
     * -1 indicates "no account".
     */
    private volatile long hash = -1;

    /**
     * May be null until the player object is available.
     */
    @Getter
    private volatile String playerName;

    /**
     * Set to true after we successfully latched a non-null name for the current hash.
     */
    private volatile boolean nameSet = false;

    @Inject
    public AccountManager(Client client, EventBus eventBus) {
        this.client = client;
        this.eventBus = eventBus;
    }

    /**
     * @return true when we have a valid account hash and a resolved player name.
     */
    public boolean ready() {
        return hash != -1 && nameSet;
    }

    /**
     * Capture initial account hash on plugin startup if already logged in.
     * Name resolution is deferred to {@link #onClientTick(ClientTick)}.
     */
    public void init() {
        if (client.getGameState() == GameState.LOGGED_IN) {
            long h = client.getAccountHash();
            if (h != -1) {
                hash = h;
                nameSet = false;
            }
        }
    }

    @Subscribe
    private void onAccountHashChanged(AccountHashChanged event) {
        long newHash = client.getAccountHash();
        if (hash != newHash) {
            hash = newHash;
            nameSet = false; // player instance/name may not be ready yet
        }
    }

    /**
     * Resolves the display name once the local player is available.
     * Runs very early every frame; keep checks cheap and exit fast.
     */
    @Subscribe
    private void onClientTick(ClientTick event) {
        // Avoid work before the client is loading or when we don't need to resolve a name.
        if (client.getGameState().getState() < GameState.LOADING.getState()) return;
        if (hash == -1 || nameSet) return;

        Player p = client.getLocalPlayer();
        if (p == null) return;

        String name = p.getName();
        if (name == null) return;

        playerName = name;
        nameSet = true;
        emit();
    }

    @Subscribe
    private void onGameStateChanged(GameStateChanged event) {
        // When hitting the login screen, clear state so downstream consumers can reset.
        if (event.getGameState() == GameState.LOGIN_SCREEN && hash != -1) {
            reset();
        }
    }

    /**
     * Clear current account context and notify listeners.
     */
    public void reset() {
        hash = -1;
        playerName = null;
        nameSet = false;
        emit();
    }

    private void emit() {
        eventBus.post(new AccountChanged(hash, playerName));
    }
}
