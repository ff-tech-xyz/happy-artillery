package xyz.pyrehaven.happyartillery.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xyz.pyrehaven.happyartillery.Controls;

import java.util.Set;

@Mixin(AbstractContainerMenu.class)
abstract class SlotGuardMixin {
    @Shadow private int quickcraftType;
    @Shadow private int quickcraftStatus;
    @Shadow @Final private Set<Slot> quickcraftSlots;

    @Inject(
            method = "clicked(IILnet/minecraft/world/inventory/ContainerInput;"
                    + "Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void happyArtillery$protectControlSlots(
            int slotId,
            int button,
            ContainerInput input,
            Player player,
            CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer
                && Controls.shouldCancelContainerMutation(
                        (AbstractContainerMenu) (Object) this, slotId, button, input, serverPlayer,
                        new Controls.QuickCraftSnapshot(
                                quickcraftStatus, quickcraftType, quickcraftSlots))) {
            callback.cancel();
        }
    }
}
