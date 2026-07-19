package ru.zaralx.pauth.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.loading.FMLPaths;
import ru.zaralx.pauth.Pauth;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simple JSON-file storage: config/pauth/users.json, keyed by lowercase username.
 * All entries are kept in memory; saves are snapshots written on a background thread.
 */
public final class PlayerDatabase {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, PlayerEntry> ENTRIES = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PAuth-DB");
        t.setDaemon(true);
        return t;
    });

    private PlayerDatabase() {}

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("pauth").resolve("users.json");
    }

    public static synchronized void load() {
        Path file = file();
        ENTRIES.clear();
        if (!Files.exists(file)) {
            Pauth.LOGGER.info("PAuth: no user database yet, starting fresh ({})", file);
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Map<String, PlayerEntry> loaded = GSON.fromJson(reader,
                    new TypeToken<Map<String, PlayerEntry>>() {}.getType());
            if (loaded != null) ENTRIES.putAll(loaded);
            Pauth.LOGGER.info("PAuth: loaded {} user(s)", ENTRIES.size());
        } catch (Exception e) {
            Pauth.LOGGER.error("PAuth: failed to load {}", file, e);
        }
    }

    public static PlayerEntry get(String name) {
        return ENTRIES.get(name.toLowerCase(Locale.ROOT));
    }

    public static PlayerEntry getOrCreate(String name) {
        return ENTRIES.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> {
            PlayerEntry e = new PlayerEntry();
            e.name = name;
            return e;
        });
    }

    public static void remove(String name) {
        ENTRIES.remove(name.toLowerCase(Locale.ROOT));
        save();
    }

    public static void save() {
        Map<String, PlayerEntry> snapshot = new HashMap<>(ENTRIES);
        IO.submit(() -> {
            Path file = file();
            Path tmp = file.resolveSibling("users.json.tmp");
            try {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, writer);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Pauth.LOGGER.error("PAuth: failed to save {}", file, e);
            }
        });
    }
}
