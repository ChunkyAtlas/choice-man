package com.choiceman.menus;

import com.choiceman.ChoiceManConfig;
import com.choiceman.ChoiceManPlugin;
import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import com.choiceman.filters.EnsouledHeadMapping;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Central gatekeeper for Choice Man menu interactions.
 */
@Singleton
public class ActionHandler
{
    private static final Set<MenuAction> DISABLED_ACTIONS = EnumSet.of(
            MenuAction.CC_OP,
            MenuAction.WIDGET_TARGET,
            MenuAction.WIDGET_TARGET_ON_WIDGET
    );

    private static final Set<MenuAction> GROUND_ACTIONS = EnumSet.of(
            MenuAction.GROUND_ITEM_FIRST_OPTION,
            MenuAction.GROUND_ITEM_SECOND_OPTION,
            MenuAction.GROUND_ITEM_THIRD_OPTION,
            MenuAction.GROUND_ITEM_FOURTH_OPTION,
            MenuAction.GROUND_ITEM_FIFTH_OPTION
    );

    private static final Set<Integer> ALWAYS_ALLOW_OBJECT_IDS = new HashSet<>();

    private static final EnumSet<MenuAction> GAME_OBJECT_ACTIONS = EnumSet.of(
            MenuAction.GAME_OBJECT_FIRST_OPTION,
            MenuAction.GAME_OBJECT_SECOND_OPTION,
            MenuAction.GAME_OBJECT_THIRD_OPTION,
            MenuAction.GAME_OBJECT_FOURTH_OPTION,
            MenuAction.GAME_OBJECT_FIFTH_OPTION
    );

    private static final Set<Integer> ENABLED_UI_GROUPS = new HashSet<>();

    private static final int ORBS_GROUP = InterfaceID.Orbs.UNIVERSE >>> 16;

    static
    {
        ALWAYS_ALLOW_OBJECT_IDS.add(net.runelite.api.gameval.ObjectID.CATABOW);

        for (EnabledUI ui : EnabledUI.values())
        {
            ENABLED_UI_GROUPS.add(ui.getId());
        }
    }

    private static final Consumer<MenuEntry> DISABLED = e -> { };

    @Inject private Client client;
    @Inject private EventBus eventBus;
    @Inject private ChoiceManPlugin plugin;
    @Inject private ChoiceManConfig config;
    @Inject private Restrictions restrictions;
    @Inject private ChoiceManUnlocks unlocks;
    @Inject private ItemsRepository itemsRepo;
    @Inject private ItemManager itemManager;

    @Getter
    @Setter
    private int enabledUIOpen = -1;

    public void startUp()
    {
        eventBus.register(this);
        eventBus.register(restrictions);
    }

    public void shutDown()
    {
        eventBus.unregister(this);
        eventBus.unregister(restrictions);
        enabledUIOpen = -1;
    }

    private EnabledUI currentEnabledUi()
    {
        return enabledUIOpen == -1 ? null : EnabledUI.fromGroupId(enabledUIOpen);
    }

    private boolean inactive()
    {
        return client.getGameState().getState() < GameState.LOADING.getState();
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (event.getGroupId() == enabledUIOpen)
        {
            enabledUIOpen = -1;
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (ENABLED_UI_GROUPS.contains(event.getGroupId()))
        {
            enabledUIOpen = event.getGroupId();
        }
    }

    /**
     * Normalize a MenuEntryAdded into a canonical item id, or -1 when the row is not item-based.
     */
    private int getItemId(MenuEntryAdded event, MenuEntry entry)
    {
        MenuAction type = entry.getType();
        boolean hasItemId = entry.getItemId() > 0 || event.getItemId() > 0;
        if (!GROUND_ACTIONS.contains(type) && !hasItemId)
        {
            return -1;
        }

        int raw = GROUND_ACTIONS.contains(type)
                ? event.getIdentifier()
                : Math.max(event.getItemId(), entry.getItemId());

        int mapped = EnsouledHeadMapping.toTradeableId(raw);
        return itemManager.canonicalize(mapped);
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (inactive())
        {
            return;
        }

        EnabledUI ui = currentEnabledUi();
        if (ui != null && !ui.isGreyLockedItems())
        {
            return;
        }

        MenuEntry entry = event.getMenuEntry();
        MenuAction action = entry.getType();
        int id = getItemId(event, entry);

        boolean allow = isGroundItem(entry)
                ? !isLockedGroundItem(id)
                : isEnabled(id, entry, action);

        if (!allow)
        {
            String option = Text.removeTags(entry.getOption());
            String target = Text.removeTags(entry.getTarget());

            entry.setOption("<col=808080>" + option);
            entry.setTarget("<col=808080>" + target);
            entry.onClick(DISABLED);

            if (config.deprioritizeLockedOptions())
            {
                entry.setDeprioritized(true);
            }
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (event.getMenuEntry().onClick() == DISABLED)
        {
            event.consume();
            return;
        }

        handleGroundItems(itemManager, unlocks, itemsRepo, event, plugin);
    }

    private static boolean isGroundItem(MenuEntry entry)
    {
        return GROUND_ACTIONS.contains(entry.getType());
    }

    /**
     * Ground-item safeguard used by the click handler.
     */
    public static void handleGroundItems(
            ItemManager itemManager,
            ChoiceManUnlocks unlocks,
            ItemsRepository itemsRepo,
            MenuOptionClicked event,
            ChoiceManPlugin plugin)
    {
        MenuAction action = event.getMenuAction();
        if (action != null && GROUND_ACTIONS.contains(action))
        {
            int rawItemId = event.getId() != -1
                    ? event.getId()
                    : event.getMenuEntry().getItemId();

            int mapped = EnsouledHeadMapping.toTradeableId(rawItemId);
            int canonicalGroundId = itemManager.canonicalize(mapped);
            String base = itemsRepo.getBaseForId(canonicalGroundId);

            if (base != null && !unlocks.isBaseUsable(base))
            {
                event.consume();
            }
        }
    }

    private boolean isLockedGroundItem(int canonicalId)
    {
        if (canonicalId <= 0)
        {
            return false;
        }

        String base = itemsRepo.getBaseForId(canonicalId);
        return base != null && !unlocks.isBaseUsable(base);
    }

    private boolean isHealthOrbCure(MenuEntry entry)
    {
        if (entry.getType() != MenuAction.CC_OP)
        {
            return false;
        }

        if (!"cure".equalsIgnoreCase(Text.removeTags(entry.getOption())))
        {
            return false;
        }

        int w1 = entry.getParam1();
        int w0 = entry.getParam0();
        return (w1 >>> 16) == ORBS_GROUP || (w0 >>> 16) == ORBS_GROUP;
    }

    private boolean isFurnaceSmelt(MenuEntry entry)
    {
        if (!GAME_OBJECT_ACTIONS.contains(entry.getType()))
        {
            return false;
        }

        String option = Text.removeTags(entry.getOption());
        String target = Text.removeTags(entry.getTarget());

        return "smelt".equalsIgnoreCase(option)
                && target != null
                && target.toLowerCase().contains("furnace");
    }

    /**
     * Core gating for non-ground rows.
     */
    private boolean isEnabled(int id, MenuEntry entry, MenuAction action)
    {
        if (isHealthOrbCure(entry) || isFurnaceSmelt(entry))
        {
            return true;
        }

        String option = Text.removeTags(entry.getOption());
        String target = Text.removeTags(entry.getTarget());

        EnabledUI ui = currentEnabledUi();
        if (ui != null && ui.isAllowAllActions())
        {
            return true;
        }

        if (GAME_OBJECT_ACTIONS.contains(action)
                && ALWAYS_ALLOW_OBJECT_IDS.contains(entry.getIdentifier()))
        {
            return true;
        }

        if (option.equalsIgnoreCase("drop") || option.equalsIgnoreCase("check"))
        {
            return true;
        }

        if (option.equalsIgnoreCase("clean") || option.equalsIgnoreCase("rub"))
        {
            if (!plugin.isInPlay(id))
            {
                return true;
            }

            return isItemUsable(id);
        }

        // Barehand barbarian fishing / Tempoross spirit pool exception.
        if ("harpoon".equalsIgnoreCase(option) && !hasAnyHarpoonInInvOrWorn())
        {
            String normalizedTarget = target.toLowerCase();
            if (normalizedTarget.contains("fishing spot") || normalizedTarget.contains("spirit pool"))
            {
                return true;
            }
        }

        if (SkillOp.isSkillOp(option))
        {
            return restrictions.isSkillOpEnabled(option);
        }

        if (Spell.isSpell(option))
        {
            return restrictions.isSpellOpEnabled(option);
        }

        if (Spell.isSpell(target))
        {
            return restrictions.isSpellOpEnabled(target);
        }

        boolean enabled = !DISABLED_ACTIONS.contains(action);
        if (enabled)
        {
            return true;
        }

        if (id == 0 || id == -1 || !plugin.isInPlay(id))
        {
            return true;
        }

        return isItemUsable(id);
    }

    private boolean isItemUsable(int itemId)
    {
        String base = itemsRepo.getBaseForId(itemId);
        return base != null && unlocks.isBaseUsable(base);
    }

    private boolean hasAnyHarpoonInInvOrWorn()
    {
        ItemContainer worn = client.getItemContainer(InventoryID.WORN);
        ItemContainer inv = client.getItemContainer(InventoryID.INV);

        if (worn != null)
        {
            for (Item item : worn.getItems())
            {
                SkillItem skillItem = SkillItem.fromId(item.getId());
                if (skillItem != null && skillItem.getSkillOp() == SkillOp.HARPOON)
                {
                    return true;
                }
            }
        }

        if (inv != null)
        {
            for (Item item : inv.getItems())
            {
                SkillItem skillItem = SkillItem.fromId(item.getId());
                if (skillItem != null && skillItem.getSkillOp() == SkillOp.HARPOON)
                {
                    return true;
                }
            }
        }

        return false;
    }
}
