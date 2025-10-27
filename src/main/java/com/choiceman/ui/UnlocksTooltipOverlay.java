package com.choiceman.ui;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lightweight hover tooltip for the music-tab unlock grid.
 * Renders the base name when the mouse is inside an icon's widget bounds.
 * <p>
 * Notes:
 * - Runs in {@link Overlay#render(Graphics2D)} as a DYNAMIC overlay above widgets
 * - Uses {@link Widget#getBounds()} to test hitboxes against the canvas-space mouse position. :contentReference[oaicite:1]{index=1}
 */
@Singleton
public class UnlocksTooltipOverlay extends Overlay {
    private static final Color BG = new Color(0, 0, 0, 120);
    private static final Color BORDER = new Color(50, 50, 50, 220);
    private static final Color TEXT = Color.WHITE;

    private final Client client;
    private final UnlocksWidgetController controller;

    @Inject
    public UnlocksTooltipOverlay(Client client, UnlocksWidgetController controller) {
        this.client = client;
        this.controller = controller;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!controller.isOverrideActive()) {
            return null;
        }

        final Point mouse = client.getMouseCanvasPosition(); // canvas-space mouse
        if (mouse == null) {
            return null;
        }

        // Snapshot to avoid concurrent modification if the grid is rebuilt on the client thread
        final List<Map.Entry<Widget, String>> entries = new ArrayList<>(controller.getIconBaseMap().entrySet());

        for (Map.Entry<Widget, String> e : entries) {
            final Widget w = e.getKey();
            if (w == null || w.isHidden()) {
                continue;
            }

            final Rectangle bounds = w.getBounds(); // canvas-space bounds of the widget :contentReference[oaicite:2]{index=2}
            if (bounds == null) {
                continue;
            }

            if (bounds.contains(mouse.getX(), mouse.getY())) {
                drawTooltip(g, e.getValue(), mouse);
                break; // show one tooltip at a time
            }
        }

        return null;
    }

    private void drawTooltip(Graphics2D g, String text, Point mouse) {
        if (text == null || text.isEmpty()) {
            return;
        }

        final FontMetrics fm = g.getFontMetrics();
        final int pad = 4;

        final int w = fm.stringWidth(text) + pad * 2;
        final int h = fm.getHeight() + pad * 2;

        int x = mouse.getX() + 10;
        int y = mouse.getY() - 10 - h;

        final Rectangle clip = g.getClipBounds();
        if (clip != null) {
            x = Math.max(clip.x, Math.min(x, clip.x + clip.width - w));
            y = Math.max(clip.y, Math.min(y, clip.y + clip.height - h));
        }

        g.setColor(BG);
        g.fillRect(x, y, w, h);

        g.setColor(BORDER);
        g.drawRect(x, y, w, h);

        g.setColor(TEXT);
        g.drawString(text, x + pad, y + pad + fm.getAscent());
    }
}