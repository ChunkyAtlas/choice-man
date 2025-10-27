package com.choiceman.menus;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Canonical set of tool/skill menu options the plugin understands.
 * <p>
 * Lookups use the exact menu option text as displayed by the client.
 */
@Getter
public enum SkillOp {
    CHOP_DOWN("Chop down"),
    MINE("Mine"),
    NET("Net"),
    CAGE("Cage"),
    BAIT("Bait"),
    LURE("Lure"),
    RAKE("Rake"),
    PRUNE("Prune"),
    CURE("Cure"),
    GRIND("Grind"),
    SMITH("Smith"),
    SMELT("Smelt"),
    SHEAR("Shear"),
    CLEAN("Clean"),
    FIRE("Fire"),
    CRAFT_RUNE("Craft-rune");

    /**
     * Unmodifiable map from menu option text to {@link SkillOp}.
     * Built once at class load for O(1) lookups.
     */
    private static final Map<String, SkillOp> STRING_TO_OP;

    static {
        Map<String, SkillOp> m = new HashMap<>(values().length);
        for (SkillOp op : values()) {
            m.put(op.option, op);
        }
        STRING_TO_OP = Collections.unmodifiableMap(m); // defensive: read-only view. :contentReference[oaicite:1]{index=1}
    }

    /**
     * Exact menu option text as shown in the client.
     */
    private final String option;

    SkillOp(String option) {
        this.option = option;
    }

    /**
     * @return true if {@code option} matches a known skill operation.
     */
    public static boolean isSkillOp(String option) {
        return option != null && STRING_TO_OP.containsKey(option);
    }

    /**
     * @return the {@link SkillOp} for {@code option}, or {@code null} if unknown.
     */
    public static SkillOp fromString(String option) {
        return option == null ? null : STRING_TO_OP.get(option);
    }
}
