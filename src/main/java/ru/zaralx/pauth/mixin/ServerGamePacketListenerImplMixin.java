package ru.zaralx.pauth.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.zaralx.pauth.auth.AuthManager;
import ru.zaralx.pauth.i18n.Messages;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow public ServerPlayer player;

    /**
     * Commands are stopped at the packet, which is the only place that catches all of them:
     * Fabric's ServerMessageEvents.ALLOW_COMMAND_MESSAGE only fires for commands that
     * broadcast a chat message (/me, /say), so it cannot serve as a command gate.
     */
    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void pauth$preventCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (pauth$refuseCommand(packet.command())) ci.cancel();
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void pauth$preventSignedCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (pauth$refuseCommand(packet.command())) ci.cancel();
    }

    @Unique
    private boolean pauth$refuseCommand(String command) {
        if (!AuthManager.isLocked(this.player)) return false;
        if (AuthManager.isAuthCommand(command)) return false;
        this.player.sendSystemMessage(Component.literal(Messages.t(Messages.Key.ONLY_AUTH_COMMANDS)));
        return true;
    }

    /** Covers both halves of the interact packet: attacking an entity and using one. */
    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void pauth$preventInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (AuthManager.isLocked(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void pauth$preventContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (AuthManager.isLocked(this.player)) {
            ci.cancel();
            this.player.containerMenu.sendAllDataToRemote();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void pauth$preventPlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (AuthManager.isLocked(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void pauth$preventUseItem(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (AuthManager.isLocked(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void pauth$preventUseItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (AuthManager.isLocked(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void pauth$preventMovePlayer(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (AuthManager.isLocked(this.player)) {
            AuthManager.Locked locked = AuthManager.getLocked(this.player);
            if (locked != null) {
                ci.cancel();
                this.player.connection.teleport(locked.pos.x, locked.pos.y, locked.pos.z,
                        this.player.getYRot(), this.player.getXRot());
            }
        }
    }
}
