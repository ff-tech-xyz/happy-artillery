package xyz.pyrehaven.happyartillery;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Objects;

/** Owns control marker encoding and identity on vanilla item custom data. */
public final class Components {
    private static final String MARKER_TAG = "happy-artillery:control";

    private Components() {
    }

    enum Control {
        FIRE("fire"),
        CRY("cry");

        private final String value;

        Control(String value) {
            this.value = value;
        }
    }

    static void mark(ItemStack stack, Control control) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(control, "control");
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag marker = existing == null ? new CompoundTag() : existing.copyTag();
        marker.putString(MARKER_TAG, control.value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
    }

    static boolean is(ItemStack stack, Control control) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(control, "control");
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null
                && data.copyTag().getString(MARKER_TAG).filter(control.value::equals).isPresent();
    }
}
