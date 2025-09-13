package com.choiceman.ui;

import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.SpriteOverride;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Temporarily replaces a widget sprite (the Music tab icon) while the Choice Man
 * unlocks view is active, and restores the original once deactivated.
 * <p>
 * All client state mutations (sprite registration and cache reset) are scheduled
 * on the client thread to avoid race conditions with the game loop.
 */
@Singleton
public final class SpriteOverrideManager implements SpriteOverride {
    /**
     * Target widget sprite id to override.
     */
    private static final int SPRITE_ID = 910;

    /**
     * PNG resource placed on the classpath to serve as the replacement sprite.
     */
    private static final String RESOURCE = "/com/choiceman/ui/unlocks.png";

    private final SpriteManager spriteManager;
    private final Client client;
    private final ClientThread clientThread;

    @Inject
    public SpriteOverrideManager(
            SpriteManager spriteManager,
            Client client,
            ClientThread clientThread
    ) {
        this.spriteManager = spriteManager;
        this.client = client;
        this.clientThread = clientThread;
    }

    /**
     * Registers this override and refreshes the widget sprite cache on the client thread.
     * Safe to call repeatedly; SpriteManager handles idempotency for duplicates.
     */
    public void register() {
        clientThread.invokeLater(() -> {
            spriteManager.addSpriteOverrides(new SpriteOverride[]{this});
            client.getWidgetSpriteCache().reset();
        });
    }

    /**
     * Unregisters this override and refreshes the widget sprite cache on the client thread.
     * Safe to call even if the override is not currently registered.
     */
    public void unregister() {
        clientThread.invokeLater(() -> {
            spriteManager.removeSpriteOverrides(new SpriteOverride[]{this});
            client.getWidgetSpriteCache().reset();
        });
    }

    @Override
    public int getSpriteId() {
        return SPRITE_ID;
    }

    @Override
    public String getFileName() {
        return RESOURCE;
    }
}
