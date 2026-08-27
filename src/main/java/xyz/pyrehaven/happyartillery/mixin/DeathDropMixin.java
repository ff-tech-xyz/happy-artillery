package xyz.pyrehaven.happyartillery.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xyz.pyrehaven.happyartillery.Controls;

@Mixin(ServerPlayer.class)
abstract class DeathDropMixin {
    @WrapOperation(
            method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;dropAllDeathLoot("
                            + "Lnet/minecraft/server/level/ServerLevel;"
                            + "Lnet/minecraft/world/damagesource/DamageSource;)V"),
            require = 1)
    private void happyArtillery$restoreBeforeDeathDrops(
            ServerPlayer player,
            ServerLevel level,
            DamageSource source,
            Operation<Void> original) {
        Controls.beforeDeathDrops(player, () -> original.call(player, level, source));
    }
}
