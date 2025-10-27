package com.choiceman.menus;

import lombok.Getter;

import java.util.Set;

/**
 * Widget group IDs for UIs where limited actions are allowed (e.g., bank/deposit box).
 * Provides a fast membership check for gating logic elsewhere.
 */
@Getter
public enum EnabledUI {
    BANK(12),
    DEPOSIT_BOX(192);

    private static final Set<Integer> IDS = Set.of(
            BANK.id,
            DEPOSIT_BOX.id
    );

    private final int id;

    EnabledUI(int id) {
        this.id = id;
    }

    /**
     * Returns an immutable set of all enabled UI group IDs.
     */
    public static Set<Integer> ids() {
        return IDS;
    }

    /**
     * @return true if the given widget group ID corresponds to an enabled UI.
     */
    public static boolean isEnabled(int groupId) {
        return IDS.contains(groupId);
    }
}
