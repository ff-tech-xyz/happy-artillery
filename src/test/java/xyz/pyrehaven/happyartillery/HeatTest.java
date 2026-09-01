package xyz.pyrehaven.happyartillery;

import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure heat transition contract tests. */
public final class HeatTest {
    @Test
    void advanceMovesTheConsumedThroughAnchorToNow() {
        GhastState before = state(40.0, 100L, 0L);

        GhastState after = Heat.advance(
                before, 140L, new Config.HeatProfile(1.0, 0.0));

        assertEquals(state(40.0, 140L, 0L), after);
    }

    @Test
    void heatAdvancePreservesPendingDeadlineAndRiderIdentity() {
        UUID riderId = UUID.fromString("83e5e798-615a-4552-8000-74b04f756c42");
        GhastState before = new GhastState(
                40.0, 100L, 0L, 0L, 0L, OptionalLong.of(200L), Optional.of(riderId));

        GhastState after = Heat.advance(
                before, 140L, new Config.HeatProfile(1.0, 0.0));

        assertEquals(OptionalLong.of(200L), after.detonateAtTick());
        assertEquals(Optional.of(riderId), after.detonatingRiderId());
    }

    @Test
    void advanceDoesNotConsumeCoolingTimeWhenNowHasNotPassedTheAnchor() {
        GhastState before = state(40.0, 100L, 130L);

        GhastState equal = Heat.advance(
                before, 100L, new Config.HeatProfile(1.0, 2.0));
        GhastState earlier = Heat.advance(
                before, 80L, new Config.HeatProfile(1.0, 2.0));

        assertEquals(before, equal);
        assertEquals(before, earlier);
    }

    @Test
    void sequentialPerTickAdvancesConsumeEachIntervalOnce() {
        Config.HeatProfile profile = new Config.HeatProfile(1.0, 20.0);

        GhastState firstTick = Heat.advance(
                state(40.0, 100L, 100L), 101L, profile);
        GhastState secondTick = Heat.advance(firstTick, 102L, profile);

        assertEquals(state(39.0, 101L, 100L), firstTick);
        assertEquals(state(38.0, 102L, 100L), secondTick);
    }

    @Test
    void passiveCoolingStartsAfterTheLaterOfAnchorAndFiringWindow() {
        GhastState before = state(40.0, 100L, 130L);

        GhastState after = Heat.advance(
                before, 150L, new Config.HeatProfile(1.0, 2.0));

        assertEquals(state(38.0, 150L, 130L), after);
    }

    @Test
    void unloadedIntervalCatchesUpOnceAndEqualNowRepeatIsIdentical() {
        Config.HeatProfile profile = new Config.HeatProfile(1.0, 1.0);
        GhastState beforeUnload = state(80.0, 100L, 100L);

        GhastState caughtUp = Heat.advance(beforeUnload, 1_100L, profile);
        GhastState repeated = Heat.advance(caughtUp, 1_100L, profile);

        assertEquals(state(30.0, 1_100L, 100L), caughtUp);
        assertEquals(caughtUp, repeated);
    }

    @Test
    void decodedStateCatchesUpOnceAndEqualNowRepeatIsIdentical() {
        Config.HeatProfile profile = new Config.HeatProfile(1.0, 1.0);
        GhastState saved = state(80.0, 100L, 100L);
        var encoded = GhastState.CODEC.encodeStart(NbtOps.INSTANCE, saved).getOrThrow();
        GhastState decoded = GhastState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        GhastState caughtUp = Heat.advance(decoded, 1_100L, profile);
        GhastState repeated = Heat.advance(caughtUp, 1_100L, profile);

        assertEquals(state(30.0, 1_100L, 100L), caughtUp);
        assertEquals(caughtUp, repeated);
    }

    @Test
    void netherProfileLeavesHeatUnchangedDuringPassiveCooling() {
        Config defaults = Config.defaults();
        GhastState before = state(40.0, 100L, 100L);

        GhastState after = Heat.advance(
                before, 10_100L, defaults.heat().nether());

        assertEquals(state(40.0, 10_100L, 100L), after);
    }


    @Test
    void coolingClampsHeatAtZero() {
        GhastState before = state(1.0, 100L, 100L);

        GhastState after = Heat.advance(
                before, 200L, new Config.HeatProfile(1.0, 5.0));

        assertEquals(state(0.0, 200L, 100L), after);
    }

    @Test
    void addShotUsesTheSuppliedLiveProfileAndExtendsTheFiringWindow() {
        GhastState before = state(10.0, 100L, 130L);
        Config.Heat heat = heat(100.0, 1.25);

        Heat.ShotResult result = Heat.addShot(
                before, 120L, new Config.HeatProfile(7.5, 0.0), heat);

        assertEquals(state(17.5, 120L, 145L), result.state());
        assertEquals(false, result.detonates());
    }

    @Test
    void addShotCoolsToZeroBeforeAddingTheNewShot() {
        Heat.ShotResult result = Heat.addShot(
                state(1.0, 100L, 100L),
                120L,
                new Config.HeatProfile(7.5, 5.0),
                heat(100.0, 1.0));

        assertEquals(state(7.5, 120L, 140L), result.state());
        assertEquals(false, result.detonates());
    }

    @Test
    void addShotDetonatesAtEqualityAndWhenOverTheLimit() {
        Config.Heat heat = heat(100.0, 1.0);

        Heat.ShotResult equal = Heat.addShot(
                state(99.0, 100L, 100L), 100L,
                new Config.HeatProfile(1.0, 0.0), heat);
        Heat.ShotResult over = Heat.addShot(
                state(99.0, 100L, 100L), 100L,
                new Config.HeatProfile(2.0, 0.0), heat);

        assertEquals(true, equal.detonates());
        assertEquals(true, over.detonates());
    }

    @Test
    void defaultProfilesDetonateOnTheirExactSustainedShotCounts() {
        Config defaults = Config.defaults();

        assertFirstDetonationShot(defaults.heat().cold(), defaults, 143);
        assertFirstDetonationShot(defaults.heat().base(), defaults, 80);
        assertFirstDetonationShot(defaults.heat().hot(), defaults, 50);
        assertFirstDetonationShot(defaults.heat().nether(), defaults, 34);
        assertFirstDetonationShot(defaults.heat().end(), defaults, 143);
    }

    @Test
    void transitionsRejectNonFiniteHeatInputs() {
        assertThrows(IllegalArgumentException.class, () -> Heat.advance(
                state(Double.NaN, 100L, 100L), 120L,
                new Config.HeatProfile(1.0, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> Heat.advance(
                state(10.0, 100L, 100L), 120L,
                new Config.HeatProfile(1.0, Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> Heat.addShot(
                state(10.0, 100L, 100L), 120L,
                new Config.HeatProfile(Double.NaN, 1.0), heat(100.0, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> Heat.addShot(
                state(10.0, 100L, 100L), 120L,
                new Config.HeatProfile(1.0, 1.0),
                heat(Double.POSITIVE_INFINITY, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> Heat.addShot(
                state(10.0, 100L, 100L), 120L,
                new Config.HeatProfile(1.0, 1.0),
                heat(100.0, Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> Heat.advance(
                state(-1.0, 100L, 100L), 120L,
                new Config.HeatProfile(1.0, 1.0)));
        assertThrows(IllegalArgumentException.class, () -> Heat.advance(
                state(10.0, 100L, 100L), 120L,
                new Config.HeatProfile(1.0, -1.0)));
    }

    @Test
    void addShotRejectsAHeatSumThatWouldBecomeNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> Heat.addShot(
                state(Double.MAX_VALUE, 100L, 100L), 100L,
                new Config.HeatProfile(Double.MAX_VALUE, 0.0),
                heat(Double.MAX_VALUE, 1.0)));
    }

    @Test
    void ordinaryFiringWindowAtNegativeNowUsesTheMathematicalDeadline() {
        Heat.ShotResult result = Heat.addShot(
                state(1.0, -100L, -100L),
                -100L,
                new Config.HeatProfile(1.0, 0.0),
                heat(100.0, 1.25));

        assertEquals(-75L, result.state().firingWindowEndTick());
    }

    @Test
    void decimalFiringWindowUsesTheConfiguredDecimalTickCount() {
        Heat.ShotResult result = Heat.addShot(
                state(1.0, 100L, 100L),
                100L,
                new Config.HeatProfile(1.0, 0.0),
                heat(100.0, 0.1));

        assertEquals(102L, result.state().firingWindowEndTick());
    }

    @Test
    void hugeFiringWindowFromLongMinimumUsesTheMathematicalDeadlineWhenItFits() {
        Heat.ShotResult result = Heat.addShot(
                state(1.0, Long.MIN_VALUE, Long.MIN_VALUE),
                Long.MIN_VALUE,
                new Config.HeatProfile(1.0, 0.0),
                heat(100.0, Math.scalb(1.0, 59)));

        assertEquals(2_305_843_009_213_693_952L, result.state().firingWindowEndTick());
    }

    @Test
    void ordinaryFiringWindowEndingAtLongMaximumDoesNotOverflow() {
        Heat.ShotResult result = Heat.addShot(
                state(1.0, Long.MAX_VALUE - 25L, Long.MAX_VALUE - 25L),
                Long.MAX_VALUE - 25L,
                new Config.HeatProfile(1.0, 0.0),
                heat(100.0, 1.25));

        assertEquals(Long.MAX_VALUE, result.state().firingWindowEndTick());
    }

    @Test
    void hugeFiringWindowAtNegativeNowSaturatesTheDeadline() {
        Heat.ShotResult result = Heat.addShot(
                state(1.0, -100L, -100L),
                -100L,
                new Config.HeatProfile(1.0, 0.0),
                heat(100.0, Double.MAX_VALUE));

        assertEquals(Long.MAX_VALUE, result.state().firingWindowEndTick());
    }

    @Test
    void firingWindowDeadlineSaturatesInsteadOfWrappingTheSavedTickDomain() {
        Heat.ShotResult result = Heat.addShot(
                state(1.0, Long.MAX_VALUE - 10L, Long.MAX_VALUE - 10L),
                Long.MAX_VALUE - 10L,
                new Config.HeatProfile(1.0, 0.0),
                heat(100.0, Double.MAX_VALUE));

        assertEquals(Long.MAX_VALUE, result.state().firingWindowEndTick());
    }

    private static void assertFirstDetonationShot(
            Config.HeatProfile profile,
            Config config,
            int expectedShot) {
        GhastState state = state(0.0, 0L, 0L);
        long shotIntervalTicks = (long) Math.ceil(config.fire().shotCooldownSeconds() * 20.0);
        for (int shot = 1; shot <= expectedShot; shot++) {
            Heat.ShotResult result = Heat.addShot(
                    state,
                    shot * shotIntervalTicks,
                    profile,
                    config.heat());
            assertEquals(shot == expectedShot, result.detonates(), "shot " + shot);
            state = result.state();
        }
    }

    private static GhastState state(double heat, long anchor, long firingWindowEnd) {
        return new GhastState(heat, anchor, firingWindowEnd, 250L, 300L,
                OptionalLong.empty());
    }


    private static Config.Heat heat(double limit, double firingWindowSeconds) {
        Config.Heat defaults = Config.defaults().heat();
        return new Config.Heat(
                limit,
                firingWindowSeconds,
                defaults.cold(),
                defaults.base(),
                defaults.hot(),
                defaults.nether(),
                defaults.end(),
                defaults.coldMaxTemperature(),
                defaults.hotMinTemperature(),
                defaults.unknownDimensionUsesTemperature());
    }
}
