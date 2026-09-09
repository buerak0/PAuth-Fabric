package ru.zaralx.pauth.event;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import ru.zaralx.pauth.auth.AuthManager;
import ru.zaralx.pauth.command.AuthCommands;
import ru.zaralx.pauth.data.PlayerDatabase;
import ru.zaralx.pauth.data.PlayerEntry;
import ru.zaralx.pauth.i18n.Messages;

public class AuthEvents {

    public static void init() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            AuthCommands.register(dispatcher);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (server.usesAuthentication()) return; // online-mode: nothing to do

            String ip = AuthManager.getPlayerIp(player);
            PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().name());
            if (entry != null && entry.premium) {
                // Could only get here through the encryption + session server check
                AuthManager.recordLogin(player);
                player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.PREMIUM_OK)));
                return;
            }
            boolean registered = entry != null && entry.passwordHash != null;
            if (registered && AuthManager.sessionValid(entry, ip)) {
                AuthManager.recordLogin(player);
                player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.SESSION_RESTORED)));
                return;
            }
            AuthManager.lock(player, registered);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!AuthManager.isLocked(player)) {
                AuthManager.recordLogin(player); // refresh session ip/time
            }
            AuthManager.onDisconnect(player);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                AuthManager.tick(player);
            }
        });

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (AuthManager.isLocked(sender)) {
                sender.sendSystemMessage(Component.literal(Messages.t(Messages.Key.MUST_LOGIN_FIRST)));
                return false;
            }
            return true;
        });

        // Commands are gated in ServerGamePacketListenerImplMixin: ALLOW_COMMAND_MESSAGE only
        // fires for commands that broadcast a chat message, so it cannot block /tp and friends.

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (AuthManager.isLocked(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (AuthManager.isLocked(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (AuthManager.isLocked(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (AuthManager.isLocked(player)) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            return !AuthManager.isLocked(player);
        });
    }
}