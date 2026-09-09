package ru.zaralx.pauth.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.zaralx.pauth.auth.AuthManager;

/**
 * Item pickup for players in limbo. Fabric has no pickup event, so the vanilla entry
 * point is the injection site.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void pauth$preventPickup(Player player, CallbackInfo ci) {
        if (AuthManager.isLocked(player)) {
            ci.cancel();
        }
    }
}
