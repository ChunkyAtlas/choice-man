package com.choiceman;

import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

import java.awt.Color;

/**
 * Choice Man user settings.
 * Only contains live features (no test toggles).
 */
@ConfigGroup("choiceman")
public interface ChoiceManConfig extends Config
{
    // ---------- Item dimmer ----------
    @ConfigItem(
            keyName = "dimLocked",
            name = "Dim locked items",
            description = "Dim items you haven't unlocked yet.",
            position = 1
    )
    default boolean dimLocked() { return true; }

    @ConfigItem(
            keyName = "dimOpacity",
            name = "Dim opacity (0-255)",
            description = "How strong the dimming should be (higher = darker).",
            position = 2
    )
    default int dimOpacity() { return 120; }

    // ---------- GE restrictions ----------
    @ConfigItem(
            keyName = "geRestrictions",
            name = "Restrict GE to unlocked & obtained",
            description = "Hide GE entries for items you haven't unlocked and obtained at least once.",
            position = 10
    )
    default boolean geRestrictions() { return true; }

    // ---------- Overlay visuals ----------
    @ConfigItem(
            keyName = "choiceRevealMs",
            name = "Choice reveal animation (ms)",
            description = "Per-card reveal duration for the unlock choice overlay.",
            position = 20
    )
    default int choiceRevealMs() { return 350; }

    @Alpha
    @ConfigItem(
            keyName = "accentColor",
            name = "Accent color",
            description = "Accent used for overlay borders & buttons.",
            position = 21
    )
    default Color accentColor() { return new Color(255, 204, 0, 255); }

    @ConfigItem(
            keyName = "sfxVolume",
            name = "SFX volume",
            description = "Volume for Choice Man sounds (0–100%).",
            position = 22
    )
    default int sfxVolume() { return 100; }
}
