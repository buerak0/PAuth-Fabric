package ru.zaralx.pauth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class Config {
    public enum PremiumMode {
        /** Names owned by Mojang accounts can only be used by their licensed owners. */
        STRICT,
        /** Unknown names never trigger a Mojang lookup; anyone may register any free name. */
        LENIENT
    }

    public static class ConfigValue<T> implements Supplier<T> {
        private T value;

        public ConfigValue(T defaultValue) {
            this.value = defaultValue;
        }

        @Override
        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }
    }

    public static final ConfigValue<PremiumMode> PREMIUM_MODE = new ConfigValue<>(PremiumMode.STRICT);
    public static final ConfigValue<Boolean> OFFLINE_UUID_FOR_PREMIUM = new ConfigValue<>(false);
    public static final ConfigValue<Integer> LOGIN_TIMEOUT_SECONDS = new ConfigValue<>(60);
    public static final ConfigValue<Integer> SESSION_MINUTES = new ConfigValue<>(30);
    public static final ConfigValue<Integer> MAX_LOGIN_ATTEMPTS = new ConfigValue<>(3);
    public static final ConfigValue<Integer> MOJANG_API_TIMEOUT_MS = new ConfigValue<>(5000);
    public static final ConfigValue<Integer> MOJANG_API_ATTEMPTS = new ConfigValue<>(2);
    /**
     * Name-lookup endpoints, tried in order until one answers; "%s" is the username.
     * An endpoint decides whether a name belongs to a licensed account, so only add
     * mirrors you trust: one that lies about a name lets it be squatted.
     */
    public static final List<String> DEFAULT_MOJANG_API_ENDPOINTS = List.of(
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/%s",
            "https://api.mojang.com/users/profiles/minecraft/%s");
    public static final ConfigValue<List<String>> MOJANG_API_ENDPOINTS =
            new ConfigValue<>(DEFAULT_MOJANG_API_ENDPOINTS);
    public static final ConfigValue<Boolean> KICK_ON_API_ERROR = new ConfigValue<>(true);
    public static final ConfigValue<Integer> MIN_PASSWORD_LENGTH = new ConfigValue<>(4);
    public static final ConfigValue<Boolean> APPLY_BLINDNESS = new ConfigValue<>(true);
    public static final ConfigValue<String> LANGUAGE = new ConfigValue<>("ru");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("pauth.json");
    }

    private static class ConfigData {
        PremiumMode premiumMode = PremiumMode.STRICT;
        boolean offlineUuidForPremium = false;
        int loginTimeoutSeconds = 60;
        int sessionMinutes = 30;
        int maxLoginAttempts = 3;
        int mojangApiTimeoutMs = 5000;
        int mojangApiAttempts = 2;
        List<String> mojangApiEndpoints = new ArrayList<>(DEFAULT_MOJANG_API_ENDPOINTS);
        boolean kickOnApiError = true;
        int minPasswordLength = 4;
        boolean applyBlindness = true;
        String language = "ru";
    }

    public static void load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null) {
                if (data.premiumMode != null) PREMIUM_MODE.set(data.premiumMode);
                OFFLINE_UUID_FOR_PREMIUM.set(data.offlineUuidForPremium);
                LOGIN_TIMEOUT_SECONDS.set(data.loginTimeoutSeconds);
                SESSION_MINUTES.set(data.sessionMinutes);
                MAX_LOGIN_ATTEMPTS.set(data.maxLoginAttempts);
                MOJANG_API_TIMEOUT_MS.set(data.mojangApiTimeoutMs);
                MOJANG_API_ATTEMPTS.set(data.mojangApiAttempts);
                MOJANG_API_ENDPOINTS.set(sanitizeEndpoints(data.mojangApiEndpoints));
                KICK_ON_API_ERROR.set(data.kickOnApiError);
                MIN_PASSWORD_LENGTH.set(data.minPasswordLength);
                APPLY_BLINDNESS.set(data.applyBlindness);
                if (data.language != null) LANGUAGE.set(data.language);
            }
        } catch (Exception e) {
            Pauth.LOGGER.error("PAuth: failed to load config", e);
        }
        save(); // write back so an older file picks up options added since it was created
    }

    /** Drops blank/garbage entries; falls back to the built-in endpoints if nothing usable is left. */
    private static List<String> sanitizeEndpoints(List<String> endpoints) {
        if (endpoints == null) return DEFAULT_MOJANG_API_ENDPOINTS;
        List<String> cleaned = new ArrayList<>();
        for (String endpoint : endpoints) {
            if (endpoint == null) continue;
            String trimmed = endpoint.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                cleaned.add(trimmed);
            } else if (!trimmed.isEmpty()) {
                Pauth.LOGGER.warn("PAuth: ignoring mojangApiEndpoints entry without an http(s) scheme: {}", trimmed);
            }
        }
        if (cleaned.isEmpty()) {
            Pauth.LOGGER.warn("PAuth: no usable mojangApiEndpoints configured, using the defaults");
            return DEFAULT_MOJANG_API_ENDPOINTS;
        }
        return List.copyOf(cleaned);
    }

    public static void save() {
        Path path = configPath();
        ConfigData data = new ConfigData();
        data.premiumMode = PREMIUM_MODE.get();
        data.offlineUuidForPremium = OFFLINE_UUID_FOR_PREMIUM.get();
        data.loginTimeoutSeconds = LOGIN_TIMEOUT_SECONDS.get();
        data.sessionMinutes = SESSION_MINUTES.get();
        data.maxLoginAttempts = MAX_LOGIN_ATTEMPTS.get();
        data.mojangApiTimeoutMs = MOJANG_API_TIMEOUT_MS.get();
        data.mojangApiAttempts = MOJANG_API_ATTEMPTS.get();
        data.mojangApiEndpoints = new ArrayList<>(MOJANG_API_ENDPOINTS.get());
        data.kickOnApiError = KICK_ON_API_ERROR.get();
        data.minPasswordLength = MIN_PASSWORD_LENGTH.get();
        data.applyBlindness = APPLY_BLINDNESS.get();
        data.language = LANGUAGE.get();

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception e) {
            Pauth.LOGGER.error("PAuth: failed to save config", e);
        }
    }
}
