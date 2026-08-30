package xyz.pyrehaven.happyartillery;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HudTest {
    private static final UUID RIDER_ID = UUID.fromString("920ac02c-8d07-4a03-918f-0b7e91ae436d");
    private static final UUID GHAST_ID = UUID.fromString("646f44ce-77ea-4bde-8a87-935850df538c");

    @Test
    void firstVisibleUpdateCreatesAndAddsExactlyOneBossBar() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);

        assertEquals(List.of("create:0.25:GREEN", "add"), access.events);
    }

    @Test
    void changedBossValuesUpdateInPlaceWithoutRemoveAddTraffic() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = RiderState.fresh();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 0L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        access.events.clear();

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 12L,
                new Hud.Snapshot(50.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 16L,
                new Hud.Snapshot(50.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("progress:0.5", "color:GOLD"), access.bossEvents());
    }

    @Test
    void configuredNormalizedWarningThresholdOverridesBiomeColors() {
        List<String> creations = new ArrayList<>();
        List<Hud.Snapshot> snapshots = List.of(
                new Hud.Snapshot(20.0, BiomeClass.COLD, Hud.Status.COOLING),
                new Hud.Snapshot(20.0, BiomeClass.NETHER, Hud.Status.NO_COOLING),
                new Hud.Snapshot(20.0, BiomeClass.END, Hud.Status.COOLING),
                new Hud.Snapshot(85.0, BiomeClass.BASE, Hud.Status.COOLING),
                new Hud.Snapshot(150.0, BiomeClass.HOT, Hud.Status.FIRING),
                new Hud.Snapshot(-5.0, BiomeClass.BASE, Hud.Status.COOLING));
        for (int index = 0; index < snapshots.size(); index++) {
            RecordingAccess access = new RecordingAccess();
            UUID rider = new UUID(0L, index + 1L);
            new Hud().update(rider, rider, GHAST_ID, RiderState.fresh(), 0L,
                    snapshots.get(index), Config.defaults(), access);
            creations.add(access.events.getFirst());
        }

        assertEquals(List.of(
                "create:0.2:BLUE",
                "create:0.2:GOLD",
                "create:0.2:GREEN",
                "create:0.85:RED",
                "create:1.0:RED",
                "create:0.0:GREEN"), creations);
    }

    @Test
    void actionBarSendsOnlyDirtyTextAtConfiguredFourTickIntervals() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = RiderState.fresh();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 0L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 1L,
                new Hud.Snapshot(26.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(26.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                new Hud.Snapshot(26.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);

        assertEquals(new ActionObservation(
                        List.of("action:GREEN:HEAT 26% · FIRING"),
                        new RiderState.HudCache(0.26, "GREEN", "HEAT 26% · FIRING", 8L)),
                new ActionObservation(access.actionEvents(), state.hudCache().orElseThrow()));
    }

    @Test
    void configuredCadenceBoundsAdversarialHudPacketsToSingleDigitsPerRiderSecond() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        access.events.clear();

        for (int tick = 1; tick < 20; tick++) {
            boolean warning = (tick / 4) % 2 == 1;
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    new Hud.Snapshot(warning ? 90.0 : 10.0,
                            warning ? BiomeClass.HOT : BiomeClass.COLD,
                            warning ? Hud.Status.FIRING : Hud.Status.COOLING),
                    Config.defaults(), access);
        }

        assertTrue(!access.events.isEmpty() && access.events.size() < 10, access.events::toString);
    }

    @Test
    void everySlidingTwentyTickWindowStaysBelowTenPresentationPacketsIncludingTickTwenty() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                Config.defaults(), access);
        assertEquals(List.of("create:0.1:BLUE", "add"), access.bossEvents());
        int[] sendsAtTick = new int[41];
        sendsAtTick[0] = 1;
        access.events.clear();

        for (int tick = 1; tick <= 40; tick++) {
            int before = access.events.size();
            boolean hot = (tick / 4) % 2 == 1;
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    new Hud.Snapshot(hot ? 80.0 : 10.0,
                            hot ? BiomeClass.HOT : BiomeClass.COLD,
                            hot ? Hud.Status.FIRING : Hud.Status.COOLING),
                    Config.defaults(), access);
            sendsAtTick[tick] = access.events.size() - before;
        }

        for (int start = 0; start <= 21; start++) {
            int packets = 0;
            for (int tick = start; tick < start + 20; tick++) {
                packets += sendsAtTick[tick];
            }
            assertTrue(packets < 10, "window " + start + ".." + (start + 19) + " sent " + packets);
        }
    }

    @Test
    void netherActionStatusOverridesSnapshotStatusAndUsesRed() {
        RecordingAccess access = new RecordingAccess();
        Hud hud = new Hud();

        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(20.0, BiomeClass.NETHER, Hud.Status.COOLING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(20.0, BiomeClass.NETHER, Hud.Status.COOLING),
                Config.defaults(), access);

        assertEquals(List.of("action:RED:NETHER · NO COOLING"), access.actionEvents());
    }

    @Test
    void warningParticlesSendOnlyOnConfiguredThresholdEntry() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = RiderState.fresh();
        int[] ticks = {0, 1, 4, 8, 9, 16, 20, 24};
        int[] heat = {84, 85, 90, 80, 85, 90, 90, 90};
        for (int index = 0; index < ticks.length; index++) {
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, ticks[index],
                    new Hud.Snapshot(heat[index], BiomeClass.BASE, Hud.Status.FIRING),
                    Config.defaults(), access);
        }

        assertEquals(List.of("particle", "particle"), access.particleEvents());
    }

    @Test
    void restartIgnoresPersistedDirtyStateAndSendsFreshUnchangedChannels() {
        RiderState persisted = new RiderState(
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                Long.MIN_VALUE, java.util.Optional.of(new RiderState.HudCache(
                0.9, "RED", "HEAT 90% · FIRING", 100L)));
        RecordingAccess access = new RecordingAccess();

        Hud hud = new Hud();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, persisted, 100L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 104L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 108L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("particle", "action:RED:HEAT 90% · FIRING"),
                access.presentationEvents());
    }

    @Test
    void sameGhastRemountSendsFreshUnchangedActionAndWarning() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        hud.remove(RIDER_ID, RIDER_ID, access);
        access.events.clear();

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 12L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("particle", "action:RED:HEAT 90% · FIRING"),
                access.presentationEvents());
    }

    @Test
    void changedGhastSessionSendsFreshUnchangedActionAndWarning() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        access.events.clear();

        UUID replacementGhast = UUID.fromString("78913731-d235-4593-8bed-ca41c5504150");
        state = hud.update(RIDER_ID, RIDER_ID, replacementGhast, state, 4L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, replacementGhast, state, 8L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, replacementGhast, state, 12L,
                new Hud.Snapshot(90.0, BiomeClass.BASE, Hud.Status.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("particle", "action:RED:HEAT 90% · FIRING"),
                access.presentationEvents());
    }

    @Test
    void actionToggleOnSendsFreshUnchangedText() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                configWithHud(true, false, 4), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                configWithHud(true, false, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);

        assertEquals(List.of("action:GREEN:HEAT 25% · COOLING"), access.actionEvents());
    }

    @Test
    void ordinaryFutureRefreshTickTreatsClockRollbackAsFreshCadence() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 100L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 50L,
                new Hud.Snapshot(30.0, BiomeClass.HOT, Hud.Status.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of("action:GOLD:HEAT 30% · FIRING"), access.actionEvents());
    }

    @Test
    void saturatedFutureRefreshTickTreatsClockRollbackAsFreshCadence() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), Long.MAX_VALUE,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, Long.MIN_VALUE,
                new Hud.Snapshot(30.0, BiomeClass.HOT, Hud.Status.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of("action:GOLD:HEAT 30% · FIRING"), access.actionEvents());
    }

    @Test
    void minimumTickValueStillHonorsCadenceAfterTheFirstRefresh() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), Long.MIN_VALUE,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, Long.MIN_VALUE,
                new Hud.Snapshot(30.0, BiomeClass.HOT, Hud.Status.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of(), access.actionEvents());
    }

    @Test
    void forwardTickOverflowCannotSuppressAChangedPresentationRefresh() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(),
                Long.MIN_VALUE + 1L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, Long.MAX_VALUE,
                new Hud.Snapshot(30.0, BiomeClass.HOT, Hud.Status.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of("action:GOLD:HEAT 30% · FIRING"), access.actionEvents());
    }

    @Test
    void persistedCacheTracksOnlyTheLastDeliveredValueOfEachChannel() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                Config.defaults(), access);
        RiderState.HudCache initial = new RiderState.HudCache(
                0.1, "BLUE", "", Long.MIN_VALUE);
        assertEquals(initial, state.hudCache().orElseThrow());

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(50.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        assertEquals(new RiderState.HudCache(0.5, "BLUE", "", Long.MIN_VALUE),
                state.hudCache().orElseThrow());

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                new Hud.Snapshot(50.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        assertEquals(new RiderState.HudCache(0.5, "GOLD", "", Long.MIN_VALUE),
                state.hudCache().orElseThrow());

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 12L,
                new Hud.Snapshot(50.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        assertEquals(new RiderState.HudCache(0.5, "GOLD", "HEAT 50% · FIRING", 12L),
                state.hudCache().orElseThrow());
    }

    @Test
    void warningCrossingOwnsNextSendAfterHeatFallsAndBossEventuallyConvergesInPlace() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                Config.defaults(), access);
        access.events.clear();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 1L,
                new Hud.Snapshot(90.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 2L,
                new Hud.Snapshot(40.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        for (int tick : List.of(4, 8, 12, 16)) {
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    new Hud.Snapshot(40.0, BiomeClass.HOT, Hud.Status.FIRING),
                    Config.defaults(), access);
        }

        assertEquals(List.of("particle", "progress:0.4", "color:GOLD",
                "action:GOLD:HEAT 40% · FIRING"), access.events);
        assertEquals(1, hud.handleCount());
    }

    @Test
    void continuouslyChangingHeatCannotStarveBossColorOrActionText() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                Config.defaults(), access);
        access.events.clear();

        for (int tick : List.of(4, 8, 12, 16, 20, 24)) {
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    new Hud.Snapshot(20.0 + tick, BiomeClass.HOT, Hud.Status.FIRING),
                    Config.defaults(), access);
        }

        assertTrue(access.events.contains("color:GOLD"), access.events::toString);
        assertTrue(access.events.stream().anyMatch(event -> event.startsWith("action:GOLD:")),
                access.events::toString);
    }

    @Test
    void repeatedWarningCrossingsCannotStarveOtherDirtyPresentationChannels() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                Config.defaults(), access);
        access.events.clear();

        for (int dueTick : List.of(4, 8, 12, 16)) {
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, dueTick - 1L,
                    new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                    Config.defaults(), access);
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, dueTick,
                    new Hud.Snapshot(90.0, BiomeClass.HOT, Hud.Status.FIRING),
                    Config.defaults(), access);
        }

        assertEquals(4, access.events.size(), access.events::toString);
        assertTrue(access.events.contains("particle"), access.events::toString);
        assertTrue(access.events.contains("progress:0.9"), access.events::toString);
        assertTrue(access.events.contains("color:RED"), access.events::toString);
        assertTrue(access.events.contains("action:RED:HEAT 90% · FIRING"), access.events::toString);
    }

    @Test
    void disablingAndReenablingBossBarRemovesThenCreatesOneFreshHandle() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = RiderState.fresh();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 0L,
                new Hud.Snapshot(10.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(10.0, BiomeClass.BASE, Hud.Status.COOLING),
                configWithHud(false, true, 4), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                new Hud.Snapshot(10.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);

        assertEquals(List.of("create:0.1:GREEN", "add", "remove", "create:0.1:GREEN", "add"),
                access.bossEvents());
        assertEquals(1, hud.handleCount());
    }

    @Test
    void bossReenableAttachmentDefersPendingWarningUntilTheNextCadence() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(10.0, BiomeClass.COLD, Hud.Status.COOLING),
                configWithHud(false, false, 4), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 5L,
                new Hud.Snapshot(90.0, BiomeClass.HOT, Hud.Status.FIRING),
                configWithHud(false, false, 4), access);
        access.events.clear();

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                new Hud.Snapshot(90.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        assertEquals(List.of("create:0.9:RED", "add"), access.events);

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 12L,
                new Hud.Snapshot(90.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);
        assertEquals(List.of("create:0.9:RED", "add", "particle"), access.events);
    }

    @Test
    void riddenGhastIdentityChangeRemovesOldHandleBeforeAddingReplacement() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);

        hud.update(RIDER_ID, RIDER_ID, UUID.fromString("78913731-d235-4593-8bed-ca41c5504150"), state, 4L,
                new Hud.Snapshot(20.0, BiomeClass.HOT, Hud.Status.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("create:0.1:GREEN", "add", "remove", "create:0.2:GOLD", "add"),
                access.bossEvents());
        assertEquals(1, hud.handleCount());
    }

    @Test
    void stableRiderIdReplacesViewerObjectExactlyOnceWithoutCreatingAnotherHandle() {
        Hud hud = new Hud();
        ViewerRecordingAccess access = new ViewerRecordingAccess();
        TestViewer first = new TestViewer("first");
        TestViewer replacement = new TestViewer("replacement");
        RiderState state = hud.update(RIDER_ID, first, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        access.events.clear();

        state = hud.update(RIDER_ID, replacement, GHAST_ID, state, 4L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        assertEquals(List.of("remove:first", "add:replacement"), access.events);

        hud.update(RIDER_ID, replacement, GHAST_ID, state, 8L,
                new Hud.Snapshot(25.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);

        assertEquals(List.of("remove:first", "add:replacement", "action:replacement"), access.events);
        assertEquals(1, hud.handleCount());
    }

    @Test
    void riderAndServerTeardownRemoveEveryBoundedHandleExactlyOnce() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        UUID passenger = UUID.fromString("a307681f-cdd8-46e0-9bdf-23d082409da3");
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        hud.update(passenger, passenger, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(10.0, BiomeClass.BASE, Hud.Status.COOLING),
                Config.defaults(), access);
        access.events.clear();

        hud.remove(RIDER_ID, RIDER_ID, access);
        hud.clear(access);

        assertEquals(new TeardownObservation(List.of("remove", "remove"), 0),
                new TeardownObservation(access.bossEvents(), hud.handleCount()));
    }

    @Test
    void minecraft262AdapterOwnsExactBossActionAndTargetedParticleBindings() throws Exception {
        Hud.class.getDeclaredMethod("update", ServerPlayer.class, HappyGhast.class,
                RiderState.class, long.class, Hud.Snapshot.class, Config.class);
        Hud.class.getDeclaredMethod("remove", ServerPlayer.class);
        Hud.class.getDeclaredMethod("clear");

        Set<String> calls = new java.util.HashSet<>();
        for (String className : List.of(
                Hud.class.getName(), Hud.class.getName() + "$MinecraftPresentationAccess")) {
            for (MethodNode method : BytecodeTestSupport.classNode(className).methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        calls.add(call.owner + "." + call.name + call.desc);
                    }
                }
            }
        }
        assertEquals(true, calls.stream().anyMatch(call -> call.startsWith(
                "net/minecraft/server/level/ServerBossEvent.<init>(Ljava/util/UUID;")));
        assertEquals(true, calls.stream().anyMatch(call -> call.contains("ServerBossEvent.addPlayer")));
        assertEquals(true, calls.stream().anyMatch(call -> call.contains("ServerBossEvent.setProgress")));
        assertEquals(true, calls.stream().anyMatch(call -> call.contains("ServerBossEvent.setColor")));
        assertEquals(true, calls.stream().anyMatch(call -> call.contains("ServerBossEvent.removePlayer")));
        assertEquals(true, calls.stream().anyMatch(call -> call.contains("ServerPlayer.sendOverlayMessage")));
        assertEquals(true, calls.stream().anyMatch(call -> call.contains("MutableComponent.withStyle")));
        assertEquals(true, calls.stream().anyMatch(call -> call.contains(
                "ServerLevel.sendParticles(Lnet/minecraft/server/level/ServerPlayer;")));
    }

    @Test
    void presentationBoundaryReceivesSnapshotWithoutGameplayMutationOrClassification() throws Exception {
        assertEquals(Set.of("createBossBar", "addViewer", "setProgress", "setColor",
                        "removeViewer", "actionBar", "warningParticle"),
                Arrays.stream(Hud.PresentationAccess.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()));

        RiderState before = new RiderState(
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(GHAST_ID),
                47L, java.util.Optional.empty());
        Hud.Snapshot snapshot = new Hud.Snapshot(63.0, BiomeClass.COLD, Hud.Status.COOLING);
        RiderState after = new Hud().update(RIDER_ID, RIDER_ID, GHAST_ID, before, 20L,
                snapshot, Config.defaults(), new RecordingAccess());
        assertEquals(List.of(before.fireStash(), before.cryStash(), before.riddenGhastId(), before.lastHandledTick()),
                List.of(after.fireStash(), after.cryStash(), after.riddenGhastId(), after.lastHandledTick()));
        assertEquals(new Hud.Snapshot(63.0, BiomeClass.COLD, Hud.Status.COOLING), snapshot);

        for (String className : List.of(
                Hud.class.getName(), Hud.class.getName() + "$MinecraftPresentationAccess")) {
            for (MethodNode method : BytecodeTestSupport.classNode(className).methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertFalse(call.owner.equals("xyz/pyrehaven/happyartillery/BiomeClass")
                                        && call.name.equals("classify"),
                                "Hud must consume the classified snapshot instead of classifying again");
                        assertFalse(Set.of("GhastState", "Heat", "Abilities", "Controls").stream()
                                        .anyMatch(owner -> call.owner.endsWith("/" + owner)),
                                "Hud must not call gameplay state or mutation owners: "
                                        + call.owner + "." + call.name);
                    }
                }
            }
        }
    }

    @Test
    void pilotAndPassengersRenderFromOnePostTransitionSnapshot() {
        Hud hud = new Hud();
        RecordingAccess access = new RecordingAccess();
        UUID passenger = UUID.fromString("7aaeb02c-60ca-4497-b666-b60ee7a044e8");
        Hud.Snapshot snapshot = new Hud.Snapshot(63.0, BiomeClass.COLD, Hud.Status.COOLING);

        List<Hud.RiderView<UUID>> updated = hud.updateAll(GHAST_ID, List.of(
                        new Hud.RiderView<>(RIDER_ID, RIDER_ID, RiderState.fresh()),
                        new Hud.RiderView<>(passenger, passenger, RiderState.fresh())),
                20L, snapshot, Config.defaults(), access);

        assertEquals(new PassengerObservation(
                        2, 0, List.of(
                                new RiderState.HudCache(0.63, "BLUE", "", Long.MIN_VALUE),
                                new RiderState.HudCache(0.63, "BLUE", "", Long.MIN_VALUE))),
                new PassengerObservation(
                        access.bossEvents().stream().filter(event -> event.equals("add")).toList().size(),
                        access.actionEvents().size(),
                        updated.stream().map(view -> view.state().hudCache().orElseThrow()).toList()));
    }

    private static Config configWithHud(boolean bossBar, boolean actionBar, int refreshTicks) {
        Config defaults = Config.defaults();
        return new Config(defaults.preset(), defaults.controls(), defaults.fire(), defaults.heat(),
                defaults.water(), defaults.overheat(), defaults.cry(),
                new Config.Hud(bossBar, actionBar, refreshTicks, defaults.hud().warningFromPercent()));
    }

    private record PassengerObservation(int bossAdds, int actions, List<RiderState.HudCache> caches) {
    }

    private record TeardownObservation(List<String> events, int handles) {
    }

    private record ActionObservation(List<String> events, RiderState.HudCache cache) {
    }

    private record TestViewer(String name) {
    }

    private static final class ViewerRecordingAccess
            implements Hud.PresentationAccess<TestViewer, String> {
        private final List<String> events = new ArrayList<>();

        @Override
        public String createBossBar(double progress, Hud.Color color) {
            events.add("create");
            return "bar";
        }

        @Override
        public void addViewer(String handle, TestViewer rider) {
            events.add("add:" + rider.name());
        }

        @Override
        public void setProgress(String handle, double progress) {
            events.add("progress");
        }

        @Override
        public void setColor(String handle, Hud.Color color) {
            events.add("color");
        }

        @Override
        public void removeViewer(String handle, TestViewer rider) {
            events.add("remove:" + rider.name());
        }

        @Override
        public void actionBar(TestViewer rider, String text, Hud.Color color) {
            events.add("action:" + rider.name());
        }

        @Override
        public void warningParticle(TestViewer rider) {
            events.add("particle:" + rider.name());
        }
    }

    private static final class RecordingAccess implements Hud.PresentationAccess<UUID, String> {
        private final List<String> events = new ArrayList<>();

        @Override
        public String createBossBar(double progress, Hud.Color color) {
            events.add("create:" + progress + ":" + color);
            return "bar";
        }

        @Override
        public void addViewer(String handle, UUID rider) {
            events.add("add");
        }

        @Override
        public void setProgress(String handle, double progress) {
            events.add("progress:" + progress);
        }

        @Override
        public void setColor(String handle, Hud.Color color) {
            events.add("color:" + color);
        }

        @Override
        public void removeViewer(String handle, UUID rider) {
            events.add("remove");
        }

        @Override
        public void actionBar(UUID rider, String text, Hud.Color color) {
            events.add("action:" + color + ":" + text);
        }

        @Override
        public void warningParticle(UUID rider) {
            events.add("particle");
        }

        private List<String> particleEvents() {
            return events.stream().filter(event -> event.equals("particle")).toList();
        }

        private List<String> actionEvents() {
            return events.stream().filter(event -> event.startsWith("action:")).toList();
        }

        private List<String> presentationEvents() {
            return events.stream()
                    .filter(event -> event.startsWith("action:") || event.equals("particle"))
                    .toList();
        }

        private List<String> bossEvents() {
            return events.stream()
                    .filter(event -> !event.startsWith("action:") && !event.equals("particle"))
                    .toList();
        }
    }
}
