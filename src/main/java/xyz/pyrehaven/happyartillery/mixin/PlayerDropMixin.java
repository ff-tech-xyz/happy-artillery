package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.pyrehaven.happyartillery.Controls;

@Mixin(ServerPlayer.class)
abstract class PlayerDropMixin {
    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void happyArtillery$protectSelectedControl(boolean dropAll, CallbackInfo callback) {
        if (Controls.shouldCancelSelectedSlotDrop((ServerPlayer) (Object) this)) {
            callback.cancel();
        }
    }
}
