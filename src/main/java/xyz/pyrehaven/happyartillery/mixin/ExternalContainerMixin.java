package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import xyz.pyrehaven.happyartillery.Controls;

@Mixin(Slot.class)
abstract class ExternalContainerMixin {
    @ModifyVariable(
            method = "set(Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"), argsOnly = true, require = 1)
    private ItemStack happyArtillery$transformExternalControlWrite(ItemStack stack) {
        return Controls.transformExternalControlWrite((Slot) (Object) this, stack);
    }
}
