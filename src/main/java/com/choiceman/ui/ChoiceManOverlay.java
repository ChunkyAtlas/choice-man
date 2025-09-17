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
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;

/**
 * Overlay responsible for rendering and interacting with Choice Man's “pick an unlock” UI.
 * <p>
 * Features:
 * <ul>
 *   <li>Animated sequential reveal of cards with hover/select SFX.</li>
 *   <li>Optional “milestone” gold background styling per presentation.</li>
 *   <li>Minimize/restore pill with pending count when multiple picks are queued.</li>
 *   <li>Strict, idempotent presentation lifecycle: {@link #presentChoicesSequential(List, boolean)} → (animations) → {@link #dismiss()}.</li>
 * </ul>
 * Threading & state:
 * <ul>
 *   <li>All rendering executes on RuneLite's overlay paint thread via {@link #render(Graphics2D)}.</li>
 *   <li>User input is handled by a local {@link MouseAdapter} returned from {@link #getMouseAdapter()}.</li>
 *   <li>Volatile fields guard render/input races; UI mutations are intentionally lightweight.</li>
 * </ul>
 */
@Singleton
public class ChoiceManOverlay extends Overlay {
    /**
     * Top padding for the overlay root; used to position title and cards.
     */
    private static final int ROOT_Y = 32, TITLE_GAP_Y = 8;

    /**
     * Card dimensions and horizontal/vertical spacing.
     */
    private static final int CARD_W = 140, CARD_H = 130, CARD_GAP = 12;

    /**
     * Dimensions for the minimize button (top-right pill).
     */
    private static final int MIN_BTN_W = 92, MIN_BTN_H = 22;

    /**
     * Dimensions for the restore pill (shown while minimized).
     */
    private static final int RESTORE_W = 170, RESTORE_H = 28;

    /**
     * Subtle per-row upward lift to create a stacked look.
     */
    private static final int LIFT_PER_ROW = 10;

    /**
     * Default timings (ms) for popup reveal and selection animation phases.
     */
    private static final int POP_REVEAL_MS = 420, EXPLODE_MS = 650;
    private static final int SEL_MOVE_MS = 700, SEL_HOLD_MS = 220, SEL_FADE_MS = 520;

    /**
     * Total time of the select sequence: explode → move → hold → fade.
     */
    private static final int ANIM_TOTAL = EXPLODE_MS + SEL_MOVE_MS + SEL_HOLD_MS + SEL_FADE_MS;

    /**
     * Title placement fine-tuning.
     */
    private static final int TITLE_TOP_Y = 0, CARDS_PULL_UP_Y = 9;

    private final Client client;
    private final CardRenderer renderer;
    private final PickAnimationEngine anim;
    // Card layout bookkeeping
    private final List<Rectangle> cardBounds = new ArrayList<>();
    private final Object boundsLock = new Object();
    // SFX gating (play once per card)
    private final Set<Integer> popupPlayed = Collections.synchronizedSet(new HashSet<>());
    private ItemManager itemManager;
    private ItemsRepository repo;
    private ChoiceManUnlocks unlocks;
    private BufferedImage titleImg, cardBgDefault, cardBgGold;
    /**
     * If true, use milestone (gold) card background for the current presentation.
     */
    private volatile boolean useGoldBgThisPresentation = false;
    @Setter
    private ChoiceManConfig config;
    /**
     * Callback invoked when the user selects a base (card). Receives the selected base name.
     */
    @Setter
    private java.util.function.Consumer<String> onPick;
    /**
     * Callback invoked after the presentation fully dismisses (post-selection or manual dismiss).
     */
    @Setter
    private Runnable onDismiss;
    /**
     * Whether a presentation is currently visible (not minimized) or minimized.
     */
    @Getter
    private volatile boolean active = false;
    // Runtime presentation state
    private volatile List<String> choices;
    private volatile Instant presentedAt;
    private volatile int hoveredIndex = -1, lastHoverIndex = -1;
    @Getter
    private volatile boolean minimized = false;
    private volatile int pendingCount = 0; // number of queued picks; shown in the restore pill
    // Hit-test rectangles for controls
    private Rectangle minimizeBounds = null, restoreBounds = null;
    private volatile boolean hoverMinimize = false, hoverRestore = false;
    // Selection animation state
    private volatile boolean animating = false;
    private volatile int selectedIndex = -1;
    private volatile Instant animStart = null;
    private volatile boolean pickSent = false;
    private volatile boolean[] fullyRevealed = new boolean[0];
    /**
     * Input handler for hover/select and minimize/restore interactions.
     * <p>
     * All coordinates are translated to overlay-local space to match render-time layout.
     */
    private final MouseAdapter mouse = new MouseAdapter() {
        private Point toOverlayLocal(MouseEvent e) {
            Rectangle ob = getBounds();
            return (ob == null) ? null : new Point(e.getX() - ob.x, e.getY() - ob.y);
        }

        @Override
        public MouseEvent mouseMoved(MouseEvent e) {
            if (!active || choices == null || choices.isEmpty() || animating) return e;
            Point lp = toOverlayLocal(e);
            if (lp == null) return e;

            if (minimized) {
                hoverRestore = restoreBounds != null && restoreBounds.contains(lp);
                hoveredIndex = -1;
                hoverMinimize = false;
            } else {
                int newHover = indexAt(lp);
                if (!isCardFullyRevealed(newHover)) newHover = -1;
                if (newHover != hoveredIndex) hoveredIndex = newHover;
                if (hoveredIndex >= 0 && hoveredIndex != lastHoverIndex) anim.playHover();
                lastHoverIndex = hoveredIndex;
                hoverMinimize = minimizeBounds != null && minimizeBounds.contains(lp);
                hoverRestore = false;
            }
            return e;
        }

        @Override
        public MouseEvent mousePressed(MouseEvent e) {
            if (!active || choices == null || choices.isEmpty() || animating) return e;
            if (e.getButton() != MouseEvent.BUTTON1) return e;
            Point lp = toOverlayLocal(e);
            if (lp == null) return e;

            if (minimized && restoreBounds != null && restoreBounds.contains(lp)) {
                setMinimized(false);
                e.consume();
                return e;
            }
            if (!minimized && minimizeBounds != null && minimizeBounds.contains(lp)) {
                setMinimized(true);
                e.consume();
                return e;
            }
            if (minimized) return e;

            int idx = indexAt(lp);
            if (!isCardFullyRevealed(idx)) return e;

            if (idx >= 0 && idx < choices.size()) {
                anim.playSelect();
                selectedIndex = idx;
                animating = true;
                animStart = Instant.now();
                anim.resetParticles();
                hoveredIndex = -1;
                lastHoverIndex = -1;
                e.consume();
            }
            return e;
        }
    };

    /**
     * Constructs the overlay and sets its position/layer.
     * The overlay paints above game widgets at the top center of the viewport.
     *
     * @param client RuneLite client (used for viewport size).
     */
    @Inject
    public ChoiceManOverlay(Client client) {
        this.client = client;
        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        this.renderer = new CardRenderer(this::getAccent);
        this.anim = new PickAnimationEngine();
    }

    /**
     * Programmatically minimize or restore the overlay.
     */
    public void setMinimized(boolean value) {
        if (!active || choices == null || choices.isEmpty()) return;
        if (minimized == value) return;
        if (value) {
            minimized = true;
            hoveredIndex = -1;
            lastHoverIndex = -1;
            return;
        }
        minimized = false;
        presentedAt = Instant.now();
        popupPlayed.clear();
        fullyRevealed = (choices != null) ? new boolean[choices.size()] : new boolean[0];
        hoveredIndex = -1;
        lastHoverIndex = -1;
        animating = false;
        selectedIndex = -1;
        animStart = null;
        pickSent = false;
        anim.resetParticles();
    }

    /**
     * @return The mouse listener to register with the {@code MouseManager}
     * when enabling the overlay, and unregister on disable.
     */
    public MouseListener getMouseAdapter() {
        return mouse;
    }

    /**
     * Update the count shown on the minimized/restore pill.
     * Has no visual effect when the overlay is fully visible.
     *
     * @param count queued pick count (clamped at 0+).
     */
    public void setPendingCount(int count) {
        pendingCount = Math.max(0, count);
    }

    /**
     * Load images and initialize audio assets for the overlay.
     * Must be called before presenting choices.
     *
     * @param bgPathDefault path to the default card background.
     * @param bgPathGold    path to the milestone (gold) card background.
     * @param itemManager   RuneLite item manager for icons/IDs.
     * @param repo          repository mapping base names to item IDs and metadata.
     * @param unlocks       unlock state (used for rendering obtained/unlocked visuals if needed).
     */
    public void setAssets(String bgPathDefault, String bgPathGold,
                          ItemManager itemManager, ItemsRepository repo, ChoiceManUnlocks unlocks) {
        this.cardBgDefault = ImageUtil.loadImageResource(getClass(), bgPathDefault);
        this.cardBgGold = ImageUtil.loadImageResource(getClass(), bgPathGold);
        this.titleImg = ImageUtil.loadImageResource(getClass(), "/com/choiceman/ui/Unlock_an_Item.png");
        this.itemManager = itemManager;
        this.repo = repo;
        this.unlocks = unlocks;

        anim.loadSfx(
                "/com/choiceman/sounds/CardMouseover.wav",
                "/com/choiceman/sounds/CardPopup.wav",
                "/com/choiceman/sounds/CardSelection.wav"
        );
        if (config != null) setSfxVolumePercent(config.sfxVolume());
    }

    /**
     * Sets SFX volume (0–100).
     *
     * @param percent percentage (0 = muted, 100 = full volume).
     */
    public void setSfxVolumePercent(int percent) {
        anim.setVolumePercent(percent);
    }

    /**
     * Begins a new pick presentation with the provided list of base names.
     * Cards reveal in sequence with a small per-card delay. If {@code milestone} is true,
     * cards use the gold background for this presentation only.
     *
     * @param bases     ordered list of base names to present as choices (1–5 typical).
     * @param milestone whether to render with milestone (gold) styling.
     */
    public void presentChoicesSequential(List<String> bases, boolean milestone) {
        this.useGoldBgThisPresentation = milestone;
        this.choices = bases;
        this.presentedAt = Instant.now();
        this.active = true;
        this.minimized = false;

        hoveredIndex = -1;
        lastHoverIndex = -1;
        hoverMinimize = hoverRestore = false;
        minimizeBounds = restoreBounds = null;

        animating = false;
        selectedIndex = -1;
        animStart = null;
        pickSent = false;
        anim.resetAll();
        popupPlayed.clear();

        synchronized (boundsLock) {
            cardBounds.clear();
        }
        fullyRevealed = new boolean[bases.size()];
        setSfxVolumePercent(config != null ? config.sfxVolume() : 100);
    }

    /**
     * Immediately dismisses the current presentation, clearing all transient state,
     * and invokes {@link #onDismiss} if set. Safe to call even if not active.
     */
    public void dismiss() {
        active = false;
        choices = null;
        presentedAt = null;
        minimized = false;
        hoveredIndex = lastHoverIndex = -1;
        hoverMinimize = hoverRestore = false;
        minimizeBounds = restoreBounds = null;
        animating = false;
        selectedIndex = -1;
        animStart = null;
        pickSent = false;
        anim.resetAll();
        popupPlayed.clear();

        synchronized (boundsLock) {
            cardBounds.clear();
        }
        fullyRevealed = new boolean[0];
        useGoldBgThisPresentation = false;
        if (onDismiss != null) try {
            onDismiss.run();
        } catch (Exception ignored) {
        }
    }

    /**
     * Main paint entry for the overlay. Draws title, cards, controls, and selection animation.
     * The method returns the final painted area to help RuneLite place subsequent overlays.
     *
     * @param g the graphics context provided by RuneLite.
     * @return a {@link Dimension} of the rendered bounds, or {@code null} if nothing is drawn.
     */
    @Override
    public Dimension render(Graphics2D g) {
        final List<String> local = choices;
        if (!active || local == null || local.isEmpty()) return null;

        renderer.enableAA(g);

        int availW = Math.max(1, client.getViewportWidth());
        int availH = Math.max(1, client.getViewportHeight());
        if (minimized) return renderer.drawRestorePill(g, availW, ROOT_Y, RESTORE_W, RESTORE_H,
                pendingCount, b -> restoreBounds = b, this::getAccent, hoverRestore);

        // Title
        int rowY = renderer.drawTitle(g, titleImg, availW, TITLE_TOP_Y, TITLE_GAP_Y, CARDS_PULL_UP_Y);

        // Reveal layout
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
            float tReveal = PickAnimationEngine.clamp01((ms - delay) / (float) revealMs);
            boolean fully = tReveal >= 1.0f;
            revealSnapshot[i] = fully;

            if (tReveal <= 0f) {
                newBounds.add(null);
                continue;
            }
            if (!animating && popupPlayed.add(i)) anim.playPopup();

            double ease = PickAnimationEngine.cubicOut(tReveal);
            int slide = (int) ((1.0 - ease) * 24);
            newBounds.add(new Rectangle(x, y + slide, CARD_W, CARD_H));
        }

        fullyRevealed = revealSnapshot;
        synchronized (boundsLock) {
            cardBounds.clear();
            cardBounds.addAll(newBounds);
        }

        if (animating && !anim.particlesSeeded()) {
            anim.seedParticles(newBounds, selectedIndex);
        }

        long animElapsed = animating ? Duration.between(animStart, Instant.now()).toMillis() : 0;
        float explodeT = animating ? PickAnimationEngine.clamp01(animElapsed / (float) EXPLODE_MS) : 0f;

        // Non-selected cards
        for (int i = 0; i < local.size(); i++) {
            if (i == selectedIndex) continue;
            Rectangle card = newBounds.get(i);
            if (card == null && !animating) continue;

            long delay = (long) (i * revealMs * 0.6);
            float tReveal = PickAnimationEngine.clamp01((ms - delay) / (float) revealMs);
            if (!animating) {
                float easeReveal = (float) PickAnimationEngine.cubicOut(tReveal);
                float alpha = easeReveal, scale = 0.92f + 0.08f * easeReveal;
                boolean hovered = (i == hoveredIndex);
                renderer.drawCard(g, card, local.get(i), hovered, alpha, scale,
                        0f, false, useGoldBgThisPresentation ? cardBgGold : cardBgDefault,
                        itemManager, repo, this::getAccent);
                continue;
            }
            float scale = 1.0f - 0.4f * explodeT;
            float alpha = 1.0f - explodeT;
            if (alpha > 0.02f && card != null) {
                renderer.drawCard(g, card, local.get(i), false, alpha, scale,
                        0f, false, useGoldBgThisPresentation ? cardBgGold : cardBgDefault,
                        itemManager, repo, this::getAccent);
            }
            anim.drawParticles(g, i, explodeT, EXPLODE_MS);
        }

        // Selected card path
        if (selectedIndex >= 0 && selectedIndex < local.size()) {
            Rectangle startCard = newBounds.get(selectedIndex);
            if (startCard != null) {
                int targetX = (availW - CARD_W) / 2, targetY = (availH - CARD_H) / 2;
                long afterExplode = Math.max(0, animElapsed - EXPLODE_MS);
                float moveT = PickAnimationEngine.clamp01(afterExplode / (float) SEL_MOVE_MS);
                float fadeT = PickAnimationEngine.clamp01((afterExplode - SEL_MOVE_MS - SEL_HOLD_MS) / (float) SEL_FADE_MS);
                float easeMove = (float) PickAnimationEngine.cubicOut(moveT);

                int curX = Math.round(PickAnimationEngine.lerp(startCard.x, targetX, easeMove));
                int curY = Math.round(PickAnimationEngine.lerp(startCard.y, targetY, easeMove));
                float alpha = (fadeT <= 0f) ? 1f : (1f - fadeT);
                float scale = 1.0f + 0.08f * easeMove;
                float glowIn = Math.max(0f, easeMove);

                Rectangle lifted = new Rectangle(curX, curY, CARD_W, CARD_H);
                renderer.drawCard(g, lifted, local.get(selectedIndex), false, alpha, scale,
                        glowIn, true, useGoldBgThisPresentation ? cardBgGold : cardBgDefault,
                        itemManager, repo, this::getAccent);
            }
        }

        // Finish animation
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

        // minimize pill
        int contentHeight = rows * CARD_H + (rows - 1) * CARD_GAP - Math.max(0, rows - 1) * LIFT_PER_ROW;
        int totalW = availW, totalH = rowY + contentHeight + 16;

        Rectangle pillBounds = renderer.drawMinimizePill(g, totalW, TITLE_TOP_Y, MIN_BTN_W, MIN_BTN_H,
                "Minimize", !animating && hoverMinimize, this::getAccent);
        minimizeBounds = pillBounds;

        return new Dimension(totalW, totalH);
    }

    // Helpers

    /**
     * @param idx card index
     * @return true when a card has fully completed its reveal animation and is eligible for hover/select.
     */
    private boolean isCardFullyRevealed(int idx) {
        boolean[] arr = fullyRevealed;
        return idx >= 0 && arr != null && idx < arr.length && arr[idx];
    }

    /**
     * Hit test helper for card rectangles in overlay-local space.
     *
     * @param p overlay-local point
     * @return the index of the card under the point, or -1 if none.
     */
    private int indexAt(Point p) {
        List<Rectangle> snapshot;
        synchronized (boundsLock) {
            if (cardBounds.isEmpty()) return -1;
            snapshot = new ArrayList<>(cardBounds);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            Rectangle r = snapshot.get(i);
            if (r != null && r.contains(p)) return i;
        }
        return -1;
    }

    /**
     * Resolve the accent color for UI elements. Falls back to a warm yellow when config is missing.
     *
     * @return non-null ARGB color used for pills, glows, and accents.
     */
    private Color getAccent() {
        return (config != null && config.accentColor() != null)
                ? config.accentColor() : new Color(255, 204, 0, 255);
    }
}
