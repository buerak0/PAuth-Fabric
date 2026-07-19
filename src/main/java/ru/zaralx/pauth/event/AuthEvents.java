package ru.zaralx.pauth.event;

import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import ru.zaralx.pauth.Pauth;
import ru.zaralx.pauth.auth.AuthManager;
import ru.zaralx.pauth.command.AuthCommands;
import ru.zaralx.pauth.data.PlayerDatabase;
import ru.zaralx.pauth.data.PlayerEntry;

import java.util.List;
import java.util.Set;

@Mod.EventBusSubscriber(modid = Pauth.MODID)
public class AuthEvents {

    private static final Set<String> ALLOWED_COMMANDS = Set.of("register", "reg", "login", "l");

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.getServer();
        if (server == null || server.usesAuthentication()) return; // online-mode: nothing to do

        PlayerEntry entry = PlayerDatabase.get(player.getGameProfile().getName());
        if (entry != null && entry.premium) {
            // Could only get here through the encryption + session server check
            AuthManager.recordLogin(player);
            player.sendSystemMessage(Component.literal("§aЛицензия подтверждена, приятной игры!"));
            return;
        }
        boolean registered = entry != null && entry.passwordHash != null;
        if (registered && AuthManager.sessionValid(entry, player.getIpAddress())) {
            AuthManager.recordLogin(player);
            player.sendSystemMessage(Component.literal("§aВход выполнен автоматически (сессия восстановлена)."));
            return;
        }
        AuthManager.lock(player, registered);
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AuthManager.isLocked(player)) {
            AuthManager.recordLogin(player); // refresh session ip/time
        }
        AuthManager.onDisconnect(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        AuthManager.tick(player);
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        if (AuthManager.isLocked(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().sendSystemMessage(Component.literal("§cСначала войдите в аккаунт."));
        }
    }

    @SubscribeEvent
    public static void onCommand(CommandEvent event) {
        CommandSourceStack source = event.getParseResults().getContext().getSource();
        if (!(source.getEntity() instanceof ServerPlayer player) || !AuthManager.isLocked(player)) return;

        List<ParsedCommandNode<CommandSourceStack>> nodes = event.getParseResults().getContext().getNodes();
        String root = nodes.isEmpty() ? "" : nodes.get(0).getNode().getName();
        if (!ALLOWED_COMMANDS.contains(root)) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("§cДоступны только /login и /register."));
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent event) {
        if (event.isCancelable() && AuthManager.isLocked(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (AuthManager.isLocked(event.getEntity())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        // No damage to or from players in limbo
        if (event.getEntity() instanceof ServerPlayer victim && AuthManager.isLocked(victim)) {
            event.setCanceled(true);
        } else if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && AuthManager.isLocked(attacker)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() != null && AuthManager.isLocked(event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && AuthManager.isLocked(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (AuthManager.isLocked(event.getPlayer())) {
            event.setCanceled(true);
            event.getPlayer().getInventory().add(event.getEntity().getItem());
        }
    }

    @SubscribeEvent
    public static void onItemPickup(EntityItemPickupEvent event) {
        if (AuthManager.isLocked(event.getEntity())) event.setCanceled(true);
    }
}
