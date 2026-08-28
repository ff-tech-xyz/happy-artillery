package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable persistent Happy Ghast state in saved Overworld game-time ticks. */
public record GhastState(
        double heat,
        long heatAnchorTick,
        long firingWindowEndTick,
        long fireReadyTick,
        long cryReadyTick,
        OptionalLong detonateAtTick) {
    private static AttachmentType<GhastState> type;

    public static final Codec<GhastState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("heat").forGetter(GhastState::heat),
            Codec.LONG.fieldOf("heat_anchor_tick").forGetter(GhastState::heatAnchorTick),
            Codec.LONG.fieldOf("firing_window_end_tick").forGetter(GhastState::firingWindowEndTick),
            Codec.LONG.fieldOf("fire_ready_tick").forGetter(GhastState::fireReadyTick),
            Codec.LONG.fieldOf("cry_ready_tick").forGetter(GhastState::cryReadyTick),
            Codec.LONG.optionalFieldOf("detonate_at_tick").forGetter(GhastState::boxedDetonateAtTick)
    ).apply(instance, GhastState::fromCodec));

    public GhastState {
        Objects.requireNonNull(detonateAtTick, "detonateAtTick");
    }

    public static GhastState fresh() {
        return new GhastState(0.0, 0L, 0L, 0L, 0L, OptionalLong.empty());
    }

    static synchronized AttachmentType<GhastState> register() {
        if (type == null) {
            type = AttachmentRegistry.createPersistent(
                    Identifier.fromNamespaceAndPath("happy-artillery", "ghast_state"), CODEC);
        }
        return type;
    }

    static GhastState replace(AttachmentTarget target, GhastState newState) {
        return target.setAttached(register(), newState);
    }

    private Optional<Long> boxedDetonateAtTick() {
        return detonateAtTick.isPresent()
                ? Optional.of(detonateAtTick.getAsLong())
                : Optional.empty();
    }

    private static GhastState fromCodec(
            double heat,
            long heatAnchorTick,
            long firingWindowEndTick,
            long fireReadyTick,
            long cryReadyTick,
            Optional<Long> detonateAtTick) {
        return new GhastState(
                heat,
                heatAnchorTick,
                firingWindowEndTick,
                fireReadyTick,
                cryReadyTick,
                detonateAtTick.isPresent()
                        ? OptionalLong.of(detonateAtTick.orElseThrow())
                        : OptionalLong.empty());
    }
}
