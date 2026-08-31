package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.pyrehaven.happyartillery.Controls;

@Mixin(Slot.class)
abstract class ExternalContainerMixin {
    @Inject(method = "setChanged()V", at = @At("HEAD"), require = 1)
    private void happyArtillery$consumeExternalControl(CallbackInfo callback) {
        Controls.consumeExternalControl((Slot) (Object) this);
    }
}
