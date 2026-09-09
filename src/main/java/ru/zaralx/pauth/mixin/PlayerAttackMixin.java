package ru.zaralx.pauth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.zaralx.pauth.auth.AuthManager;

/**
 * attack(Entity) is declared in Player, not overridden in ServerPlayer,
 * so it must be targeted here rather than in ServerPlayerEntityMixin.
 */
@Mixin(Player.class)
public abstract class PlayerAttackMixin {

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void pauth$preventAttack(Entity target, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self instanceof ServerPlayer player && AuthManager.isLocked(player)) {
            ci.cancel();
        }
    }
}
