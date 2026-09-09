package ru.zaralx.pauth.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.zaralx.pauth.auth.AuthManager;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerEntityMixin {

    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"), cancellable = true)
    private void pauth$preventDrop(ItemStack itemStack, boolean throwRandomly, boolean retainOwnership, CallbackInfoReturnable<ItemEntity> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (AuthManager.isLocked(player)) {
            // Callers hand over a stack they already took out of the inventory, so it has to
            // go back - returning null on its own would delete it.
            if (!itemStack.isEmpty()) player.getInventory().add(itemStack);
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
    private void pauth$preventDamage(net.minecraft.server.level.ServerLevel level, DamageSource damageSource, float amount, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (AuthManager.isLocked(player)) {
            cir.setReturnValue(false);
            return;
        }
        if (damageSource.getEntity() instanceof ServerPlayer attacker && AuthManager.isLocked(attacker)) {
            cir.setReturnValue(false);
        }
    }
}
