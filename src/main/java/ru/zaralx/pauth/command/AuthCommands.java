package ru.zaralx.pauth.command;

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
                .requires(source -> source.hasPermission(3))
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
            player.sendSystemMessage(Component.literal("§aВы уже вошли в аккаунт."));
            return 0;
        }
        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().getName());
        if (entry != null && entry.passwordHash != null) {
            player.sendSystemMessage(Component.literal("§cВы уже зарегистрированы, используйте /login <пароль>."));
            return 0;
        }
        if (!password.equals(confirm)) {
            player.sendSystemMessage(Component.literal("§cПароли не совпадают."));
            return 0;
        }
        if (password.length() < Config.MIN_PASSWORD_LENGTH.get()) {
            player.sendSystemMessage(Component.literal(
                    "§cПароль слишком короткий (минимум " + Config.MIN_PASSWORD_LENGTH.get() + " символа)."));
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
                player.sendSystemMessage(Component.literal("§aРегистрация успешна, приятной игры!"));
            });
        });
        return 1;
    }

    private static int executeLogin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String password = StringArgumentType.getString(ctx, "password");

        if (!AuthManager.isLocked(player)) {
            player.sendSystemMessage(Component.literal("§aВы уже вошли в аккаунт."));
            return 0;
        }
        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().getName());
        if (entry == null || entry.passwordHash == null) {
            player.sendSystemMessage(Component.literal("§cВы не зарегистрированы: /register <пароль> <пароль>."));
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
                    player.sendSystemMessage(Component.literal("§aВход выполнен, приятной игры!"));
                } else {
                    locked.attempts++;
                    int left = Config.MAX_LOGIN_ATTEMPTS.get() - locked.attempts;
                    if (left <= 0) {
                        player.connection.disconnect(Component.literal("Слишком много неверных попыток входа"));
                    } else {
                        player.sendSystemMessage(Component.literal(
                                "§cНеверный пароль! Осталось попыток: " + left));
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
            player.sendSystemMessage(Component.literal("§cСначала войдите в аккаунт."));
            return 0;
        }
        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().getName());
        if (entry == null || entry.passwordHash == null) {
            player.sendSystemMessage(Component.literal("§cУ вас нет пароля (premium-аккаунт)."));
            return 0;
        }
        if (newPassword.length() < Config.MIN_PASSWORD_LENGTH.get()) {
            player.sendSystemMessage(Component.literal("§cНовый пароль слишком короткий."));
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
                    player.sendSystemMessage(Component.literal("§cНеверный старый пароль."));
                    return;
                }
                PlayerEntry current = PlayerDatabase.get(player.getGameProfile().getName());
                if (current != null) {
                    current.passwordHash = newHash;
                    PlayerDatabase.save();
                }
                player.sendSystemMessage(Component.literal("§aПароль изменён."));
            });
        });
        return 1;
    }

    private static int executeUnregister(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        PlayerEntry entry = PlayerDatabase.get(name);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("Игрок " + name + " не найден в базе."));
            return 0;
        }
        PlayerDatabase.remove(name);
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (online != null) {
            online.connection.disconnect(Component.literal("Ваш аккаунт был сброшен администратором"));
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Аккаунт " + name + " удалён из базы."), true);
        return 1;
    }

    private static int executeInfo(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        PlayerEntry entry = PlayerDatabase.get(name);
        if (entry == null) {
            ctx.getSource().sendFailure(Component.literal("Игрок " + name + " не найден в базе."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Игрок " + entry.name
                        + " | premium: " + entry.premium
                        + " | зарегистрирован: " + (entry.passwordHash != null)
                        + " | mojangUuid: " + entry.mojangUuid
                        + " | lastIp: " + entry.lastIp), false);
        return 1;
    }
}
