package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.pyrehaven.happyartillery.Controls;

@Mixin(BundleContents.class)
abstract class BundleContentsMixin {
    @Inject(
            method = "canItemBeInBundle(Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private static void happyArtillery$blockControlInsertion(
            ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        if (Controls.blocksBundleInsertion(stack)) {
            callback.setReturnValue(false);
        }
    }
}
