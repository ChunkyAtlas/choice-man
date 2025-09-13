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
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@PluginDescriptor(
        name = "Choice Man",
        description = "Every total-level increase presents items to unlock; dims/blocks locked items & spells; GE/shop restrictions.",
        tags = {"unlock", "ironman", "rules", "ge", "shops", "overlay", "runelite"}
)
@Slf4j
public class ChoiceManPlugin extends Plugin
{
    // GE script/ids used to prune results
    private static final int GE_SEARCH_BUILD_SCRIPT = 751;
    private static final int GE_GROUP_ID = 162;
    private static final int GE_RESULTS_CHILD = 51;

    // Cache non-overall skills to avoid per-tick allocations
    private static final Skill[] TRACKED_SKILLS = java.util.Arrays.stream(Skill.values())
            .filter(s -> s != Skill.OVERALL)
            .toArray(Skill[]::new);

    // Pending choice presentations across callbacks
    private final AtomicInteger pendingChoices = new AtomicInteger(0);

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private EventBus eventBus;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private MouseManager mouseManager;
    @Getter @Inject private ItemManager itemManager;
    @Inject private Gson gson; // RuneLite's Gson (injected)
    @Inject private ChoiceManConfig config;
    @Inject private ChoiceManOverlay choiceManOverlay;
    @Inject private UnlocksTooltipOverlay unlocksTooltipOverlay;
    @Inject private ItemDimmerController itemDimmerController;
    @Inject private ActionHandler actionHandler;
    @Inject private Restrictions restrictions;
    @Inject private UnlocksWidgetController unlocksWidgetController;
    @Inject private MusicOpenButton musicOpenButton;
    @Inject private TabListener tabListener;
    @Inject private ItemsRepository itemsRepo;
    @Inject private ChoiceManUnlocks unlocks;

    private ChoiceManPanel choiceManPanel;
    private NavigationButton navButton;
    private MouseListener overlayMouse;

    // client-thread-guarded state (visibility via volatile)
    private volatile boolean featuresActive = false;
    private volatile int lastKnownTotal = -1;
    private volatile boolean baselineReady = false;

    /** Number of offer cards scales with total level. */
    private static int choiceCountForTotal(int total)
    {
        if (total >= 1000) return 5;
        if (total >= 500)  return 4;
        if (total >= 200)  return 3;
        return 2;
    }

    @Provides
    ChoiceManConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(ChoiceManConfig.class);
    }

    @Override
    protected void startUp()
    {
        itemsRepo.loadFromResources(gson);
        eventBus.register(this);
        if (isNormalWorld()) {
            enableFeatures();
        }
    }

    @Override
    protected void shutDown()
    {
        if (featuresActive) {
            disableFeatures();
        }
        eventBus.unregister(this);
    }

    /** Enable overlays, gating, panel, and music-tab integrations for normal worlds. */
    private void enableFeatures()
    {
        if (featuresActive) return;
        featuresActive = true;

        unlocks.init(gson, itemsRepo);
        unlocks.loadFromDisk();

        pendingChoices.set(0);
        lastKnownTotal = -1;
        baselineReady = false;

        choiceManOverlay.setAssets("/com/choiceman/ui/panel_bg.png", itemManager, itemsRepo, unlocks);
        choiceManOverlay.setConfig(config);
        try {
            choiceManOverlay.setSfxVolumePercent(config.sfxVolume());
        } catch (Exception ex) {
            log.debug("Failed to set initial SFX volume", ex);
        }

        choiceManOverlay.setOnPick(baseName -> {
            unlocks.unlockBase(baseName, itemsRepo.getIdsForBase(baseName));
            unlocks.saveToDisk();
            if (choiceManPanel != null) {
                SwingUtilities.invokeLater(() -> choiceManPanel.refresh(unlocks));
            }
            if (pendingChoices.decrementAndGet() > 0) {
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

        actionHandler.startUp();

        eventBus.register(musicOpenButton);
        musicOpenButton.onStart();
        eventBus.register(tabListener);

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

    /** Disable overlays, panel, and event subscriptions. */
    private void disableFeatures()
    {
        if (!featuresActive) return;
        featuresActive = false;

        try {
            clientThread.invokeLater(unlocksWidgetController::restore);
        } catch (Exception ex) {
            log.debug("Restore unlocks view failed", ex);
        }

        try { actionHandler.shutDown(); } catch (Exception ex) { log.debug("ActionHandler shutdown failed", ex); }
        try { eventBus.unregister(itemDimmerController); } catch (Exception ex) { log.debug("Unregister dimmer failed", ex); }
        try { musicOpenButton.onStop(); } catch (Exception ex) { log.debug("MusicOpenButton stop failed", ex); }
        try { eventBus.unregister(musicOpenButton); } catch (Exception ex) { log.debug("Unregister musicOpenButton failed", ex); }
        try { eventBus.unregister(tabListener); } catch (Exception ex) { log.debug("Unregister tabListener failed", ex); }

        try {
            overlayManager.remove(choiceManOverlay);
            overlayManager.remove(unlocksTooltipOverlay);
        } catch (Exception ex) {
            log.debug("Overlay removal failed", ex);
        }

        if (overlayMouse != null) {
            try { mouseManager.unregisterMouseListener(overlayMouse); }
            catch (Exception ex) { log.debug("Mouse listener unregister failed", ex); }
            finally { overlayMouse = null; }
        }

        if (navButton != null) {
            try { clientToolbar.removeNavigation(navButton); }
            catch (Exception ex) { log.debug("Nav button removal failed", ex); }
            finally { navButton = null; }
        }
        choiceManPanel = null;

        pendingChoices.set(0);
        lastKnownTotal = -1;
        baselineReady = false;
    }

    /** Toggle features when hopping between normal and special worlds. */
    @Subscribe
    public void onWorldChanged(WorldChanged event)
    {
        if (isNormalWorld()) enableFeatures();
        else disableFeatures();
    }

    /** Reset baseline at login to avoid double-awarding on reconnect. */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (!featuresActive) return;
        if (event.getGameState() == GameState.LOGGED_IN || event.getGameState() == GameState.LOGGING_IN) {
            baselineReady = false;
        }
    }

    /** Award a pending choice per net increase in total level since the last tick. */
    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (!featuresActive || client.getGameState() != GameState.LOGGED_IN) return;

        final int current = computeTotalLevel();
        if (!baselineReady) {
            lastKnownTotal = current;
            baselineReady = true;
            return;
        }

        if (current > lastKnownTotal) {
            int delta = current - lastKnownTotal;
            pendingChoices.addAndGet(delta);
            lastKnownTotal = current;
            startChoiceIfNeeded();
        }
    }

    /** React to live config changes only (dim/opacity/SFX/GE). */
    @Subscribe
    public void onConfigChanged(net.runelite.client.events.ConfigChanged event)
    {
        if (!featuresActive || !"choiceman".equals(event.getGroup())) return;

        switch (event.getKey()) {
            case "dimLocked":
            case "dimOpacity":
                itemDimmerController.setEnabled(config.dimLocked());
                itemDimmerController.setDimOpacity(config.dimOpacity());
                break;

            case "sfxVolume":
                try { choiceManOverlay.setSfxVolumePercent(config.sfxVolume()); }
                catch (Exception ex) { log.debug("Failed to apply SFX volume", ex); }
                break;

            default:
                break;
        }
    }

    /** Intentionally unused; awarding is handled on GameTick to avoid double counting. */
    @Subscribe
    public void onStatChanged(StatChanged event) { /* no-op */ }

    /** Start a presentation if we have pending choices and the overlay is idle. */
    private void startChoiceIfNeeded()
    {
        if (pendingChoices.get() <= 0 || choiceManOverlay.isActive()) return;

        List<String> pool = itemsRepo.getAllBasesStillLocked(unlocks);
        if (pool.isEmpty()) {
            pendingChoices.set(0);
            return;
        }

        int offerCount = choiceCountForTotal(computeTotalLevel());
        Collections.shuffle(pool, ThreadLocalRandom.current());
        List<String> offer = pool.stream().limit(Math.min(offerCount, pool.size())).collect(Collectors.toList());
        choiceManOverlay.presentChoicesSequential(offer);
    }

    /** Mark bases as obtained the first time they appear in inventory; update panel counts. */
    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!featuresActive) return;
        if (event.getItemContainer() != null && event.getContainerId() == InventoryID.INV) {
            for (net.runelite.api.Item item : event.getItemContainer().getItems()) {
                if (item == null) continue;
                int canon = itemManager.canonicalize(item.getId());
                String base = itemsRepo.getBaseForId(canon);
                if (base != null && unlocks.markObtainedBaseIfFirst(base) && choiceManPanel != null) {
                    SwingUtilities.invokeLater(() -> choiceManPanel.refresh(unlocks));
                }
            }
        }
    }

    /** After GE search list is built, hide entries that are locked or never obtained. */
    @Subscribe
    public void onScriptPostFired(ScriptPostFired event)
    {
        if (!featuresActive || !config.geRestrictions()) return;
        if (event.getScriptId() == GE_SEARCH_BUILD_SCRIPT) {
            filterGeResults();
        }
    }

    private void filterGeResults()
    {
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

            final boolean ok = (base != null) && unlocks.isBaseUnlocked(base) && unlocks.isBaseObtained(base);
            if (!ok) {
                if (row != null) row.setHidden(true);
                if (icon != null) icon.setOpacity(70);
                item.setOpacity(70);
            }
        }
    }

    /** @return whether an item is tracked by Choice Man (present in items.json). */
    public boolean isInPlay(int itemId)
    {
        int canon = itemManager.canonicalize(itemId);
        return itemsRepo.getBaseForId(canon) != null;
    }

    /** Normal worlds exclude PvP/beta/tournament/speedrunning variants. */
    public boolean isNormalWorld()
    {
        EnumSet<WorldType> worldTypes = client.getWorldType();
        return !(worldTypes.contains(WorldType.DEADMAN)
                || worldTypes.contains(WorldType.SEASONAL)
                || worldTypes.contains(WorldType.BETA_WORLD)
                || worldTypes.contains(WorldType.PVP_ARENA)
                || worldTypes.contains(WorldType.QUEST_SPEEDRUNNING)
                || worldTypes.contains(WorldType.TOURNAMENT_WORLD));
    }

    /** Compute total level from real levels only (test overrides removed). */
    private int computeTotalLevel()
    {
        int sum = 0;
        for (Skill s : TRACKED_SKILLS) {
            sum += client.getRealSkillLevel(s);
        }
        return sum;
    }
}
