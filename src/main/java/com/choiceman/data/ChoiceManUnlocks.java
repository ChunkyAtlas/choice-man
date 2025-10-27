package com.choiceman.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

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
    private static final Type STRING_SET = new TypeToken<Set<String>>() {
    }.getType();
    private final Set<String> unlockedBases = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> obtainedBases = Collections.synchronizedSet(new LinkedHashSet<>());
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
     * Loads unlock/obtain sets from disk and drops entries no longer present in {@link ItemsRepository}.
     * Missing files are tolerated.
     */
    public void loadFromDisk() {
        try {
            Path dir = RUNELITE_DIR.toPath().resolve(DIR);
            Files.createDirectories(dir);
            Path uf = dir.resolve(UNLOCKED_FILE);
            Path of = dir.resolve(OBTAINED_FILE);

            if (Files.exists(uf)) {
                try (Reader r = Files.newBufferedReader(uf)) {
                    Set<String> s = gson.fromJson(r, STRING_SET);
                    if (s != null) {
                        synchronized (unlockedBases) {
                            unlockedBases.clear();
                            unlockedBases.addAll(s);
                        }
                    }
                }
            }

            if (Files.exists(of)) {
                try (Reader r = Files.newBufferedReader(of)) {
                    Set<String> s = gson.fromJson(r, STRING_SET);
                    if (s != null) {
                        synchronized (obtainedBases) {
                            obtainedBases.clear();
                            obtainedBases.addAll(s);
                        }
                    }
                }
            }

            Set<String> allowed = new HashSet<>(repo.getAllBases());
            synchronized (unlockedBases) {
                unlockedBases.retainAll(allowed);
            }
            synchronized (obtainedBases) {
                obtainedBases.retainAll(allowed);
            }
        } catch (Exception ignored) {
            // Fail-safe: unreadable files should not disrupt runtime behavior
        }
    }

    /**
     * Persist both unlocked and obtained sets atomically using a temp file then move.
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

            writeJson(dir.resolve(UNLOCKED_FILE), uSnap);
            writeJson(dir.resolve(OBTAINED_FILE), oSnap);
        } catch (IOException ignored) {
            // Ignore persistence errors
        }
    }

    private void writeJson(Path path, Object obj) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            gson.toJson(obj, w);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
