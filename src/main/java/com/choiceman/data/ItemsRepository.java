package com.choiceman.data;

import com.google.gson.*;

import javax.inject.Singleton;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * In-memory index of item “bases” (logical items) to their concrete item IDs and vice-versa.
 * Data is loaded from a JSON resource bundled at /com/choiceman/items.json.
 * <p>
 * Threading
 * - Loads replace internal maps atomically.
 * - Readers get snapshots or unmodifiable views so callers cannot mutate internal state.
 */
@Singleton
public class ItemsRepository {
    private static final String ITEMS_RESOURCE = "/com/choiceman/items.json";

    // Backing maps. Mutated only inside synchronized blocks; reads return snapshots.
    private final Map<String, Set<Integer>> baseToIds = new LinkedHashMap<>();
    private final Map<Integer, String> idToBase = new HashMap<>();

    private static void parseInto(Gson gson,
                                  InputStream in,
                                  Map<String, Set<Integer>> baseToIdsOut,
                                  Map<Integer, String> idToBaseOut) {
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonArray arr = gson.fromJson(reader, JsonArray.class);
            if (arr == null) return;

            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();

                String name = obj.has("name") ? obj.get("name").getAsString() : null;
                if (name == null || (name = name.trim()).isEmpty()) continue;

                Set<Integer> ids = baseToIdsOut.computeIfAbsent(name, k -> new LinkedHashSet<>());
                boolean addedAny = false;

                // ids: [1,2,3]
                if (obj.has("ids") && obj.get("ids").isJsonArray()) {
                    for (JsonElement idEl : obj.get("ids").getAsJsonArray()) {
                        addedAny |= tryAddId(name, ids, idEl, idToBaseOut);
                    }
                }

                // id: 123
                if (!addedAny && obj.has("id")) {
                    addedAny |= tryAddId(name, ids, obj.get("id"), idToBaseOut);
                }

                // itemid: array | number | "1,2,3"
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
            }
        } catch (Exception ignored) {
            // let caller decide whether to swap maps
        }
    }

    private static boolean tryAddId(String baseName,
                                    Set<Integer> ids,
                                    JsonElement idEl,
                                    Map<Integer, String> idToBaseOut) {
        try {
            int id = idEl.getAsInt();
            if (id <= 0) return false;
            boolean changed = ids.add(id);

            // Only map if not present or consistent with current base.
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
            // fall through to swap cleared maps
        }

        synchronized (this) {
            baseToIds.clear();
            idToBase.clear();
            baseToIds.putAll(newBaseToIds);
            idToBase.putAll(newIdToBase);
        }
    }

    /**
     * Load from an arbitrary input stream. On error, current data remains unchanged.
     */
    public void loadFromStream(Gson gson, InputStream in) {
        if (in == null) return;

        Map<String, Set<Integer>> newBaseToIds = new LinkedHashMap<>();
        Map<Integer, String> newIdToBase = new HashMap<>();

        try {
            parseInto(gson, in, newBaseToIds, newIdToBase);
        } catch (Exception ignored) {
            return; // keep existing maps intact
        }

        synchronized (this) {
            baseToIds.clear();
            idToBase.clear();
            baseToIds.putAll(newBaseToIds);
            idToBase.putAll(newIdToBase);
        }
    }

    /**
     * Returns the base name for a concrete item id, or null if unknown.
     */
    public String getBaseForId(int id) {
        synchronized (this) {
            return idToBase.get(id);
        }
    }

    /**
     * Returns an immutable snapshot of item IDs for the given base.
     * Empty set if the base is unknown.
     */
    public Set<Integer> getIdsForBase(String base) {
        synchronized (this) {
            Set<Integer> ids = baseToIds.get(base);
            return (ids == null)
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
}
