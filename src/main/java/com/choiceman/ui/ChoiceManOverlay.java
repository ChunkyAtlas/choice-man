package com.choiceman.ui;

import com.choiceman.ChoiceManConfig;
import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseListener;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Choice Man pick overlay. Renders selectable “cards” for a set of base names
 * and plays reveal/selection animations. Can be minimized into a pill
 * that shows how many pending choices are queued.
 */
@Singleton
public class ChoiceManOverlay extends Overlay {
    private static final int ROOT_Y = 32;
    private static final int TITLE_GAP_Y = 8;
    private static final int CARD_W = 140;
    private static final int CARD_H = 130;
    private static final int CARD_GAP = 12;
    private static final int ICON_W = 28;
    private static final int ICON_H = 28;
    private static final float NAME_FONT_SIZE = 14f;
    private static final int NAME_MAX_LINES = 2;
    private static final int NAME_SIDE_PADDING = 8;
    private static final int NAME_LINE_SPACING = 0;
    private static final int MIN_BTN_W = 92;
    private static final int MIN_BTN_H = 22;
    private static final int RESTORE_W = 170;
    private static final int RESTORE_H = 28;
    private static final int LIFT_PER_ROW = 10;
    private static final int POP_REVEAL_MS = 420;
    private static final int EXPLODE_MS = 650;
    private static final int SEL_MOVE_MS = 700;
    private static final int SEL_HOLD_MS = 220;
    private static final int SEL_FADE_MS = 520;
    private static final int ANIM_TOTAL = EXPLODE_MS + SEL_MOVE_MS + SEL_HOLD_MS + SEL_FADE_MS;

    private final Client client;

    private final List<Rectangle> cardBounds = new ArrayList<>();
    private final Object boundsLock = new Object();

    private final Map<Integer, List<Particle>> particles = new HashMap<>();
    private final Set<Integer> popupPlayed = Collections.synchronizedSet(new HashSet<>());

    private ItemManager itemManager;
    private ItemsRepository repo;
    private ChoiceManUnlocks unlocks;

    /**
     * Default presentation background.
     */
    private BufferedImage cardBgDefault;
    /**
     * Gold presentation background (milestones 200/500/1000).
     */
    private BufferedImage cardBgGold;
    /**
     * Which background to use for the *current* presentation.
     */
    private volatile boolean useGoldBgThisPresentation = false;

    @Setter
    private ChoiceManConfig config;
    @Setter
    private java.util.function.Consumer<String> onPick;

    @Getter
    private volatile boolean active = false;
    private volatile List<String> choices;
    private volatile Instant presentedAt;

    private volatile int hoveredIndex = -1;
    private int lastHoverIndex = -1;

    private volatile boolean minimized = false;
    private volatile int pendingCount = 0;

    private Rectangle minimizeBounds = null;
    private Rectangle restoreBounds = null;
    private volatile boolean hoverMinimize = false;
    private volatile boolean hoverRestore = false;

    private volatile boolean animating = false;
    private volatile int selectedIndex = -1;
    private volatile Instant animStart = null;
    private volatile boolean pickSent = false;
    private volatile boolean particlesSeeded = false;

    /**
     * Updated every render; true only when a card has fully finished revealing.
     */
    private volatile boolean[] fullyRevealed = new boolean[0];

    private Sfx sfxHover;
    private Sfx sfxPopup;
    private Sfx sfxSelect;
    private final MouseAdapter mouse = new MouseAdapter() {
        private Point toOverlayLocal(MouseEvent e) {
            Rectangle ob = getBounds();
            if (ob == null) return null;
            return new Point(e.getX() - ob.x, e.getY() - ob.y);
        }

        @Override
        public MouseEvent mouseMoved(MouseEvent e) {
            if (!active || choices == null || choices.isEmpty()) return e;
            if (animating) return e;
            Point lp = toOverlayLocal(e);
            if (lp == null) return e;

            if (minimized) {
                hoverRestore = restoreBounds != null && restoreBounds.contains(lp);
                hoveredIndex = -1;
                hoverMinimize = false;
            } else {
                int newHover = indexAt(lp);
                if (!isCardFullyRevealed(newHover)) {
                    newHover = -1;
                }
                if (newHover != hoveredIndex) {
                    hoveredIndex = newHover;
                }
                if (hoveredIndex >= 0 && hoveredIndex != lastHoverIndex) {
                    if (sfxHover != null) sfxHover.play();
                }
                lastHoverIndex = hoveredIndex;
                hoverMinimize = minimizeBounds != null && minimizeBounds.contains(lp);
                hoverRestore = false;
            }
            return e;
        }

        @Override
        public MouseEvent mousePressed(MouseEvent e) {
            if (!active || choices == null || choices.isEmpty()) return e;

            // Only allow left button to select; ignore middle/right.
            if (e.getButton() != MouseEvent.BUTTON1) {
                return e;
            }

            Point lp = toOverlayLocal(e);
            if (lp == null) return e;
            if (animating) return e;

            if (minimized && restoreBounds != null && restoreBounds.contains(lp)) {
                minimized = false;
                presentedAt = Instant.now();
                e.consume();
                return e;
            }
            if (!minimized && minimizeBounds != null && minimizeBounds.contains(lp)) {
                minimized = true;
                hoveredIndex = -1;
                lastHoverIndex = -1;
                e.consume();
                return e;
            }
            if (minimized) return e;

            int idx = indexAt(lp);
            if (!isCardFullyRevealed(idx)) {
                return e; // guard: do not allow selecting until fully revealed
            }

            if (idx >= 0 && idx < (choices != null ? choices.size() : 0)) {
                if (sfxSelect != null) sfxSelect.play();

                selectedIndex = idx;
                animating = true;
                animStart = Instant.now();
                pickSent = false;
                particles.clear();
                particlesSeeded = false;
                hoveredIndex = -1;
                lastHoverIndex = -1;
                e.consume();
            }
            return e;
        }
    };
    private float sfxVolumeLinear = 1f;

    @Inject
    public ChoiceManOverlay(Client client) {
        this.client = client;
        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    private static void drawCenteredAcross(Graphics2D g, String s, int containerWidth, int baselineY) {
        FontMetrics fm = g.getFontMetrics();
        int sw = fm.stringWidth(s);
        int x = Math.max(0, (containerWidth - sw) / 2);
        g.drawString(s, x, baselineY);
    }

    private static void drawCentered(Graphics2D g, String s, int x, int baselineY, int w) {
        FontMetrics fm = g.getFontMetrics();
        int sw = fm.stringWidth(s);
        g.drawString(s, x + (w - sw) / 2, baselineY);
    }

    private static List<String> wrapText(String text, FontMetrics fm, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty() || fm.stringWidth(text) <= maxWidth) {
            out.add(text == null ? "" : text);
            return out;
        }
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String next = (line.length() == 0) ? words[i] : line + " " + words[i];
            if (fm.stringWidth(next) <= maxWidth) {
                line.setLength(0);
                line.append(next);
            } else {
                if (line.length() == 0) out.add(hardBreak(words[i], fm, maxWidth));
                else {
                    out.add(line.toString());
                    if (fm.stringWidth(words[i]) > maxWidth) out.add(hardBreak(words[i], fm, maxWidth));
                    else line = new StringBuilder(words[i]);
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

    private static String ellipsize(CharSequence s, FontMetrics fm, int maxWidth) {
        String str = s.toString().trim();
        if (fm.stringWidth(str) <= maxWidth) return str;
        String ell = "…";
        while (str.length() > 0 && fm.stringWidth(str + ell) > maxWidth) {
            str = str.substring(0, str.length() - 1);
        }
        return str + ell;
    }

    private static double cubicOut(double t) {
        double f = t - 1.0;
        return f * f * f + 1.0;
    }

    private static float clamp01(float f) {
        return f < 0 ? 0 : (f > 1 ? 1 : f);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static float lerp(float a, float b, float t) {
        return (a + (b - a) * t);
    }

    private static float lerp(int a, int b, float t) {
        return a + (b - a) * t;
    }

    public MouseListener getMouseAdapter() {
        return mouse;
    }

    public void setPendingCount(int count) {
        pendingCount = Math.max(0, count);
    }

    /**
     * Load both default and gold backgrounds.
     */
    public void setAssets(String bgPathDefault, String bgPathGold, ItemManager itemManager, ItemsRepository repo, ChoiceManUnlocks unlocks) {
        this.cardBgDefault = ImageUtil.loadImageResource(getClass(), bgPathDefault);
        this.cardBgGold = ImageUtil.loadImageResource(getClass(), bgPathGold);
        this.itemManager = itemManager;
        this.repo = repo;
        this.unlocks = unlocks;

        try {
            sfxHover = Sfx.load("/com/choiceman/sounds/CardMouseover.wav", 4);
        } catch (Exception ignored) {
        }
        try {
            sfxPopup = Sfx.load("/com/choiceman/sounds/CardPopup.wav", 6);
        } catch (Exception ignored) {
        }
        try {
            sfxSelect = Sfx.load("/com/choiceman/sounds/CardSelection.wav", 3);
        } catch (Exception ignored) {
        }

        if (config != null) setSfxVolumePercent(config.sfxVolume());
    }

    /**
     * Backwards-compatible helper if only one path was provided.
     */
    public void setAssets(String bgPathDefault, ItemManager itemManager, ItemsRepository repo, ChoiceManUnlocks unlocks) {
        setAssets(bgPathDefault, bgPathDefault, itemManager, repo, unlocks);
    }

    public void setSfxVolumePercent(int percent) {
        final float v = clamp01(percent / 100f);
        sfxVolumeLinear = v;
        if (sfxHover != null) sfxHover.setVolume(v);
        if (sfxPopup != null) sfxPopup.setVolume(v);
        if (sfxSelect != null) sfxSelect.setVolume(v);
    }

    /**
     * Present with default (non-milestone) background.
     */
    public void presentChoicesSequential(List<String> bases) {
        presentChoicesSequential(bases, false);
    }

    /**
     * Present with explicit milestone background selection.
     */
    public void presentChoicesSequential(List<String> bases, boolean milestone) {
        this.useGoldBgThisPresentation = milestone;
        this.choices = bases;
        this.presentedAt = Instant.now();
        this.active = true;
        this.minimized = false;

        hoveredIndex = -1;
        lastHoverIndex = -1;
        hoverMinimize = false;
        hoverRestore = false;
        minimizeBounds = null;
        restoreBounds = null;

        animating = false;
        selectedIndex = -1;
        animStart = null;
        pickSent = false;
        particles.clear();
        particlesSeeded = false;
        popupPlayed.clear();

        synchronized (boundsLock) {
            cardBounds.clear();
        }

        fullyRevealed = new boolean[bases.size()];

        setSfxVolumePercent(config != null ? config.sfxVolume() : 100);
    }

    public void dismiss() {
        active = false;
        choices = null;
        presentedAt = null;
        minimized = false;
        hoveredIndex = -1;
        lastHoverIndex = -1;
        hoverMinimize = false;
        hoverRestore = false;
        minimizeBounds = null;
        restoreBounds = null;

        animating = false;
        selectedIndex = -1;
        animStart = null;
        pickSent = false;
        particles.clear();
        particlesSeeded = false;
        popupPlayed.clear();

        synchronized (boundsLock) {
            cardBounds.clear();
        }

        fullyRevealed = new boolean[0];
        useGoldBgThisPresentation = false; // reset for next time
    }

    @Override
    public Dimension render(Graphics2D g) {
        final List<String> local = choices;
        if (!active || local == null || local.isEmpty()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int availW = client.getViewportWidth();
        int availH = client.getViewportHeight();
        if (availW <= 0) {
            Rectangle clip = g.getClipBounds();
            availW = (clip != null ? clip.width : 765);
        }
        if (availH <= 0) availH = 503;

        if (minimized) {
            return renderRestorePill(g, availW);
        }

        g.setFont(g.getFont().deriveFont(Font.BOLD, 16f));
        g.setColor(Color.WHITE);
        final int titleY = ROOT_Y + 18;
        drawCenteredAcross(g, "Choose your unlock", availW, titleY);

        final int rowY = ROOT_Y + 24 + TITLE_GAP_Y;

        long ms = Duration.between(presentedAt, Instant.now()).toMillis();
        final int revealMs = (config != null ? Math.max(120, config.choiceRevealMs()) : POP_REVEAL_MS);

        final int sidePadding = 8;
        int maxCols = Math.max(1, (availW - (sidePadding * 2)) / (CARD_W + CARD_GAP));
        int cols = Math.max(1, Math.min(maxCols, local.size()));
        int rows = (local.size() + cols - 1) / cols;

        boolean[] revealSnapshot = new boolean[local.size()];
        List<Rectangle> newBounds = new ArrayList<>(local.size());
        for (int i = 0; i < local.size(); i++) {
            int row = i / cols;
            int startIndexOfRow = row * cols;
            int colsInThisRow = Math.min(cols, local.size() - startIndexOfRow);
            int colInRow = i - startIndexOfRow;

            int rowWidthThis = (CARD_W * colsInThisRow) + (CARD_GAP * Math.max(0, colsInThisRow - 1));
            int rowXThis = Math.max(sidePadding, (availW - rowWidthThis) / 2);

            int baseY = rowY + row * (CARD_H + CARD_GAP);
            int liftedY = baseY - (row * LIFT_PER_ROW);

            int x = rowXThis + colInRow * (CARD_W + CARD_GAP);
            int y = liftedY;

            long delay = (long) (i * revealMs * 0.6);
            float tReveal = clamp01((ms - delay) / (float) revealMs);
            boolean fully = tReveal >= 1.0f;
            revealSnapshot[i] = fully;

            if (tReveal <= 0f) {
                newBounds.add(null);
                continue;
            }

            if (!animating && !popupPlayed.contains(i)) {
                popupPlayed.add(i);
                if (sfxPopup != null) sfxPopup.play();
            }

            double ease = cubicOut(tReveal);
            int slide = (int) ((1.0 - ease) * 24);

            Rectangle card = new Rectangle(x, y + slide, CARD_W, CARD_H);
            newBounds.add(card);
        }

        fullyRevealed = revealSnapshot;

        synchronized (boundsLock) {
            cardBounds.clear();
            cardBounds.addAll(newBounds);
        }

        if (animating && !particlesSeeded) {
            seedParticles(newBounds, selectedIndex);
            particlesSeeded = true;
        }

        long animElapsed = animating ? Duration.between(animStart, Instant.now()).toMillis() : 0;
        float explodeT = animating ? clamp01(animElapsed / (float) EXPLODE_MS) : 0f;

        for (int i = 0; i < local.size(); i++) {
            if (i == selectedIndex) continue;
            Rectangle card = newBounds.get(i);
            if (card == null && !animating) continue;
            String base = local.get(i);

            long delay = (long) (i * revealMs * 0.6);
            float tReveal = clamp01((ms - delay) / (float) revealMs);
            if (!animating) {
                float easeReveal = (float) cubicOut(tReveal);
                float alpha = easeReveal;
                float scale = 0.92f + 0.08f * easeReveal;
                boolean hovered = (i == hoveredIndex);
                drawCard(g, card, base, hovered, alpha, scale, 0f, false);
                continue;
            }

            float e = explodeT;
            float scale = 1.0f - 0.4f * e;
            float alpha = 1.0f - e;

            if (alpha > 0.02f && card != null) {
                drawCard(g, card, base, false, alpha, scale, 0f, false);
            }

            List<Particle> ps = particles.get(i);
            if (ps != null) drawParticles(g, ps, e);
        }

        if (selectedIndex >= 0 && selectedIndex < local.size()) {
            Rectangle startCard = (selectedIndex < newBounds.size()) ? newBounds.get(selectedIndex) : null;
            if (startCard != null) {
                String base = local.get(selectedIndex);

                int targetX = (availW - CARD_W) / 2;
                int targetY = (availH - CARD_H) / 2;

                int sx = startCard.x;
                int sy = startCard.y;

                long afterExplode = Math.max(0, animElapsed - EXPLODE_MS);
                float moveT = clamp01(afterExplode / (float) SEL_MOVE_MS);
                float fadeT = clamp01((afterExplode - SEL_MOVE_MS - SEL_HOLD_MS) / (float) SEL_FADE_MS);

                float easeMove = (float) cubicOut(moveT);
                int curX = Math.round(lerp(sx, targetX, easeMove));
                int curY = Math.round(lerp(sy, targetY, easeMove));

                float alpha = (fadeT <= 0f) ? 1f : (1f - fadeT);
                float scale = 1.0f + 0.08f * easeMove;
                float glow = 0.6f * Math.max(0f, easeMove);

                Rectangle lifted = new Rectangle(curX, curY, CARD_W, CARD_H);
                drawLightBeam(g, lifted, Math.max(0.2f, easeMove) * alpha);
                drawCard(g, lifted, base, false, alpha, scale, glow * alpha, true);
            }
        }

        if (animating && animElapsed >= ANIM_TOTAL) {
            if (!pickSent && onPick != null && selectedIndex >= 0 && selectedIndex < local.size()) {
                pickSent = true;
                try {
                    onPick.accept(local.get(selectedIndex));
                } catch (Exception ignored) {
                }
            }
            dismiss();
            return new Dimension(availW, ROOT_Y + 24 + TITLE_GAP_Y + CARD_H + 16);
        }

        int contentHeight = rows * CARD_H + (rows - 1) * CARD_GAP - Math.max(0, rows - 1) * LIFT_PER_ROW;
        int totalW = availW;
        int totalH = ROOT_Y + (24 + TITLE_GAP_Y) + contentHeight + 16;

        int minX = totalW - MIN_BTN_W - 8;
        int minY = ROOT_Y - 2;
        minimizeBounds = new Rectangle(minX, minY, MIN_BTN_W, MIN_BTN_H);
        renderMinimizePill(g, minimizeBounds, "Minimize", !animating && hoverMinimize);

        return new Dimension(totalW, totalH);
    }

    private boolean isCardFullyRevealed(int idx) {
        boolean[] arr = fullyRevealed;
        return idx >= 0 && arr != null && idx < arr.length && arr[idx];
    }

    private int indexAt(Point localPoint) {
        List<Rectangle> snapshot;
        synchronized (boundsLock) {
            if (cardBounds.isEmpty()) return -1;
            snapshot = new ArrayList<>(cardBounds);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            Rectangle r = snapshot.get(i);
            if (r != null && r.contains(localPoint)) return i;
        }
        return -1;
    }

    private Dimension renderRestorePill(Graphics2D g, int viewportW) {
        final int w = RESTORE_W;
        final int h = RESTORE_H;
        final int x = Math.max(8, (viewportW - w) / 2);
        final int y = ROOT_Y;

        restoreBounds = new Rectangle(x, y, w, h);

        g.setColor(new Color(26, 26, 26, 232));
        g.fillRoundRect(x, y, w, h, 12, 12);

        Color acc = getAccent();
        if (hoverRestore) {
            acc = new Color(acc.getRed(), acc.getGreen(), acc.getBlue(),
                    Math.min(255, (int) (acc.getAlpha() * 1.1)));
        }
        Stroke s = g.getStroke();
        g.setStroke(new BasicStroke(2.0f));
        g.setColor(acc);
        g.drawRoundRect(x, y, w, h, 12, 12);
        g.setStroke(s);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
        g.setColor(Color.WHITE);
        final int count = Math.max(0, pendingCount);
        String label = (count > 0) ? ("Show choices (" + count + ")") : "Show choices";
        drawCentered(g, label, x, y + ((h - g.getFontMetrics().getHeight()) / 2) + g.getFontMetrics().getAscent(), w);

        return new Dimension(viewportW, h + ROOT_Y + 8);
    }

    private void renderMinimizePill(Graphics2D g, Rectangle r, String label, boolean hovered) {
        g.setColor(new Color(26, 26, 26, 220));
        g.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);

        Color acc = getAccent();
        if (hovered) {
            acc = new Color(acc.getRed(), acc.getGreen(), acc.getBlue(),
                    Math.min(255, (int) (acc.getAlpha() * 1.1)));
        }
        Stroke s = g.getStroke();
        g.setStroke(new BasicStroke(1.8f));
        g.setColor(acc);
        g.drawRoundRect(r.x, r.y, r.width, r.height, 10, 10);
        g.setStroke(s);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.setColor(Color.WHITE);
        drawCentered(g, label, r.x, r.y + ((r.height - g.getFontMetrics().getHeight()) / 2) + g.getFontMetrics().getAscent(), r.width);
    }

    private Color getAccent() {
        return (config != null && config.accentColor() != null)
                ? config.accentColor()
                : new Color(255, 204, 0, 255);
    }

    private void drawCard(Graphics2D g, Rectangle card, String base, boolean hovered,
                          float alpha, float scale, float glow, boolean emphasize) {
        if (card == null) return;

        Composite oldComp = g.getComposite();
        AffineTransform oldTx = g.getTransform();

        int cx = card.x + card.width / 2;
        int cy = card.y + card.height / 2;

        g.translate(cx, cy);
        g.scale(scale, scale);
        g.translate(-card.width / 2.0, -card.height / 2.0);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(alpha)));

        final BufferedImage bg = useGoldBgThisPresentation && cardBgGold != null ? cardBgGold : cardBgDefault;
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

        if (hovered || emphasize) {
            Stroke s = g.getStroke();
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Color acc = getAccent();
            int a = hovered ? acc.getAlpha() : Math.min(255, (int) (acc.getAlpha() * (0.6f + glow)));
            g.setColor(new Color(acc.getRed(), acc.getGreen(), acc.getBlue(), a));
            g.drawRoundRect(1, 1, card.width - 2, card.height - 2, 12, 12);
            g.setStroke(s);
        }

        Font nameFont = g.getFont().deriveFont(Font.PLAIN, NAME_FONT_SIZE);
        FontMetrics nfm = g.getFontMetrics(nameFont);

        int maxTextWidth = card.width - (NAME_SIDE_PADDING * 2);
        List<String> lines = wrapText(base, nfm, maxTextWidth, NAME_MAX_LINES);

        final int spacing = 6;
        final int lineH = nfm.getHeight() + NAME_LINE_SPACING;
        final int textBlockH = lines.size() * lineH - NAME_LINE_SPACING;
        final int contentH = ICON_H + spacing + textBlockH;

        final int topY = (card.height - contentH) / 2;

        int anyId = repo != null ? repo.getIdsForBase(base).stream().findFirst().orElse(0) : 0;
        Image img = (itemManager != null && anyId > 0) ? itemManager.getImage(anyId) : null;
        if (img != null) {
            Object prevInterp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int ix = (card.width - ICON_W) / 2;
            int iy = topY;
            g.drawImage(img, ix, iy, ICON_W, ICON_H, null);
            if (prevInterp != null) {
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, prevInterp);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        g.setFont(nameFont);
        g.setColor(new Color(255, 255, 255, Math.min(255, (int) (255 * alpha))));
        int baseline = topY + ICON_H + spacing + nfm.getAscent();
        for (String line : lines) {
            drawCentered(g, line, 0, baseline, card.width);
            baseline += lineH;
        }

        g.setTransform(oldTx);
        g.setComposite(oldComp);
    }

    private void drawLightBeam(Graphics2D g, Rectangle card, float t) {
        int pad = 12;
        int beamX = card.x - pad;
        int beamY = card.y - 18;
        int beamW = card.width + pad * 2;
        int beamH = card.height + 48;

        int topAlpha = (int) (200 * t);
        GradientPaint gp = new GradientPaint(
                beamX + beamW / 2f, beamY,
                new Color(255, 245, 180, clamp255(topAlpha)),
                beamX + beamW / 2f, beamY + beamH,
                new Color(255, 245, 180, 0)
        );
        Paint old = g.getPaint();
        g.setPaint(gp);
        g.fillRoundRect(beamX, beamY, beamW, beamH, 18, 18);
        g.setPaint(old);

        g.setColor(new Color(255, 230, 120, clamp255((int) (120 * t))));
        g.drawRoundRect(beamX, beamY, beamW, beamH, 18, 18);
    }

    private void seedParticles(List<Rectangle> bounds, int selIdx) {
        particles.clear();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < bounds.size(); i++) {
            if (i == selIdx) continue;
            Rectangle r = bounds.get(i);
            if (r == null) continue;
            int cx = r.x + r.width / 2;
            int cy = r.y + r.height / 2;

            int count = 26 + rnd.nextInt(12);
            List<Particle> list = new ArrayList<>(count);
            for (int p = 0; p < count; p++) {
                double angle = rnd.nextDouble(0, Math.PI * 2);
                double speed = 90 + rnd.nextDouble(90);
                float vx = (float) (Math.cos(angle) * speed);
                float vy = (float) (Math.sin(angle) * speed - 40);

                float size = 2.0f + rnd.nextFloat() * 2.2f;
                int rshift = rnd.nextInt(30);
                int gshift = rnd.nextInt(20);
                Color c = new Color(
                        clamp255(255),
                        clamp255(200 + gshift),
                        clamp255(40 + rshift),
                        255
                );
                list.add(new Particle(cx, cy, vx, vy, size, c));
            }
            particles.put(i, list);
        }
    }

    private void drawParticles(Graphics2D g, List<Particle> list, float norm) {
        float grav = 120f;

        Composite old = g.getComposite();
        for (Particle p : list) {
            float t = clamp01(norm);
            float x = p.x0 + p.vx * t * (EXPLODE_MS / 1000f);
            float y = p.y0 + (p.vy * t * (EXPLODE_MS / 1000f)) + 0.5f * grav * t * t;

            int alpha = clamp255((int) (255 * (1.0f - t)));
            if (alpha <= 0) continue;

            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
            g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), alpha));

            float size = Math.max(0.5f, p.size * (1.0f - t));
            int s = Math.max(1, Math.round(size));
            g.fillOval(Math.round(x) - s / 2, Math.round(y) - s / 2, s, s);
        }
        g.setComposite(old);
    }

    private static final class Particle {
        final float x0, y0;
        final float vx, vy;
        final float size;
        final Color color;

        Particle(float x0, float y0, float vx, float vy, float size, Color color) {
            this.x0 = x0;
            this.y0 = y0;
            this.vx = vx;
            this.vy = vy;
            this.size = size;
            this.color = color;
        }
    }

    private static final class Sfx {
        private final List<Clip> pool = new ArrayList<>();
        private final byte[] wavBytes;
        private final int maxPool;
        private volatile float volumeLinear = 1f;

        private Sfx(byte[] wavBytes, int maxPool) {
            this.wavBytes = wavBytes;
            this.maxPool = Math.max(1, maxPool);
        }

        static Sfx load(String resourcePath, int maxPool) throws IOException, UnsupportedAudioFileException, LineUnavailableException {
            try (InputStream in = ChoiceManOverlay.class.getResourceAsStream(resourcePath)) {
                if (in == null) throw new IOException("Missing SFX: " + resourcePath);
                byte[] bytes = in.readAllBytes();

                Sfx sfx = new Sfx(bytes, maxPool);
                Clip first = sfx.makeClip();
                sfx.applyVolume(first);
                synchronized (sfx.pool) {
                    sfx.pool.add(first);
                }
                return sfx;
            }
        }

        private Clip makeClip() throws UnsupportedAudioFileException, IOException, LineUnavailableException {
            try (AudioInputStream ais0 = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
                AudioFormat base = ais0.getFormat();
                AudioInputStream use = ais0;
                if (base.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
                    AudioFormat pcm = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            base.getSampleRate(),
                            16,
                            base.getChannels(),
                            base.getChannels() * 2,
                            base.getSampleRate(),
                            false
                    );
                    use = AudioSystem.getAudioInputStream(pcm, ais0);
                }
                Clip c = AudioSystem.getClip();
                c.open(use);
                return c;
            }
        }

        void setVolume(float linear01) {
            volumeLinear = clamp01(linear01);
            synchronized (pool) {
                for (Clip c : pool) applyVolume(c);
            }
        }

        private void applyVolume(Clip c) {
            try {
                FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (volumeLinear <= 0f) ? gain.getMinimum()
                        : (float) (20.0 * Math.log10(volumeLinear));
                db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
                gain.setValue(db);
                return;
            } catch (IllegalArgumentException ignored) {
            }
            try {
                FloatControl vol = (FloatControl) c.getControl(FloatControl.Type.VOLUME);
                float v = Math.max(vol.getMinimum(), Math.min(vol.getMaximum(), volumeLinear));
                vol.setValue(v);
            } catch (IllegalArgumentException ignored) {
            }
        }

        void play() {
            try {
                Clip chosen = null;
                synchronized (pool) {
                    for (Clip c : pool) {
                        if (!c.isRunning()) {
                            chosen = c;
                            break;
                        }
                    }
                    if (chosen == null && pool.size() < maxPool) {
                        chosen = makeClip();
                        applyVolume(chosen);
                        pool.add(chosen);
                    }
                }
                if (chosen != null) {
                    chosen.stop();
                    chosen.flush();
                    chosen.setFramePosition(0);
                    chosen.start();
                }
            } catch (Exception ignored) {
                // never break rendering on SFX problems
            }
        }
    }
}