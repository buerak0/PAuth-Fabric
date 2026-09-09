package ru.zaralx.pauth.command;

import ru.zaralx.pauth.i18n.Messages;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import ru.zaralx.pauth.Config;
import ru.zaralx.pauth.auth.AuthManager;
import ru.zaralx.pauth.auth.PasswordHasher;
import ru.zaralx.pauth.data.PlayerDatabase;
import ru.zaralx.pauth.data.PlayerEntry;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AuthCommands {
    /** PBKDF2 is deliberately slow (~100ms), so hashing runs off the server thread. */
    private static final ExecutorService HASHER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PAuth-Hasher");
        t.setDaemon(true);
        return t;
    });

    private AuthCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var registerCmd = Commands.literal("register")
                .then(Commands.argument("password", StringArgumentType.word())
                        .then(Commands.argument("confirm", StringArgumentType.word())
                                .executes(AuthCommands::executeRegister)));
        var loginCmd = Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(AuthCommands::executeLogin));

        var registerNode = dispatcher.register(registerCmd);
        dispatcher.register(Commands.literal("reg").redirect(registerNode));
        var loginNode = dispatcher.register(loginCmd);
        dispatcher.register(Commands.literal("l").redirect(loginNode));

        dispatcher.register(Commands.literal("changepassword")
                .then(Commands.argument("old", StringArgumentType.word())
                        .then(Commands.argument("new", StringArgumentType.word())
                                .executes(AuthCommands::executeChangePassword))));

        dispatcher.register(Commands.literal("pauth")
        // Commands.hasPermission(int) only exists from 1.21.8; the source-level check
        // works on every version in this tree.
        .requires(source -> source.hasPermission(Commands.LEVEL_ADMINS))
        .then(Commands.literal("unregister")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(AuthCommands::executeUnregister)))
        .then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(AuthCommands::executeInfo))));
    }

    private static int executeRegister(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String password = StringArgumentType.getString(ctx, "password");
        String confirm = StringArgumentType.getString(ctx, "confirm");

        if (!AuthManager.isLocked(player)) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.ALREADY_LOGGED_IN)));
            return 0;
        }
        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().getName());
        if (entry != null && entry.passwordHash != null) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.ALREADY_REGISTERED)));
            return 0;
        }
        if (!password.equals(confirm)) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.PASSWORDS_MISMATCH)));
            return 0;
        }
        if (password.length() < Config.MIN_PASSWORD_LENGTH.get()) {
            player.sendSystemMessage(Component.literal(
                    Messages.t(Messages.Key.PASSWORD_TOO_SHORT, Config.MIN_PASSWORD_LENGTH.get())));
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        HASHER.submit(() -> {
            String hash = PasswordHasher.hash(password);
            server.execute(() -> {
                if (player.hasDisconnected() || !AuthManager.isLocked(player)) return;
                PlayerEntry created = PlayerDatabase.getOrCreate(player.getGameProfile().getName());
                created.passwordHash = hash;
                created.registeredAtMs = System.currentTimeMillis();
                AuthManager.authenticate(player);
                player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.REGISTER_OK)));
            });
        });
        return 1;
    }

    private static int executeLogin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String password = StringArgumentType.getString(ctx, "password");

        if (!AuthManager.isLocked(player)) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.ALREADY_LOGGED_IN)));
            return 0;
        }
        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().getName());
        if (entry == null || entry.passwordHash == null) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.NOT_REGISTERED)));
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        String storedHash = entry.passwordHash;
        HASHER.submit(() -> {
            boolean ok = PasswordHasher.verify(password, storedHash);
            server.execute(() -> {
                if (player.hasDisconnected()) return;
                AuthManager.Locked locked = AuthManager.getLocked(player);
                if (locked == null) return;
                if (ok) {
                    AuthManager.authenticate(player);
                    player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.LOGIN_OK)));
                } else {
                    locked.attempts++;
                    int left = Config.MAX_LOGIN_ATTEMPTS.get() - locked.attempts;
                    if (left <= 0) {
                        player.connection.disconnect(Component.literal(Messages.t(Messages.Key.TOO_MANY_ATTEMPTS)));
                    } else {
                        player.sendSystemMessage(Component.literal(
                                Messages.t(Messages.Key.WRONG_PASSWORD, left)));
                    }
                }
            });
        });
        return 1;
    }

    private static int executeChangePassword(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String oldPassword = StringArgumentType.getString(ctx, "old");
        String newPassword = StringArgumentType.getString(ctx, "new");

        if (AuthManager.isLocked(player)) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.MUST_LOGIN_FIRST)));
            return 0;
        }
        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().getName());
        if (entry == null || entry.passwordHash == null) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.NO_PASSWORD_PREMIUM)));
            return 0;
        }
        if (newPassword.length() < Config.MIN_PASSWORD_LENGTH.get()) {
            player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.NEW_PASSWORD_TOO_SHORT)));
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        String storedHash = entry.passwordHash;
        HASHER.submit(() -> {
            boolean ok = PasswordHasher.verify(oldPassword, storedHash);
            String newHash = ok ? PasswordHasher.hash(newPassword) : null;
            server.execute(() -> {
                if (player.hasDisconnected()) return;
                if (!ok) {
                    player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.WRONG_OLD_PASSWORD)));
                    return;
                }
                PlayerEntry current = PlayerDatabase.get(player.getGameProfile().getName());
                if (current != null) {
                    current.passwordHash = newHash;
                    PlayerDatabase.save();
                }
                player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.PASSWORD_CHANGED)));
            });
        });
        return 1;
    }

    private static int executeUnregister(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        PlayerEntry entry = PlayerDatabase.get(name);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal(Messages.t(Messages.Key.PLAYER_NOT_FOUND, name)));
            return 0;
        }
        PlayerDatabase.remove(name);
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            online.connection.disconnect(Component.literal(Messages.t(Messages.Key.ACCOUNT_RESET_BY_ADMIN)));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(Messages.t(Messages.Key.ACCOUNT_DELETED, name)), true);
        return 1;
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        PlayerEntry entry = PlayerDatabase.get(name);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal(Messages.t(Messages.Key.PLAYER_NOT_FOUND, name)));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Player " + entry.name
                        + " | premium: " + entry.premium
                        + " | registered: " + (entry.passwordHash != null)
                        + " | mojangUuid: " + entry.mojangUuid
                        + " | lastIp: " + entry.lastIp), false);
        return 1;
    }
}