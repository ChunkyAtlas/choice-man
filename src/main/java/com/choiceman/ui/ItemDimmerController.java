package com.choiceman.ui;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dims item icon widgets when the item is tracked (present in items.json) and its base is NOT usable.
 *  only enforces during BeforeRender so clientscripts in the same frame can't undo it.
 * <p>
 * Opacity semantics: 0 = fully opaque, 255 = fully transparent.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ItemDimmerController {
    private final Client client;
    private final ChoiceManUnlocks unlocks;
    private final ItemsRepository itemsRepo;
    private final ItemManager itemManager;
    /**
     * Cache: canonical item id -> base name (null if not tracked).
     */
    private final ConcurrentHashMap<Integer, String> baseCache = new ConcurrentHashMap<>();
    /**
     * 0..255 where 0 is opaque and 255 is transparent.
     */
    private volatile int dimOpacity = 150;
    @Setter
    private volatile boolean enabled = true;

    public void setDimOpacity(int opacity) {
        this.dimOpacity = Math.max(0, Math.min(255, opacity));
    }

    /**
     * listen for key scripts but do not perform work here.
     * Enforcement happens once per frame in BeforeRender.
     */
    @Subscribe
    public void onScriptPostFired(ScriptPostFired e) {
        if (!enabled || client.getGameState() != GameState.LOGGED_IN) return;

        switch (e.getScriptId()) {
            case ScriptID.INVENTORY_DRAWITEM:
            case ScriptID.BANKMAIN_BUILD:
            case ScriptID.BANKMAIN_FINISHBUILDING:
            case ScriptID.BANKMAIN_SEARCH_REFRESH:
            case ScriptID.BANK_DEPOSITBOX_INIT:
                // no-op; BeforeRender will handle dimming this frame
                break;
            default:
                // ignore others
        }
    }

    /**
     * Last hook before drawing this frame; enforce opacity once per frame.
     */
    @Subscribe
    public void onBeforeRender(BeforeRender e) {
        if (!enabled || client.getGameState() != GameState.LOGGED_IN) return;
        dimAllRoots();
    }

    private void dimAllRoots() {
        final Widget[] roots = client.getWidgetRoots();
        if (roots == null || roots.length == 0) return;

        final Deque<Widget> stack = new ArrayDeque<>(64);
        for (Widget r : roots) if (r != null) stack.push(r);

        while (!stack.isEmpty()) {
            final Widget w = stack.pop();
            if (w == null || w.isHidden()) continue;

            final int rawItemId = w.getItemId();
            if (rawItemId > 0) {
                final int target = shouldDim(rawItemId) ? dimOpacity : 0;
                if (w.getOpacity() != target) {
                    w.setOpacity(target); // 0 = opaque, 255 = transparent
                }
            }

            Widget[] kids = w.getDynamicChildren();
            if (kids != null) for (Widget c : kids) if (c != null) stack.push(c);
            kids = w.getStaticChildren();
            if (kids != null) for (Widget c : kids) if (c != null) stack.push(c);
            kids = w.getNestedChildren();
            if (kids != null) for (Widget c : kids) if (c != null) stack.push(c);
        }
    }

    /**
     * Dim iff the item is tracked (items.json has a base for it) and that base is not usable.
     * Fail-open (return false) on any error so we avoid accidental dimming.
     */
    private boolean shouldDim(int rawItemId) {
        try {
            final int canonical = itemManager.canonicalize(rawItemId);
            if (canonical <= 0) return false;

            final String base = baseCache.computeIfAbsent(canonical, itemsRepo::getBaseForId);
            if (base == null) return false; // not tracked → do not dim

            return !unlocks.isBaseUsable(base); // tracked but not usable → dim
        } catch (Exception ignored) {
            return false;
        }
    }
}
