package ru.zaralx.pauth.mixin;

import ru.zaralx.pauth.i18n.Messages;
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
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.zaralx.pauth.Pauth;
import ru.zaralx.pauth.auth.AuthManager;
import ru.zaralx.pauth.auth.PremiumResolver;

/**
 * Premium (licensed) account detection on an offline-mode server.
 *
 * How it works:
 * 1. handleHello (client sent its username) is intercepted before vanilla logic runs.
 *    The decision "should we attempt premium auth for this name" may require a Mojang API
 *    call, so the packet is cancelled and resolved asynchronously; then handleHello is
 *    re-invoked with the decision cached in {@code pauth$decision}.
 * 2. A redirect on {@code MinecraftServer#usesAuthentication()} makes vanilla take its own
 *    online-mode branch (encryption request -> client key -> Mojang hasJoined check) for
 *    premium candidates only. Genuine licensed clients pass it; others are kicked by
 *    vanilla with "unverified username".
 * 3. startClientVerification is the single funnel both branches end in, and it receives the
 *    final authenticated profile. If the premium path was taken, the player is recorded as
 *    verified premium; the profile itself is swapped here (rather than after assignment) so
 *    that the field, the ban/dupe checks and the login-finished packet all see one profile.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow @Final MinecraftServer server;
    @Shadow @Final public Connection connection;

    @Shadow public abstract void disconnect(Component reason);

    @Unique private volatile PremiumResolver.Decision pauth$decision;
    @Unique private volatile boolean pauth$resolving;

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void pauth$resolvePremium(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (this.server.usesAuthentication()) return; // online-mode server: vanilla handles everything
        if (this.pauth$decision != null) return; // decision made, let vanilla run (see redirect)

        ci.cancel();
        if (this.pauth$resolving) return; // duplicate hello while resolving
        this.pauth$resolving = true;

        PremiumResolver.resolve(packet.name()).thenAccept(decision -> {
            if (!this.connection.isConnected()) return;
            if (decision == PremiumResolver.Decision.DISCONNECT) {
                this.disconnect(Component.literal(
                        Messages.t(Messages.Key.LICENSE_CHECK_FAILED)));
                return;
            }
            this.pauth$decision = decision;
            try {
                ((ServerLoginPacketListenerImpl) (Object) this).handleHello(packet);
            } catch (Exception e) {
                Pauth.LOGGER.error("PAuth: login failed for {}", packet.name(), e);
                this.disconnect(Component.literal(Messages.t(Messages.Key.INTERNAL_LOGIN_ERROR)));
            }
        });
    }

    @Redirect(method = "handleHello",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"))
    private boolean pauth$forceAuthForPremium(MinecraftServer server) {
        if (server.usesAuthentication()) return true;
        return this.pauth$decision == PremiumResolver.Decision.PREMIUM;
    }

    @ModifyVariable(method = "startClientVerification", at = @At("HEAD"), argsOnly = true)
    private GameProfile pauth$onAcceptedLogin(GameProfile profile) {
        if (this.server.usesAuthentication()) return profile;
        if (this.pauth$decision != PremiumResolver.Decision.PREMIUM) return profile;
        // complete profile == it came back from the session server
        if (profile == null || profile.getId() == null || profile.getName() == null) return profile;

        // Session server confirmed this player owns the account
        AuthManager.onPremiumVerified(profile.getName(), profile.getId());

        if (!ru.zaralx.pauth.Config.OFFLINE_UUID_FOR_PREMIUM.get()) return profile;

        GameProfile offline = new GameProfile(
                UUIDUtil.createOfflinePlayerUUID(profile.getName()),
                profile.getName());
        offline.getProperties().putAll(profile.getProperties());
        return offline;
    }
}
