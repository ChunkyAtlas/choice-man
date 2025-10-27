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
    private static final int SPRITE_ID = 910;
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

    public void register() {
        spriteManager.addSpriteOverrides(new SpriteOverride[]{this});
        clientThread.invokeLater(() -> {
            try (var in = SpriteOverrideManager.class.getResourceAsStream(RESOURCE)) {
                if (in != null) {
                    var img = javax.imageio.ImageIO.read(in);
                    if (img != null) {
                        var pixels = net.runelite.client.util.ImageUtil.getImageSpritePixels(img, client);
                        client.getSpriteOverrides().put(SPRITE_ID, pixels);
                    }
                } else {
                    System.out.println("[ChoiceMan] unlocks.png not found at " + RESOURCE);
                }
            } catch (Exception ignored) { /* noop */ }

            client.getWidgetSpriteCache().reset();
        });
    }

    public void unregister() {
        spriteManager.removeSpriteOverrides(new SpriteOverride[]{this});
        clientThread.invokeLater(() -> {
            client.getSpriteOverrides().remove(SPRITE_ID); // clear any direct override too
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
