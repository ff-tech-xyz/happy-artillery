package xyz.pyrehaven.happyartillery;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Unit;

import java.util.List;

/** Owns the two persistent, synchronized control marker components. */
public final class Components {
    public static final DataComponentType<Unit> FIRE_CONTROL = marker();
    public static final DataComponentType<Unit> CRY_CONTROL = marker();
    private static final List<DataComponentType<Unit>> CATALOG = List.of(FIRE_CONTROL, CRY_CONTROL);
    private static final Identifier FIRE_ID =
            Identifier.fromNamespaceAndPath("happy-artillery", "fire_control");
    private static final Identifier CRY_ID =
            Identifier.fromNamespaceAndPath("happy-artillery", "cry_control");

    private Components() {
    }

    public static synchronized void register() {
        register(FIRE_ID, FIRE_CONTROL);
        register(CRY_ID, CRY_CONTROL);
    }

    static void register(Identifier id, DataComponentType<Unit> marker) {
        var existing = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(id);
        if (existing.isEmpty()) {
            Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id, marker);
        } else if (existing.get() != marker) {
            throw new IllegalStateException("Different data component already registered for " + id);
        }
    }

    static List<DataComponentType<Unit>> catalog() {
        return CATALOG;
    }

    private static DataComponentType<Unit> marker() {
        return DataComponentType.<Unit>builder()
                .persistent(Unit.CODEC)
                .networkSynchronized(Unit.STREAM_CODEC)
                .build();
    }
}
