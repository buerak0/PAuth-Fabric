package ru.zaralx.pauth.auth;

import ru.zaralx.pauth.i18n.Messages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import ru.zaralx.pauth.Config;
import ru.zaralx.pauth.Pauth;
import ru.zaralx.pauth.data.PlayerDatabase;
import ru.zaralx.pauth.data.PlayerEntry;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks players that joined but have not yet authenticated ("limbo"). */
public final class AuthManager {

    /** The only commands a player in limbo may run. */
    private static final Set<String> AUTH_COMMANDS = Set.of("register", "reg", "login", "l");

    /**
     * Whether a command line a locked player sent is one of the auth commands.
     * The string is what the client typed, without the leading slash.
     */
    public static boolean isAuthCommand(String command) {
        String root = command.trim();
        if (root.startsWith("/")) root = root.substring(1);
        int space = root.indexOf(' ');
        if (space >= 0) root = root.substring(0, space);
        return AUTH_COMMANDS.contains(root.toLowerCase(Locale.ROOT));
    }

    public static final class Locked {
        public final Vec3 pos;
        public final boolean registered;
        public final long lockedAtMs = System.currentTimeMillis();
        public int attempts;
        long lastReminderMs;

        Locked(Vec3 pos, boolean registered) {
            this.pos = pos;
            this.registered = registered;
        }
    }

    private static final Map<UUID, Locked> LOCKED = new ConcurrentHashMap<>();

    private AuthManager() {}

    public static boolean isLocked(Player player) {
        return LOCKED.containsKey(player.getUUID());
    }

    public static Locked getLocked(Player player) {
        return LOCKED.get(player.getUUID());
    }

    /** Called from the login mixin when the Mojang session server confirmed ownership. */
    public static void onPremiumVerified(String name, UUID mojangUuid) {
        PlayerEntry entry = PlayerDatabase.getOrCreate(name);
        if (!entry.premium) Pauth.LOGGER.info("PAuth: {} verified as premium ({})", name, mojangUuid);
        entry.premium = true;
        entry.mojangUuid = mojangUuid.toString();
        PlayerDatabase.save();
    }

    public static void lock(ServerPlayer player, boolean registered) {
        LOCKED.put(player.getUUID(), new Locked(player.position(), registered));
        if (Config.APPLY_BLINDNESS.get()) {
            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS,
                    Config.LOGIN_TIMEOUT_SECONDS.get() * 20 + 40, 0, false, false));
        }
        sendPrompt(player, registered);
    }

    /** Marks the player as authenticated and records the session. */
    public static void authenticate(ServerPlayer player) {
        LOCKED.remove(player.getUUID());
        player.removeEffect(MobEffects.BLINDNESS);
        recordLogin(player);
    }

    public static String getPlayerIp(ServerPlayer player) {
        if (player.connection != null && player.connection.getRemoteAddress() instanceof java.net.InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return "127.0.0.1";
    }

    public static void recordLogin(ServerPlayer player) {
        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().name());
        if (entry == null) return;
        entry.lastIp = getPlayerIp(player);
        entry.lastLoginMs = System.currentTimeMillis();
        PlayerDatabase.save();
    }

    public static boolean sessionValid(PlayerEntry entry, String ip) {
        int minutes = Config.SESSION_MINUTES.get();
        if (minutes <= 0 || entry.lastIp == null || !entry.lastIp.equals(ip)) return false;
        return System.currentTimeMillis() - entry.lastLoginMs < minutes * 60_000L;
    }

    public static void onDisconnect(ServerPlayer player) {
        LOCKED.remove(player.getUUID());
    }

    /** Called every tick for every player: enforces limbo, reminders and the timeout. */
    public static void tick(ServerPlayer player) {
        Locked locked = LOCKED.get(player.getUUID());
        if (locked == null) return;

        long now = System.currentTimeMillis();
        if (now - locked.lockedAtMs > Config.LOGIN_TIMEOUT_SECONDS.get() * 1000L) {
            player.connection.disconnect(Component.literal(Messages.t(Messages.Key.LOGIN_TIMEOUT)));
            return;
        }
        if (now - locked.lastReminderMs > 10_000) {
            locked.lastReminderMs = now;
            sendPrompt(player, locked.registered);
        }
        if (player.position().distanceToSqr(locked.pos) > 0.0625) {
            player.connection.teleport(locked.pos.x, locked.pos.y, locked.pos.z,
                    player.getYRot(), player.getXRot());
            player.setDeltaMovement(Vec3.ZERO);
        }
    }

    private static void sendPrompt(ServerPlayer player, boolean registered) {
        if (registered) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.PROMPT_LOGIN)));
        } else {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.PROMPT_REGISTER)));
        }
    }
}
