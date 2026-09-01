package xyz.pyrehaven.happyartillery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Pure authority for heat transitions in saved Overworld game time. */
public final class Heat {
    private Heat() {
    }

    public static GhastState advance(
            GhastState state,
            long now,
            Config.HeatProfile profile) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(profile, "profile");
        requireNonNegativeFinite("state.heat", state.heat());
        requirePositiveFinite("profile.heatPerShot", profile.heatPerShot());
        requireNonNegativeFinite("profile.coolPerSecond", profile.coolPerSecond());
        if (now <= state.heatAnchorTick()) {
            return state;
        }

        long coolingStart = Math.max(state.heatAnchorTick(), state.firingWindowEndTick());
        double elapsedTicks = now > coolingStart ? (double) now - coolingStart : 0.0;
        double cooledHeat = Math.min(
                state.heat(),
                Math.max(0.0, state.heat() - profile.coolPerSecond() * elapsedTicks / 20.0));
        return new GhastState(
                cooledHeat,
                now,
                state.firingWindowEndTick(),
                state.fireReadyTick(),
                state.cryReadyTick(),
                state.detonateAtTick(),
                state.detonatingRiderId());
    }

    public static ShotResult addShot(
            GhastState state,
            long now,
            Config.HeatProfile profile,
            Config.Heat heat) {
        Objects.requireNonNull(heat, "heat");
        requirePositiveFinite("heat.limit", heat.limit());
        requireNonNegativeFinite("heat.firingWindowSeconds", heat.firingWindowSeconds());
        GhastState advanced = advance(state, now, profile);
        double shotHeat = advanced.heat() + profile.heatPerShot();
        requireFinite("shot heat", shotHeat);
        long shotWindowEnd = firingWindowDeadline(now, heat.firingWindowSeconds());
        long firingWindowEnd = Math.max(advanced.firingWindowEndTick(), shotWindowEnd);
        GhastState updated = new GhastState(
                shotHeat,
                advanced.heatAnchorTick(),
                firingWindowEnd,
                advanced.fireReadyTick(),
                advanced.cryReadyTick(),
                advanced.detonateAtTick(),
                advanced.detonatingRiderId());
        return new ShotResult(updated, shotHeat >= heat.limit());
    }

    private static long firingWindowDeadline(long now, double seconds) {
        double windowTickCount = seconds * 20.0;
        if (!Double.isFinite(windowTickCount)) {
            return Long.MAX_VALUE;
        }
        BigDecimal deadline = BigDecimal.valueOf(now).add(
                new BigDecimal(windowTickCount).setScale(0, RoundingMode.CEILING));
        return deadline.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE
                : deadline.longValueExact();
    }

    private static void requirePositiveFinite(String name, double value) {
        requireFinite(name, value);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegativeFinite(String name, double value) {
        requireFinite(name, value);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    public record ShotResult(GhastState state, boolean detonates) {
    }
}
