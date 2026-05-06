package com.choiceman.data;

import com.google.gson.*;
import lombok.Getter;

import javax.inject.Singleton;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * In-memory index of item "bases" / logical items to their concrete item IDs and vice versa.
 * <p>
 * Default data is loaded from the bundled resource:
 * /com/choiceman/items.json
 * <p>
 * Custom data can also be loaded from a synced JSON string after it has been imported.
 * <p>
 * Threading:
 * - Loads replace internal maps atomically.
 * - Readers get snapshots or unmodifiable views so callers cannot mutate internal state.
 */
@Singleton
public class ItemsRepository {
    private static final String ITEMS_RESOURCE = "/com/choiceman/items.json";

    /**
     * Maps logical/base item name to all concrete OSRS item IDs that should count as that base.
     */
    private final Map<String, Set<Integer>> baseToIds = new LinkedHashMap<>();

    /**
     * Reverse lookup from concrete item ID to logical/base item name.
     */
    private final Map<Integer, String> idToBase = new HashMap<>();

    /**
     * Forgiving parser used for bundled data and generic stream loading.
     * <p>
     * Supported formats:
     * - { "name": "...", "ids": [1, 2, 3] }
     * - { "name": "...", "id": 1 }
     * - { "name": "...", "itemid": [1, 2, 3] }
     * - { "name": "...", "itemid": "1,2,3" }
     * <p>
     * This parser skips bad entries. It does not throw validation errors.
     */
    private static void parseInto(Gson gson,
                                  InputStream in,
                                  Map<String, Set<Integer>> baseToIdsOut,
                                  Map<Integer, String> idToBaseOut) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonArray arr = gson.fromJson(reader, JsonArray.class);
            if (arr == null) {
                return;
            }

            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }

                JsonObject obj = el.getAsJsonObject();

                String name = obj.has("name") ? obj.get("name").getAsString() : null;
                if (name == null || (name = name.trim()).isEmpty()) {
                    continue;
                }

                Set<Integer> ids = baseToIdsOut.computeIfAbsent(name, k -> new LinkedHashSet<>());
                boolean addedAny = false;

                // Preferred format: ids: [1, 2, 3]
                if (obj.has("ids") && obj.get("ids").isJsonArray()) {
                    for (JsonElement idEl : obj.get("ids").getAsJsonArray()) {
                        addedAny |= tryAddId(name, ids, idEl, idToBaseOut);
                    }
                }

                // Legacy/simple format: id: 123
                if (!addedAny && obj.has("id")) {
                    addedAny |= tryAddId(name, ids, obj.get("id"), idToBaseOut);
                }

                // Legacy supported format: itemid: array | number | "1,2,3"
                if (!addedAny && obj.has("itemid")) {
                    JsonElement ie = obj.get("itemid");
                    if (ie.isJsonArray()) {
                        for (JsonElement idEl : ie.getAsJsonArray()) {
                            addedAny |= tryAddId(name, ids, idEl, idToBaseOut);
                        }
                    } else if (ie.isJsonPrimitive()) {
                        JsonPrimitive p = ie.getAsJsonPrimitive();
                        if (p.isNumber()) {
                            addedAny |= tryAddId(name, ids, ie, idToBaseOut);
                        } else if (p.isString()) {
                            for (String part : p.getAsString().split(",")) {
                                try {
                                    int parsed = Integer.parseInt(part.trim());
                                    addedAny |= tryAddId(name, ids, new JsonPrimitive(parsed), idToBaseOut);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                }

                // Do not keep empty bases.
                if (ids.isEmpty()) {
                    baseToIdsOut.remove(name);
                }
            }
        } catch (Exception ignored) {
            // Let caller decide whether to swap maps.
        }
    }

    /**
     * Adds an item ID to both indexes if it is valid.
     * Duplicate IDs assigned to the same base are fine.
     * Duplicate IDs assigned to different bases keep the first mapping.
     */
    private static boolean tryAddId(String baseName,
                                    Set<Integer> ids,
                                    JsonElement idEl,
                                    Map<Integer, String> idToBaseOut) {
        try {
            int id = idEl.getAsInt();
            if (id <= 0) {
                return false;
            }

            boolean changed = ids.add(id);

            String existing = idToBaseOut.get(id);
            if (existing == null || existing.equals(baseName)) {
                idToBaseOut.put(id, baseName);
            }

            return changed;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Strictly validates player-provided custom_items.json.
     * <p>
     * This is used before saving the custom list to ConfigManager/cloud sync.
     * <p>
     * Required custom format:
     * [
     * {
     * "name": "Abyssal whip",
     * "ids": [4151]
     * }
     * ]
     * <p>
     * For convenience, "id" and "itemid" are still accepted, but "ids" is preferred.
     */
    public static ValidationResult validateCustomItemsJson(Gson gson, String json) {
        Map<String, Set<Integer>> parsedBaseToIds = new LinkedHashMap<>();
        Map<Integer, String> parsedIdToBase = new HashMap<>();
        List<String> errors = new ArrayList<>();

        if (json == null || json.trim().isEmpty()) {
            errors.add("File is empty.");
            return new ValidationResult(false, errors, parsedBaseToIds, parsedIdToBase);
        }

        JsonElement root;
        try {
            root = gson.fromJson(new StringReader(json), JsonElement.class);
        } catch (Exception e) {
            errors.add("File is not valid JSON: " + e.getMessage());
            return new ValidationResult(false, errors, parsedBaseToIds, parsedIdToBase);
        }

        if (root == null || !root.isJsonArray()) {
            errors.add("Root value must be a JSON array.");
            return new ValidationResult(false, errors, parsedBaseToIds, parsedIdToBase);
        }

        JsonArray arr = root.getAsJsonArray();
        if (arr.size() == 0) {
            errors.add("Item list is empty.");
            return new ValidationResult(false, errors, parsedBaseToIds, parsedIdToBase);
        }

        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            String prefix = "Entry " + (i + 1) + ": ";

            if (!el.isJsonObject()) {
                errors.add(prefix + "must be an object.");
                continue;
            }

            JsonObject obj = el.getAsJsonObject();

            String name = null;
            if (obj.has("name") && obj.get("name").isJsonPrimitive()) {
                name = obj.get("name").getAsString();
            }

            if (name == null || (name = name.trim()).isEmpty()) {
                errors.add(prefix + "missing non-empty name.");
                continue;
            }

            List<Integer> ids = collectIdsForValidation(obj, prefix, errors);
            if (ids.isEmpty()) {
                errors.add(prefix + "\"" + name + "\" has no valid positive item IDs.");
                continue;
            }

            Set<Integer> set = parsedBaseToIds.computeIfAbsent(name, k -> new LinkedHashSet<>());
            for (int id : ids) {
                String existingBase = parsedIdToBase.get(id);
                if (existingBase != null && !existingBase.equals(name)) {
                    errors.add(prefix + "item ID " + id + " is already assigned to \"" + existingBase + "\".");
                    continue;
                }

                set.add(id);
                parsedIdToBase.put(id, name);
            }
        }

        parsedBaseToIds.entrySet().removeIf(e -> e.getValue().isEmpty());

        if (parsedBaseToIds.isEmpty()) {
            errors.add("No valid items were found.");
        }

        return new ValidationResult(errors.isEmpty(), errors, parsedBaseToIds, parsedIdToBase);
    }

    /**
     * Collects item IDs from supported fields for validation.
     * <p>
     * Preferred:
     * - ids: [1, 2, 3]
     * <p>
     * Accepted for compatibility:
     * - id: 1
     * - itemid: [1, 2, 3]
     * - itemid: "1,2,3"
     */
    private static List<Integer> collectIdsForValidation(JsonObject obj, String prefix, List<String> errors) {
        List<Integer> ids = new ArrayList<>();

        if (obj.has("ids")) {
            JsonElement idsEl = obj.get("ids");
            if (!idsEl.isJsonArray()) {
                errors.add(prefix + "\"ids\" must be an array.");
                return ids;
            }

            for (JsonElement idEl : idsEl.getAsJsonArray()) {
                addValidatedId(idEl, ids, prefix, errors);
            }

            return ids;
        }

        if (obj.has("id")) {
            addValidatedId(obj.get("id"), ids, prefix, errors);
            return ids;
        }

        if (obj.has("itemid")) {
            JsonElement itemIdEl = obj.get("itemid");

            if (itemIdEl.isJsonArray()) {
                for (JsonElement idEl : itemIdEl.getAsJsonArray()) {
                    addValidatedId(idEl, ids, prefix, errors);
                }
            } else if (itemIdEl.isJsonPrimitive() && itemIdEl.getAsJsonPrimitive().isString()) {
                for (String part : itemIdEl.getAsString().split(",")) {
                    try {
                        int id = Integer.parseInt(part.trim());
                        if (id > 0) {
                            ids.add(id);
                        } else {
                            errors.add(prefix + "item ID must be positive: " + part.trim());
                        }
                    } catch (NumberFormatException e) {
                        errors.add(prefix + "invalid item ID: " + part.trim());
                    }
                }
            } else {
                addValidatedId(itemIdEl, ids, prefix, errors);
            }

            return ids;
        }

        errors.add(prefix + "missing \"ids\" array.");
        return ids;
    }

    /**
     * Adds one validated positive item ID, or records a validation error.
     */
    private static void addValidatedId(JsonElement idEl, List<Integer> ids, String prefix, List<String> errors) {
        try {
            int id = idEl.getAsInt();
            if (id <= 0) {
                errors.add(prefix + "item ID must be positive: " + id);
                return;
            }

            ids.add(id);
        } catch (Exception e) {
            errors.add(prefix + "invalid item ID: " + idEl);
        }
    }

    /**
     * Load from the default bundled resource.
     * If the resource is missing or invalid, the repository is cleared.
     */
    public void loadFromResources(Gson gson) {
        Map<String, Set<Integer>> newBaseToIds = new LinkedHashMap<>();
        Map<Integer, String> newIdToBase = new HashMap<>();

        try (InputStream in = ItemsRepository.class.getResourceAsStream(ITEMS_RESOURCE)) {
            if (in != null) {
                parseInto(gson, in, newBaseToIds, newIdToBase);
            }
        } catch (Exception ignored) {
            // Fall through to swap cleared maps.
        }

        synchronized (this) {
            baseToIds.clear();
            idToBase.clear();
            baseToIds.putAll(newBaseToIds);
            idToBase.putAll(newIdToBase);
        }
    }

    /**
     * Load from an arbitrary input stream.
     * <p>
     * On error, current data remains unchanged.
     */
    public void loadFromStream(Gson gson, InputStream in) {
        if (in == null) {
            return;
        }

        Map<String, Set<Integer>> newBaseToIds = new LinkedHashMap<>();
        Map<Integer, String> newIdToBase = new HashMap<>();

        try {
            parseInto(gson, in, newBaseToIds, newIdToBase);
        } catch (Exception ignored) {
            return;
        }

        if (newBaseToIds.isEmpty()) {
            return;
        }

        synchronized (this) {
            baseToIds.clear();
            idToBase.clear();
            baseToIds.putAll(newBaseToIds);
            idToBase.putAll(newIdToBase);
        }
    }

    /**
     * Strictly load a custom JSON string.
     * This is used for the cloud-synced custom item list stored in ConfigManager.
     *
     * @return true if the repository was replaced with the custom list.
     */
    public boolean loadFromString(Gson gson, String json) {
        ValidationResult result = validateCustomItemsJson(gson, json);
        if (!result.isValid()) {
            return false;
        }

        synchronized (this) {
            baseToIds.clear();
            idToBase.clear();
            baseToIds.putAll(result.parsedBaseToIds);
            idToBase.putAll(result.parsedIdToBase);
        }

        return true;
    }

    /**
     * Returns the base name for a concrete item ID, or null if unknown.
     */
    public String getBaseForId(int id) {
        synchronized (this) {
            return idToBase.get(id);
        }
    }

    /**
     * Returns an immutable snapshot of item IDs for the given base.
     * <p>
     * Empty set if the base is unknown.
     */
    public Set<Integer> getIdsForBase(String base) {
        synchronized (this) {
            Set<Integer> ids = baseToIds.get(base);
            return ids == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        }
    }

    /**
     * Returns an immutable snapshot of all known base names.
     */
    public List<String> getAllBases() {
        synchronized (this) {
            return new ArrayList<>(baseToIds.keySet());
        }
    }

    /**
     * Returns base names that are present in the repo but still locked per the provided unlock state.
     */
    public List<String> getAllBasesStillLocked(ChoiceManUnlocks unlocks) {
        List<String> out = new ArrayList<>();

        synchronized (this) {
            for (String b : baseToIds.keySet()) {
                if (!unlocks.isBaseUnlocked(b)) {
                    out.add(b);
                }
            }
        }

        return out;
    }

    /**
     * True if the base name exists in the repository.
     */
    public boolean isTrackedBase(String base) {
        synchronized (this) {
            return baseToIds.containsKey(base);
        }
    }

    /**
     * Result object used when validating player provided custom_items.json.
     * Validation is intentionally stricter than the bundled parser so players get
     * helpful feedback instead of silently importing a broken list.
     */
    public static final class ValidationResult {
        @Getter
        private final boolean valid;

        @Getter
        private final List<String> errors;

        private final Map<String, Set<Integer>> parsedBaseToIds;
        private final Map<Integer, String> parsedIdToBase;

        private ValidationResult(
                boolean valid,
                List<String> errors,
                Map<String, Set<Integer>> parsedBaseToIds,
                Map<Integer, String> parsedIdToBase) {
            this.valid = valid;
            this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
            this.parsedBaseToIds = parsedBaseToIds;
            this.parsedIdToBase = parsedIdToBase;
        }
    }
}