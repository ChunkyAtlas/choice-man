package com.choiceman.ui;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.ScriptEvent;
import net.runelite.api.gameval.InterfaceID;
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
 * then restores the original UI on demand. Provides a lightweight refresh path while active.
 */
@Singleton
public final class UnlocksWidgetController
{
    private static final int MUSIC_GROUP = InterfaceID.Music.UNIVERSE >>> 16;

    private static final int ICON_SIZE = 32;
    private static final int PADDING = 4;
    private static final int COLUMNS = 4;
    private static final int MARGIN_X = 8;
    private static final int MARGIN_Y = 8;

    private static final int CLOSE_SPRITE = 520;
    private static final int CLOSE_SIZE = 10;
    private static final int CLOSE_PAD = 4;

    private static final int BAR_HEIGHT = 15;

    private static final String CFG_GROUP = "choiceman";
    private static final String CFG_FAVS = "unlockFavorites";
    private static final int STAR_SPRITE = 366;   // yellow star

    // Widgets we hide during override and MUST ensure come back after restore.
    private static final int[] RESTORE_FORCE_VISIBLE_PACKEDS = new int[]
            {
                    InterfaceID.Music.CONTROLS,
                    InterfaceID.Music.AREA,
                    InterfaceID.Music.SHUFFLE,
                    InterfaceID.Music.SINGLE,
                    InterfaceID.Music.SKIP,
                    InterfaceID.Music.PLAYLIST,
                    InterfaceID.Music.DROPDOWN_CONTAINER,
                    InterfaceID.Music.DROPDOWN,
                    InterfaceID.Music.DROPDOWN_CONTENT,
                    InterfaceID.Music.DROPDOWN_SCROLLBAR,
                    InterfaceID.Music.COUNT,
                    InterfaceID.Music.NOW_PLAYING_TEXT,
            };

    private static final int[] RESTORE_FORCE_SHOW = new int[]
            {
                    InterfaceID.Music.CONTROLS,
                    InterfaceID.Music.AREA,
                    InterfaceID.Music.SHUFFLE,
                    InterfaceID.Music.SINGLE,
                    InterfaceID.Music.SKIP,
                    InterfaceID.Music.PLAYLIST,
                    InterfaceID.Music.DROPDOWN_CONTAINER,
                    InterfaceID.Music.DROPDOWN,
                    InterfaceID.Music.DROPDOWN_CONTENT,
                    InterfaceID.Music.DROPDOWN_SCROLLBAR,
                    InterfaceID.Music.COUNT,
                    InterfaceID.Music.NOW_PLAYING_TEXT,

                    InterfaceID.Music.JUKEBOX,
                    InterfaceID.Music.INNER,
                    InterfaceID.Music.SCROLLABLE,
                    InterfaceID.Music.SCROLLBAR,
                    InterfaceID.Music.NOW_PLAYING,
                    InterfaceID.Music.CONTENTS,
                    InterfaceID.Music.OVERLAY,
                    InterfaceID.Music.UNIVERSE
            };

    private final Client client;
    private final ClientThread clientThread;
    private final ChoiceManUnlocks unlocks;
    private final ItemsRepository itemsRepo;
    private final ItemSpriteCache itemSpriteCache;
    private final SpriteOverrideManager spriteOverrideManager;
    private final ConfigManager configManager;

    /**
     * Icon widget -> base name for hover tooltips.
     */
    @Getter
    private final Map<Widget, String> iconBaseMap = new LinkedHashMap<>();
    private final Set<String> favoriteBases = new LinkedHashSet<>();

    private final List<Widget> createdScrollWidgets = new ArrayList<>();
    private final List<Widget> createdRootWidgets = new ArrayList<>();
    @Getter
    private volatile boolean overrideActive = false;

    private String originalTitleText;
    private Widget pluginToggle;   // "View Unlocks"
    private boolean favoritesLoaded = false;

    private final Map<Integer, Boolean> hiddenStateByPacked = new HashMap<>();

    private static final class ChildBackup
    {
        List<Widget> stat = Collections.emptyList();
        List<Widget> dyn = Collections.emptyList();

        boolean captured()
        {
            return !stat.isEmpty() || !dyn.isEmpty();
        }
    }

    private enum SnapTarget
    {
        SCROLLABLE(InterfaceID.Music.SCROLLABLE),
        JUKEBOX(InterfaceID.Music.JUKEBOX),
        NOW_PLAYING(InterfaceID.Music.NOW_PLAYING),
        CONTROLS(InterfaceID.Music.CONTROLS);

        final int packed;

        SnapTarget(int packed)
        {
            this.packed = packed;
        }
    }

    private final EnumMap<SnapTarget, ChildBackup> backups = new EnumMap<>(SnapTarget.class);

    @Inject
    public UnlocksWidgetController(
            Client client,
            ClientThread clientThread,
            ChoiceManUnlocks unlocks,
            ItemsRepository itemsRepo,
            ItemSpriteCache itemSpriteCache,
            SpriteOverrideManager spriteOverrideManager,
            ConfigManager configManager
    )
    {
        this.client = client;
        this.clientThread = clientThread;
        this.unlocks = unlocks;
        this.itemsRepo = itemsRepo;
        this.itemSpriteCache = itemSpriteCache;
        this.spriteOverrideManager = spriteOverrideManager;
        this.configManager = configManager;
    }

    private Widget widget(int packed)
    {
        return client.getWidget(packed);
    }

    private void setHiddenRevalidate(Widget w, boolean hidden)
    {
        if (w == null)
        {
            return;
        }
        w.setHidden(hidden);
        w.revalidate();
    }

    private void revalidateScroll(Widget scrollbar)
    {
        if (scrollbar == null)
        {
            return;
        }
        scrollbar.revalidate();
        scrollbar.revalidateScroll();
    }

    private void rememberAndHidePacked(int packed)
    {
        Widget w = widget(packed);
        if (w == null)
        {
            return;
        }
        hiddenStateByPacked.putIfAbsent(packed, w.isHidden());
        setHiddenRevalidate(w, true);
    }

    private void rememberAndHideAll(int... packeds)
    {
        for (int p : packeds)
        {
            rememberAndHidePacked(p);
        }
    }

    private void restoreHiddenStates()
    {
        for (Map.Entry<Integer, Boolean> e : hiddenStateByPacked.entrySet())
        {
            Widget w = widget(e.getKey());
            if (w == null)
            {
                continue;
            }
            setHiddenRevalidate(w, Boolean.TRUE.equals(e.getValue()));
        }
        hiddenStateByPacked.clear();
    }

    private void forceShowCoreMusicControls()
    {
        for (int packed : RESTORE_FORCE_SHOW)
        {
            setHiddenRevalidate(widget(packed), false);
        }
    }

    private static List<Widget> copyChildren(Widget parent, boolean dynamic)
    {
        if (parent == null)
        {
            return Collections.emptyList();
        }
        Widget[] kids = dynamic ? parent.getDynamicChildren() : parent.getChildren();
        if (kids == null)
        {
            return Collections.emptyList();
        }
        List<Widget> out = new ArrayList<>(kids.length);
        for (Widget k : kids)
        {
            if (k != null) out.add(k);
        }
        return out;
    }

    private void ensureBaselineCaptured()
    {
        for (SnapTarget t : SnapTarget.values())
        {
            ChildBackup b = backups.computeIfAbsent(t, k -> new ChildBackup());
            if (b.captured())
            {
                continue;
            }
            Widget w = widget(t.packed);
            b.stat = copyChildren(w, false);
            b.dyn = copyChildren(w, true);
        }
    }

    private static void hideChildren(Widget[] kids)
    {
        if (kids == null)
        {
            return;
        }
        for (Widget w : kids)
        {
            if (w != null) w.setHidden(true);
        }
    }

    private static void unhideAndRevalidate(List<Widget> kids)
    {
        if (kids == null)
        {
            return;
        }
        for (Widget w : kids)
        {
            if (w != null && w.getType() != 0)
            {
                w.setHidden(false);
                w.revalidate();
            }
        }
    }

    private static void restoreChildren(Widget parent, List<Widget> staticKids, List<Widget> dynamicKids)
    {
        if (parent == null)
        {
            return;
        }

        hideChildren(parent.getChildren());
        hideChildren(parent.getDynamicChildren());

        unhideAndRevalidate(staticKids);
        unhideAndRevalidate(dynamicKids);

        parent.revalidate();
    }

    private void restoreBaseline()
    {
        for (Map.Entry<SnapTarget, ChildBackup> e : backups.entrySet())
        {
            Widget w = widget(e.getKey().packed);
            ChildBackup b = e.getValue();
            restoreChildren(w, b.stat, b.dyn);
        }
        backups.clear();
    }

    private static Widget[] merge(Widget[] a, Widget[] b)
    {
        if (a == null) return b;
        if (b == null) return a;
        final Widget[] out = new Widget[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * If the override is currently visible, rebuild it to reflect latest state.
     */
    public void refreshIfActive()
    {
        if (!overrideActive) return;
        clientThread.invokeLater(this::applyOverride);
    }

    /**
     * Entry point from the Music tab button; builds or refreshes the override UI.
     */
    public void overrideWithLatest()
    {
        if (unlocks.unlockedList().isEmpty())
        {
            return;
        }
        loadFavoritesIfNeeded();

        if (!overrideActive)
        {
            overrideActive = true;
            clientThread.invokeLater(() ->
            {
                applyOverride();
                spriteOverrideManager.register();
            });
        }
        else
        {
            clientThread.invokeLater(this::applyOverride);
        }
    }

    /**
     * Restore the stock Music tab.
     */
    public void restore()
    {
        if (!overrideActive)
        {
            return;
        }
        spriteOverrideManager.unregister();
        itemSpriteCache.clear();
        clientThread.invokeLater(this::revertOverride);
    }

    /**
     * Ensure stock top-row controls and the "View Unlocks" button are visible.
     */
    public void restoreTopRowControls()
    {
        forceShowCoreMusicControls();

        final Widget contents = widget(InterfaceID.Music.CONTENTS);
        if (contents != null)
        {
            final Widget btn = findByAction(contents, "View Unlocks");
            if (btn != null)
            {
                btn.setHidden(false);
                btn.revalidate();
            }
        }
    }

    private void loadFavoritesIfNeeded()
    {
        if (favoritesLoaded) return;
        favoritesLoaded = true;

        final String csv = configManager.getConfiguration(CFG_GROUP, CFG_FAVS);
        if (csv == null || csv.isEmpty()) return;

        for (String s : csv.split(","))
        {
            final String b = s.trim();
            if (!b.isEmpty()) favoriteBases.add(b);
        }
    }

    private void saveFavorites()
    {
        configManager.setConfiguration(CFG_GROUP, CFG_FAVS, String.join(",", favoriteBases));
    }

    private boolean isFavorite(String base)
    {
        return favoriteBases.contains(base);
    }

    private void toggleFavorite(String base)
    {
        if (!favoriteBases.remove(base)) favoriteBases.add(base);
        saveFavorites();
        applyOverride(); // re-sort and redraw grid
    }

    // helpers

    private void hideOtherMusicUi()
    {
        rememberAndHidePacked(InterfaceID.Music.JUKEBOX);
        rememberAndHideAll(RESTORE_FORCE_VISIBLE_PACKEDS);

        Widget np = widget(InterfaceID.Music.NOW_PLAYING);
        Widget c = widget(InterfaceID.Music.CONTROLS);

        if (np != null)
        {
            hideChildren(np.getChildren());
            hideChildren(np.getDynamicChildren());
            np.revalidate();
        }
        if (c != null)
        {
            hideChildren(c.getChildren());
            hideChildren(c.getDynamicChildren());
            c.revalidate();
        }
    }

    private Widget updateTitle()
    {
        Widget title = widget(InterfaceID.Music.NOW_PLAYING_TITLE);
        if (title != null)
        {
            if (originalTitleText == null)
            {
                originalTitleText = title.getText();
            }
            title.setText("Unlocked items");
            title.revalidate();
        }
        return title;
    }

    private int absX(Widget root, Widget w)
    {
        int x = 0;
        Widget cur = w;
        while (cur != null && root != null && cur.getId() != root.getId())
        {
            x += cur.getOriginalX();
            int pid = cur.getParentId();
            if (pid == -1)
            {
                break;
            }
            cur = client.getWidget(pid);
        }
        return x;
    }

    private int absY(Widget root, Widget w)
    {
        int y = 0;
        Widget cur = w;
        while (cur != null && root != null && cur.getId() != root.getId())
        {
            y += cur.getOriginalY();
            int pid = cur.getParentId();
            if (pid == -1)
            {
                break;
            }
            cur = client.getWidget(pid);
        }
        return y;
    }

    private int clamp(int v, int min, int max)
    {
        return Math.max(min, Math.min(max, v));
    }

    private void applyOverride()
    {
        ensureBaselineCaptured();
        iconBaseMap.clear();

        purgeOverrideWidgets();

        hideOtherMusicUi();

        final Widget root = widget(InterfaceID.Music.UNIVERSE);
        final Widget contents = widget(InterfaceID.Music.CONTENTS);
        final Widget frame = widget(InterfaceID.Music.FRAME);
        final Widget scrollable = widget(InterfaceID.Music.SCROLLABLE);
        final Widget jukebox = widget(InterfaceID.Music.JUKEBOX);
        final Widget scrollbar = widget(InterfaceID.Music.SCROLLBAR);

        if (contents != null)
        {
            pluginToggle = findByAction(contents, "View Unlocks");
            if (pluginToggle != null)
            {
                pluginToggle.setHidden(true);
                pluginToggle.revalidate();
            }
        }

        final Widget title = updateTitle();

        hideChildren(jukebox != null ? jukebox.getChildren() : null);
        hideChildren(jukebox != null ? jukebox.getDynamicChildren() : null);
        hideChildren(scrollable != null ? scrollable.getChildren() : null);
        hideChildren(scrollable != null ? scrollable.getDynamicChildren() : null);

        if (scrollable != null)
        {
            scrollable.deleteAllChildren();
            scrollable.revalidate();
        }

        final List<DisplayBase> bases = unlocks.unlockedList().stream()
                .map(b -> new DisplayBase(b, pickRepId(b)))
                .filter(db -> db.repId > 0)
                .collect(Collectors.toList());

        bases.sort(Comparator
                .comparing((DisplayBase db) -> !isFavorite(db.baseName))
                .thenComparing(db -> db.baseName.toLowerCase(Locale.ROOT)));

        setHiddenRevalidate(scrollable, false);
        setHiddenRevalidate(scrollbar, false);

        if (scrollable != null && scrollbar != null)
        {
            int displayIndex = 0;
            for (DisplayBase db : bases)
            {
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

                icon.setOnClickListener((JavaScriptCallback) (ScriptEvent ev) -> toggleFavorite(db.baseName));
                icon.setHasListener(true);

                if (isFavorite(db.baseName))
                {
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
            revalidateScroll(scrollbar);
        }

        drawProgressStretchToFrame(root, title, frame,
                unlocks.unlockedList().size(),
                itemsRepo.getAllBases().size());

        if (root != null)
        {
            final Widget close = root.createChild(-1);
            close.setHidden(false);
            close.setType(WidgetType.GRAPHIC);
            close.setOriginalX(CLOSE_PAD);
            close.setOriginalY(CLOSE_PAD);
            close.setOriginalWidth(CLOSE_SIZE);
            close.setOriginalHeight(CLOSE_SIZE);
            close.setSpriteId(CLOSE_SPRITE);
            close.setAction(0, "Close");
            close.setOnOpListener((JavaScriptCallback) (ScriptEvent ev) -> restore());
            close.setHasListener(true);
            close.revalidate();
            createdRootWidgets.add(close);

            root.revalidate();
        }
    }

    private void drawProgressStretchToFrame(Widget root, Widget title, Widget frame, int unlocked, int total)
    {
        if (root == null || title == null)
        {
            return;
        }

        int fontId = title.getFontId();
        boolean shadowed = title.getTextShadowed();

        int titleX = absX(root, title);
        int titleY = absY(root, title);
        int titleW = title.getOriginalWidth();
        int titleH = title.getOriginalHeight();

        int frameX = frame != null ? absX(root, frame) : (titleX + titleW);
        int frameW = frame != null ? frame.getOriginalWidth() : 0;
        int frameRight = frameW > 0 ? (frameX + frameW) : (titleX + titleW);

        int barX = titleX;
        int barY = Math.max(0, titleY + titleH - 1);

        int newW = Math.max(120, frameRight - barX - 8);
        newW = clamp(newW, 0, 4096);

        final Widget bg = root.createChild(-1, WidgetType.RECTANGLE);
        createdRootWidgets.add(bg);
        bg.setHidden(false);
        bg.setFilled(true);
        bg.setTextColor(0x000000);
        bg.setOriginalX(barX);
        bg.setOriginalY(barY);
        bg.setOriginalWidth(newW);
        bg.setOriginalHeight(BAR_HEIGHT);
        bg.revalidate();

        final int border = 1;
        final int inner = Math.max(0, newW - border * 2);
        final int fillW = total == 0 ? 0 : Math.round(inner * (float) unlocked / (float) total);

        final Widget fill = root.createChild(-1, WidgetType.RECTANGLE);
        createdRootWidgets.add(fill);
        fill.setHidden(false);
        fill.setFilled(true);
        fill.setTextColor(0x00b33c);
        fill.setOriginalX(barX + border);
        fill.setOriginalY(barY + border);
        fill.setOriginalWidth(Math.max(0, fillW));
        fill.setOriginalHeight(BAR_HEIGHT - border * 2);
        fill.revalidate();

        final String txt = unlocked + "/" + total;
        final Widget label = root.createChild(-1, WidgetType.TEXT);
        createdRootWidgets.add(label);
        label.setHidden(false);
        label.setText(txt);
        label.setTextColor(0xFFFFFF);
        label.setFontId(fontId);
        label.setTextShadowed(shadowed);
        label.setOriginalX(barX + (newW / 2) - (txt.length() * 4));
        label.setOriginalY(barY + (BAR_HEIGHT / 2) - 6);
        label.setOriginalWidth(newW);
        label.setOriginalHeight(BAR_HEIGHT);
        label.revalidate();
    }

    private int pickRepId(String base)
    {
        try
        {
            final Set<Integer> ids = itemsRepo.getIdsForBase(base);
            if (ids == null || ids.isEmpty()) return 0;
            return ids.stream().min(Integer::compareTo).orElse(0);
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    private void revertOverride()
    {
        if (!overrideActive) return;

        final Widget root = widget(InterfaceID.Music.UNIVERSE);
        final Widget scrollable = widget(InterfaceID.Music.SCROLLABLE);
        final Widget jukebox = widget(InterfaceID.Music.JUKEBOX);
        final Widget overlay = widget(InterfaceID.Music.OVERLAY);
        final Widget scrollbar = widget(InterfaceID.Music.SCROLLBAR);
        final Widget title = widget(InterfaceID.Music.NOW_PLAYING_TITLE);

        purgeOverrideWidgets();
        restoreBaseline();

        restoreHiddenStates();
        forceShowCoreMusicControls();

        if (scrollbar != null)
        {
            scrollbar.setHidden(false);
            scrollbar.revalidate();
            scrollbar.revalidateScroll();
        }
        if (overlay != null)
        {
            overlay.setHidden(false);
            overlay.revalidate();
        }

        if (title != null && originalTitleText != null)
        {
            title.setText(originalTitleText);
            title.revalidate();
        }

        runOnLoad(root);
        runOnLoad(overlay);
        runOnLoad(scrollbar);
        runOnLoad(jukebox);
        runOnLoad(scrollable);

        originalTitleText = null;
        iconBaseMap.clear();
        pluginToggle = null;

        overrideActive = false;
        restoreTopRowControls();
    }

    private void purgeOverrideWidgets()
    {
        purgeWidgets(createdRootWidgets);
        purgeWidgets(createdScrollWidgets);
        createdRootWidgets.clear();
        createdScrollWidgets.clear();
        iconBaseMap.clear();
    }

    /**
     * Forcefully removes widgets we created during override so they cannot
     * be resurrected by the music tab's onLoad() which tends to unhide children.
     */
    private static void purgeWidgets(List<Widget> widgets)
    {
        if (widgets == null)
        {
            return;
        }
        for (Widget w : widgets)
        {
            if (w == null)
            {
                continue;
            }
            try
            {
                w.setOnOpListener((JavaScriptCallback) null);
                w.setOnClickListener((JavaScriptCallback) null);
                w.setHasListener(false);
                w.setHidden(true);
                w.setOriginalX(0);
                w.setOriginalY(0);
                w.setOriginalWidth(0);
                w.setOriginalHeight(0);
                w.setType(0);
                w.revalidate();
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private Widget findByAction(Widget parent, String action)
    {
        if (parent == null) return null;
        final Widget[] kids = merge(parent.getChildren(), parent.getDynamicChildren());
        if (kids == null) return null;
        for (Widget c : kids)
        {
            if (c == null) continue;
            final String[] actions = c.getActions();
            if (actions != null)
            {
                for (String a : actions)
                {
                    if (a != null && a.equalsIgnoreCase(action)) return c;
                }
            }
            final Widget deeper = findByAction(c, action);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private void runOnLoad(Widget w)
    {
        if (w == null || w.getOnLoadListener() == null) return;
        w.revalidate();
    }

    private static final class DisplayBase
    {
        final String baseName;
        final int repId;

        DisplayBase(String baseName, int repId)
        {
            this.baseName = baseName;
            this.repId = repId;
        }
    }
}