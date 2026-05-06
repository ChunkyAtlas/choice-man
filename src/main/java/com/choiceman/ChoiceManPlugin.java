package com.choiceman;

import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import com.choiceman.menus.ActionHandler;
import com.choiceman.menus.Restrictions;
import com.choiceman.ui.*;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

/**
 * Main plugin. Tracks level-ups, presents unlock choices, dims/blocks locked items,
 * and integrates with the Music tab.
 */
@PluginDescriptor(
        name = "Choice Man",
        description = "Every total-level increase presents items to unlock; dims/blocks locked items & spells; GE/shop restrictions.",
        tags = {"unlock", "ironman", "rules", "ge", "shops", "overlay", "runelite"}
)
@Slf4j
public class ChoiceManPlugin extends Plugin {
    private static final int GE_SEARCH_BUILD_SCRIPT = 751;
    private static final int GE_GROUP_ID = 162;
    private static final int GE_RESULTS_CHILD = 51;
    private static final String COL_RESET = "</col>";

    private static final String CONFIG_GROUP = "choiceman";
    private static final String CUSTOM_ITEMS_DATA_KEY = "customItems.data";
    private static final String CUSTOM_ITEMS_TIMESTAMP_KEY = "customItems.timestamp";
    private static final String CUSTOM_ITEMS_DIR = "choiceman";
    private static final String CUSTOM_ITEMS_FILE = "custom_items.json";
    private static final String CUSTOM_ITEMS_README_FILE = "custom_items_README.txt";

    private static final String DEFAULT_CUSTOM_ITEMS_TEMPLATE = String.join("\n",
            "[",
            "  {",
            "    \"name\": \"Example item - Abyssal whip\",",
            "    \"ids\": [4151]",
            "  },",
            "  {",
            "    \"name\": \"Example item - Dragon scimitar\",",
            "    \"ids\": [4587]",
            "  },",
            "  {",
            "    \"name\": \"Example item with variants\",",
            "    \"ids\": [11802, 11804, 11806, 11808]",
            "  }",
            "]",
            ""
    );

    private static final String CUSTOM_ITEMS_README = String.join("\n",
            "Choice Man custom item list",
            "",
            "Edit custom_items.json to define your own item pool.",
            "",
            "Format:",
            "[",
            "  {",
            "    \"name\": \"Abyssal whip\",",
            "    \"ids\": [4151]",
            "  },",
            "  {",
            "    \"name\": \"Dragon scimitar\",",
            "    \"ids\": [4587]",
            "  }",
            "]",
            "",
            "Rules:",
            "- The file must be valid JSON.",
            "- The root value must be an array.",
            "- Each item needs a non-empty name.",
            "- Each item needs ids as a non-empty array of positive OSRS item IDs.",
            "- Use multiple IDs for variants if you want them treated as the same base item.",
            "- After editing this file, toggle 'Use custom item list' off/on in the Choice Man config.",
            "- Valid custom lists are saved to RuneLite config so they can sync across profiles/machines.",
            ""
    );

    private static final Skill[] TRACKED_SKILLS = java.util.Arrays.stream(Skill.values())
            .toArray(Skill[]::new);

    /**
     * Queue of pending presentations across callbacks.
     */
    private final AtomicInteger pendingChoices = new AtomicInteger(0);

    /**
     * How many of the pending presentations are milestone (200/500/1000) and should use gold BG.
     */
    private final AtomicInteger pendingMilestoneChoices = new AtomicInteger(0);

    private final AtomicInteger pendingAutoMinimizeChoices = new AtomicInteger(0);

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private EventBus eventBus;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private MouseManager mouseManager;

    @Getter
    @Inject
    private ItemManager itemManager;

    @Inject
    private Gson gson;

    @Inject
    private ConfigManager configManager;

    @Inject
    private ChoiceManConfig config;

    @Inject
    private ChoiceManOverlay choiceManOverlay;

    @Inject
    private UnlocksTooltipOverlay unlocksTooltipOverlay;

    @Inject
    private ItemDimmerController itemDimmerController;

    @Inject
    private ActionHandler actionHandler;

    @Inject
    private Restrictions restrictions;

    @Inject
    private UnlocksWidgetController unlocksWidgetController;

    @Inject
    private ItemsRepository itemsRepo;

    @Inject
    private ChoiceManUnlocks unlocks;

    @Inject
    private UnlocksTabUI unlocksTabUI;

    @Inject
    private CombatMinimizer combatMinimizer;

    private ChoiceManPanel choiceManPanel;
    private NavigationButton navButton;
    private MouseListener overlayMouse;

    private volatile Integer forcedOfferCount = null;
    private volatile boolean featuresActive = false;
    private volatile int lastKnownTotal = -1;
    private volatile boolean baselineReady = false;
    private volatile int lastHintRemaining = Integer.MIN_VALUE;
    private volatile int lastHintThreshold = -2;
    private volatile boolean maxHintSent = false;
    private volatile boolean autoMinimizedActive = false;

    // Chat color helpers
    private static String blue(String s) {
        return "<col=0055aa>" + s + COL_RESET;
    }

    private static String red(String s) {
        return "<col=ff4040>" + s + COL_RESET;
    }

    private static String black(String s) {
        return "<col=000000>" + s + COL_RESET;
    }

    /**
     * Number of offer cards scales with total level.
     */
    private static int choiceCountForTotal(int total) {
        if (total >= 1000) return 5;
        if (total >= 500) return 4;
        if (total >= 200) return 3;
        return 2;
    }

    /**
     * Next threshold where choice-count increases.
     */
    private static int nextThreshold(int total) {
        if (total < 200) return 200;
        if (total < 500) return 500;
        if (total < 1000) return 1000;
        return -1;
    }

    @Provides
    ChoiceManConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ChoiceManConfig.class);
    }

    @Override
    protected void startUp() {
        ensureCustomItemsTemplateExists();

        // Startup prefers synced custom JSON so the default template does not override cloud-synced data.
        reloadItemRepository(false);

        eventBus.register(this);

        if (isNormalWorld()) {
            enableFeatures();
        }
    }

    @Override
    protected void shutDown() {
        if (featuresActive) {
            disableFeatures();
        }

        eventBus.unregister(this);
    }

    /**
     * Enable overlays, gating, panel, and music-tab integrations for normal worlds.
     */
    private void enableFeatures() {
        if (featuresActive) return;

        reloadItemRepository(false);

        featuresActive = true;

        unlocks.init(gson, itemsRepo);
        unlocks.loadFromDisk();

        pendingChoices.set(0);
        pendingMilestoneChoices.set(0);
        pendingAutoMinimizeChoices.set(0);
        lastKnownTotal = -1;
        baselineReady = false;
        lastHintRemaining = Integer.MIN_VALUE;
        lastHintThreshold = -2;
        maxHintSent = false;
        autoMinimizedActive = false;

        // Load both backgrounds: default and gold
        choiceManOverlay.setAssets(
                "/com/choiceman/ui/panel_bg.png",
                "/com/choiceman/ui/panel_bg_gold.png",
                itemManager, itemsRepo, unlocks
        );

        choiceManOverlay.setConfig(config);

        try {
            choiceManOverlay.setSfxVolumePercent(config.sfxVolume());
        } catch (Exception ex) {
            log.debug("Failed to set initial SFX volume", ex);
        }

        choiceManOverlay.setPendingCount(0);

        choiceManOverlay.setOnPick(baseName -> {
            unlocks.unlockBase(baseName, itemsRepo.getIdsForBase(baseName));
            unlocks.saveToDisk();

            choiceManOverlay.setOnDismiss(() ->
                    clientThread.invoke(this::startChoiceIfNeeded));

            // Live-refresh UI that depends on unlock state
            clientThread.invokeLater(() -> unlocksWidgetController.refreshIfActive());

            if (choiceManPanel != null) {
                SwingUtilities.invokeLater(() -> choiceManPanel.refresh(unlocks));
            }

            // Advance the queue
            int remaining = pendingChoices.decrementAndGet();
            choiceManOverlay.setPendingCount(Math.max(0, remaining));

            if (remaining <= 0) {
                forcedOfferCount = null; // return to normal (level-based) behavior
            }

            if (remaining > 0) {
                clientThread.invoke(this::startChoiceIfNeeded);
            }
        });

        overlayManager.add(choiceManOverlay);
        overlayManager.add(unlocksTooltipOverlay);

        overlayMouse = choiceManOverlay.getMouseAdapter();
        mouseManager.registerMouseListener(overlayMouse);

        itemDimmerController.setEnabled(config.dimLocked());
        itemDimmerController.setDimOpacity(config.dimOpacity());

        eventBus.register(itemDimmerController);
        eventBus.register(combatMinimizer);

        actionHandler.startUp();
        unlocksTabUI.startUp();

        choiceManPanel = new ChoiceManPanel(itemsRepo, unlocks, itemManager);

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/net/runelite/client/plugins/choiceman/icon.png");
        navButton = NavigationButton.builder()
                .tooltip("Choice Man")
                .icon(icon)
                .priority(5)
                .panel(choiceManPanel)
                .build();

        clientToolbar.addNavigation(navButton);

        SwingUtilities.invokeLater(() -> choiceManPanel.refresh(unlocks));
    }

    /**
     * Disable overlays, panel, and event subscriptions.
     */
    private void disableFeatures() {
        if (!featuresActive) return;

        featuresActive = false;

        try {
            clientThread.invokeLater(unlocksWidgetController::restore);
        } catch (Exception ex) {
            log.debug("Restore unlocks view failed", ex);
        }

        try {
            actionHandler.shutDown();
        } catch (Exception ex) {
            log.debug("ActionHandler shutdown failed", ex);
        }

        try {
            eventBus.unregister(itemDimmerController);
        } catch (Exception ex) {
            log.debug("Unregister dimmer failed", ex);
        }

        try {
            eventBus.unregister(combatMinimizer);
        } catch (Exception ex) {
            log.debug("CombatMinimizer shutdown failed", ex);
        }

        try {
            unlocksTabUI.shutDown();
        } catch (Exception ex) {
            log.debug("UnlocksTabUI shutdown failed", ex);
        }

        try {
            overlayManager.remove(choiceManOverlay);
            overlayManager.remove(unlocksTooltipOverlay);
        } catch (Exception ex) {
            log.debug("Overlay removal failed", ex);
        }

        if (overlayMouse != null) {
            try {
                mouseManager.unregisterMouseListener(overlayMouse);
            } catch (Exception ex) {
                log.debug("Mouse listener unregister failed", ex);
            } finally {
                overlayMouse = null;
            }
        }

        if (navButton != null) {
            try {
                clientToolbar.removeNavigation(navButton);
            } catch (Exception ex) {
                log.debug("Nav button removal failed", ex);
            } finally {
                navButton = null;
            }
        }

        choiceManPanel = null;

        pendingChoices.set(0);
        pendingMilestoneChoices.set(0);
        pendingAutoMinimizeChoices.set(0);
        choiceManOverlay.setPendingCount(0);
        lastKnownTotal = -1;
        autoMinimizedActive = false;
        baselineReady = false;
    }

    /**
     * Toggle features when hopping between normal and special worlds.
     */
    @Subscribe
    public void onWorldChanged(WorldChanged event) {
        if (isNormalWorld()) enableFeatures();
        else disableFeatures();
    }

    /**
     * Reset baseline at login to avoid double-awarding on reconnect.
     */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (!featuresActive) return;

        if (event.getGameState() == GameState.LOGGED_IN || event.getGameState() == GameState.LOGGING_IN) {
            baselineReady = false;
        }
    }

    /**
     * Award a pending choice per net increase in total level since the last tick.
     * Queue milestone presentations when crossing 200/500/1000.
     * Also announce how many more levels until the next choice-count threshold.
     */
    @Subscribe
    public void onGameTick(GameTick tick) {
        if (!featuresActive || client.getGameState() != GameState.LOGGED_IN) return;

        if (choiceManOverlay.isActive()
                && choiceManOverlay.isMinimized()
                && autoMinimizedActive
                && !combatMinimizer.isInCombatNow()) {
            choiceManOverlay.setMinimized(false);
            autoMinimizedActive = false;
        }

        final int current = computeTotalLevel();

        if (!baselineReady) {
            lastKnownTotal = current;
            baselineReady = true;
            announceThresholdHint(current); // initial hint after login
            return;
        }

        if (current > lastKnownTotal) {
            int delta = current - lastKnownTotal;

            // Count milestone crossings (each one deserves a gold presentation)
            int crossedMilestones = 0;
            if (lastKnownTotal < 200 && current >= 200) crossedMilestones++;
            if (lastKnownTotal < 500 && current >= 500) crossedMilestones++;
            if (lastKnownTotal < 1000 && current >= 1000) crossedMilestones++;

            if (crossedMilestones > 0) {
                pendingMilestoneChoices.addAndGet(crossedMilestones);
            }

            if (combatMinimizer.isInCombatNow()) {
                pendingAutoMinimizeChoices.addAndGet(delta);
            }

            int totalQueued = pendingChoices.addAndGet(delta);
            lastKnownTotal = current;

            // Update minimized pill with true queue count
            choiceManOverlay.setPendingCount(Math.max(0, totalQueued));

            // Chat hint about the next threshold
            announceThresholdHint(current);

            startChoiceIfNeeded();
        }
    }

    /**
     * React to live config changes (dim/opacity/SFX/GE/custom items).
     */
    @Subscribe
    public void onConfigChanged(net.runelite.client.events.ConfigChanged event) {
        if (!CONFIG_GROUP.equals(event.getGroup())) return;

        if ("useCustomItems".equals(event.getKey())) {
            // Config toggle ON reads the local file immediately and syncs it if valid.
            reloadItemRepositoryAndRefresh(true);
            return;
        }

        if (!featuresActive) return;

        switch (event.getKey()) {
            case "dimLocked":
            case "dimOpacity":
                itemDimmerController.setEnabled(config.dimLocked());
                itemDimmerController.setDimOpacity(config.dimOpacity());
                break;

            case "sfxVolume":
                try {
                    choiceManOverlay.setSfxVolumePercent(config.sfxVolume());
                } catch (Exception ex) {
                    log.debug("Failed to apply SFX volume", ex);
                }
                break;

            default:
                break;
        }
    }

    /**
     * Start a presentation if we have pending choices and the overlay is idle.
     * Pulls one milestone flag from the queue if available.
     */
    private void startChoiceIfNeeded() {
        if (pendingChoices.get() <= 0) return;
        if (choiceManOverlay.isActive()) return;

        List<String> pool = itemsRepo.getAllBasesStillLocked(unlocks);
        if (pool.isEmpty()) {
            pendingChoices.set(0);
            pendingMilestoneChoices.set(0);
            pendingAutoMinimizeChoices.set(0);
            choiceManOverlay.setPendingCount(0);
            return;
        }

        int offerCount = (forcedOfferCount != null)
                ? Math.max(1, Math.min(5, forcedOfferCount))
                : choiceCountForTotal(computeTotalLevel());

        Collections.shuffle(pool, ThreadLocalRandom.current());

        List<String> offer = pool.stream()
                .limit(Math.min(offerCount, pool.size()))
                .collect(Collectors.toList());

        boolean milestone = false;
        int m = pendingMilestoneChoices.get();

        if (m > 0) {
            // consume one milestone flag for this presentation
            pendingMilestoneChoices.decrementAndGet();
            milestone = true;
        }

        choiceManOverlay.presentChoicesSequential(offer, milestone);

        int hadTag = pendingAutoMinimizeChoices.getAndUpdate(v -> v > 0 ? v - 1 : 0);
        if (hadTag > 0 && combatMinimizer.isInCombatNow()) {
            choiceManOverlay.setMinimized(true);
            autoMinimizedActive = true;
        }
    }

    /**
     * Mark bases as obtained the first time they appear in inventory; update dependent UI.
     */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (!featuresActive) return;

        if (event.getItemContainer() != null && event.getContainerId() == InventoryID.INV) {
            for (net.runelite.api.Item item : event.getItemContainer().getItems()) {
                if (item == null) continue;

                int canon = itemManager.canonicalize(item.getId());
                String base = itemsRepo.getBaseForId(canon);

                if (base != null && unlocks.markObtainedBaseIfFirst(base)) {
                    clientThread.invokeLater(() -> unlocksWidgetController.refreshIfActive());

                    if (choiceManPanel != null) {
                        SwingUtilities.invokeLater(() -> choiceManPanel.refresh(unlocks));
                    }
                }
            }
        }
    }

    @Subscribe
    public void onCommandExecuted(CommandExecuted e) {
        if (!featuresActive) return;
        if (!"roll".equalsIgnoreCase(e.getCommand())) return;

        Integer showCount = null;
        Integer queuedCount = null;

        for (String arg : e.getArguments()) {
            try {
                int v = Integer.parseInt(arg.trim());
                if (showCount == null) {
                    showCount = v;
                } else if (queuedCount == null) {
                    queuedCount = v;
                    break;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (showCount == null || showCount < 1 || showCount > 5) {
            addGameMessage(black("[") + blue("Choice Man") + black("] ")
                    + "Usage: ::roll " + red("N") + " unlocks " + black("[") + red("Q") + black("] ")
                    + "(N = 1-5 cards per pick, optional Q = number of picks to queue).");
            return;
        }

        int q = queuedCount == null ? 1 : Math.max(1, Math.min(50, queuedCount)); // safety cap

        // Build pool to ensure there is at least something to show
        List<String> allLocked = itemsRepo.getAllBasesStillLocked(unlocks);
        if (allLocked.isEmpty()) {
            addGameMessage(black("[") + blue("Choice Man") + black("] ") + "No locked items remain to roll.");
            return;
        }

        // Force the presentation size for this manual queue
        forcedOfferCount = showCount;

        // Add Q presentations to the standard queue and reflect in minimized pill
        int totalQueued = pendingChoices.addAndGet(q);
        choiceManOverlay.setPendingCount(Math.max(0, totalQueued));

        if (!choiceManOverlay.isActive()) {
            startChoiceIfNeeded();

            addGameMessage(black("[") + blue("Choice Man") + black("] ")
                    + "Rolling " + red(String.valueOf(q)) + " pick" + (q == 1 ? "" : "s")
                    + " with " + red(String.valueOf(showCount)) + " choices each.");
        } else {
            addGameMessage(black("[") + blue("Choice Man") + black("] ")
                    + "Queued " + red(String.valueOf(q)) + " more pick" + (q == 1 ? "" : "s")
                    + " (" + red(String.valueOf(showCount)) + " choices each).");
        }
    }

    /**
     * After GE search list is built, hide entries that are locked or never obtained.
     */
    @Subscribe
    public void onScriptPostFired(ScriptPostFired event) {
        if (!featuresActive || !config.geRestrictions()) return;

        if (event.getScriptId() == GE_SEARCH_BUILD_SCRIPT) {
            filterGeResults();
        }
    }

    private void filterGeResults() {
        final Widget results = client.getWidget(GE_GROUP_ID, GE_RESULTS_CHILD);
        if (results == null) return;

        final Widget[] children = results.getDynamicChildren();
        if (children == null || children.length < 2 || children.length % 3 != 0) return;

        for (int i = 0; i < children.length; i += 3) {
            final Widget row = children[i];
            final Widget icon = children[i + 1];
            final Widget item = children[i + 2];

            if (item == null) continue;

            final int canon = itemManager.canonicalize(item.getItemId());
            final String base = itemsRepo.getBaseForId(canon);

            final boolean ok = base != null && unlocks.isBaseUnlocked(base) && unlocks.isBaseObtained(base);
            if (!ok) {
                if (row != null) row.setHidden(true);
                if (icon != null) icon.setOpacity(70);
                item.setOpacity(70);
            }
        }
    }

    /**
     * @return true if an item is tracked by Choice Man (present in items.json).
     */
    public boolean isInPlay(int itemId) {
        int canon = itemManager.canonicalize(itemId);
        return itemsRepo.getBaseForId(canon) != null;
    }

    /**
     * Normal worlds exclude PvP/beta/tournament/speedrunning variants.
     */
    public boolean isNormalWorld() {
        EnumSet<WorldType> worldTypes = client.getWorldType();

        return !(worldTypes.contains(WorldType.DEADMAN)
                || worldTypes.contains(WorldType.SEASONAL)
                || worldTypes.contains(WorldType.BETA_WORLD)
                || worldTypes.contains(WorldType.PVP_ARENA)
                || worldTypes.contains(WorldType.QUEST_SPEEDRUNNING)
                || worldTypes.contains(WorldType.TOURNAMENT_WORLD));
    }

    /**
     * Compute total level from real levels only.
     */
    private int computeTotalLevel() {
        int sum = 0;
        for (Skill s : TRACKED_SKILLS) sum += client.getRealSkillLevel(s);
        return sum;
    }

    private void announceThresholdHint(int currentTotal) {
        final int currentChoices = choiceCountForTotal(currentTotal);
        final int next = nextThreshold(currentTotal);

        final String prefix = black("[") + blue("Choice Man") + black("] ");

        if (next == -1) {
            if (maxHintSent) return;

            maxHintSent = true;
            addGameMessage(prefix + "You're at the max - " + red(currentChoices + " options") + " per pick.");
            return;
        }

        maxHintSent = false;

        final int remaining = Math.max(0, next - currentTotal);
        if (remaining <= 0 || remaining % 5 != 0) return;
        if (remaining == lastHintRemaining && next == lastHintThreshold) return;

        lastHintRemaining = remaining;
        lastHintThreshold = next;

        final String levelsWord = remaining == 1 ? "level" : "levels";

        addGameMessage(prefix
                + red(remaining + " " + levelsWord) + " until your picks show "
                + red((currentChoices + 1) + " options")
                + " (threshold: " + red(String.valueOf(next)) + " total).");
    }

    /**
     * Always creates the local example files if missing.
     * Existing player-edited files are never overwritten.
     */
    private Path ensureCustomItemsTemplateExists() {
        Path dir = RUNELITE_DIR.toPath().resolve(CUSTOM_ITEMS_DIR);
        Path customItemsPath = dir.resolve(CUSTOM_ITEMS_FILE);
        Path readmePath = dir.resolve(CUSTOM_ITEMS_README_FILE);

        try {
            Files.createDirectories(dir);

            if (!Files.exists(customItemsPath)) {
                Files.write(customItemsPath, DEFAULT_CUSTOM_ITEMS_TEMPLATE.getBytes(StandardCharsets.UTF_8));
            }

            if (!Files.exists(readmePath)) {
                Files.write(readmePath, CUSTOM_ITEMS_README.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            log.warn("Failed to create Choice Man custom item template files", ex);
        }

        return customItemsPath;
    }

    /**
     * Loads bundled items first. If custom items are enabled, attempts custom loading.
     * <p>
     * preferLocalCustomFile:
     * - true when the user toggles the config, so their edited local file is imported immediately.
     * - false on startup, so synced config data wins over the untouched starter template.
     */
    private void reloadItemRepository(boolean preferLocalCustomFile) {
        itemsRepo.loadFromResources(gson);

        if (!config.useCustomItems()) {
            return;
        }

        String json = null;

        if (preferLocalCustomFile) {
            json = readLocalCustomItemsAndSync(true);
        }

        if (json == null) {
            json = readSyncedCustomItems(preferLocalCustomFile);
        }

        if (json == null && !preferLocalCustomFile) {
            json = readLocalCustomItemsAndSync(false);
        }

        if (json != null && !itemsRepo.loadFromString(gson, json)) {
            log.warn("Failed to load custom Choice Man items; using bundled items.");
        }
    }

    private void reloadItemRepositoryAndRefresh(boolean preferLocalCustomFile) {
        reloadItemRepository(preferLocalCustomFile);

        if (!featuresActive) {
            return;
        }

        unlocks.init(gson, itemsRepo);
        unlocks.loadFromDisk();

        clientThread.invokeLater(() -> unlocksWidgetController.refreshIfActive());

        if (choiceManPanel != null) {
            SwingUtilities.invokeLater(() -> choiceManPanel.refresh(unlocks));
        }
    }

    private String readLocalCustomItemsAndSync(boolean showMessages) {
        Path path = ensureCustomItemsTemplateExists();

        String json;
        try {
            json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("Failed to read custom Choice Man items from {}", path, ex);

            if (showMessages) {
                addGameMessage(black("[") + blue("Choice Man") + black("] ")
                        + "Failed to read custom_items.json. Using bundled items.");
            }

            return null;
        }

        if (isDefaultCustomItemsTemplate(json)) {
            if (showMessages) {
                addGameMessage(black("[") + blue("Choice Man") + black("] ")
                        + "custom_items.json is still the example template. Edit it first, then toggle this setting off/on.");
            }

            return null;
        }

        ItemsRepository.ValidationResult validation = ItemsRepository.validateCustomItemsJson(gson, json);
        if (!validation.isValid()) {
            if (showMessages) {
                addValidationErrors("custom_items.json is invalid", validation.getErrors());
            }

            return null;
        }

        configManager.setConfiguration(CONFIG_GROUP, CUSTOM_ITEMS_DATA_KEY, json);
        configManager.setConfiguration(CONFIG_GROUP, CUSTOM_ITEMS_TIMESTAMP_KEY, String.valueOf(System.currentTimeMillis()));

        return json;
    }

    private String readSyncedCustomItems(boolean showMessages) {
        String json = configManager.getConfiguration(CONFIG_GROUP, CUSTOM_ITEMS_DATA_KEY);
        if (json == null || json.trim().isEmpty()) {
            if (showMessages) {
                addGameMessage(black("[") + blue("Choice Man") + black("] ")
                        + "No synced custom item list found. Using bundled items.");
            }

            return null;
        }

        ItemsRepository.ValidationResult validation = ItemsRepository.validateCustomItemsJson(gson, json);
        if (!validation.isValid()) {
            log.warn("Synced custom Choice Man item list is invalid: {}", validation.getErrors());

            if (showMessages) {
                addValidationErrors("Synced custom item list is invalid", validation.getErrors());
            }

            return null;
        }

        return json;
    }

    private boolean isDefaultCustomItemsTemplate(String json) {
        if (json == null) {
            return false;
        }

        String normalized = json.replace("\r\n", "\n").trim();
        String template = DEFAULT_CUSTOM_ITEMS_TEMPLATE.replace("\r\n", "\n").trim();

        return normalized.equals(template);
    }

    private void addValidationErrors(String heading, List<String> errors) {
        String firstErrors = errors.stream()
                .limit(3)
                .collect(Collectors.joining("; "));

        addGameMessage(black("[") + blue("Choice Man") + black("] ")
                + heading + ": " + red(firstErrors));

        if (errors.size() > 3) {
            addGameMessage(black("[") + blue("Choice Man") + black("] ")
                    + red(String.valueOf(errors.size() - 3)) + " more validation error(s). Check custom_items.json.");
        }
    }

    private void addGameMessage(String msg) {
        try {
            client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
        } catch (Exception ex) {
            log.debug("Failed to add chat message", ex);
        }
    }
}