package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xyz.pyrehaven.happyartillery.Controls;

import java.util.function.Predicate;

@Mixin(ProjectileWeaponItem.class)
abstract class HeldProjectileMixin {
    @Redirect(
            method = "getHeldProjectile",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"),
            require = 2)
    private static boolean happyArtillery$excludeControlFromHeldProjectiles(
            Predicate<ItemStack> vanillaSelection, Object candidate) {
        return Controls.allowsProjectileSelection((ItemStack) candidate, vanillaSelection);
    }
}
