# Choice Man

## Overview

**Choice Man** is a RuneLite plugin that turns item progression into a level-based unlock system. Each time your total level increases, Choice Man presents a random set of locked item bases and lets you choose one to unlock.

Choice Man tracks two separate states for item bases — **Unlocked** and **Obtained**. Unlocking controls whether a tracked item can be used, while obtaining records whether you have actually had that item base appear in your inventory.

Progress is saved locally and restored automatically when the plugin starts.

---

## Core Concepts

### Item Bases

Choice Man tracks logical item bases instead of treating every item ID as a separate unlock.

For example, multiple item IDs or variants may map to the same base item. Unlocking the base unlocks the tracked variants associated with it.

Tracked item bases are loaded from the plugin’s bundled `items.json` resource by default.

Choice Man can also use a custom item list from:

```text
~/.runelite/choiceman/custom_items.json
```

When a valid custom list is enabled, it is saved to RuneLite config so it can sync across RuneLite profiles and machines.

### Unlocked Items

Items you have selected through the Choice Man unlock system.

Unlocked tracked items can be used normally.

### Obtained Items

Items you have actually had in your inventory.

Obtained status is tracked separately from unlocked status and is used for progress tracking and Grand Exchange restrictions.

### Locked Items

Tracked items that have not been unlocked yet.

Locked tracked items are restricted from most normal interactions until their base item is unlocked.

Untracked items are ignored by Choice Man.

---

## Features

### Level-Based Unlock Choices

- Each increase in total level queues one unlock choice.
- Choice Man detects total-level increases from real skill levels.
- If multiple levels are gained, multiple choices are queued.
- The plugin avoids awarding duplicate choices on login by establishing a baseline first.
- Choices are selected from item bases that are still locked.
- Once no locked item bases remain, pending choices are cleared.

---

### Scaling Choice Count

The number of item cards shown per pick scales with total level:

- **Below 200 total level** – 2 choices
- **200+ total level** – 3 choices
- **500+ total level** – 4 choices
- **1000+ total level** – 5 choices

Choice Man also gives chat hints when you are close to the next choice-count threshold.

---

### Milestone Choices

Crossing major total-level thresholds queues a special milestone presentation.

Milestone choices occur when crossing:

- **200 total level**
- **500 total level**
- **1000 total level**

Milestone presentations use the gold Choice Man overlay background.

---

### Choice Overlay

Choice Man displays an interactive overlay when an unlock choice is available.

The overlay supports:

- Sequential card reveal animation
- Configurable reveal duration
- Configurable accent color
- Configurable sound-effect volume
- Normal and gold background styles
- Pending-choice count
- Minimize and restore behavior

When you pick a card, that item base is unlocked, saved to disk, and the next queued choice begins if any remain.

---

### Combat Minimize Behavior

If you gain a level while in combat, Choice Man can automatically minimize the choice overlay.

The overlay restores after combat ends, allowing you to make the queued choice once it is safer to interact with the UI.

---

### Custom Item Lists

Choice Man supports custom item lists through a local `custom_items.json` file.

On startup, Choice Man creates an example file if one does not already exist:

```text
~/.runelite/choiceman/custom_items.json
```

It also creates a README file with formatting instructions:

```text
~/.runelite/choiceman/custom_items_README.txt
```

To use a custom item list:

1. Edit `custom_items.json`.
2. Open RuneLite’s plugin settings.
3. Select **Choice Man**.
4. Enable **Use custom item list**.
5. If the setting was already enabled, toggle it off and back on after editing the file.

When **Use custom item list** is enabled, Choice Man validates the local file. If the file is valid, the plugin loads it immediately and saves the JSON to RuneLite config so it can sync across profiles and machines.

If the custom file is invalid, missing, or still unchanged from the example template, Choice Man falls back to the bundled item list.

Custom item list format:

```json
[
  {
    "name": "Abyssal whip",
    "ids": [4151]
  },
  {
    "name": "Dragon scimitar",
    "ids": [4587]
  },
  {
    "name": "Example item with variants",
    "ids": [11802, 11804, 11806, 11808]
  }
]
```

Rules:

- The file must be valid JSON.
- The root value must be an array.
- Each item needs a non-empty `name`.
- Each item needs `ids` as a non-empty array of positive OSRS item IDs.
- Use multiple IDs for variants if you want them treated as the same base item.
- The preferred field is `ids`, but `id` and `itemid` are also accepted for compatibility.

---

## Item Restrictions

Choice Man blocks most interactions with tracked item bases that are still locked.

Locked tracked items may be blocked from actions such as:

- Equipping
- Using
- Eating
- Picking up from the ground
- Using an item on NPCs, players, objects, widgets, or ground items
- Other item-based menu interactions

Choice Man allows safe actions such as:

- Examine
- Drop
- Destroy
- Release

While bank or deposit-box interfaces are open, benign banking actions such as deposit, withdraw, examine, release, and destroy are allowed.

---

## Skill and Spell Restrictions

Choice Man also gates supported skill and spell interactions.

Skill and spell menu options are checked against the items and rune providers you currently have available.

Spell checks use visible spell or autocast requirement widgets where applicable. Rune-providing items only count if their tracked base is unlocked and the provider is available in the correct place, such as equipped, inventory, or rune pouch.

Some special cases are supported, including:

- Fountain of Rune area allowing spells
- Last Man Standing worlds allowing spells
- Blighted sack handling

---

## Grand Exchange Restrictions

When Grand Exchange restrictions are enabled, Choice Man filters GE search results.

A GE result is allowed only when its tracked base item is both:

- **Unlocked**
- **Obtained**

If the item is locked, untracked, or not yet obtained, the result row is hidden or visually suppressed.

---

## Sidebar Panel

The Choice Man sidebar panel shows your unlock and obtain progress.

The panel includes:

- **Unlocked** item-base view
- **Obtained** item-base view
- Search bar
- Button to swap between Unlocked and Obtained views
- Filter for **Unlocked and Not Obtained**
- Filter for **Unlocked and Obtained**
- Representative item icons
- Progress counts against the total tracked base count

Rows display the base item name and a representative icon.

---

## Music Tab Unlock View

Choice Man integrates with the Music tab to show unlock progress.

The unlock view is managed by the plugin while features are active and is restored when the plugin disables its features.

A configuration option controls whether the unlock view stays open when switching away from the Music tab.

---

## Visual Feedback

Choice Man can dim locked tracked items across RuneLite interfaces.

Dimming applies when:

- The item is tracked in the active item list
- The item’s base is not unlocked

Dimming behavior includes:

- Configurable enable/disable toggle
- Configurable opacity from 0 to 255
- Frame-level enforcement so client scripts do not immediately undo the dimming

Untracked items are not dimmed.

---

## Configuration

Open RuneLite’s plugin settings and select **Choice Man** to configure:

- **Dim locked items**
- **Dim opacity (0-255)**
- **Restrict GE to unlocked & obtained**
- **Show Unlocks Always Open**
- **Deprioritize Locked Menu Options**
- **Choice reveal animation (ms)**
- **Accent color**
- **SFX volume**
- **Use custom item list**

---

## Usage

1. **Enable the Plugin**
   - Enable **Choice Man** in RuneLite.
   - The plugin loads tracked item bases from its bundled item data by default.
   - Saved unlocked and obtained progress is loaded from disk.

2. **Optional: Configure a Custom Item List**
   - Edit `~/.runelite/choiceman/custom_items.json`.
   - Enable **Use custom item list** in the Choice Man config.
   - If valid, the custom list loads immediately and is saved to RuneLite config for profile sync.
   - If invalid, Choice Man falls back to the bundled item list.

3. **Gain a Level**
   - Each total-level increase queues one unlock choice.
   - The number of cards shown depends on your current total level.

4. **Choose an Unlock**
   - Pick one card from the Choice Man overlay.
   - The selected item base is unlocked and saved automatically.

5. **Obtain Items**
   - When a tracked item appears in your inventory, its base is marked as obtained.
   - Obtained progress updates the sidebar and unlock views.

6. **Use Unlocked Items**
   - Tracked items can be used normally once their base is unlocked.
   - Locked tracked items remain restricted to safe actions.

7. **Track Progress**
   - Use the Choice Man sidebar to view unlocked and obtained item bases.
   - Use the Music tab unlock view for a larger progress display.

---

## File Locations

Choice Man stores local files under:

```text
~/.runelite/choiceman/
```

### Saved Files

- **Unlocked Items**

  ```text
  unlocked_items.json
  ```

- **Obtained Items**

  ```text
  obtained_items.json
  ```

The plugin writes both files atomically using a temporary file and then replaces the saved file.

Missing files are tolerated. Entries that are no longer present in the item repository are dropped when data is loaded.

### Custom Item Files

- **Custom Item List**

  ```text
  custom_items.json
  ```

- **Custom Item Instructions**

  ```text
  custom_items_README.txt
  ```

`custom_items.json` is the editable local file. When **Use custom item list** is enabled and the file is valid, Choice Man saves the custom JSON to RuneLite config so it can sync across profiles and machines.

---

## World Restrictions

Choice Man only enables its active features on normal worlds.

The plugin disables features on unsupported world types such as:

- Deadman
- Seasonal
- Beta worlds
- PvP Arena
- Quest Speedrunning
- Tournament worlds

---

## Contribution

Contributions, bug reports, balance feedback, and UX suggestions are welcome.

If something feels confusing, inconsistent, or incorrect, please open an issue or submit a pull request.

---

## Contact

For questions, support, or feature requests, open a GitHub issue for the project.