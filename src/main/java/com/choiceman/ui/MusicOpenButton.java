package com.choiceman.ui;

import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Adds a small button to the Music tab that opens the Choice Man unlocks view.
 * Placement rules:
 * - If a "Toggle all" widget exists, anchor to its left; otherwise align to the music frame’s right edge.
 * Lifecycle:
 * - Created once per Music group load and reused across reloads.
 * - Hidden while the unlocks override is active.
 * Threading:
 * - All widget mutations must occur on the client thread. Callers already invoke via {@link ClientThread#invokeLater(Runnable)}.
 */
@Singleton
public class MusicOpenButton {
    private static final int MUSIC_GROUP = 239;
    private static final int CONTENTS = 1;
    private static final int FRAME = 2;

    private static final int SPRITE_OPEN = 1976;
    private static final int W = 14, H = 14;

    private static final int GAP = 4;
    private static final int NUDGE_LEFT = 26;
    private static final int NUDGE_DOWN = 0;

    private final Client client;
    private final ClientThread clientThread;
    private final UnlocksWidgetController unlocksWidgetController;

    private Widget icon;
    @Getter
    private volatile boolean overrideActive = false;

    // cache last placement to avoid unnecessary revalidate spam
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;

    @Inject
    public MusicOpenButton(Client client, ClientThread clientThread, UnlocksWidgetController unlocksWidgetController) {
        this.client = client;
        this.clientThread = clientThread;
        this.unlocksWidgetController = unlocksWidgetController;
    }

    private static void move(Widget w, int x, int y, int width, int height) {
        w.setOriginalX(x);
        w.setOriginalY(y);
        w.setOriginalWidth(width);
        w.setOriginalHeight(height);
        w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
        w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
    }

    private static Widget[] merge(Widget[] a, Widget[] b) {
        if (a == null) return b;
        if (b == null) return a;
        Widget[] out = new Widget[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Called from plugin start on the client thread.
     */
    public void onStart() {
        clientThread.invokeLater(this::placeIcon);
    }

    /**
     * Called from plugin stop on the client thread.
     */
    public void onStop() {
        clientThread.invokeLater(this::hide);
    }

    public void onOverrideActivated() {
        overrideActive = true;
        clientThread.invokeLater(this::hide);
    }

    public void onOverrideDeactivated() {
        overrideActive = false;
        clientThread.invokeLater(this::placeIcon);
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded e) {
        if (e.getGroupId() == MUSIC_GROUP) {
            clientThread.invokeLater(this::placeIcon);
        }
    }

    private void placeIcon() {
        if (overrideActive) {
            hide();
            return;
        }

        Widget contents = client.getWidget(MUSIC_GROUP, CONTENTS);
        Widget frame = client.getWidget(MUSIC_GROUP, FRAME);
        if (contents == null || frame == null) {
            hide();
            return;
        }

        // If the current icon was orphaned by a UI rebuild, recreate it.
        if (icon != null && icon.getParent() != contents) {
            icon = null;
            lastX = Integer.MIN_VALUE;
            lastY = Integer.MIN_VALUE;
        }

        Widget root = client.getWidget(MUSIC_GROUP, 0);
        Widget toggleAll = findByAction(root, "Toggle all");

        int x, y;
        if (toggleAll != null) {
            x = toggleAll.getOriginalX() - W - GAP - NUDGE_LEFT;
            y = toggleAll.getOriginalY() + (toggleAll.getOriginalHeight() - H) / 2 + NUDGE_DOWN;
        } else {
            int frameRight = frame.getOriginalX() + frame.getOriginalWidth();
            x = frameRight - W - (GAP + 10) - NUDGE_LEFT;
            y = Math.max(6, frame.getOriginalY() - H - GAP) + NUDGE_DOWN;
        }

        if (icon == null) {
            icon = contents.createChild(-1, WidgetType.GRAPHIC);
            icon.setHasListener(true);
            icon.setAction(0, "View unlocks");
            icon.setOnOpListener((JavaScriptCallback) ev -> unlocksWidgetController.overrideWithLatest());
            icon.setSpriteId(SPRITE_OPEN);
        }

        icon.setHidden(false);

        // Only move/revalidate if needed.
        if (x != lastX || y != lastY || icon.getOriginalWidth() != W || icon.getOriginalHeight() != H) {
            move(icon, x, y, W, H);
            icon.revalidate();
            lastX = x;
            lastY = y;
        }
    }

    private void hide() {
        if (icon != null) {
            icon.setHidden(true);
            // keep placement cache; UI rebuild will reset it when icon parent changes
        }
    }

    /**
     * Depth-first search for a descendant that exposes the given right-click action text.
     */
    private Widget findByAction(Widget parent, String action) {
        if (parent == null) return null;
        Widget[] kids = merge(parent.getChildren(), parent.getDynamicChildren());
        if (kids == null) return null;

        for (Widget c : kids) {
            if (c == null) continue;

            String[] actions = c.getActions();
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && a.equalsIgnoreCase(action)) {
                        return c;
                    }
                }
            }

            Widget deeper = findByAction(c, action);
            if (deeper != null) return deeper;
        }
        return null;
    }
}
