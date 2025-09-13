package com.choiceman.menus;

import com.choiceman.ChoiceManPlugin;
import com.choiceman.data.ChoiceManUnlocks;
import com.choiceman.data.ItemsRepository;
import net.runelite.api.*;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
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
 * Computes, each tick, which skill ops and spells are currently allowed under Choice Man rules.
 * Data produced here is consumed by {@link ActionHandler} to enable/disable menu interactions.
 */
@Singleton
public class Restrictions {
    public static final int SPELL_REQUIREMENT_OVERLAY_NORMAL = 14287051;
    public static final int AUTOCAST_REQUIREMENT_OVERLAY_NORMAL = 13172738;
    private static final int[] RUNE_POUCH_TYPE_VARBITS = {29, 1622, 1623, 14285, 15373, 15374};
    private static final int[] RUNE_POUCH_AMOUNT_VARBITS = {1624, 1625, 1626, 14286, 15375, 15376};
    private static final WorldArea FOUNTAIN_OF_RUNE_AREA = new WorldArea(3367, 3890, 13, 9, 0);
    private final Set<SkillOp> enabledSkillOps = EnumSet.noneOf(SkillOp.class);
    /**
     * Canonical rune IDs currently available from unlocked providers (inventory/equipped/pouch).
     */
    private final Set<Integer> availableRunes = new HashSet<>();
    @Inject
    private ChoiceManPlugin plugin;
    @Inject
    private Client client;
    @Inject
    private ChoiceManUnlocks unlocks;
    @Inject
    private ItemsRepository itemsRepo;

    private boolean isInFountainArea() {
        Player lp = client.getLocalPlayer();
        if (lp == null) return false;
        WorldPoint wp = lp.getWorldLocation();
        return wp != null && FOUNTAIN_OF_RUNE_AREA.contains(wp);
    }

    private boolean isInLMS() {
        EnumSet<WorldType> worldTypes = client.getWorldType();
        return worldTypes != null && worldTypes.contains(WorldType.LAST_MAN_STANDING);
    }

    /**
     * @return true if the item is tracked by Choice Man and its base is unlocked.
     */
    private boolean isUnlockedAndInPlay(int rawId) {
        int id = plugin.getItemManager().canonicalize(rawId);
        if (!plugin.isInPlay(id)) return false;
        String base = itemsRepo.getBaseForId(id);
        return base != null && unlocks.isBaseUnlocked(base);
    }

    /**
     * Adds provided runes for a provider iff the provider is unlocked and (if required) equipped.
     * Only runes that are themselves unlocked are counted.
     */
    private void addProviderRunesIfUnlocked(int providerId, boolean providerMustBeEquipped, boolean actuallyEquipped) {
        if (!isUnlockedAndInPlay(providerId)) return;
        if (providerMustBeEquipped && !actuallyEquipped) return;

        Set<Integer> provided = RuneProvider.getProvidedRunes(providerId);
        if (provided == null || provided.isEmpty()) return;

        ItemManager im = plugin.getItemManager();
        for (int runeId : provided) {
            if (isUnlockedAndInPlay(runeId)) {
                availableRunes.add(im.canonicalize(runeId));
            }
        }
    }

    /**
     * Recomputes enabled tools and available runes. Runs on the client thread.
     */
    @Subscribe
    public void onGameTick(GameTick event) {
        enabledSkillOps.clear();
        availableRunes.clear();

        ItemContainer equipped = client.getItemContainer(InventoryID.WORN);
        ItemContainer inv = client.getItemContainer(InventoryID.INV);

        // Equipped items
        if (equipped != null) {
            for (Item item : equipped.getItems()) {
                if (item == null) continue;
                int id = item.getId();

                SkillItem s = SkillItem.fromId(id);
                if (s != null && (!s.isRequiresUnlock() || isUnlockedAndInPlay(id))) {
                    enabledSkillOps.add(s.getOption());
                }

                if (RuneProvider.isEquippedProvider(id)) addProviderRunesIfUnlocked(id, true, true);
                if (RuneProvider.isInvProvider(id)) addProviderRunesIfUnlocked(id, false, true);
            }
        }

        // Inventory items
        if (inv != null) {
            for (Item item : inv.getItems()) {
                if (item == null) continue;
                int id = item.getId();

                SkillItem s = SkillItem.fromId(id);
                if (s != null && (!s.isRequiresUnlock() || isUnlockedAndInPlay(id))) {
                    enabledSkillOps.add(s.getOption());
                }

                if (RuneProvider.isInvProvider(id)) addProviderRunesIfUnlocked(id, false, false);
            }
        }

        // Rune pouch slots behave like inventory providers
        EnumComposition pouchEnum = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        if (pouchEnum != null) {
            ItemManager im = plugin.getItemManager();
            for (int i = 0; i < RUNE_POUCH_AMOUNT_VARBITS.length; i++) {
                int qty = client.getVarbitValue(RUNE_POUCH_AMOUNT_VARBITS[i]);
                if (qty <= 0) continue;

                int typeIdx = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[i]);
                int runeId = pouchEnum.getIntValue(typeIdx);

                if (RuneProvider.isInvProvider(runeId) && isUnlockedAndInPlay(runeId)) {
                    availableRunes.add(im.canonicalize(runeId));
                }
            }
        }
    }

    /**
     * @return true if the named skill option is currently enabled.
     */
    public boolean isSkillOpEnabled(String option) {
        SkillOp op = SkillOp.fromString(option);
        return op != null && enabledSkillOps.contains(op);
    }

    /**
     * Determines whether a spell can be cast now under Choice Man rules.
     * In Fountain of Rune or LMS, all spells are allowed.
     * Blighted sack behavior:
     * • SURGE sack: allowed if present.
     * • Others: require the sack base to be unlocked and present.
     * Otherwise, requirements are taken from the spell/auto-cast overlays and validated against {@link #availableRunes}.
     */
    public boolean isSpellOpEnabled(String spellName) {
        if (isInFountainArea() || isInLMS()) return true;

        BlightedSack sack = BlightedSack.fromSpell(spellName);
        if (sack != null) {
            ItemContainer inv = client.getItemContainer(InventoryID.INV);
            if (inv != null) {
                boolean hasSack = false;
                int sackId = sack.getSackItemId();
                for (Item i : inv.getItems()) {
                    if (i != null && i.getId() == sackId) {
                        hasSack = true;
                        break;
                    }
                }
                if (hasSack) {
                    if (sack == BlightedSack.SURGE) return true;
                    if (isUnlockedAndInPlay(sack.getSackItemId())) return true;
                }
            }
        }

        Widget autocast = client.getWidget(AUTOCAST_REQUIREMENT_OVERLAY_NORMAL);
        if (autocast != null) return processChildren(autocast);

        Widget spell = client.getWidget(SPELL_REQUIREMENT_OVERLAY_NORMAL);
        if (spell != null) return processChildren(spell);

        return false;
    }

    /**
     * Validates that every rune listed by the given requirement overlay is tracked, unlocked, and currently provided.
     */
    public boolean processChildren(Widget widget) {
        Widget[] children = widget.getDynamicChildren();
        if (children == null) return true;

        ItemManager im = plugin.getItemManager();

        for (Widget child : children) {
            int rawId = child.getItemId();
            if (rawId == -1) continue;

            int id = im.canonicalize(rawId);

            if (!plugin.isInPlay(id)) return false;

            String base = itemsRepo.getBaseForId(id);
            if (base == null || !unlocks.isBaseUnlocked(base)) return false;

            if (!availableRunes.contains(id)) return false;
        }
        return true;
    }
}
