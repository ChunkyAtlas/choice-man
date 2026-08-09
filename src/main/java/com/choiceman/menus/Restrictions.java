package com.choiceman.menus;

import com.choiceman.ChoiceManPlugin;
import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Computes which skill operations and spells are currently usable under Choice Man rules.
 */
@Singleton
public class Restrictions
{
    public static final int SPELL_REQUIREMENT_OVERLAY_NORMAL = InterfaceID.MagicSpellbook.TOOLTIP;
    public static final int AUTOCAST_REQUIREMENT_OVERLAY_NORMAL = InterfaceID.Autocast.INFO;

    private static final int[] RUNE_POUCH_TYPE_VARBITS = {
            29,
            1622,
            1623,
            14285,
            15373,
            15374
    };

    private static final int[] RUNE_POUCH_AMOUNT_VARBITS = {
            1624,
            1625,
            1626,
            14286,
            15375,
            15376
    };

    private static final WorldArea FOUNTAIN_OF_RUNE_AREA =
            new WorldArea(3367, 3890, 13, 9, 0);

    private final Set<SkillOp> enabledSkillOps = EnumSet.noneOf(SkillOp.class);
    private final Set<Integer> availableRunes = new HashSet<>();

    @Inject private ChoiceManPlugin plugin;
    @Inject private Client client;
    @Inject private ChoiceManUnlocks unlocks;
    @Inject private ItemsRepository itemsRepo;

    private boolean isInFountainArea()
    {
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null)
        {
            return false;
        }

        WorldPoint worldPoint = localPlayer.getWorldLocation();
        return worldPoint != null && FOUNTAIN_OF_RUNE_AREA.contains(worldPoint);
    }

    private boolean isInLMS()
    {
        EnumSet<WorldType> worldTypes = client.getWorldType();
        return worldTypes != null && worldTypes.contains(WorldType.LAST_MAN_STANDING);
    }

    /**
     * Resolve the active Choice Man base for an item ID.
     * Returns null when that item is not tracked by the current item repository.
     */
    private String trackedBaseFor(int rawItemId)
    {
        if (rawItemId <= 0)
        {
            return null;
        }

        try
        {
            int canonical = plugin.getItemManager().canonicalize(rawItemId);
            return itemsRepo.getBaseForId(canonical);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /**
     * Non-tracked items are unrestricted. Tracked items require their base unlock.
     */
    private boolean isItemAllowed(int rawItemId)
    {
        String base = trackedBaseFor(rawItemId);
        return base == null || unlocks.isBaseUsable(base);
    }

    private boolean isRuneProviderAllowed(int providerId)
    {
        String directBase = trackedBaseFor(providerId);
        if (directBase != null)
        {
            return unlocks.isBaseUsable(directBase);
        }

        RuneProvider provider = RuneProvider.fromId(providerId);
        if (provider == null)
        {
            return false;
        }

        return isItemAllowed(provider.getUnlockItemId());
    }

    private void addProvidedRunes(int providerId)
    {
        ItemManager itemManager = plugin.getItemManager();

        for (int runeId : RuneProvider.getProvidedRunes(providerId))
        {
            try
            {
                availableRunes.add(itemManager.canonicalize(runeId));
            }
            catch (Exception ignored)
            {
                availableRunes.add(runeId);
            }
        }
    }

    private void addProviderRunesIfAllowed(int providerId)
    {
        if (!isRuneProviderAllowed(providerId))
        {
            return;
        }

        addProvidedRunes(providerId);
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        enabledSkillOps.clear();
        availableRunes.clear();

        ItemContainer equippedItems = client.getItemContainer(InventoryID.WORN);
        ItemContainer inventoryItems = client.getItemContainer(InventoryID.INV);

        if (equippedItems != null)
        {
            for (Item item : equippedItems.getItems())
            {
                if (item == null)
                {
                    continue;
                }

                int id = item.getId();
                SkillItem skillItem = SkillItem.fromId(id);

                if (skillItem != null
                        && (!skillItem.isRequiresUnlock() || isItemAllowed(id)))
                {
                    enabledSkillOps.add(skillItem.getSkillOp());
                }

                if (RuneProvider.isEquippedProvider(id))
                {
                    addProviderRunesIfAllowed(id);
                }

                if (RuneProvider.isInvProvider(id))
                {
                    addProviderRunesIfAllowed(id);
                }
            }
        }

        if (inventoryItems != null)
        {
            for (Item item : inventoryItems.getItems())
            {
                if (item == null)
                {
                    continue;
                }

                int id = item.getId();
                SkillItem skillItem = SkillItem.fromId(id);

                if (skillItem != null
                        && (!skillItem.isRequiresUnlock() || isItemAllowed(id)))
                {
                    enabledSkillOps.add(skillItem.getSkillOp());
                }

                if (RuneProvider.isInvProvider(id))
                {
                    addProviderRunesIfAllowed(id);
                }
            }
        }

        EnumComposition pouchEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        if (pouchEnum != null)
        {
            for (int i = 0; i < RUNE_POUCH_AMOUNT_VARBITS.length; i++)
            {
                int quantity = client.getVarbitValue(RUNE_POUCH_AMOUNT_VARBITS[i]);
                if (quantity <= 0)
                {
                    continue;
                }

                int typeIndex = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[i]);
                int runeId = pouchEnum.getIntValue(typeIndex);

                if (RuneProvider.isInvProvider(runeId))
                {
                    addProviderRunesIfAllowed(runeId);
                }
            }
        }
    }

    public boolean isSkillOpEnabled(String option)
    {
        SkillOp op = SkillOp.fromString(option);
        return op != null && enabledSkillOps.contains(op);
    }

    public boolean isSpellOpEnabled(String spellName)
    {
        if (isInFountainArea() || isInLMS())
        {
            return true;
        }

        BlightedSack sack = BlightedSack.fromSpell(spellName);
        if (sack != null)
        {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv != null)
            {
                int sackId = sack.getSackItemId();

                for (Item item : inv.getItems())
                {
                    if (item == null || item.getId() != sackId)
                    {
                        continue;
                    }

                    if (sack == BlightedSack.SURGE || isItemAllowed(sackId))
                    {
                        return true;
                    }
                }
            }
        }

        Widget autocastOverlay = client.getWidget(AUTOCAST_REQUIREMENT_OVERLAY_NORMAL);
        if (autocastOverlay != null)
        {
            return processChildren(autocastOverlay);
        }

        Widget spellOverlay = client.getWidget(SPELL_REQUIREMENT_OVERLAY_NORMAL);
        if (spellOverlay != null)
        {
            return processChildren(spellOverlay);
        }

        return false;
    }

    public boolean processChildren(Widget widget)
    {
        Widget[] children = widget.getDynamicChildren();
        if (children == null)
        {
            return true;
        }

        ItemManager itemManager = plugin.getItemManager();

        for (Widget child : children)
        {
            if (child == null)
            {
                continue;
            }

            int rawId = child.getItemId();
            if (rawId == -1)
            {
                continue;
            }

            int id;
            try
            {
                id = itemManager.canonicalize(rawId);
            }
            catch (Exception ignored)
            {
                id = rawId;
            }

            String base = itemsRepo.getBaseForId(id);
            if (base != null && !availableRunes.contains(id))
            {
                return false;
            }
        }

        return true;
    }
}
