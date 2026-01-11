package com.choiceman.menus;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Canonical set of tool/skill menu options the plugin understands.
 * <p>
 * Lookups use the exact menu option text as displayed by the client.
 */
@Getter
public enum SkillOp
{
    CHOP_DOWN("Chop down"),
    MINE("Mine"),
    SMALL_NET("Small Net"),
    BIG_NET("Big Net"),
    CAGE("Cage"),
    BAIT("Bait"),
    LURE("Lure"),
    HARPOON("Harpoon"),
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

    private final String option;

    SkillOp(String option)
    {
        this.option = option;
    }

    private static final HashSet<String> ALL_SKILL_OPS = new HashSet<>();
    private static final HashMap<String, SkillOp> STRING_TO_OP = new HashMap<>();

    static
    {
        for (SkillOp skillOp : SkillOp.values())
        {
            ALL_SKILL_OPS.add(skillOp.option);
            STRING_TO_OP.put(skillOp.option, skillOp);
        }
    }

    public static boolean isSkillOp(String option)
    {
        return ALL_SKILL_OPS.contains(option);
    }

    public static SkillOp fromString(String option)
    {
        if (!isSkillOp(option)) return null;
        return STRING_TO_OP.get(option);
    }
}
