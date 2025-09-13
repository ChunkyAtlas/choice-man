package com.choiceman.menus;

import com.choiceman.ChoiceManPlugin;
import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Central gatekeeper for menu interactions:
 * - If an item is tracked in items.json but its base is not unlocked, interaction is blocked.
 * - Non-tracked items are never blocked.
 * - Special-cases ground items, "use item on ..." flows, and bank/deposit UI allowances.
 */
@Singleton
public class ActionHandler {
    private static final Set<MenuAction> GROUND_ACTIONS = EnumSet.of(
            MenuAction.GROUND_ITEM_FIRST_OPTION,
            MenuAction.GROUND_ITEM_SECOND_OPTION,
            MenuAction.GROUND_ITEM_THIRD_OPTION,
            MenuAction.GROUND_ITEM_FOURTH_OPTION,
            MenuAction.GROUND_ITEM_FIFTH_OPTION
    );

    // Modern target interactions for "Use item on ..."
    private static final Set<MenuAction> USE_ACTIONS = EnumSet.of(
            MenuAction.WIDGET_TARGET_ON_NPC,
            MenuAction.WIDGET_TARGET_ON_PLAYER,
            MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
            MenuAction.WIDGET_TARGET_ON_WIDGET,
            MenuAction.WIDGET_TARGET_ON_GAME_OBJECT
    );

    // UIs in which we allow a limited set of benign operations
    private static final Set<Integer> ENABLED_UI_GROUPS = Set.of(
            EnabledUI.BANK.getId(),
            EnabledUI.DEPOSIT_BOX.getId()
    );

    private static final Consumer<MenuEntry> DISABLED = e -> {
    };

    @Inject
    private Client client;
    @Inject
    private EventBus eventBus;
    @Inject
    private ChoiceManPlugin plugin;
    @Inject
    private Restrictions restrictions;
    @Inject
    private ChoiceManUnlocks unlocks;
    @Inject
    private ItemsRepository itemsRepo;
    @Inject
    private ItemManager itemManager;

    @Getter
    @Setter
    private int enabledUIOpen = -1;

    private static boolean isGroundItem(MenuEntry entry) {
        return GROUND_ACTIONS.contains(entry.getType());
    }

    private static boolean equalsAny(String s, String... opts) {
        for (String o : opts) {
            if (s.equalsIgnoreCase(o)) return true;
        }
        return false;
    }

    /**
     * Ground-item safeguard used by click handler:
     * consume the event if the ground item is tracked but not unlocked.
     */
    public static void handleGroundItems(
            ItemManager itemManager,
            ChoiceManUnlocks unlocks,
            ItemsRepository itemsRepo,
            MenuOptionClicked event,
            ChoiceManPlugin plugin) {
        final MenuAction act = event.getMenuAction();
        if (act != null && GROUND_ACTIONS.contains(act)) {
            final int rawItemId = event.getId() != -1 ? event.getId() : event.getMenuEntry().getItemId();
            final int canonicalGroundId = itemManager.canonicalize(rawItemId);

            final String base = itemsRepo.getBaseForId(canonicalGroundId);
            if (base != null && !unlocks.isBaseUsable(base)) {
                event.consume();
            }
        }
    }

    public void startUp() {
        eventBus.register(this);
        eventBus.register(restrictions);
    }

    public void shutDown() {
        eventBus.unregister(this);
        eventBus.unregister(restrictions);
        enabledUIOpen = -1;
    }

    private boolean enabledUiOpen() {
        return enabledUIOpen != -1;
    }

    private boolean inactive() {
        return client.getGameState().getState() < GameState.LOADING.getState();
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        if (ENABLED_UI_GROUPS.contains(event.getGroupId())) {
            enabledUIOpen = -1;
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (ENABLED_UI_GROUPS.contains(event.getGroupId())) {
            enabledUIOpen = event.getGroupId();
        }
    }

    /**
     * Normalize a MenuEntryAdded into a canonical item id, or -1 if this row is not item-based.
     */
    private int getItemId(MenuEntryAdded event, MenuEntry entry) {
        final MenuAction type = entry.getType();
        final boolean hasItemId = entry.getItemId() > 0 || event.getItemId() > 0;
        if (!GROUND_ACTIONS.contains(type) && !hasItemId) return -1;

        final int raw = GROUND_ACTIONS.contains(type)
                ? event.getIdentifier()
                : Math.max(event.getItemId(), entry.getItemId());

        return itemManager.canonicalize(raw);
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        if (inactive()) return;

        final MenuEntry entry = event.getMenuEntry();
        final int id = getItemId(event, entry);

        final boolean allow = isGroundItem(entry)
                ? !isLockedGroundItem(id)
                : isEnabled(id, entry);

        if (!allow) {
            final String option = Text.removeTags(entry.getOption());
            final String target = Text.removeTags(entry.getTarget());
            entry.setOption("<col=808080>" + option);
            entry.setTarget("<col=808080>" + target);
            entry.onClick(DISABLED);
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        // Row was already disabled in onMenuEntryAdded
        if (event.getMenuEntry().onClick() == DISABLED) {
            event.consume();
            return;
        }

        // 1) Guard picking up tracked-but-locked ground items
        handleGroundItems(itemManager, unlocks, itemsRepo, event, plugin);

        // 2) Block "use item on ..." only when an actual item is being used
        final MenuEntry entry = event.getMenuEntry();
        final MenuAction action = entry.getType();

        if (USE_ACTIONS.contains(action)) {
            final int usedItemId = entry.getItemId(); // must be > 0 to be an item
            if (usedItemId > 0) {
                final int useCanon = itemManager.canonicalize(usedItemId);
                if (useCanon > 0) {
                    final String base = itemsRepo.getBaseForId(useCanon);
                    if (base != null && !unlocks.isBaseUsable(base)) {
                        event.consume();
                        return;
                    }
                }
            }
        }

        // 3) Extra safety for item-based rows only (skip NPC/object-only rows).
        if (entry.getItemId() > 0 && !GROUND_ACTIONS.contains(action)) {
            final String option = Text.removeTags(entry.getOption());

            // Always allow these safe ops
            if (equalsAny(option, "drop", "destroy", "release", "examine")) {
                return; // let it through
            }

            // While bank/deposit UI is open, allow benign banking ops
            if (enabledUiOpen()) {
                if (option.startsWith("Deposit")
                        || option.startsWith("Withdraw")
                        || option.startsWith("Examine")
                        || option.startsWith("Release")
                        || option.startsWith("Destroy")) {
                    return; // let it through
                }
            }

            final int canon = itemManager.canonicalize(entry.getItemId());
            if (canon > 0) {
                final String base = itemsRepo.getBaseForId(canon);
                if (base != null && !unlocks.isBaseUsable(base)) {
                    event.consume(); // block everything else on locked bases
                }
            }
        }
    }

    /**
     * A ground item is blocked if it's tracked and its base is not unlocked.
     */
    private boolean isLockedGroundItem(int canonicalId) {
        final String base = itemsRepo.getBaseForId(canonicalId);
        return base != null && !unlocks.isBaseUsable(base);
    }

    /**
     * Core gating for non-ground rows.
     * - Always allow safe operations (examine/drop/destroy/release).
     * - Defer skill/spell rows to {@link Restrictions}.
     * - If the row references a tracked item whose base is locked, only allow the safe operations.
     * - While bank/deposit UI is open, only allow benign banking operations.
     */
    private boolean isEnabled(int id, MenuEntry entry) {
        final String option = Text.removeTags(entry.getOption());
        final String target = Text.removeTags(entry.getTarget());

        // Always allow harmless operations
        if (equalsAny(option, "drop", "destroy", "release", "examine")) return true;

        // Skill/spell gating
        if (SkillOp.isSkillOp(option)) return restrictions.isSkillOpEnabled(option);
        if (Spell.isSpell(option)) return restrictions.isSpellOpEnabled(option);
        if (Spell.isSpell(target)) return restrictions.isSpellOpEnabled(target);

        // If this row doesn't reference an item, don't interfere
        if (id <= 0) return true;

        // Ignore untracked items
        if (!plugin.isInPlay(id)) return true;

        // Tracked: enforce base unlock
        final String base = itemsRepo.getBaseForId(id);
        final boolean baseUsable = base != null && unlocks.isBaseUsable(base);

        if (enabledUiOpen()) {
            // While bank/deposit UI is open, only allow these ops
            return option.startsWith("Deposit")
                    || option.startsWith("Withdraw")
                    || option.startsWith("Examine")
                    || option.startsWith("Release")
                    || option.startsWith("Destroy");
        }

        if (!baseUsable) {
            // Locked tracked item: allow only safe ops
            return equalsAny(option, "examine", "drop", "destroy", "release");
        }

        return true;
    }
}
