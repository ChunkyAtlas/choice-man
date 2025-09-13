package com.choiceman.menus;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
public enum RuneProvider {
    // Banana (yeah)
    BANANA(1963),

    // Runes
    AIR_RUNE(556),
    WATER_RUNE(555),
    EARTH_RUNE(557),
    FIRE_RUNE(554),
    MIND_RUNE(558),
    BODY_RUNE(559),
    COSMIC_RUNE(564),
    CHAOS_RUNE(562),
    NATURE_RUNE(561),
    LAW_RUNE(563),
    DEATH_RUNE(560),
    BLOOD_RUNE(565),
    SOUL_RUNE(566),
    WRATH_RUNE(21880),
    SUNFIRE_RUNE(false, 28929, FIRE_RUNE),

    // Elemental equipment
    AIR_STAFF(true, 1381, AIR_RUNE),
    MYSTIC_AIR_STAFF(true, 1405, AIR_RUNE),
    WATER_STAFF(true, 1383, WATER_RUNE),
    MYSTIC_WATER_STAFF(true, 1403, WATER_RUNE),
    EARTH_STAFF(true, 1385, EARTH_RUNE),
    MYSTIC_EARTH_STAFF(true, 1407, EARTH_RUNE),
    FIRE_STAFF(true, 1387, FIRE_RUNE),
    MYSTIC_FIRE_STAFF(true, 1401, FIRE_RUNE),
    AIR_BATTLESTAFF(true, 1397, AIR_RUNE),
    WATER_BATTLESTAFF(true, 1395, WATER_RUNE),
    KODAI_WAND(true, 21006, WATER_RUNE),
    EARTH_BATTLESTAFF(true, 1399, EARTH_RUNE),
    FIRE_BATTLESTAFF(true, 1393, FIRE_RUNE),
    TOME_OF_FIRE(true, 20714, FIRE_RUNE),
    TOME_OF_WATER(true, 25574, WATER_RUNE),
    TOME_OF_EARTH(true, 30064, EARTH_RUNE),

    // Combo runes
    AETHER_RUNE(false, 30844, COSMIC_RUNE, SOUL_RUNE),
    MIST_RUNE(false, 4695, AIR_RUNE, WATER_RUNE),
    DUST_RUNE(false, 4696, AIR_RUNE, EARTH_RUNE),
    MUD_RUNE(false, 4698, WATER_RUNE, EARTH_RUNE),
    SMOKE_RUNE(false, 4697, FIRE_RUNE, AIR_RUNE),
    STEAM_RUNE(false, 4694, WATER_RUNE, FIRE_RUNE),
    LAVA_RUNE(false, 4699, EARTH_RUNE, FIRE_RUNE),

    // Combo staves
    MIST_STAFF(true, 20730, AIR_RUNE, WATER_RUNE),
    MYSTIC_MIST_STAFF(true, 20733, AIR_RUNE, WATER_RUNE),
    DUST_STAFF(true, 20736, AIR_RUNE, EARTH_RUNE),
    MYSTIC_DUST_STAFF(true, 20739, AIR_RUNE, EARTH_RUNE),
    MUD_STAFF(true, 6562, WATER_RUNE, EARTH_RUNE),
    MYSTIC_MUD_STAFF(true, 6563, WATER_RUNE, EARTH_RUNE),
    SMOKE_STAFF(true, 11998, FIRE_RUNE, AIR_RUNE),
    MYSTIC_SMOKE_STAFF(true, 12000, FIRE_RUNE, AIR_RUNE),
    STEAM_STAFF(true, 11787, WATER_RUNE, FIRE_RUNE),
    STEAM_STAFF_OR(true, 12795, WATER_RUNE, FIRE_RUNE),
    MYSTIC_STEAM_STAFF(true, 11789, WATER_RUNE, FIRE_RUNE),
    MYSTIC_STEAM_STAFF_OR(true, 12796, WATER_RUNE, FIRE_RUNE),
    LAVA_STAFF(true, 3053, EARTH_RUNE, FIRE_RUNE),
    LAVA_STAFF_OR(true, 21198, EARTH_RUNE, FIRE_RUNE),
    MYSTIC_LAVA_STAFF(true, 3054, EARTH_RUNE, FIRE_RUNE),
    MYSTIC_LAVA_STAFF_OR(true, 21200, EARTH_RUNE, FIRE_RUNE),
    TWINFLAME_STAFF(true, 30634, WATER_RUNE, FIRE_RUNE),

    // Other
    BRYOPHYTAS_STAFF_CHARGED(true, 22370, NATURE_RUNE);

    /** Whether this provider only works while equipped. */
    private final boolean requiresEquipped;

    /** The item ID of this provider or rune. */
    private final int id;

    /** Canonical rune IDs this provider supplies when eligible. Internal, mutable during enum construction only. */
    private final Set<Integer> provides = new HashSet<>();

    RuneProvider(int id) {
        this.requiresEquipped = false;
        this.id = id;
        this.provides.add(id);
    }

    RuneProvider(boolean requiresEquipped, int id, RuneProvider... provides) {
        this.requiresEquipped = requiresEquipped;
        this.id = id;
        for (RuneProvider rp : provides) {
            this.provides.addAll(rp.getProvides());
        }
    }

    private static final Set<Integer> EQUIPPED_PROVIDERS;
    private static final Set<Integer> INV_PROVIDERS;
    private static final Map<Integer, Set<Integer>> PROVIDER_TO_PROVIDED;

    static {
        Map<Integer, Set<Integer>> p2p = new HashMap<>();
        Set<Integer> equipped = new HashSet<>();
        Set<Integer> inv = new HashSet<>();

        for (RuneProvider rp : RuneProvider.values()) {
            // Store an unmodifiable view so callers cannot mutate our internal state
            p2p.put(rp.getId(), Collections.unmodifiableSet(new HashSet<>(rp.getProvides())));
            if (rp.isRequiresEquipped()) {
                equipped.add(rp.getId());
            } else {
                inv.add(rp.getId());
            }
        }

        PROVIDER_TO_PROVIDED = Collections.unmodifiableMap(p2p);
        EQUIPPED_PROVIDERS = Collections.unmodifiableSet(equipped);
        INV_PROVIDERS = Collections.unmodifiableSet(inv);
    }

    /** @return true if the item id is a provider that must be equipped. */
    public static boolean isEquippedProvider(int id) {
        return EQUIPPED_PROVIDERS.contains(id);
    }

    /** @return true if the item id is a valid inventory-based provider. */
    public static boolean isInvProvider(int id) {
        return INV_PROVIDERS.contains(id);
    }

    /**
     * Returns the canonical rune ids provided by the given provider item id.
     * The returned set is unmodifiable; if the id is unknown, an empty set is returned.
     */
    public static Set<Integer> getProvidedRunes(int id) {
        Set<Integer> s = PROVIDER_TO_PROVIDED.get(id);
        return s != null ? s : Collections.emptySet();
    }
}
