package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xyz.pyrehaven.happyartillery.Controls;

import java.util.function.Predicate;

@Mixin(Player.class)
abstract class PlayerProjectileMixin {
    @Redirect(
            method = "getProjectile",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"),
            require = 1)
    private boolean happyArtillery$excludeControlFromInventoryProjectiles(
            Predicate<ItemStack> vanillaSelection, Object candidate) {
        return Controls.allowsProjectileSelection((ItemStack) candidate, vanillaSelection);
    }
}
