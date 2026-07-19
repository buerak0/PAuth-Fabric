package ru.zaralx.pauth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.zaralx.pauth.Pauth;
import ru.zaralx.pauth.auth.AuthManager;
import ru.zaralx.pauth.auth.PremiumResolver;

import javax.annotation.Nullable;

/**
 * Premium (licensed) account detection on an offline-mode server.
 *
 * How it works:
 * 1. handleHello (client sent its username) is intercepted before vanilla logic runs.
 *    The decision "should we attempt premium auth for this name" may require a Mojang API
 *    call, so the packet is cancelled and resolved asynchronously; then handleHello is
 *    re-invoked with the decision cached in {@code pauth$premiumAttempt}.
 * 2. A redirect on {@code MinecraftServer#usesAuthentication()} makes vanilla take its own
 *    online-mode branch (encryption request -> client key -> Mojang hasJoined check) for
 *    premium candidates only. Genuine licensed clients pass it; others are kicked by
 *    vanilla with "unverified username".
 * 3. handleAcceptedLogin fires only after a successful login; if the premium path was taken
 *    and the profile is complete (came from the session server), the player is recorded as
 *    verified premium.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow @Final MinecraftServer server;
    @Shadow @Final public Connection connection;
    @Shadow @Nullable GameProfile gameProfile;

    @Shadow public abstract void disconnect(Component reason);

    @Unique private volatile Boolean pauth$premiumAttempt;
    @Unique private volatile boolean pauth$resolving;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void pauth$resolvePremium(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (this.server.usesAuthentication()) return; // online-mode server: vanilla handles everything
        if (this.pauth$premiumAttempt != null) return; // decision made, let vanilla run (see redirect)

        ci.cancel();
        if (this.pauth$resolving) return; // duplicate hello while resolving
        this.pauth$resolving = true;

        PremiumResolver.shouldAttemptPremium(packet.name()).thenAccept(premium -> {
            this.pauth$premiumAttempt = premium;
            if (!this.connection.isConnected()) return;
            try {
                ((ServerLoginPacketListenerImpl) (Object) this).handleHello(packet);
            } catch (Exception e) {
                Pauth.LOGGER.error("PAuth: login failed for {}", packet.name(), e);
                this.disconnect(Component.literal("PAuth: internal login error"));
            }
        });
    }

    @Redirect(method = "handleHello",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean pauth$forceAuthForPremium(MinecraftServer server) {
        if (server.usesAuthentication()) return true;
        return this.pauth$premiumAttempt != null && this.pauth$premiumAttempt;
    }

    @Inject(method = "handleAcceptedLogin", at = @At("HEAD"))
    private void pauth$onAcceptedLogin(CallbackInfo ci) {
        if (this.server.usesAuthentication()) return;
        boolean attempted = this.pauth$premiumAttempt != null && this.pauth$premiumAttempt;
        if (!attempted || this.gameProfile == null || !this.gameProfile.isComplete()) return;

        // Session server confirmed this player owns the account
        AuthManager.onPremiumVerified(this.gameProfile.getName(), this.gameProfile.getId());

        if (ru.zaralx.pauth.Config.OFFLINE_UUID_FOR_PREMIUM.get()) {
            GameProfile offline = new GameProfile(
                    UUIDUtil.createOfflinePlayerUUID(this.gameProfile.getName()), this.gameProfile.getName());
            offline.getProperties().putAll(this.gameProfile.getProperties());
            this.gameProfile = offline;
        }
    }
}
