package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.pyrehaven.happyartillery.Controls;

@Mixin(ServerPlayer.class)
abstract class PlayerDropMixin {
    @Inject(
            method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)"
                    + "Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("RETURN"),
            require = 1)
    private void happyArtillery$consumeMarkedDrop(
            ItemStack stack,
            boolean randomThrow,
            boolean retainOwnership,
            CallbackInfoReturnable<ItemEntity> callback) {
        Controls.consumeDroppedControl(stack, callback.getReturnValue());
    }
}
