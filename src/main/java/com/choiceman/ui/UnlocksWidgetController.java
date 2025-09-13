package com.choiceman.ui;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.ScriptEvent;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Replaces the Music tab content with a grid of Choice Man unlocks (one icon per base) and a progress bar,
 * then restores the original UI on demand.
 * <p>
 */
@Singleton
public final class UnlocksWidgetController {
    // Music group widget ids
    private static final int MUSIC_GROUP = 239;

    private static final int ROOT = 0;
    private static final int CONTENTS = 1;
    private static final int FRAME = 2;
    private static final int SCROLLABLE = 4;
    private static final int OVERLAY = 5;
    private static final int JUKEBOX = 6;
    private static final int SCROLLBAR = 7;
    private static final int TITLE = 8;
    private static final int STOCK_BAR = 9;

    // Grid metrics
    private static final int ICON_SIZE = 32;
    private static final int PADDING = 4;
    private static final int COLUMNS = 4;
    private static final int MARGIN_X = 8;
    private static final int MARGIN_Y = 8;

    // Favorites persistence
    private static final String CFG_GROUP = "choiceman";
    private static final String CFG_FAVS = "unlockFavorites";
    private static final int STAR_SPRITE = 366;   // small yellow star in stock sprite sheet

    private final Client client;
    private final ClientThread clientThread;
    private final ChoiceManUnlocks unlocks;
    private final ItemsRepository itemsRepo;
    private final ItemSpriteCache itemSpriteCache;
    private final SpriteOverrideManager spriteOverrideManager;
    private final ConfigManager configManager;
    /**
     * Map of widget icon -> base name for hover tooltips. Kept stable across rebuilds while override is active.
     */
    @Getter
    private final Map<Widget, String> iconBaseMap = new LinkedHashMap<>();
    // Favorites
    private final Set<String> favoriteBases = new LinkedHashSet<>();
    // Widgets created by the override (tracked for deterministic cleanup)
    private final List<Widget> createdScrollWidgets = new ArrayList<>();
    private final List<Widget> createdRootWidgets = new ArrayList<>();
    @Getter
    private volatile boolean overrideActive = false;
    // Stock UI snapshots for restore
    private List<Widget> backupJukeboxStaticKids;
    private List<Widget> backupJukeboxDynamicKids;
    private List<Widget> backupScrollStaticKids;
    private List<Widget> backupScrollDynamicKids;
    private String originalTitleText;
    // Stock top-row widgets we hide while override is active
    private Widget stockToggleAll; // 239.0[0]
    private Widget stockSearch;    // 239.0[1]
    private Widget pluginToggle;   // "View Unlocks" button injected by MusicOpenButton
    private boolean favoritesLoaded = false;

    @Inject
    public UnlocksWidgetController(
            Client client,
            ClientThread clientThread,
            ChoiceManUnlocks unlocks,
            ItemsRepository itemsRepo,
            ItemSpriteCache itemSpriteCache,
            SpriteOverrideManager spriteOverrideManager,
            ConfigManager configManager
    ) {
        this.client = client;
        this.clientThread = clientThread;
        this.unlocks = unlocks;
        this.itemsRepo = itemsRepo;
        this.itemSpriteCache = itemSpriteCache;
        this.spriteOverrideManager = spriteOverrideManager;
        this.configManager = configManager;
    }

    private static List<Widget> copyChildren(Widget parent, boolean dynamic) {
        if (parent == null) return Collections.emptyList();
        final Widget[] kids = dynamic ? parent.getDynamicChildren() : parent.getChildren();
        return kids != null ? new ArrayList<>(Arrays.asList(kids)) : Collections.emptyList();
    }

    private static void hideAllChildren(Widget parent) {
        if (parent == null) return;
        final Widget[] stat = parent.getChildren();
        if (stat != null) for (Widget w : stat) if (w != null) w.setHidden(true);
        final Widget[] dyn = parent.getDynamicChildren();
        if (dyn != null) for (Widget w : dyn) if (w != null) w.setHidden(true);
    }

    private static void restoreChildren(Widget parent, List<Widget> stat, List<Widget> dyn) {
        if (parent == null) return;
        if (stat != null) {
            for (Widget w : stat)
                if (w != null) {
                    w.setHidden(false);
                    w.revalidate();
                }
        }
        if (dyn != null) {
            for (Widget w : dyn)
                if (w != null) {
                    w.setHidden(false);
                    w.revalidate();
                }
        }
        parent.revalidate();
    }

    // Internals

    private static Widget[] merge(Widget[] a, Widget[] b) {
        if (a == null) return b;
        if (b == null) return a;
        final Widget[] out = new Widget[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Entry point from the Music tab button.
     * Builds or refreshes the override UI from the current unlock state.
     * No-op if there are no unlocks to display.
     */
    public void overrideWithLatest() {
        if (unlocks.unlockedList().isEmpty()) {
            return;
        }
        loadFavoritesIfNeeded();

        if (!overrideActive) {
            overrideActive = true;
            clientThread.invokeLater(() ->
            {
                applyOverride();
                spriteOverrideManager.register();
            });
        } else {
            clientThread.invokeLater(this::applyOverride);
        }
    }

    /**
     * Restores the stock Music tab UI and resets transient override state.
     * Safe to call even if the override is not active.
     */
    public void restore() {
        if (!overrideActive) {
            return;
        }
        spriteOverrideManager.unregister();
        itemSpriteCache.clear();
        clientThread.invokeLater(this::revertOverride);
    }

    /**
     * Ensures the stock top row widgets (toggle all, search) and the plugin's "View Unlocks" button are unhidden.
     * Used when entering/leaving the Music tab so the stock UI is never stranded hidden.
     */
    public void restoreTopRowControls() {
        final Widget root = client.getWidget(MUSIC_GROUP, ROOT);
        if (root != null) {
            final Widget[] dyn = root.getDynamicChildren();
            if (dyn != null && dyn.length >= 2) {
                final Widget toggleAll = dyn[0];
                final Widget stockSrch = dyn[1];
                if (toggleAll != null) {
                    toggleAll.setHidden(false);
                    toggleAll.revalidate();
                }
                if (stockSrch != null) {
                    stockSrch.setHidden(false);
                    stockSrch.revalidate();
                }
            }
        }

        final Widget contents = client.getWidget(MUSIC_GROUP, CONTENTS);
        if (contents != null) {
            final Widget btn = findByAction(contents, "View Unlocks");
            if (btn != null) {
                btn.setHidden(false);
                btn.revalidate();
            }
        }
    }

    private void loadFavoritesIfNeeded() {
        if (favoritesLoaded) return;
        favoritesLoaded = true;

        final String csv = configManager.getConfiguration(CFG_GROUP, CFG_FAVS);
        if (csv == null || csv.isEmpty()) return;

        for (String s : csv.split(",")) {
            final String b = s.trim();
            if (!b.isEmpty()) favoriteBases.add(b);
        }
    }

    private void saveFavorites() {
        configManager.setConfiguration(CFG_GROUP, CFG_FAVS, String.join(",", favoriteBases));
    }

    private boolean isFavorite(String base) {
        return favoriteBases.contains(base);
    }

    private void toggleFavorite(String base) {
        if (!favoriteBases.remove(base)) {
            favoriteBases.add(base);
        }
        saveFavorites();
        applyOverride(); // re-sort and redraw grid
    }

    /**
     * Builds the override UI in the Music tab:
     * 1) Hides stock children under scrollable/jukebox and stores references for restore.
     * 2) Creates a grid of item icons for each unlocked base, with an optional star badge for favorites.
     * 3) Creates a progress bar and label stretched to the right frame edge.
     * 4) Updates iconBaseMap for tooltip overlay.
     */
    private void applyOverride() {
        iconBaseMap.clear();

        // Hide stock widgets we don't use in override
        for (int id = 9; id <= 19; id++) {
            final Widget w = client.getWidget(MUSIC_GROUP, id);
            if (w != null) w.setHidden(true);
        }

        final Widget root = client.getWidget(MUSIC_GROUP, ROOT);
        final Widget contents = client.getWidget(MUSIC_GROUP, CONTENTS);
        final Widget frame = client.getWidget(MUSIC_GROUP, FRAME);
        final Widget scrollable = client.getWidget(MUSIC_GROUP, SCROLLABLE);
        final Widget jukebox = client.getWidget(MUSIC_GROUP, JUKEBOX);
        final Widget scrollbar = client.getWidget(MUSIC_GROUP, SCROLLBAR);
        final Widget title = client.getWidget(MUSIC_GROUP, TITLE);

        // Hide stock top-row controls while override is active
        if (root != null) {
            final Widget[] dyn = root.getDynamicChildren();
            if (dyn != null && dyn.length >= 2) {
                stockToggleAll = dyn[0];
                stockSearch = dyn[1];
                if (stockToggleAll != null) {
                    stockToggleAll.setHidden(true);
                    stockToggleAll.revalidate();
                }
                if (stockSearch != null) {
                    stockSearch.setHidden(true);
                    stockSearch.revalidate();
                }
            }
        }

        if (contents != null) {
            pluginToggle = findByAction(contents, "View Unlocks");
            if (pluginToggle != null) {
                pluginToggle.setHidden(true);
                pluginToggle.revalidate();
            }
        }

        if (title != null) {
            if (originalTitleText == null) originalTitleText = title.getText();
            title.setText("Unlocked items");
            title.revalidate();
        }

        // Snapshot originals once for restore
        if (backupJukeboxStaticKids == null && jukebox != null) backupJukeboxStaticKids = copyChildren(jukebox, false);
        if (backupJukeboxDynamicKids == null && jukebox != null) backupJukeboxDynamicKids = copyChildren(jukebox, true);
        if (backupScrollStaticKids == null && scrollable != null)
            backupScrollStaticKids = copyChildren(scrollable, false);
        if (backupScrollDynamicKids == null && scrollable != null)
            backupScrollDynamicKids = copyChildren(scrollable, true);

        // Hide everything in stock containers and hard-clear any previous override widgets
        hideAllChildren(jukebox);
        hideAllChildren(scrollable);
        deleteWidgets(createdScrollWidgets);
        deleteWidgets(createdRootWidgets);

        if (scrollable != null) {
            scrollable.deleteAllChildren();
            scrollable.revalidate();
        }

        // Build data: representative id = smallest id for stable visuals
        final List<DisplayBase> bases = unlocks.unlockedList().stream()
                .map(b -> new DisplayBase(b, pickRepId(b)))
                .filter(db -> db.repId > 0)
                .collect(Collectors.toList());

        bases.sort(Comparator
                .comparing((DisplayBase db) -> !isFavorite(db.baseName))
                .thenComparing(db -> db.baseName.toLowerCase(Locale.ROOT)));

        // Icons grid with optional star badge
        if (scrollable != null && scrollbar != null) {
            int displayIndex = 0;
            for (DisplayBase db : bases) {
                final Widget icon = scrollable.createChild(-1, WidgetType.GRAPHIC);
                createdScrollWidgets.add(icon);

                icon.setHidden(false);
                icon.setOriginalWidth(ICON_SIZE);
                icon.setOriginalHeight(ICON_SIZE);
                icon.setItemQuantityMode(ItemQuantityMode.NEVER);

                final int spriteId = itemSpriteCache.getSpriteId(db.repId);
                icon.setSpriteId(spriteId);

                final int col = displayIndex % COLUMNS;
                final int row = displayIndex / COLUMNS;
                final int x = MARGIN_X + col * (ICON_SIZE + PADDING);
                final int y = MARGIN_Y + row * (ICON_SIZE + PADDING);

                icon.setOriginalX(x);
                icon.setOriginalY(y);
                icon.revalidate();

                // Left-click to toggle favorite (no context menu entry)
                icon.setOnClickListener((JavaScriptCallback) (ScriptEvent ev) -> toggleFavorite(db.baseName));
                icon.setHasListener(true);

                if (isFavorite(db.baseName)) {
                    final Widget star = scrollable.createChild(-1, WidgetType.GRAPHIC);
                    createdScrollWidgets.add(star);

                    star.setHidden(false);
                    star.setSpriteId(STAR_SPRITE);
                    star.setOriginalWidth(12);
                    star.setOriginalHeight(12);
                    star.setOriginalX(x + ICON_SIZE - 10);
                    star.setOriginalY(y - 2);
                    star.revalidate();
                }

                iconBaseMap.put(icon, db.baseName);
                displayIndex++;
            }

            final int rows = (bases.size() + COLUMNS - 1) / COLUMNS;
            scrollable.setScrollHeight(MARGIN_Y * 2 + rows * (ICON_SIZE + PADDING));
            scrollable.revalidate();
            scrollbar.revalidateScroll();
        }

        // Progress bar + label
        drawProgressStretchToFrame(title, frame, unlocks.unlockedList().size(), itemsRepo.getAllBases().size());

        if (root != null) root.revalidate();
    }

    /**
     * Draws a simple progress bar aligned with the stock bar coordinates and stretched to the frame's right edge.
     * Creates three widgets (bg, fill, label) under ROOT and tracks them for cleanup.
     */
    private void drawProgressStretchToFrame(Widget title, Widget frame, int unlocked, int total) {
        final Widget root = client.getWidget(MUSIC_GROUP, ROOT);
        if (root == null || title == null) return;

        deleteWidgets(createdRootWidgets);

        final Widget oldBar = client.getWidget(MUSIC_GROUP, STOCK_BAR);
        if (oldBar == null) return;

        final int xOld = oldBar.getOriginalX();
        final int yOld = oldBar.getOriginalY();
        final int hOld = oldBar.getOriginalHeight();

        final int frameRight = (frame != null)
                ? (frame.getOriginalX() + frame.getOriginalWidth())
                : (xOld + oldBar.getOriginalWidth());

        final int newW = Math.max(0, frameRight - xOld - 8);
        final int newY = yOld + (hOld - 15) / 2;

        final Widget bg = root.createChild(-1, WidgetType.RECTANGLE);
        createdRootWidgets.add(bg);
        bg.setHidden(false);
        bg.setFilled(true);
        bg.setTextColor(0x000000);
        bg.setOriginalX(xOld);
        bg.setOriginalY(newY);
        bg.setOriginalWidth(newW);
        bg.setOriginalHeight(15);
        bg.revalidate();

        final int inner = Math.max(0, newW - 2);
        final int fillW = total == 0 ? 0 : Math.round(inner * (float) unlocked / (float) total);

        final Widget fill = root.createChild(-1, WidgetType.RECTANGLE);
        createdRootWidgets.add(fill);
        fill.setHidden(false);
        fill.setFilled(true);
        fill.setTextColor(0x00b33c);
        fill.setOriginalX(xOld + 1);
        fill.setOriginalY(newY + 1);
        fill.setOriginalWidth(Math.max(0, fillW));
        fill.setOriginalHeight(13);
        fill.revalidate();

        final String txt = unlocked + "/" + total;
        final Widget label = root.createChild(-1, WidgetType.TEXT);
        createdRootWidgets.add(label);
        label.setHidden(false);
        label.setText(txt);
        label.setTextColor(0xFFFFFF);
        label.setFontId(title.getFontId());
        label.setTextShadowed(title.getTextShadowed());
        label.setOriginalX(xOld + (newW / 2) - (txt.length() * 4));
        label.setOriginalY(newY + (15 / 2) - 6);
        label.setOriginalWidth(newW);
        label.setOriginalHeight(15);
        label.revalidate();
    }

    private int pickRepId(String base) {
        try {
            final Set<Integer> ids = itemsRepo.getIdsForBase(base);
            if (ids == null || ids.isEmpty()) return 0;
            return ids.stream().min(Integer::compareTo).orElse(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Restores the stock Music UI by unhiding the original children and deleting all override widgets.
     * Triggers onLoad listeners to let stock scripts reconstruct any ephemeral elements.
     */
    private void revertOverride() {
        if (!overrideActive) return;

        final Widget root = client.getWidget(MUSIC_GROUP, ROOT);
        final Widget scrollable = client.getWidget(MUSIC_GROUP, SCROLLABLE);
        final Widget jukebox = client.getWidget(MUSIC_GROUP, JUKEBOX);
        final Widget overlay = client.getWidget(MUSIC_GROUP, OVERLAY);
        final Widget scrollbar = client.getWidget(MUSIC_GROUP, SCROLLBAR);
        final Widget title = client.getWidget(MUSIC_GROUP, TITLE);

        // Hide top-row dynamic children we might have exposed
        if (root != null) {
            final Widget[] dynRoot = root.getDynamicChildren();
            if (dynRoot != null) {
                for (Widget w : dynRoot) {
                    if (w != null) {
                        w.setHidden(true);
                        w.revalidate();
                    }
                }
            }
        }

        deleteWidgets(createdScrollWidgets);
        deleteWidgets(createdRootWidgets);

        if (scrollable != null) {
            scrollable.deleteAllChildren();
            scrollable.revalidate();
        }

        restoreChildren(scrollable, backupScrollStaticKids, backupScrollDynamicKids);
        restoreChildren(jukebox, backupJukeboxStaticKids, backupJukeboxDynamicKids);

        for (int id = 9; id <= 19; id++) {
            final Widget w = client.getWidget(MUSIC_GROUP, id);
            if (w != null) {
                w.setHidden(false);
                w.revalidate();
            }
        }

        if (scrollbar != null) {
            scrollbar.setHidden(false);
            scrollbar.revalidate();
        }
        if (overlay != null) {
            overlay.setHidden(false);
            overlay.revalidate();
        }

        if (title != null && originalTitleText != null) {
            title.setText(originalTitleText);
            title.revalidate();
        }

        if (stockToggleAll != null) {
            stockToggleAll.setHidden(false);
            stockToggleAll.revalidate();
        }
        if (stockSearch != null) {
            stockSearch.setHidden(false);
            stockSearch.revalidate();
        }
        if (pluginToggle != null) {
            pluginToggle.setHidden(false);
            pluginToggle.revalidate();
        }

        // Re-fire onLoad listeners for stock widgets so their scripts rebuild internal content
        runOnLoad(root);
        runOnLoad(overlay);
        runOnLoad(scrollbar);
        runOnLoad(jukebox);
        runOnLoad(scrollable);

        originalTitleText = null;
        iconBaseMap.clear();
        backupJukeboxStaticKids = null;
        backupJukeboxDynamicKids = null;
        backupScrollStaticKids = null;
        backupScrollDynamicKids = null;
        stockToggleAll = null;
        stockSearch = null;
        pluginToggle = null;

        overrideActive = false;
        restoreTopRowControls();
    }

    private void runOnLoad(Widget w) {
        if (w == null || w.getOnLoadListener() == null) return;
        client.createScriptEvent(w.getOnLoadListener()).setSource(w).run();
        w.revalidate();
    }

    private Widget findByAction(Widget parent, String action) {
        if (parent == null) return null;
        final Widget[] kids = merge(parent.getChildren(), parent.getDynamicChildren());
        if (kids == null) return null;
        for (Widget c : kids) {
            if (c == null) continue;
            final String[] actions = c.getActions();
            if (actions != null) {
                for (String a : actions) {
                    if (a != null && a.equalsIgnoreCase(action)) return c;
                }
            }
            final Widget deeper = findByAction(c, action);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private void deleteWidgets(List<Widget> list) {
        if (list.isEmpty()) return;
        for (Widget w : list) {
            if (w == null) continue;
            try {
                w.deleteAllChildren();
            } catch (Throwable t) {
                try {
                    w.setHidden(true);
                    w.revalidate();
                } catch (Throwable ignored) {
                }
            }
        }
        list.clear();
    }

    private static final class DisplayBase {
        final String baseName;
        final int repId;

        DisplayBase(String baseName, int repId) {
            this.baseName = baseName;
            this.repId = repId;
        }
    }
}
