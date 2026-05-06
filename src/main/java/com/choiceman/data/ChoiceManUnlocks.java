package com.choiceman.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

/**
 * Persists and queries unlock/obtain state for item bases defined by {@link ItemsRepository}.
 * JSON is stored under ~/.runelite/choiceman/ as two files: unlocked and obtained.
 * Set access uses synchronized snapshots for safe iteration and persistence.
 */
@Singleton
public class ChoiceManUnlocks {
    private static final String DIR = "choiceman";
    private static final String UNLOCKED_FILE = "unlocked_items.json";
    private static final String OBTAINED_FILE = "obtained_items.json";

    private static final String CONFIG_GROUP = "choiceman";
    private static final String SYNC_UNLOCKED_KEY = "unlockedItems.data";
    private static final String SYNC_UNLOCKED_TIMESTAMP_KEY = "unlockedItems.timestamp";
    private static final String SYNC_OBTAINED_KEY = "obtainedItems.data";
    private static final String SYNC_OBTAINED_TIMESTAMP_KEY = "obtainedItems.timestamp";

    private static final Type STRING_SET = new TypeToken<Set<String>>() {
    }.getType();

    private final Set<String> unlockedBases = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> obtainedBases = Collections.synchronizedSet(new LinkedHashSet<>());

    @Inject
    private ConfigManager configManager;

    private transient Gson gson;
    private transient ItemsRepository repo;

    /**
     * Wire runtime services used for JSON I/O and base validation.
     */
    public void init(Gson gson, ItemsRepository repo) {
        this.gson = gson;
        this.repo = repo;
    }

    /**
     * True if {@code base} exists in the repository.
     */
    public boolean isBaseTracked(String base) {
        return base != null && repo != null && repo.isTrackedBase(base);
    }

    /**
     * True if the base is known and has been unlocked.
     */
    public boolean isBaseUsable(String base) {
        return isBaseTracked(base) && unlockedBases.contains(base);
    }

    public boolean isBaseUnlocked(String base) {
        return unlockedBases.contains(base);
    }

    public boolean isBaseObtained(String base) {
        return obtainedBases.contains(base);
    }

    /**
     * Mark a base as unlocked and persist the change.
     * The ids parameter is kept for call-site compatibility but is not used.
     */
    @SuppressWarnings("unused")
    public void unlockBase(String base, Set<Integer> ignoredIds) {
        if (base == null) return;

        if (unlockedBases.add(base)) {
            saveToDisk();
        }
    }

    /**
     * Mark a base as obtained the first time it is seen and persist the change.
     *
     * @return true if this call recorded the first obtain for the base.
     */
    public boolean markObtainedBaseIfFirst(String base) {
        if (base == null) return false;

        if (obtainedBases.add(base)) {
            saveToDisk();
            return true;
        }

        return false;
    }

    /**
     * Snapshot list of unlocked bases.
     */
    public List<String> unlockedList() {
        synchronized (unlockedBases) {
            return new ArrayList<>(unlockedBases);
        }
    }

    /**
     * Snapshot list of obtained bases.
     */
    public List<String> obtainedList() {
        synchronized (obtainedBases) {
            return new ArrayList<>(obtainedBases);
        }
    }

    /**
     * Loads unlock/obtain sets from local files and RuneLite config sync.
     * The newer source wins. Missing files/config are tolerated.
     */
    public void loadFromDisk() {
        try {
            Path dir = RUNELITE_DIR.toPath().resolve(DIR);
            Files.createDirectories(dir);

            Path unlockedPath = dir.resolve(UNLOCKED_FILE);
            Path obtainedPath = dir.resolve(OBTAINED_FILE);

            Set<String> localUnlocked = readSetFromDisk(unlockedPath);
            Set<String> localObtained = readSetFromDisk(obtainedPath);
            long localTimestamp = Math.max(lastModified(unlockedPath), lastModified(obtainedPath));

            Set<String> syncedUnlocked = readSetFromConfig(SYNC_UNLOCKED_KEY);
            Set<String> syncedObtained = readSetFromConfig(SYNC_OBTAINED_KEY);
            long syncedTimestamp = Math.max(
                    readTimestamp(SYNC_UNLOCKED_TIMESTAMP_KEY),
                    readTimestamp(SYNC_OBTAINED_TIMESTAMP_KEY)
            );

            boolean useSynced = syncedTimestamp > localTimestamp
                    && (syncedUnlocked != null || syncedObtained != null);

            Set<String> selectedUnlocked = useSynced
                    ? emptyIfNull(syncedUnlocked)
                    : emptyIfNull(localUnlocked);

            Set<String> selectedObtained = useSynced
                    ? emptyIfNull(syncedObtained)
                    : emptyIfNull(localObtained);

            retainTracked(selectedUnlocked);
            retainTracked(selectedObtained);

            synchronized (unlockedBases) {
                unlockedBases.clear();
                unlockedBases.addAll(selectedUnlocked);
            }

            synchronized (obtainedBases) {
                obtainedBases.clear();
                obtainedBases.addAll(selectedObtained);
            }

            if (useSynced) {
                writeJson(unlockedPath, selectedUnlocked);
                writeJson(obtainedPath, selectedObtained);
            } else if (localTimestamp > 0 && syncedTimestamp < localTimestamp) {
                saveToConfig(selectedUnlocked, selectedObtained, localTimestamp);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Persist both unlocked and obtained sets atomically using a temp file then move.
     * Also writes the same state to RuneLite config for profile/cloud sync.
     */
    public synchronized void saveToDisk() {
        try {
            Path dir = RUNELITE_DIR.toPath().resolve(DIR);
            Files.createDirectories(dir);

            Set<String> uSnap;
            Set<String> oSnap;

            synchronized (unlockedBases) {
                uSnap = new LinkedHashSet<>(unlockedBases);
            }

            synchronized (obtainedBases) {
                oSnap = new LinkedHashSet<>(obtainedBases);
            }

            retainTracked(uSnap);
            retainTracked(oSnap);

            writeJson(dir.resolve(UNLOCKED_FILE), uSnap);
            writeJson(dir.resolve(OBTAINED_FILE), oSnap);
            saveToConfig(uSnap, oSnap, System.currentTimeMillis());
        } catch (IOException ignored) {
            // Ignore persistence errors
        }
    }

    private Set<String> readSetFromDisk(Path path) {
        if (!Files.exists(path)) {
            return null;
        }

        try (Reader r = Files.newBufferedReader(path)) {
            Set<String> s = gson.fromJson(r, STRING_SET);
            return s == null ? null : new LinkedHashSet<>(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<String> readSetFromConfig(String key) {
        if (configManager == null) {
            return null;
        }

        String json = configManager.getConfiguration(CONFIG_GROUP, key);
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            Set<String> s = gson.fromJson(json, STRING_SET);
            return s == null ? null : new LinkedHashSet<>(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveToConfig(Set<String> unlocked, Set<String> obtained, long timestamp) {
        if (configManager == null) {
            return;
        }

        configManager.setConfiguration(CONFIG_GROUP, SYNC_UNLOCKED_KEY, gson.toJson(unlocked));
        configManager.setConfiguration(CONFIG_GROUP, SYNC_UNLOCKED_TIMESTAMP_KEY, String.valueOf(timestamp));
        configManager.setConfiguration(CONFIG_GROUP, SYNC_OBTAINED_KEY, gson.toJson(obtained));
        configManager.setConfiguration(CONFIG_GROUP, SYNC_OBTAINED_TIMESTAMP_KEY, String.valueOf(timestamp));
    }

    private long readTimestamp(String key) {
        if (configManager == null) {
            return 0L;
        }

        String raw = configManager.getConfiguration(CONFIG_GROUP, key);
        if (raw == null || raw.trim().isEmpty()) {
            return 0L;
        }

        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private Set<String> emptyIfNull(Set<String> set) {
        return set == null ? new LinkedHashSet<>() : new LinkedHashSet<>(set);
    }

    private void retainTracked(Set<String> set) {
        if (repo == null) {
            return;
        }

        set.retainAll(new HashSet<>(repo.getAllBases()));
    }

    private void writeJson(Path path, Object obj) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            gson.toJson(obj, w);
        }

        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}