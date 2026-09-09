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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

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

    /** Resolver threads: each blocks on a lookup, so this bounds parallel logins. */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "PAuth-Resolver");
        t.setDaemon(true);
        return t;
    });
    /**
     * The HTTP client gets its own threads on purpose. It used to share the resolver pool,
     * where a couple of simultaneous logins could occupy every thread while blocked inside
     * send(), leaving nothing to run the client's own I/O callbacks.
     */
    private static final ExecutorService HTTP_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PAuth-HTTP");
        t.setDaemon(true);
        return t;
    });

    /** Shape of a Mojang username: anything else cannot own an account, so it needs no lookup. */
    private static final Pattern MOJANG_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /**
     * Vanilla drops a login that has not completed within ~30s, so every endpoint and retry
     * together has to fit comfortably inside that budget.
     */
    private static final long MAX_TOTAL_LOOKUP_MS = 15_000;
    private static final long RETRY_BACKOFF_MS = 250;
    private static final int MAX_CACHE_ENTRIES = 4096;

    private record Cached(Lookup result, long expiresAtMs) {}
    private static final Map<String, Cached> MOJANG_CACHE = new ConcurrentHashMap<>();
    private static final long POSITIVE_TTL_MS = 10 * 60_000;
    private static final long NEGATIVE_TTL_MS = 2 * 60_000;
    /** Expired entries are kept this long so they can still answer while the API is down. */
    private static final long STALE_RETENTION_MS = 24 * 60 * 60_000L;

    private static volatile HttpClient http;
    private static volatile int httpConnectTimeoutMs;

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
        if (!isValidUsername(name)) {
            return decided(name, Decision.OFFLINE, "username vanilla will reject anyway");
        }

        PlayerEntry entry = PlayerDatabase.get(name);
        if (entry != null) {
            // proven owner -> always require license; registered cracked -> password flow
            if (entry.premium) return decided(name, Decision.PREMIUM, "known premium account in users.json");
            if (entry.passwordHash != null) return decided(name, Decision.OFFLINE, "registered password account");
        }
        if (Config.PREMIUM_MODE.get() == Config.PremiumMode.LENIENT) {
            return decided(name, Decision.OFFLINE, "premiumMode=LENIENT, no lookup");
        }
        // A name Mojang could never issue cannot belong to a licensed account.
        if (!MOJANG_NAME.matcher(name).matches()) {
            return decided(name, Decision.OFFLINE, "not a name Mojang could issue");
        }

        // STRICT mode, unknown name: ask Mojang.
        switch (mojangLookup(name)) {
            case EXISTS:
                return decided(name, Decision.PREMIUM, "name belongs to a Mojang account, license required");
            case FREE:
                return decided(name, Decision.OFFLINE, "name is free on Mojang");
            case ERROR:
            default:
                // Can't tell if this name belongs to a licensed account. Letting it into the
                // offline flow would let anyone squat (and lock out) a premium name during an
                // API outage, so refuse instead of guessing.
                if (Config.KICK_ON_API_ERROR.get()) {
                    Pauth.LOGGER.warn("PAuth: could not reach the name-lookup API for {}, refusing the login "
                                    + "(kickOnApiError=true). Check outbound HTTPS from the server to {}.",
                            name, Config.MOJANG_API_ENDPOINTS.get());
                    return Decision.DISCONNECT;
                }
                Pauth.LOGGER.warn("PAuth: could not reach the name-lookup API for {}, letting it in offline "
                        + "(kickOnApiError=false).", name);
                return Decision.OFFLINE;
        }
    }

    /**
     * One line per login saying how the name was routed and why. Without it an admin cannot
     * tell "PAuth refused this client" apart from "the client hung up on its own".
     */
    private static Decision decided(String name, Decision decision, String reason) {
        Pauth.LOGGER.info("PAuth: {} -> {} ({})", name, decision, reason);
        return decision;
    }

    private static Lookup mojangLookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = MOJANG_CACHE.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs > now) return cached.result;

        Lookup result = queryWithFallbacks(name);
        if (result != Lookup.ERROR) {
            cache(key, result, now);
            return result;
        }
        if (cached != null) {
            // The API is unreachable but we asked about this name before: an answer that is a
            // few minutes stale beats kicking a player over a network blip.
            Pauth.LOGGER.warn("PAuth: name-lookup API unreachable, reusing the last known result {} for {}",
                    cached.result, name);
            return cached.result;
        }
        return Lookup.ERROR;
    }

    /** Tries every configured endpoint, retrying the whole set, until one gives a definitive answer. */
    private static Lookup queryWithFallbacks(String name) {
        List<String> endpoints = Config.MOJANG_API_ENDPOINTS.get();
        int attempts = Math.max(1, Math.min(5, Config.MOJANG_API_ATTEMPTS.get()));
        long deadline = System.currentTimeMillis() + MAX_TOTAL_LOOKUP_MS;
        int failures = 0;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            for (String endpoint : endpoints) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    Pauth.LOGGER.warn("PAuth: name lookup for {} ran out of its {}ms budget after {} failed request(s)",
                            name, MAX_TOTAL_LOOKUP_MS, failures);
                    return Lookup.ERROR;
                }
                String url = buildUrl(endpoint, name);
                Lookup result = query(url, name, Math.min(timeoutMs(), remaining), attempt);
                if (result != Lookup.ERROR) {
                    if (failures > 0) {
                        // Worth an INFO line: it says the retry or the fallback endpoint is what
                        // kept this login alive, which a debug-level success line would hide.
                        Pauth.LOGGER.info("PAuth: name lookup for {} succeeded via {} after {} failed request(s)",
                                name, host(url), failures);
                    }
                    return result;
                }
                failures++;
            }
            long backoff = Math.min(RETRY_BACKOFF_MS * attempt, deadline - System.currentTimeMillis());
            if (attempt < attempts && backoff > 0) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Lookup.ERROR;
                }
            }
        }
        return Lookup.ERROR;
    }

    private static String buildUrl(String endpoint, String name) {
        return endpoint.contains("%s") ? endpoint.replace("%s", name) : endpoint + name;
    }

    private static Lookup query(String url, String name, long timeoutMs, int attempt) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "PAuth")
                    .GET()
                    .build();
            int status = http().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            Lookup result = switch (status) {
                case 200 -> Lookup.EXISTS;           // name owned by a Mojang account
                case 204, 400, 404 -> Lookup.FREE;   // no such account (400 = not a name Mojang would issue)
                default -> Lookup.ERROR;             // 429 rate limit, 5xx, anything unexpected
            };
            if (result == Lookup.ERROR) {
                Pauth.LOGGER.warn("PAuth: {} returned status {} for {} (attempt {}), treating as error",
                        host(url), status, name, attempt);
            } else {
                Pauth.LOGGER.debug("PAuth: name lookup for {} -> {} (status {}, via {})",
                        name, result, status, host(url));
            }
            return result;
        } catch (Exception e) {
            Pauth.LOGGER.warn("PAuth: lookup for {} via {} failed (attempt {}, timeout {}ms): {}",
                    name, host(url), attempt, timeoutMs, e.toString());
            return Lookup.ERROR;
        }
    }

    /**
     * Built lazily so it picks up the configured timeout, and rebuilt if that value changes.
     * Both the connect and the request timeout come from the config: a hardcoded connect
     * timeout is what makes a slow or filtered route fail however high the config is set.
     */
    private static HttpClient http() {
        int connectMs = timeoutMs();
        HttpClient client = http;
        if (client != null && httpConnectTimeoutMs == connectMs) return client;
        synchronized (PremiumResolver.class) {
            if (http == null || httpConnectTimeoutMs != connectMs) {
                http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(connectMs))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .executor(HTTP_EXECUTOR)
                        .build();
                httpConnectTimeoutMs = connectMs;
            }
            return http;
        }
    }

    private static int timeoutMs() {
        return Math.max(1000, Math.min(30_000, Config.MOJANG_API_TIMEOUT_MS.get()));
    }

    private static void cache(String key, Lookup result, long now) {
        if (MOJANG_CACHE.size() >= MAX_CACHE_ENTRIES) {
            MOJANG_CACHE.values().removeIf(c -> c.expiresAtMs + STALE_RETENTION_MS <= now);
            if (MOJANG_CACHE.size() >= MAX_CACHE_ENTRIES) MOJANG_CACHE.clear();
        }
        long ttl = result == Lookup.EXISTS ? POSITIVE_TTL_MS : NEGATIVE_TTL_MS;
        MOJANG_CACHE.put(key, new Cached(result, now + ttl));
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean isValidUsername(String name) {
        return !name.isEmpty() && name.length() <= 16
                && name.chars().allMatch(c -> c > 32 && c < 127);
    }
}
