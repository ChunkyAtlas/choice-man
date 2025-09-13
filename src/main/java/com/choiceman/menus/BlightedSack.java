package com.choiceman.menus;

import lombok.Getter;
import net.runelite.api.gameval.ItemID;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps blighted sack items to the spells they enable and provides
 * a fast lookup from spell name → corresponding sack.
 */
@Getter
public enum BlightedSack {
    ENTANGLE(
            ItemID.BLIGHTED_SACK_ENTANGLE,
            Set.of("Snare", "Entangle", "Bind")
    ),
    SURGE(
            ItemID.BLIGHTED_SACK_SURGE,
            Set.of(
                    "Wind Surge", "Water Surge", "Earth Surge", "Fire Surge",
                    "Wind Wave", "Water Wave", "Earth Wave", "Fire Wave"
            )
    ),
    TELEBLOCK(
            ItemID.BLIGHTED_SACK_TELEBLOCK,
            Set.of("Tele Block", "Teleport to Target")
    ),
    VENGEANCE(
            ItemID.BLIGHTED_SACK_VENGEANCE,
            Set.of("Vengeance", "Vengeance Other")
    ),
    ANCIENT_ICE(
            ItemID.BLIGHTED_SACK_ICEBARRAGE,
            Set.of("Ice Rush", "Ice Burst", "Ice Blitz", "Ice Barrage")
    );

    private static final Map<String, BlightedSack> SPELL_TO_SACK;

    static {
        // Build an immutable lookup for O(1) spell → sack resolution.
        Map<String, BlightedSack> map = new HashMap<>();
        for (BlightedSack sack : values()) {
            for (String spell : sack.allowedSpells) {
                map.put(spell, sack);
            }
        }
        SPELL_TO_SACK = Map.copyOf(map);
    }

    private final int sackItemId;
    private final Set<String> allowedSpells;

    BlightedSack(int sackItemId, Set<String> allowedSpells) {
        this.sackItemId = sackItemId;
        this.allowedSpells = allowedSpells;
    }

    /**
     * Returns the sack that grants the given spell, or {@code null} if none.
     */
    public static BlightedSack fromSpell(String spellName) {
        return SPELL_TO_SACK.get(spellName);
    }
}
