package com.choiceman.ui;

import com.choiceman.data.ItemsRepository;
import net.runelite.client.game.ItemManager;

import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * Renders Choice Man "card" UI elements: background, icon, name text, hover/selection
 * borders and glow, and the minimize/restore pills. Stateless aside from the provided
 * {@link Supplier} for an accent color, so a single instance can be reused.
 */
final class CardRenderer {

    private static final int ICON_W = 28, ICON_H = 28;
    private static final float NAME_FONT_SIZE = 15.5f;
    private static final int NAME_MAX_LINES = 2, NAME_SIDE_PADDING = 8, NAME_LINE_SPACING = 0;

    /**
     * Stable FontRenderContext used for text wrapping/measurement independent of the
     * current {@link Graphics2D} transform. This avoids reflow when the card is scaled.
     */
    private static final java.awt.font.FontRenderContext BASE_FRC =
            new java.awt.font.FontRenderContext(null, /*antialias*/true, /*fractional*/true);

    private final Supplier<Color> accentSupplier;

    /**
     * @param accentSupplier supplier for the current accent color used in borders/glows.
     */
    CardRenderer(Supplier<Color> accentSupplier) {
        this.accentSupplier = accentSupplier;
    }

    /**
     * Draws an inner radial glow used during selection emphasis.
     *
     * @param g   graphics context
     * @param r   card bounds (local to card transform)
     * @param t   normalized intensity [0..1]
     * @param acc accent color
     */
    private static void drawSelectedGlowInside(Graphics2D g, Rectangle r, float t, Color acc) {
        t = clamp01(t);
        final int cx = r.x + r.width / 2;
        final int cy = r.y + r.height / 2 - 6;
        final float radius = Math.min(r.width, r.height) * 0.52f;
        final int corner = 12;

        Paint oldPaint = g.getPaint();
        Composite oldComp = g.getComposite();

        RadialGradientPaint rg = new RadialGradientPaint(
                new Point(cx, cy), radius,
                new float[]{0f, 0.35f, 1f},
                new Color[]{
                        new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int) (150 * t)),
                        new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int) (60 * t)),
                        new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), 0)
                }
        );

        g.setComposite(AlphaComposite.SrcOver);
        g.setPaint(rg);
        g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, corner, corner);

        Stroke sOld = g.getStroke();
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(255, 255, 255, (int) (38 * t)));
        g.drawRoundRect(r.x + 3, r.y + 3, r.width - 6, r.height - 6, corner - 2, corner - 2);

        g.setStroke(sOld);
        g.setPaint(oldPaint);
        g.setComposite(oldComp);
    }

    /**
     * Draws a gradient ring + subtle outer glow around the card. Used for hover and selection.
     *
     * @param g   graphics context
     * @param r   card bounds (local to card transform)
     * @param t01 normalized visibility [0..1]
     * @param acc accent color
     */
    private static void drawFancyBorder(Graphics2D g, Rectangle r, float t01, Color acc) {
        t01 = clamp01(t01);
        final int arc = 12, innerInset = 2, ringOutset = 2, glowPasses = 2;

        Paint oldPaint = g.getPaint();
        Stroke oldStroke = g.getStroke();
        Composite oldComp = g.getComposite();

        var outer = new java.awt.geom.RoundRectangle2D.Float(r.x - ringOutset, r.y - ringOutset,
                r.width + ringOutset * 2, r.height + ringOutset * 2, arc + ringOutset * 2, arc + ringOutset * 2);
        var inner = new java.awt.geom.RoundRectangle2D.Float(r.x + innerInset, r.y + innerInset,
                r.width - innerInset * 2, r.height - innerInset * 2, Math.max(2, arc - 2), Math.max(2, arc - 2));
        var ring = new java.awt.geom.Area(outer);
        ring.subtract(new java.awt.geom.Area(inner));

        float[] stops = {0f, .5f, 1f};
        Color cTop = new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int) (200 * t01));
        Color cMid = new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int) (160 * t01));
        Color cBot = new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), (int) (200 * t01));
        var lg = new java.awt.LinearGradientPaint(r.x, r.y, r.x, r.y + r.height, stops, new Color[]{cTop, cMid, cBot});
        g.setPaint(lg);
        g.setComposite(AlphaComposite.SrcOver);
        g.fill(ring);

        for (int i = 1; i <= glowPasses; i++) {
            float falloff = (glowPasses - (i - 1)) / (float) glowPasses;
            int a = (int) (60 * falloff * t01);
            if (a <= 0) continue;
            var glowOuter = new java.awt.geom.RoundRectangle2D.Float(
                    r.x - (ringOutset + i), r.y - (ringOutset + i),
                    r.width + (ringOutset + i) * 2, r.height + (ringOutset + i) * 2,
                    arc + (ringOutset + i) * 2, arc + (ringOutset + i) * 2
            );
            var glowRing = new java.awt.geom.Area(glowOuter);
            glowRing.subtract(new java.awt.geom.Area(outer));
            g.setPaint(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), a));
            g.fill(glowRing);
        }
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 255, 255, (int) (36 * t01)));
        g.draw(new java.awt.geom.RoundRectangle2D.Float(r.x + 2, r.y + 2, r.width - 4, r.height - 4, Math.max(1, arc - 4), Math.max(1, arc - 4)));

        g.setPaint(oldPaint);
        g.setStroke(oldStroke);
        g.setComposite(oldComp);
    }

    /**
     * Clamps a float to the [0..1] range.
     *
     * @param f value
     * @return clamped value
     */
    static float clamp01(float f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    /**
     * Draws a single choice card with optional hover/selection styling.
     * Handles text wrapping using a stable {@link java.awt.font.FontRenderContext} so that
     * scaling the card doesn't change line breaks.
     *
     * @param g           graphics context
     * @param card        card rectangle in overlay-local space
     * @param base        base name to render under the icon
     * @param hovered     whether to show the hover border
     * @param alpha       overall opacity [0..1]
     * @param scale       uniform scale factor for the card
     * @param glowT       selection-emphasis intensity [0..1] (used by inner glow)
     * @param emphasize   whether this card is the selected one (adds inner glow and stronger border)
     * @param bg          background image to draw behind content; if null, a flat style is used
     * @param itemManager RuneLite ItemManager for loading the icon image
     * @param repo        repository to resolve item IDs from the base name
     * @param accent      supplier of accent color for borders/glow
     */
    void drawCard(Graphics2D g, Rectangle card, String base, boolean hovered,
                  float alpha, float scale, float glowT, boolean emphasize,
                  BufferedImage bg, ItemManager itemManager, ItemsRepository repo,
                  Supplier<Color> accent) {
        if (card == null) return;

        // measure/wrap text against a stable (identity) FRC BEFORE any scaling
        final Font nameFont = g.getFont().deriveFont(Font.BOLD, NAME_FONT_SIZE);
        final int maxTextWidth = card.width - (NAME_SIDE_PADDING * 2);

        java.util.function.ToIntFunction<String> measure = s -> {
            if (s == null || s.isEmpty()) return 0;
            return (int) Math.ceil(nameFont.getStringBounds(s, BASE_FRC).getWidth());
        };

        java.util.List<String> lines = new java.util.ArrayList<>(NAME_MAX_LINES);
        {
            String text = (base == null) ? "" : base.trim();
            if (measure.applyAsInt(text) <= maxTextWidth || !text.contains(" ")) {
                if (measure.applyAsInt(text) > maxTextWidth) {
                    String ell = "…";
                    while (!text.isEmpty() && measure.applyAsInt(text + ell) > maxTextWidth) {
                        text = text.substring(0, text.length() - 1);
                    }
                    text = text + ell;
                }
                lines.add(text);
            } else {
                String[] words = text.split("\\s+");
                StringBuilder line = new StringBuilder();
                for (String w : words) {
                    String next = (line.length() == 0) ? w : line + " " + w;
                    if (measure.applyAsInt(next) <= maxTextWidth) {
                        line.setLength(0);
                        line.append(next);
                    } else {
                        if (line.length() == 0) {
                            String ell = "…";
                            String ww = w;
                            while (!ww.isEmpty() && measure.applyAsInt(ww + ell) > maxTextWidth) {
                                ww = ww.substring(0, ww.length() - 1);
                            }
                            lines.add(ww + ell);
                        } else {
                            lines.add(line.toString());
                            line = new StringBuilder(w);
                        }
                        if (lines.size() == NAME_MAX_LINES) {
                            String last = lines.get(lines.size() - 1);
                            String rem = (line.length() == 0) ? "" : (" " + line);
                            String candidate = last + rem;
                            String ell = "…";
                            while (!candidate.isEmpty() && measure.applyAsInt(candidate + ell) > maxTextWidth) {
                                candidate = candidate.substring(0, candidate.length() - 1);
                            }
                            lines.set(lines.size() - 1, candidate + ell);
                            break;
                        }
                    }
                }
                if (lines.size() < NAME_MAX_LINES && line.length() > 0) {
                    if (measure.applyAsInt(line.toString()) <= maxTextWidth) {
                        lines.add(line.toString());
                    } else {
                        String s = line.toString();
                        String ell = "…";
                        while (!s.isEmpty() && measure.applyAsInt(s + ell) > maxTextWidth) {
                            s = s.substring(0, s.length() - 1);
                        }
                        lines.add(s + ell);
                    }
                }
            }
        }

        FontMetrics nfm = g.getFontMetrics(nameFont);

        int iconW = ICON_W, iconH = ICON_H;
        int anyId = (repo != null) ? repo.getIdsForBase(base).stream().findFirst().orElse(0) : 0;
        BufferedImage img = (itemManager != null && anyId > 0) ? itemManager.getImage(anyId) : null;
        if (img != null) {
            int srcW = Math.max(1, img.getWidth()), srcH = Math.max(1, img.getHeight());
            int maxIconW = card.width - (NAME_SIDE_PADDING * 2);
            int scaleInt = (srcW * 2 <= maxIconW) ? 2 : 1;
            iconW = srcW * scaleInt;
            iconH = srcH * scaleInt;
        }

        final int spacing = 6;
        final int lineH = nfm.getHeight() + NAME_LINE_SPACING;
        final int textBlockH = lines.size() * lineH - NAME_LINE_SPACING;
        final int contentH = iconH + spacing + textBlockH;
        final int topY = (card.height - contentH) / 2;

        Composite oldComp = g.getComposite();
        AffineTransform oldTx = g.getTransform();

        int cx = card.x + card.width / 2, cy = card.y + card.height / 2;
        g.translate(cx, cy);
        g.scale(scale, scale);
        g.translate(-card.width / 2.0, -card.height / 2.0);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, CardRenderer.clamp01(alpha)));

        if (bg != null) {
            g.setColor(new Color(0, 0, 0, Math.min(180, (int) (120 * alpha))));
            g.fillRoundRect(2, 4, card.width, card.height, 16, 16);
            g.drawImage(bg, 0, 0, card.width, card.height, null);
        } else {
            g.setColor(new Color(26, 26, 26, Math.min(255, (int) (232 * alpha))));
            g.fillRoundRect(0, 0, card.width, card.height, 14, 14);
            g.setColor(new Color(96, 96, 96, Math.min(255, (int) (220 * alpha))));
            g.drawRoundRect(0, 0, card.width, card.height, 14, 14);
        }

        if (emphasize) {
            drawSelectedGlowInside(g, new Rectangle(0, 0, card.width, card.height),
                    CardRenderer.clamp01(glowT), accent.get());
        }
        if (hovered || emphasize) {
            float t = hovered ? 1f : Math.min(1f, 0.60f + glowT);
            drawFancyBorder(g, new Rectangle(0, 0, card.width, card.height), t, accent.get());
        }

        if (img != null) {
            int ix = (card.width - iconW) / 2, iy = topY;
            Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            try {
                boolean integerMultiple = (iconW % Math.max(1, img.getWidth()) == 0) &&
                        (iconH % Math.max(1, img.getHeight()) == 0);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        integerMultiple ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                                : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img, ix, iy, iconW, iconH, null);
            } finally {
                if (prevInterp != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp);
            }
        }

        g.setFont(nameFont);
        int baseline = topY + iconH + spacing + nfm.getAscent();
        Object prevFM = g.getRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        for (String line : lines) {
            int sw = nfm.stringWidth(line), tx = (card.width - sw) / 2;
            GlyphVector gv = nameFont.createGlyphVector(g.getFontRenderContext(), line);
            Shape textShape = gv.getOutline();

            AffineTransform preTx = g.getTransform();
            Composite preComp = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, CardRenderer.clamp01(alpha)));
            g.translate(tx, baseline);

            g.translate(2, 2);
            g.setColor(new Color(0, 0, 0, 235));
            g.fill(textShape);
            g.translate(-2, -2);
            int[][] feather = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
            for (int[] o : feather) {
                g.translate(o[0], o[1]);
                g.setColor(new Color(0, 0, 0, 180));
                g.fill(textShape);
                g.translate(-o[0], -o[1]);
            }

            g.setColor(new Color(255, 255, 255, Math.min(255, (int) (255 * alpha))));
            g.fill(textShape);
            g.setTransform(preTx);
            g.setComposite(preComp);
            baseline += lineH;
        }
        if (prevFM != null)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, prevFM);

        g.setTransform(oldTx);
        g.setComposite(oldComp);
    }

    /**
     * Draws the title image (or a text fallback) and returns the y-position where cards should
     * start rendering below the title block.
     *
     * @param g      graphics context
     * @param title  title image (nullable). If null, a text fallback is used.
     * @param availW viewport width for centering
     * @param topY   top y-coordinate to place the title
     * @param gapY   extra vertical gap under the title
     * @param pullUp negative offset to pull cards up into the title a bit
     * @return y-position for the first row of cards
     */
    int drawTitle(Graphics2D g, BufferedImage title, int availW, int topY, int gapY, int pullUp) {
        if (title != null) {
            int drawX = Math.max(0, (availW - title.getWidth()) / 2);
            g.drawImage(title, drawX, topY, null);
            return topY + title.getHeight() + gapY - pullUp;
        }
        g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
        g.setColor(Color.WHITE);
        String s = "Choose your unlock";
        int sw = g.getFontMetrics().stringWidth(s);
        g.drawString(s, Math.max(0, (availW - sw) / 2), topY + 16);
        return topY + 24 + gapY - pullUp;
    }

    /**
     * Draws the top-right minimize pill and returns its hitbox for input.
     *
     * @param g       graphics context
     * @param w       pill width
     * @param h       pill height
     * @param label   button label
     * @param hovered whether to render hover state
     * @param acc     accent color supplier
     * @return rectangle bounds of the pill for hit testing
     */
    Rectangle drawMinimizePillAt(Graphics2D g, int x, int y, int w, int h,
                                 String label, boolean hovered, Supplier<Color> acc) {
        g.setColor(new Color(26, 26, 26, 220));
        g.fillRoundRect(x, y, w, h, 10, 10);
        Color a = acc.get();
        if (hovered) {
            a = new Color(a.getRed(), a.getGreen(), a.getBlue(),
                    Math.min(255, (int) (a.getAlpha() * 1.1)));
        }

        Stroke s = g.getStroke();
        g.setStroke(new BasicStroke(1.8f));
        g.setColor(a);
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setStroke(s);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.setColor(Color.WHITE);
        FontMetrics fm = g.getFontMetrics();

        g.drawString(label,
                x + (w - fm.stringWidth(label)) / 2,
                y + ((h - fm.getHeight()) / 2) + fm.getAscent());

        return new Rectangle(x, y, w, h);
    }

    /**
     * Draws the centered restore pill used when the presentation is minimized, and returns
     * the painted extent to help layout upstream overlays.
     *
     * @param g            graphics context
     * @param viewportW    viewport width for centering
     * @param rootY        top y-position
     * @param w            pill width
     * @param h            pill height
     * @param pendingCount number of queued presentations to show beside the label (optional)
     * @param outBounds    consumer that receives the hitbox rectangle
     * @param acc          accent color supplier
     * @param hovered      whether to render hover state
     * @return rendered area extent (width of viewport, and height up to the pill bottom)
     */
    Dimension drawRestorePill(Graphics2D g, int viewportW, int rootY, int w, int h,
                              int pendingCount, java.util.function.Consumer<Rectangle> outBounds,
                              Supplier<Color> acc, boolean hovered) {
        int x = Math.max(8, (viewportW - w) / 2), y = rootY;
        outBounds.accept(new Rectangle(x, y, w, h));
        g.setColor(new Color(26, 26, 26, 232));
        g.fillRoundRect(x, y, w, h, 12, 12);
        Color a = acc.get();
        if (hovered) a = new Color(a.getRed(), a.getGreen(), a.getBlue(), Math.min(255, (int) (a.getAlpha() * 1.1)));
        Stroke s = g.getStroke();
        g.setStroke(new BasicStroke(2f));
        g.setColor(a);
        g.drawRoundRect(x, y, w, h, 12, 12);
        g.setStroke(s);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
        g.setColor(Color.WHITE);
        String label = pendingCount > 0 ? ("Show choices (" + pendingCount + ")") : "Show choices";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, x + (w - fm.stringWidth(label)) / 2, y + ((h - fm.getHeight()) / 2) + fm.getAscent());
        return new Dimension(viewportW, h + rootY + 8);
    }

    /**
     * Enables antialiasing for shapes and text. Call at the start of a paint pass.
     *
     * @param g graphics context
     */
    void enableAA(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /**
     * Simple text-wrapping utilities used by older call-sites. The main card rendering path
     * uses a BASE_FRC-based wrapper to keep line breaks stable across transforms, but this
     * helper remains available when a {@link FontMetrics} based approach is sufficient.
     */
    static final class TextWrap {
        /**
         * Greedy wrap into up to {@code maxLines} lines, ellipsizing the last line if needed.
         *
         * @param text     text to wrap
         * @param fm       font metrics to measure width
         * @param maxWidth maximum width in pixels per line
         * @param maxLines maximum number of lines to emit
         * @return list of wrapped lines (size 1..maxLines). Empty input produces a single empty line.
         */
        static java.util.List<String> wrap(String text, FontMetrics fm, int maxWidth, int maxLines) {
            java.util.List<String> out = new java.util.ArrayList<>();
            if (text == null || text.isEmpty() || fm.stringWidth(text) <= maxWidth) {
                out.add(text == null ? "" : text);
                return out;
            }
            String[] words = text.split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String w : words) {
                String next = (line.length() == 0) ? w : line + " " + w;
                if (fm.stringWidth(next) <= maxWidth) {
                    line.setLength(0);
                    line.append(next);
                } else {
                    if (line.length() == 0) out.add(hardBreak(w, fm, maxWidth));
                    else {
                        out.add(line.toString());
                        if (fm.stringWidth(w) > maxWidth) out.add(hardBreak(w, fm, maxWidth));
                        else line = new StringBuilder(w);
                    }
                    if (out.size() == maxLines) {
                        String last = out.get(out.size() - 1);
                        out.set(out.size() - 1, ellipsize(last + (line.length() == 0 ? "" : " " + line), fm, maxWidth));
                        return out;
                    }
                }
            }
            if (line.length() > 0) {
                if (out.size() < maxLines) out.add(line.toString());
                else {
                    String last = out.get(out.size() - 1);
                    out.set(out.size() - 1, ellipsize(last + " " + line, fm, maxWidth));
                }
            }
            return out;
        }

        /**
         * Breaks a single long word to fit, appending an ellipsis when truncated.
         */
        private static String hardBreak(String word, FontMetrics fm, int maxWidth) {
            if (fm.stringWidth(word) <= maxWidth) return word;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                sb.append(word.charAt(i));
                if (fm.stringWidth(sb.toString() + "…") > maxWidth) {
                    if (sb.length() > 1) sb.setLength(sb.length() - 1);
                    sb.append("…");
                    break;
                }
            }
            return sb.toString();
        }

        /**
         * Ellipsizes a string to fit within {@code maxWidth} using the supplied metrics.
         */
        private static String ellipsize(CharSequence s, FontMetrics fm, int maxWidth) {
            String str = s.toString().trim();
            if (fm.stringWidth(str) <= maxWidth) return str;
            String ell = "…";
            while (str.length() > 0 && fm.stringWidth(str + ell) > maxWidth) str = str.substring(0, str.length() - 1);
            return str + ell;
        }
    }
}
