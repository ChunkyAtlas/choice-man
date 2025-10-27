package com.choiceman.ui;

import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates and caches scaled item sprites for use in the unlocks view.
 * <p>
 * Notes on threading
 * • ItemManager#getImage may return an AsyncBufferedImage when off the game thread; the image will fill in later, which is fine for UI usage. :contentReference[oaicite:0]{index=0}
 * • Writes to Client#getSpriteOverrides and clearing the widget sprite cache must happen on the client thread. :contentReference[oaicite:1]{index=1}
 * • ImageUtil helpers are pure image operations and are safe to call off-thread. :contentReference[oaicite:2]{index=2}
 */
@Singleton
public final class ItemSpriteCache {
    private static final int ICON_SIZE = 32;
    private static final int FIRST_CUSTOM_SPRITE_ID = 0x10000;

    private final ItemManager itemManager;
    private final Client client;
    private final ClientThread clientThread;

    /**
     * itemId -> generated spriteId
     */
    private final Map<Integer, Integer> spriteIds = new ConcurrentHashMap<>();

    /**
     * Unique sprite IDs for overrides.
     */
    private final AtomicInteger nextSpriteId = new AtomicInteger(FIRST_CUSTOM_SPRITE_ID);

    @Inject
    public ItemSpriteCache(ItemManager itemManager, Client client, ClientThread clientThread) {
        this.itemManager = itemManager;
        this.client = client;
        this.clientThread = clientThread;
    }

    /**
     * Returns a sprite ID for the given item.
     * If missing, generates a 32x32 override image and registers it on the client thread.
     *
     * @param itemId any item definition ID
     * @return the override sprite ID, or -1 if the base item image is unavailable
     */
    public int getSpriteId(int itemId) {
        // Fast path if we already have it
        Integer cached = spriteIds.get(itemId);
        if (cached != null) {
            return cached;
        }

        // Obtain the base item image; may be an AsyncBufferedImage that fills later
        BufferedImage img = itemManager.getImage(itemId, 1, false);
        if (img == null) {
            return -1;
        }

        // Resize off-thread
        BufferedImage resized = ImageUtil.resizeImage(img, ICON_SIZE, ICON_SIZE);

        // Build SpritePixels and register the override on the client thread
        final int spriteId = nextSpriteId.getAndIncrement();
        clientThread.invokeLater(() ->
        {
            SpritePixels pixels = ImageUtil.getImageSpritePixels(resized, client);
            client.getSpriteOverrides().put(spriteId, pixels);
        });

        // Publish to cache after scheduling registration
        spriteIds.put(itemId, spriteId);
        return spriteId;
    }

    /**
     * Removes all generated overrides and resets the ID counter.
     * Must run on the client thread to safely mutate the override map and cache.
     */
    public void clear() {
        Map<Integer, Integer> snapshot = Map.copyOf(spriteIds);
        spriteIds.clear();

        clientThread.invokeLater(() ->
        {
            snapshot.values().forEach(id -> client.getSpriteOverrides().remove(id));
            nextSpriteId.set(FIRST_CUSTOM_SPRITE_ID);
        });
    }
}
