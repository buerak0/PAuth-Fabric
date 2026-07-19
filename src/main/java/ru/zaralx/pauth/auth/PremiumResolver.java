package ru.zaralx.pauth.auth;

import ru.zaralx.pauth.Config;
import ru.zaralx.pauth.Pauth;
import ru.zaralx.pauth.data.PlayerDatabase;
import ru.zaralx.pauth.data.PlayerEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decides whether the server should attempt the premium (encryption + session server)
 * login flow for a username. Runs off-thread because it may hit the Mojang API.
 */
public final class PremiumResolver {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "PAuth-Resolver");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(EXECUTOR)
            .build();

    private record Cached(boolean premium, long expiresAtMs) {}
    private static final Map<String, Cached> MOJANG_CACHE = new ConcurrentHashMap<>();
    private static final long POSITIVE_TTL_MS = 10 * 60_000;
    private static final long NEGATIVE_TTL_MS = 2 * 60_000;

    private PremiumResolver() {}

    public static CompletableFuture<Boolean> shouldAttemptPremium(String name) {
        return CompletableFuture.supplyAsync(() -> decide(name), EXECUTOR)
                .exceptionally(e -> {
                    Pauth.LOGGER.error("PAuth: premium resolution failed for {}", name, e);
                    return false;
                });
    }

    private static boolean decide(String name) {
        if (!isValidUsername(name)) return false; // vanilla will reject it anyway

        PlayerEntry entry = PlayerDatabase.get(name);
        if (entry != null) {
            if (entry.premium) return true;            // proven owner before -> always require license
            if (entry.passwordHash != null) return false; // registered cracked -> password flow
        }
        if (Config.PREMIUM_MODE.get() == Config.PremiumMode.LENIENT) return false;
        return mojangNameExists(name);
    }

    private static boolean mojangNameExists(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = MOJANG_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs > now) return cached.premium;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofMillis(Config.MOJANG_API_TIMEOUT_MS.get()))
                    .GET()
                    .build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            boolean exists = status == 200;
            long ttl = exists ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
            MOJANG_CACHE.put(key, new Cached(exists, now + ttl));
            Pauth.LOGGER.debug("PAuth: Mojang lookup for {} -> {} (status {})", name, exists, status);
            return exists;
        } catch (Exception e) {
            // API down / timeout: fall back to the password flow, don't lock players out
            Pauth.LOGGER.warn("PAuth: Mojang API lookup failed for {} ({}), treating as cracked",
                    name, e.toString());
            return false;
        }
    }

    private static boolean isValidUsername(String name) {
        return !name.isEmpty() && name.length() <= 16
                && name.chars().allMatch(c -> c > 32 && c < 127);
    }
}
