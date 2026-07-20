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
 * Decides how to log a username in. Runs off-thread because it may hit the Mojang API.
 */
public final class PremiumResolver {

    /** What the login mixin should do for this username. */
    public enum Decision {
        /** Run the online flow (encryption + session server). */
        PREMIUM,
        /** Let the player in offline; password auth handles the rest. */
        OFFLINE,
        /** Refuse the login for now (e.g. Mojang API unreachable in strict mode). */
        DISCONNECT
    }

    private enum Lookup { EXISTS, FREE, ERROR }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "PAuth-Resolver");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .executor(EXECUTOR)
            .build();

    private record Cached(Lookup result, long expiresAtMs) {}
    private static final Map<String, Cached> MOJANG_CACHE = new ConcurrentHashMap<>();
    private static final long POSITIVE_TTL_MS = 10 * 60_000;
    private static final long NEGATIVE_TTL_MS = 2 * 60_000;

    private PremiumResolver() {}

    public static CompletableFuture<Decision> resolve(String name) {
        return CompletableFuture.supplyAsync(() -> decide(name), EXECUTOR)
                .exceptionally(e -> {
                    Pauth.LOGGER.error("PAuth: premium resolution failed for {}", name, e);
                    // Unexpected internal error: fail safe by refusing rather than leaking a premium name.
                    return Config.PREMIUM_MODE.get() == Config.PremiumMode.STRICT
                            ? Decision.DISCONNECT : Decision.OFFLINE;
                });
    }

    private static Decision decide(String name) {
        if (!isValidUsername(name)) return Decision.OFFLINE; // vanilla will reject it anyway

        PlayerEntry entry = PlayerDatabase.get(name);
        if (entry != null) {
            if (entry.premium) return Decision.PREMIUM;            // proven owner -> always require license
            if (entry.passwordHash != null) return Decision.OFFLINE; // registered cracked -> password flow
        }
        if (Config.PREMIUM_MODE.get() == Config.PremiumMode.LENIENT) return Decision.OFFLINE;

        // STRICT mode, unknown name: ask Mojang.
        switch (mojangLookup(name)) {
            case EXISTS:
                return Decision.PREMIUM;
            case FREE:
                return Decision.OFFLINE;
            case ERROR:
            default:
                // Can't tell if this name belongs to a licensed account. Letting it into the
                // offline flow would let anyone squat (and lock out) a premium name during an
                // API outage, so refuse instead of guessing.
                return Config.KICK_ON_API_ERROR.get() ? Decision.DISCONNECT : Decision.OFFLINE;
        }
    }

    private static Lookup mojangLookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = MOJANG_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs > now) return cached.result;

        Lookup result = queryMojang(name);
        if (result != Lookup.ERROR) { // never cache a transient failure
            long ttl = result == Lookup.EXISTS ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
            MOJANG_CACHE.put(key, new Cached(result, now + ttl));
        }
        return result;
    }

    private static Lookup queryMojang(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofMillis(Config.MOJANG_API_TIMEOUT_MS.get()))
                    .GET()
                    .build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            Lookup result = switch (status) {
                case 200 -> Lookup.EXISTS;      // name owned by a Mojang account
                case 204, 404 -> Lookup.FREE;   // no such account
                default -> Lookup.ERROR;        // 429 rate limit, 5xx, anything unexpected
            };
            if (result == Lookup.ERROR) {
                Pauth.LOGGER.warn("PAuth: Mojang API returned status {} for {}, treating as error", status, name);
            } else {
                Pauth.LOGGER.debug("PAuth: Mojang lookup for {} -> {} (status {})", name, result, status);
            }
            return result;
        } catch (Exception e) {
            Pauth.LOGGER.warn("PAuth: Mojang API lookup failed for {} ({})", name, e.toString());
            return Lookup.ERROR;
        }
    }

    private static boolean isValidUsername(String name) {
        return !name.isEmpty() && name.length() <= 16
                && name.chars().allMatch(c -> c > 32 && c < 127);
    }
}
