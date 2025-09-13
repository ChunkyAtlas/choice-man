package com.choiceman.ui;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Per-frame sweep that dims item widgets whose bases are tracked but not usable.
 * Opacity semantics follow RuneLite: 0 is fully opaque, 255 is fully transparent.
 * Runs on the client thread during BeforeRender, which is an appropriate time to adjust widget properties.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ItemDimmerController {
    private final Client client;
    private final ChoiceManUnlocks unlocks;
    private final ItemsRepository itemsRepo;
    private final ItemManager itemManager;

    /**
     * 0..255, where 0 is opaque and 255 is transparent.
     */
    private volatile int dimOpacity = 150;

    /**
     * Master toggle controlled by config.
     */
    @Setter
    private volatile boolean enabled = true;

    public void setDimOpacity(int opacity) {
        // clamp without branching overflow
        this.dimOpacity = Math.max(0, Math.min(255, opacity));
    }

    /**
     * Cheap breadth-first sweep each frame so opacity never goes stale between scripts.
     * Skips when dragging widgets or a menu is open to avoid visual thrash.
     */
    @Subscribe
    public void onBeforeRender(BeforeRender e) {
        if (!enabled) return;
        if (client.getGameState() != GameState.LOGGED_IN) return;
        if (client.isDraggingWidget() || client.isMenuOpen()) return;

        Widget[] roots = client.getWidgetRoots();
        if (roots == null || roots.length == 0) return;

        // Iterative traversal avoids deep recursion in pathological widget trees.
        Deque<Widget> stack = new ArrayDeque<>(64);
        for (Widget r : roots) if (r != null) stack.push(r);

        while (!stack.isEmpty()) {
            Widget w = stack.pop();
            if (w == null || w.isHidden()) continue;

            int rawItemId = w.getItemId();
            if (rawItemId > 0) {
                int target = shouldDim(rawItemId) ? dimOpacity : 0;
                if (w.getOpacity() != target) {
                    // 0=opaque, 255=transparent
                    w.setOpacity(target);
                }
            }

            Widget[] a = w.getDynamicChildren();
            if (a != null) for (Widget c : a) if (c != null) stack.push(c);

            a = w.getStaticChildren();
            if (a != null) for (Widget c : a) if (c != null) stack.push(c);

            a = w.getNestedChildren();
            if (a != null) for (Widget c : a) if (c != null) stack.push(c);
        }
    }

    /**
     * Dim when the item is tracked and its base is not usable according to ChoiceManUnlocks.
     * Any failure resolves to "do not dim" to fail open.
     */
    private boolean shouldDim(int rawItemId) {
        try {
            int canonical = itemManager.canonicalize(rawItemId);
            if (canonical <= 0) return false;

            String base = itemsRepo.getBaseForId(canonical);
            if (base == null) return false; // not tracked → do not dim

            return !unlocks.isBaseUsable(base); // tracked but not usable → dim
        } catch (Exception ignored) {
            return false;
        }
    }
}
