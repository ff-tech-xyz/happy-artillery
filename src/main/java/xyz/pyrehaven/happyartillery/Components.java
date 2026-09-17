package xyz.pyrehaven.happyartillery;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sole owner of generated-control identity in vanilla custom data. */
public final class Components {
    private static final String TYPE_TAG = "happy-artillery:control_type";
    private static final String OWNER_TAG = "happy-artillery:control_owner";
    private static final String RIDE_TAG = "happy-artillery:control_ride";

    private Components() {
    }

    enum Control {
        FIRE("fire"), CRY("cry");
        private final String value;
        Control(String value) { this.value = value; }
        static Optional<Control> parse(String value) {
            return java.util.Arrays.stream(values()).filter(control -> control.value.equals(value)).findFirst();
        }
    }

    record Marker(Control control, UUID ownerId, UUID rideId) {
        Marker {
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(rideId, "rideId");
        }
    }

    sealed interface MarkerRead permits Absent, Valid, Malformed {
        Optional<Marker> marker();

        default boolean isAbsent() {
            return this instanceof Absent;
        }
    }

    enum Absent implements MarkerRead {
        INSTANCE;
        @Override public Optional<Marker> marker() { return Optional.empty(); }
    }

    record Valid(Marker value) implements MarkerRead {
        Valid { Objects.requireNonNull(value, "value"); }
        @Override public Optional<Marker> marker() { return Optional.of(value); }
    }

    enum Malformed implements MarkerRead {
        INSTANCE;
        @Override public Optional<Marker> marker() { return Optional.empty(); }
    }

    static void mark(ItemStack stack, Marker marker) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(marker, "marker");
        CustomData existing = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag data = existing == null ? new CompoundTag() : existing.copyTag();
        data.putString(TYPE_TAG, marker.control().value);
        data.putString(OWNER_TAG, marker.ownerId().toString());
        data.putString(RIDE_TAG, marker.rideId().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    static MarkerRead marker(ItemStack stack) {
        return marker(stack, CustomData::copyTag);
    }

    static MarkerRead marker(ItemStack stack, CustomDataReader reader) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(reader, "reader");
        if (stack.isEmpty() || !stack.has(DataComponents.CUSTOM_DATA)) {
            return Absent.INSTANCE;
        }
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag data = reader.copy(Objects.requireNonNull(customData, "customData"));
        boolean attempted = data.contains(TYPE_TAG) || data.contains(OWNER_TAG) || data.contains(RIDE_TAG);
        if (!attempted) {
            return Absent.INSTANCE;
        }
        Optional<String> type = data.getString(TYPE_TAG);
        Optional<String> owner = data.getString(OWNER_TAG);
        Optional<String> ride = data.getString(RIDE_TAG);
        if (type.isEmpty() || owner.isEmpty() || ride.isEmpty()) {
            return Malformed.INSTANCE;
        }
        try {
            return Control.parse(type.get())
                    .<MarkerRead>map(control -> new Valid(
                            new Marker(control, UUID.fromString(owner.get()), UUID.fromString(ride.get()))))
                    .orElse(Malformed.INSTANCE);
        } catch (IllegalArgumentException malformedUuid) {
            return Malformed.INSTANCE;
        }
    }

    @FunctionalInterface
    interface CustomDataReader {
        CompoundTag copy(CustomData customData);
    }

    static boolean matches(ItemStack stack, UUID ownerId, UUID rideId) {
        return marker(stack).marker().filter(marker -> marker.ownerId().equals(ownerId)
                && marker.rideId().equals(rideId)).isPresent();
    }
}
