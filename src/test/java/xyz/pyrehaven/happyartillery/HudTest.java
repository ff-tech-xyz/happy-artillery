package xyz.pyrehaven.happyartillery;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HudTest {
    @Test
    void snapshotCarriesOneTypedModeWithoutBiomeStatusOrCompatibilityConstructors() {
        assertTrue(Hud.Mode.class.isSealed());
        assertEquals(Set.of(Hud.Firing.class, Hud.Cooling.class),
                Set.of(Hud.Mode.class.getPermittedSubclasses()));
        assertEquals(List.of(double.class),
                Arrays.stream(Hud.Cooling.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getType).toList());
        assertEquals(1, Hud.Snapshot.class.getDeclaredConstructors().length);
        assertEquals(List.of(double.class, Hud.Mode.class, java.util.Optional.class, boolean.class),
                Arrays.stream(Hud.Snapshot.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getType).toList());
        assertFalse(Arrays.stream(Hud.Snapshot.class.getRecordComponents())
                .anyMatch(component -> component.getType() == BiomeClass.class));
        assertFalse(Arrays.stream(Hud.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("Status")));
        assertThrows(IllegalArgumentException.class, () -> new Hud.Cooling(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new Hud.Cooling(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Hud.Cooling(Double.POSITIVE_INFINITY));
    }

    @Test
    void effectiveCoolingTextAndConfiguredBandsUseExactRatesAndLowerEquality() {
        Config config = configWithCooling(new Config.Cooling(
                "PAUSED", Config.Color.BLUE,
                0.5, Config.Color.RED,
                1.0, Config.Color.GOLD,
                Config.Color.GREEN));

        assertEquals(List.of(
                        "action:BLUE:HEAT 25% · PAUSED",
                        "action:RED:HEAT 25% · COOLING 0.3333333333333333/s",
                        "action:RED:HEAT 25% · COOLING 0.5/s",
                        "action:GOLD:HEAT 25% · COOLING 0.75/s",
                        "action:GOLD:HEAT 25% · COOLING 1/s",
                        "action:GREEN:HEAT 25% · COOLING 5/s"),
                List.of(0.0, 0.3333333333333333, 0.5, 0.75, 1.0, 5.0).stream()
                        .map(rate -> deliveredAction(new Hud.Cooling(rate), config, 25.0))
                        .toList());
        assertEquals("action:GREEN:HEAT 25% · FIRING",
                deliveredAction(Hud.Firing.FIRING, config, 25.0));
        assertEquals("action:RED:HEAT 95% · COOLING 5/s",
                deliveredAction(new Hud.Cooling(5.0), config, 95.0));
    }

    @Test
    void blankConfiguredNoCoolingTextIsPresentedWithoutFallback() {
        Config config = configWithCooling(new Config.Cooling(
                "", Config.Color.BLUE,
                0.5, Config.Color.RED,
                1.0, Config.Color.GOLD,
                Config.Color.GREEN));

        assertEquals("action:BLUE:HEAT 25% · ",
                deliveredAction(new Hud.Cooling(0.0), config, 25.0));
    }

    private static final UUID RIDER_ID = UUID.fromString("920ac02c-8d07-4a03-918f-0b7e91ae436d");
    private static final UUID GHAST_ID = UUID.fromString("646f44ce-77ea-4bde-8a87-935850df538c");

    @Test
    void sessionStorageOwnsTypedViewerAndHandleAtTheClassBoundary() throws Exception {
        assertEquals(List.of("R", "H"), Arrays.stream(Hud.class.getTypeParameters())
                .map(java.lang.reflect.TypeVariable::getName).toList());

        Class<?> session = Class.forName(Hud.class.getName() + "$Session");
        assertEquals(List.of("R", "H"), Arrays.stream(session.getTypeParameters())
                .map(java.lang.reflect.TypeVariable::getName).toList());
        assertEquals("R", session.getDeclaredField("viewer").getGenericType().getTypeName());
        assertEquals("H", session.getDeclaredField("display").getGenericType().getTypeName());
        assertEquals("java.util.Map<java.lang.Object, xyz.pyrehaven.happyartillery.Hud$Session<R, H>>",
                Hud.class.getDeclaredField("sessions").getGenericType().getTypeName());
        assertEquals(Set.of(Hud.ViewerAccess.class),
                Set.of(Hud.PresentationAccess.class.getInterfaces()));
        assertEquals(Set.of("removeViewer"), Arrays.stream(Hud.ViewerAccess.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()));
        assertEquals(false, Arrays.stream(Hud.class.getDeclaredClasses())
                .anyMatch(type -> type.getSimpleName().equals("Display")));
    }

    @Test
    void firstVisibleUpdateCreatesAndAddsExactlyOneBossBar() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);

        assertEquals(List.of("create:0.25:GREEN", "add"), access.events);
    }

    @Test
    void changedBossValuesUpdateInPlaceWithoutRemoveAddTraffic() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = RiderState.fresh();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 12L,
                snapshot(50.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 17L,
                snapshot(50.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("progress:0.5"), access.bossEvents());
    }

    @Test
    void changedGhastCustomNameUpdatesTheExistingBossBarOnTheAuxiliaryCadence() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();
        access.currentName = "Captain Cloud";

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        assertEquals(List.of(), access.bossEvents());

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 5L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        assertEquals(List.of("name:Captain Cloud"), access.bossEvents());
    }

    @Test
    void simultaneousWarningBossAndNameChangesConvergeWithoutNameStarvation() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();
        access.currentName = "Captain Cloud";

        for (int tick : new int[]{5, 10, 15, 20}) {
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                    Config.defaults(), access);
        }

        assertEquals(List.of(
                "particle", "progress:0.9", "color:RED", "name:Captain Cloud"),
                access.events.stream().filter(event -> !event.startsWith("action:")).toList());
    }

    @Test
    void configuredNormalizedWarningThresholdOverridesBiomeColors() {
        List<String> creations = new ArrayList<>();
        List<Hud.Snapshot> snapshots = List.of(
                snapshot(20.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                snapshot(20.0, BiomeClass.NETHER, new Hud.Cooling(0.0)),
                snapshot(20.0, BiomeClass.END, new Hud.Cooling(1.0)),
                snapshot(85.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                snapshot(150.0, BiomeClass.HOT, Hud.Firing.FIRING),
                snapshot(-5.0, BiomeClass.BASE, new Hud.Cooling(1.0)));
        for (int index = 0; index < snapshots.size(); index++) {
            RecordingAccess access = new RecordingAccess();
            UUID rider = new UUID(0L, index + 1L);
            new Hud<UUID, String>(access).update(rider, rider, GHAST_ID, RiderState.fresh(), 0L,
                    snapshots.get(index), Config.defaults(), access);
            creations.add(access.events.getFirst());
        }

        assertEquals(List.of(
                "create:0.2:BLUE",
                "create:0.2:RED",
                "create:0.2:GREEN",
                "create:0.85:RED",
                "create:1.0:RED",
                "create:0.0:GREEN"), creations);
    }

    @Test
    void actionBarSendsOnlyDirtyTextAtConfiguredFourTickIntervals() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = RiderState.fresh();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 1L,
                snapshot(26.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(26.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                snapshot(26.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);

        assertEquals(new ActionObservation(
                        List.of("action:GREEN:HEAT 26% · FIRING"),
                        new RiderState.HudCache(0.26, "GREEN", "HEAT 26% · FIRING", 4L)),
                new ActionObservation(access.actionEvents(), state.hudCache().orElseThrow()));
    }

    @Test
    void unchangedNormalStatusResendsWhilePilotHoldsFireControl() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        Hud.Snapshot firing = snapshot(
                25.0, BiomeClass.BASE, Hud.Firing.FIRING,
                java.util.Optional.of(controlSnapshot(
                        Controls.ControlLocation.HAND_ACCESSIBLE,
                        Controls.ControlLocation.HAND_ACCESSIBLE)),
                true);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                firing, Config.defaults(), access);

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                firing, Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                firing, Config.defaults(), access);

        assertEquals(List.of(
                "action:GREEN:HEAT 25% · FIRING",
                "action:GREEN:HEAT 25% · FIRING"),
                access.actionEvents());
    }

    @Test
    void configuredCadenceBoundsAdversarialHudPacketsToSingleDigitsPerRiderSecond() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();

        for (int tick = 1; tick < 20; tick++) {
            boolean warning = (tick / 4) % 2 == 1;
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(warning ? 90.0 : 10.0,
                            warning ? BiomeClass.HOT : BiomeClass.COLD,
                            warning ? Hud.Firing.FIRING : new Hud.Cooling(1.0)),
                    Config.defaults(), access);
        }

        assertTrue(!access.events.isEmpty() && access.events.size() < 10, access.events::toString);
    }

    @Test
    void everySlidingTwentyTickWindowStaysBelowTenPresentationPacketsIncludingTickTwenty() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        assertEquals(List.of("create:0.1:BLUE", "add"), access.bossEvents());
        int[] sendsAtTick = new int[41];
        sendsAtTick[0] = 1;
        access.events.clear();

        for (int tick = 1; tick <= 40; tick++) {
            int before = access.events.size();
            boolean hot = (tick / 4) % 2 == 1;
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(hot ? 80.0 : 10.0,
                            hot ? BiomeClass.HOT : BiomeClass.COLD,
                            hot ? Hud.Firing.FIRING : new Hud.Cooling(1.0)),
                    Config.defaults(), access);
            sendsAtTick[tick] = access.events.size() - before;
        }

        for (int start = 0; start <= 21; start++) {
            int packets = 0;
            for (int tick = start; tick < start + 20; tick++) {
                packets += sendsAtTick[tick];
            }
            assertTrue(packets < 10, "window " + start + ".." + (start + 19)
                    + " sent " + packets + ": " + java.util.Arrays.toString(sendsAtTick));
        }
    }

    @Test
    void separatedActionAndAuxiliaryCadencesStayBelowTenForReviewCounterexample() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        Controls.InventorySnapshot controls = controlSnapshot(
                Controls.ControlLocation.HAND_ACCESSIBLE,
                Controls.ControlLocation.HAND_ACCESSIBLE);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.COLD, Hud.Firing.FIRING,
                        java.util.Optional.of(controls), true),
                Config.defaults(), access);
        access.events.clear();
        int[] sendsAtTick = new int[31];

        for (int tick = 1; tick <= 30; tick++) {
            double heat = tick == 5 || tick >= 18 ? 90.0 : tick >= 6 && tick <= 10 ? 50.0 : 10.0;
            BiomeClass biomeClass = heat >= 50.0 ? BiomeClass.HOT : BiomeClass.COLD;
            int before = access.events.size();
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(heat, biomeClass, Hud.Firing.FIRING,
                            java.util.Optional.of(controls), true),
                    Config.defaults(), access);
            sendsAtTick[tick] = access.events.size() - before;
        }

        assertEquals(7, access.actionEvents().size());
        assertEquals(List.of("particle", "particle"), access.particleEvents());
        assertEquals(new RiderState.HudCache(
                        0.9, "RED", "HEAT 90% · FIRING", 28L),
                state.hudCache().orElseThrow());
        for (int start = 1; start <= 11; start++) {
            int packets = Arrays.stream(sendsAtTick, start, start + 20).sum();
            assertTrue(packets < 10, "window " + start + ".." + (start + 19)
                    + " sent " + packets + ": " + Arrays.toString(sendsAtTick));
        }
    }

    @Test
    void netherActionStatusOverridesSnapshotStatusAndUsesRed() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);

        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(20.0, BiomeClass.NETHER, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(20.0, BiomeClass.NETHER, new Hud.Cooling(1.0)),
                Config.defaults(), access);

        assertEquals(List.of("action:RED:HEAT 20% · NO COOLING"), access.actionEvents());
    }

    @Test
    void pilotControlWarningsUseExactPriorityWordingAndNeverLeakToPassengers() {
        List<Controls.InventorySnapshot> controls = List.of(
                controlSnapshot(Controls.ControlLocation.MISSING,
                        Controls.ControlLocation.MAIN_INVENTORY_ONLY),
                controlSnapshot(Controls.ControlLocation.HAND_ACCESSIBLE,
                        Controls.ControlLocation.MAIN_INVENTORY_ONLY),
                controlSnapshot(Controls.ControlLocation.MAIN_INVENTORY_ONLY,
                        Controls.ControlLocation.MAIN_INVENTORY_ONLY),
                controlSnapshot(Controls.ControlLocation.HAND_ACCESSIBLE,
                        Controls.ControlLocation.HAND_ACCESSIBLE));
        List<String> expected = List.of(
                "action:RED:CONTROL MISSING · DISMOUNT AND REMOUNT",
                "action:GOLD:CONTROL IN INVENTORY",
                "action:GOLD:CONTROLS IN INVENTORY",
                "action:GREEN:HEAT 25% · COOLING 1/s");

        for (int index = 0; index < controls.size(); index++) {
            RecordingAccess access = new RecordingAccess();
            Hud<UUID, String> hud = new Hud<>(access);
            RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                    snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0),
                            java.util.Optional.of(controls.get(index))),
                    Config.defaults(), access);
            assertEquals(index < 3 ? List.of(expected.get(index)) : List.of(),
                    access.actionEvents(), "fresh case " + index);
            hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                    snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0),
                            java.util.Optional.of(controls.get(index))),
                    Config.defaults(), access);
            assertEquals(index < 3
                            ? List.of(expected.get(index), expected.get(index))
                            : List.of(expected.get(index)),
                    access.actionEvents(), "repeat case " + index);
        }

        RecordingAccess passengerAccess = new RecordingAccess();
        Hud<UUID, String> passengerHud = new Hud<>(passengerAccess);
        RiderState passenger = passengerHud.update(RIDER_ID, RIDER_ID, GHAST_ID,
                RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), passengerAccess);
        passengerHud.update(RIDER_ID, RIDER_ID, GHAST_ID, passenger, 4L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), passengerAccess);
        assertEquals(List.of("action:GREEN:HEAT 25% · COOLING 1/s"), passengerAccess.actionEvents());
    }

    @Test
    void unchangedControlWarningResendsAtConfiguredCadence() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        Hud.Snapshot warning = snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0),
                java.util.Optional.of(controlSnapshot(
                        Controls.ControlLocation.MISSING,
                        Controls.ControlLocation.HAND_ACCESSIBLE)));
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                warning, Config.defaults(), access);

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 1L,
                warning, Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                warning, Config.defaults(), access);

        assertEquals(List.of(
                "action:RED:CONTROL MISSING · DISMOUNT AND REMOUNT",
                "action:RED:CONTROL MISSING · DISMOUNT AND REMOUNT"),
                access.actionEvents());
    }

    @Test
    void unchangedControlWarningResendsWhenThresholdEntryIsDue() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(90.0, BiomeClass.HOT, Hud.Firing.FIRING,
                        java.util.Optional.of(controlSnapshot(
                                Controls.ControlLocation.MISSING,
                                Controls.ControlLocation.HAND_ACCESSIBLE))),
                configWithWarningThreshold(95), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(90.0, BiomeClass.HOT, Hud.Firing.FIRING,
                        java.util.Optional.of(controlSnapshot(
                                Controls.ControlLocation.MISSING,
                                Controls.ControlLocation.HAND_ACCESSIBLE))),
                Config.defaults(), access);

        assertTrue(access.presentationEvents().contains(
                "action:RED:CONTROL MISSING · DISMOUNT AND REMOUNT"), access.events::toString);
    }

    @Test
    void unchangedActiveFireStatusResendsWhenThresholdEntryIsDue() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        Hud.Snapshot activeFire = snapshot(
                90.0, BiomeClass.HOT, Hud.Firing.FIRING,
                java.util.Optional.of(controlSnapshot(
                        Controls.ControlLocation.HAND_ACCESSIBLE,
                        Controls.ControlLocation.HAND_ACCESSIBLE)), true);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                activeFire, configWithWarningThreshold(95), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                activeFire, configWithWarningThreshold(95), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                activeFire, Config.defaults(), access);

        assertTrue(access.presentationEvents().contains("action:RED:HEAT 90% · FIRING"),
                access.events::toString);
    }

    @Test
    void reservedAuxiliarySlotRemainsDueOnTheNextTick() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        Hud.Snapshot activeFire = snapshot(
                90.0, BiomeClass.HOT, Hud.Firing.FIRING,
                java.util.Optional.of(controlSnapshot(
                        Controls.ControlLocation.HAND_ACCESSIBLE,
                        Controls.ControlLocation.HAND_ACCESSIBLE)), true);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                activeFire, configWithWarningThreshold(95), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                activeFire, configWithWarningThreshold(95), access);
        access.events.clear();

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                activeFire, Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 9L,
                activeFire, Config.defaults(), access);

        assertEquals(List.of("action:RED:HEAT 90% · FIRING", "particle"),
                access.presentationEvents());
    }

    @Test
    void changedControlWarningWaitsForCadenceAfterPromptFreshSessionWarning() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        Hud.Snapshot missing = snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0),
                java.util.Optional.of(controlSnapshot(
                        Controls.ControlLocation.MISSING,
                        Controls.ControlLocation.HAND_ACCESSIBLE)));
        Hud.Snapshot inventory = snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0),
                java.util.Optional.of(controlSnapshot(
                        Controls.ControlLocation.MAIN_INVENTORY_ONLY,
                        Controls.ControlLocation.HAND_ACCESSIBLE)));

        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                missing, Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 1L,
                inventory, Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 2L,
                missing, Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                inventory, Config.defaults(), access);

        assertEquals(List.of(
                "action:RED:CONTROL MISSING · DISMOUNT AND REMOUNT",
                "action:GOLD:CONTROL IN INVENTORY"), access.actionEvents());
    }

    @Test
    void sustainedWarningRepeatsAtBoundedCadenceWithinEveryPacketWindow() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(84.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        access.events.clear();
        int[] sendsAtTick = new int[41];

        for (int tick = 1; tick <= 40; tick++) {
            int before = access.events.size();
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                    Config.defaults(), access);
            sendsAtTick[tick] = access.events.size() - before;
        }

        assertTrue(access.particleEvents().size() >= 3, access.events::toString);
        for (int start = 1; start <= 21; start++) {
            int packets = Arrays.stream(sendsAtTick, start, start + 20).sum();
            assertTrue(packets < 10, "window " + start + ".." + (start + 19)
                    + " sent " + packets + ": " + Arrays.toString(sendsAtTick));
        }
    }

    @Test
    void restartIgnoresPersistedDirtyStateAndSendsFreshUnchangedChannels() {
        RiderState persisted = new RiderState(
                java.util.Optional.empty(), Long.MIN_VALUE,
                java.util.Optional.of(new RiderState.HudCache(
                0.9, "RED", "HEAT 90% · FIRING", 100L)));
        RecordingAccess access = new RecordingAccess();

        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, persisted, 100L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 104L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 108L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("action:RED:HEAT 90% · FIRING", "particle"),
                access.presentationEvents());
    }

    @Test
    void sameGhastRemountSendsFreshUnchangedActionAndWarning() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        hud.remove(RIDER_ID, access);
        access.events.clear();

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 12L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("action:RED:HEAT 90% · FIRING", "particle"),
                access.presentationEvents());
    }

    @Test
    void changedGhastSessionSendsFreshUnchangedActionAndWarning() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        access.events.clear();

        UUID replacementGhast = UUID.fromString("78913731-d235-4593-8bed-ca41c5504150");
        state = hud.update(RIDER_ID, RIDER_ID, replacementGhast, state, 4L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, replacementGhast, state, 8L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);
        hud.update(RIDER_ID, RIDER_ID, replacementGhast, state, 12L,
                snapshot(90.0, BiomeClass.BASE, Hud.Firing.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("action:RED:HEAT 90% · FIRING", "particle"),
                access.presentationEvents());
    }

    @Test
    void actionToggleOnSendsFreshUnchangedText() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(true, false, 4), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(true, false, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);

        assertEquals(List.of("action:GREEN:HEAT 25% · COOLING 1/s"), access.actionEvents());
    }

    @Test
    void ordinaryFutureRefreshTickTreatsClockRollbackAsFreshCadence() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 100L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 50L,
                snapshot(30.0, BiomeClass.HOT, Hud.Firing.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of("action:GREEN:HEAT 30% · FIRING"), access.actionEvents());
    }

    @Test
    void saturatedFutureRefreshTickTreatsClockRollbackAsFreshCadence() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), Long.MAX_VALUE,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, Long.MIN_VALUE,
                snapshot(30.0, BiomeClass.HOT, Hud.Firing.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of("action:GREEN:HEAT 30% · FIRING"), access.actionEvents());
    }

    @Test
    void minimumTickValueStillHonorsCadenceAfterTheFirstRefresh() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), Long.MIN_VALUE,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, Long.MIN_VALUE,
                snapshot(30.0, BiomeClass.HOT, Hud.Firing.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of(), access.actionEvents());
    }

    @Test
    void forwardTickOverflowCannotSuppressAChangedPresentationRefresh() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(),
                Long.MIN_VALUE + 1L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, Long.MAX_VALUE,
                snapshot(30.0, BiomeClass.HOT, Hud.Firing.FIRING),
                configWithHud(false, true, 4), access);

        assertEquals(List.of("action:GREEN:HEAT 30% · FIRING"), access.actionEvents());
    }

    @Test
    void persistedCacheTracksOnlyTheLastDeliveredValueOfEachChannel() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        RiderState.HudCache initial = new RiderState.HudCache(
                0.1, "BLUE", "", Long.MIN_VALUE);
        assertEquals(initial, state.hudCache().orElseThrow());

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(50.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        assertEquals(new RiderState.HudCache(0.1, "BLUE", "HEAT 50% · FIRING", 4L),
                state.hudCache().orElseThrow());

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                snapshot(50.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        assertEquals(new RiderState.HudCache(0.5, "BLUE", "HEAT 50% · FIRING", 4L),
                state.hudCache().orElseThrow());

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 12L,
                snapshot(50.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        assertEquals(new RiderState.HudCache(0.5, "BLUE", "HEAT 50% · FIRING", 4L),
                state.hudCache().orElseThrow());

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 13L,
                snapshot(50.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        assertEquals(new RiderState.HudCache(0.5, "GREEN", "HEAT 50% · FIRING", 4L),
                state.hudCache().orElseThrow());
    }

    @Test
    void warningCrossingOwnsNextSendAfterHeatFallsAndBossEventuallyConvergesInPlace() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 1L,
                snapshot(90.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 2L,
                snapshot(40.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        for (int tick : List.of(4, 8, 12, 16, 21)) {
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(40.0, BiomeClass.HOT, Hud.Firing.FIRING),
                    Config.defaults(), access);
        }

        assertEquals(List.of("action:GREEN:HEAT 40% · FIRING", "particle",
                "progress:0.4", "color:GREEN"), access.events);
    }

    @Test
    void continuouslyChangingHeatCannotStarveBossColorOrActionText() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();

        for (int tick : List.of(4, 8, 12, 16, 20, 24)) {
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(20.0 + tick, BiomeClass.HOT, Hud.Firing.FIRING),
                    Config.defaults(), access);
        }

        assertTrue(access.events.contains("color:GREEN"), access.events::toString);
        assertTrue(access.events.stream().anyMatch(event -> event.startsWith("action:GREEN:")),
                access.events::toString);
    }

    @Test
    void repeatedWarningCrossingsCannotStarveOtherDirtyPresentationChannels() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();

        for (int tick = 1; tick <= 25; tick++) {
            boolean warning = (tick / 4) % 2 == 1;
            state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, tick,
                    snapshot(warning ? 90.0 : 10.0,
                            warning ? BiomeClass.HOT : BiomeClass.COLD,
                            warning ? Hud.Firing.FIRING : new Hud.Cooling(1.0)),
                    Config.defaults(), access);
        }

        assertTrue(access.events.contains("particle"), access.events::toString);
        assertTrue(access.events.contains("progress:0.9"), access.events::toString);
        assertTrue(access.events.contains("color:RED"), access.events::toString);
        assertTrue(access.events.contains("action:RED:HEAT 90% · FIRING"), access.events::toString);
    }

    @Test
    void disablingAndReenablingBossBarRemovesThenCreatesOneFreshHandle() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = RiderState.fresh();
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 0L,
                snapshot(10.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(10.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(false, true, 4), access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                snapshot(10.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);

        assertEquals(List.of("create:0.1:GREEN", "add", "remove", "create:0.1:GREEN", "add"),
                access.bossEvents());
    }

    @Test
    void bossReenableAttachmentDefersPendingWarningUntilTheNextCadence() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                snapshot(10.0, BiomeClass.COLD, new Hud.Cooling(1.0)),
                configWithHud(false, false, 4), access);
        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 5L,
                snapshot(90.0, BiomeClass.HOT, Hud.Firing.FIRING),
                configWithHud(false, false, 4), access);
        access.events.clear();

        state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 8L,
                snapshot(90.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        assertEquals(List.of("create:0.9:RED", "add"), access.events);

        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 13L,
                snapshot(90.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);
        assertEquals(List.of("create:0.9:RED", "add",
                "action:RED:HEAT 90% · FIRING", "particle"), access.events);
    }

    @Test
    void riddenGhastIdentityChangeRemovesOldHandleBeforeAddingReplacement() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);

        hud.update(RIDER_ID, RIDER_ID, UUID.fromString("78913731-d235-4593-8bed-ca41c5504150"), state, 4L,
                snapshot(20.0, BiomeClass.HOT, Hud.Firing.FIRING),
                Config.defaults(), access);

        assertEquals(List.of("create:0.1:GREEN", "add", "remove", "create:0.2:GREEN", "add"),
                access.bossEvents());
    }

    @Test
    void stableRiderIdReplacesViewerObjectExactlyOnceWithoutCreatingAnotherHandle() {
        ViewerRecordingAccess access = new ViewerRecordingAccess();
        Hud<TestViewer, String> hud = new Hud<>(access);
        TestViewer first = new TestViewer("first");
        TestViewer replacement = new TestViewer("replacement");
        RiderState state = hud.update(RIDER_ID, first, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();

        state = hud.update(RIDER_ID, replacement, GHAST_ID, state, 4L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        assertEquals(List.of("remove:first", "add:replacement"), access.events);

        hud.update(RIDER_ID, replacement, GHAST_ID, state, 8L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);

        assertEquals(List.of("remove:first", "add:replacement", "action:replacement"), access.events);
    }

    @Test
    void teardownDetachesStoredReplacementViewerAndRemountCreatesFreshSession() {
        ViewerRecordingAccess access = new ViewerRecordingAccess();
        Hud<TestViewer, String> hud = new Hud<>(access);
        TestViewer first = new TestViewer("first");
        TestViewer replacement = new TestViewer("replacement");
        RiderState state = hud.update(RIDER_ID, first, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        state = hud.update(RIDER_ID, replacement, GHAST_ID, state, 4L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();

        hud.remove(RIDER_ID, access);
        hud.update(RIDER_ID, replacement, GHAST_ID, state, 8L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);

        assertEquals(List.of("remove:replacement", "create", "add:replacement"), access.events);
    }

    @Test
    void clearDetachesEveryStoredCurrentViewerAndEvictsSessionsWithoutDisplays() {
        ViewerRecordingAccess access = new ViewerRecordingAccess();
        Hud<TestViewer, String> hud = new Hud<>(access);
        TestViewer first = new TestViewer("first");
        TestViewer replacement = new TestViewer("replacement");
        TestViewer passenger = new TestViewer("passenger");
        TestViewer hidden = new TestViewer("hidden");
        UUID passengerId = UUID.fromString("a307681f-cdd8-46e0-9bdf-23d082409da3");
        UUID hiddenId = UUID.fromString("7aaeb02c-60ca-4497-b666-b60ee7a044e8");
        RiderState riderState = hud.update(RIDER_ID, first, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        hud.update(RIDER_ID, replacement, GHAST_ID, riderState, 4L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        hud.update(passengerId, passenger, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        RiderState hiddenState = hud.update(hiddenId, hidden, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                configWithHud(false, true, 4), access);
        access.events.clear();

        hud.clear(access);
        hud.update(hiddenId, hidden, GHAST_ID, hiddenState, 1L,
                snapshot(25.0, BiomeClass.BASE, new Hud.Cooling(1.0),
                        java.util.Optional.of(controlSnapshot(
                                Controls.ControlLocation.MISSING,
                                Controls.ControlLocation.HAND_ACCESSIBLE))),
                configWithHud(false, true, 4), access);

        assertEquals(List.of("remove:replacement", "remove:passenger",
                "action:hidden"), access.events);
    }

    @Test
    void riderAndServerTeardownRemoveEveryBoundedHandleExactlyOnce() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        UUID passenger = UUID.fromString("a307681f-cdd8-46e0-9bdf-23d082409da3");
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        hud.update(passenger, passenger, GHAST_ID, RiderState.fresh(), 0L,
                snapshot(10.0, BiomeClass.BASE, new Hud.Cooling(1.0)),
                Config.defaults(), access);
        access.events.clear();

        hud.remove(RIDER_ID, access);
        hud.clear(access);

        assertEquals(List.of("remove", "remove"), access.bossEvents());
    }

    @Test
    void bossBarNamePreservesCustomComponentAndUsesExactUnnamedFallback() {
        Component customName = Component.literal("Cloudbreaker");

        assertSame(customName, Hud.bossBarName(customName));
        assertEquals("HappyGhast", Hud.bossBarName(null).getString());
    }

    @Test
    void minecraft262AdapterOwnsExactBossActionAndTargetedParticleBindings() throws Exception {
        Hud.class.getDeclaredMethod("minecraftPresentation",
                net.minecraft.server.level.ServerLevel.class, HappyGhast.class);
        Hud.class.getDeclaredMethod("remove", ServerPlayer.class);
        Hud.class.getDeclaredMethod("clear");

        Set<String> calls = new java.util.HashSet<>();
        for (String className : List.of(
                Hud.class.getName(), Hud.class.getName() + "$MinecraftPresentationAccess",
                Hud.class.getName() + "$MinecraftViewerAccess")) {
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
                        "hasCurrentName", "setName", "actionBar", "warningParticle"),
                Arrays.stream(Hud.PresentationAccess.class.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName).collect(java.util.stream.Collectors.toSet()));

        RiderState before = new RiderState(java.util.Optional.of(GHAST_ID),
                47L, java.util.Optional.empty());
        Hud.Snapshot snapshot = snapshot(63.0, BiomeClass.COLD, new Hud.Cooling(1.0));
        RecordingAccess access = new RecordingAccess();
        RiderState after = new Hud<UUID, String>(access).update(
                RIDER_ID, RIDER_ID, GHAST_ID, before, 20L,
                snapshot, Config.defaults(), access);
        assertEquals(List.of(before.riddenGhastId(), before.lastHandledTick()),
                List.of(after.riddenGhastId(), after.lastHandledTick()));
        assertEquals(snapshot(63.0, BiomeClass.COLD, new Hud.Cooling(1.0)), snapshot);

        for (ClassNode hudClass : hudClassFamily()) {
            for (MethodNode method : hudClass.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
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
    void completeHudClassFamilyContainsNoBiomeClassReference() throws Exception {
        for (ClassNode hudClass : hudClassFamily()) {
            assertNoBiomeClassReferences(hudClass);
        }
    }

    @Test
    void biomeReferenceScannerReportsInjectedDescriptorTypeAndHandleReferences() {
        ClassNode injected = new ClassNode();
        injected.name = "xyz/pyrehaven/happyartillery/Hud$Injected";
        injected.superName = "java/lang/Object";
        injected.fields.add(new FieldNode(
                0, "biome", "Lxyz/pyrehaven/happyartillery/BiomeClass;", null, null));
        MethodNode method = new MethodNode(0, "leak",
                "(Lxyz/pyrehaven/happyartillery/BiomeClass;)V", null, null);
        method.instructions.add(new TypeInsnNode(
                org.objectweb.asm.Opcodes.CHECKCAST,
                "xyz/pyrehaven/happyartillery/BiomeClass"));
        method.instructions.add(new LdcInsnNode(
                Type.getType("Lxyz/pyrehaven/happyartillery/BiomeClass;")));
        method.instructions.add(new LdcInsnNode(new Handle(
                org.objectweb.asm.Opcodes.H_INVOKESTATIC,
                "xyz/pyrehaven/happyartillery/BiomeClass",
                "classify",
                "(Lxyz/pyrehaven/happyartillery/BiomeClass;)V",
                false)));
        injected.methods.add(method);

        List<String> references = biomeClassReferences(injected);
        assertTrue(references.stream().anyMatch(value -> value.contains("field biome descriptor")),
                references::toString);
        assertTrue(references.stream().anyMatch(value -> value.contains("method leak")
                        && value.contains(" descriptor:")), references::toString);
        assertTrue(references.stream().anyMatch(value -> value.contains("type instruction")),
                references::toString);
        assertTrue(references.stream().anyMatch(value -> value.contains("ldc type")),
                references::toString);
        assertTrue(references.stream().anyMatch(value -> value.contains("ldc handle owner")),
                references::toString);
        assertTrue(references.stream().anyMatch(value -> value.contains("ldc handle descriptor")),
                references::toString);
    }

    @Test
    void hudClassFamilyUsesNestMembersWhenInnerClassesOmitSyntheticMember() throws Exception {
        ClassNode root = BytecodeTestSupport.classNode(Hud.class.getName());
        Set<String> expected = new TreeSet<>(root.nestMembers);
        expected.add(root.name);
        String synthetic = root.name + "$1";
        assertTrue(expected.contains(synthetic), expected::toString);
        root.innerClasses.removeIf(inner -> synthetic.equals(inner.name));

        assertEquals(expected, authoritativeHudClassNames(root));
    }

    @Test
    void pilotAndPassengersRenderFromOnePostTransitionSnapshot() {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        UUID passenger = UUID.fromString("7aaeb02c-60ca-4497-b666-b60ee7a044e8");
        Hud.Snapshot snapshot = snapshot(63.0, BiomeClass.COLD, new Hud.Cooling(1.0));

        RiderState pilotState = hud.update(RIDER_ID, RIDER_ID, GHAST_ID,
                RiderState.fresh(), 20L, snapshot, Config.defaults(), access);
        RiderState passengerState = hud.update(passenger, passenger, GHAST_ID,
                RiderState.fresh(), 20L, snapshot, Config.defaults(), access);

        assertEquals(new PassengerObservation(
                        2, 0, List.of(
                                new RiderState.HudCache(0.63, "BLUE", "", Long.MIN_VALUE),
                                new RiderState.HudCache(0.63, "BLUE", "", Long.MIN_VALUE))),
                new PassengerObservation(
                        access.bossEvents().stream().filter(event -> event.equals("add")).toList().size(),
                        access.actionEvents().size(),
                        List.of(pilotState.hudCache().orElseThrow(),
                                passengerState.hudCache().orElseThrow())));
    }

    @Test
    void productionTeardownWrappersDelegateWithExactReceiverIdAndTypedBoundary() throws Exception {
        org.objectweb.asm.tree.ClassNode owner = BytecodeTestSupport.classNode(Hud.class.getName());
        MethodNode remove = exactMethod(owner, "remove",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        MethodNode clear = exactMethod(owner, "clear", "()V");

        assertEquals(List.of(
                        "ALOAD 0",
                        "ALOAD 1",
                        "INVOKEVIRTUAL net/minecraft/server/level/ServerPlayer.getUUID()Ljava/util/UUID;",
                        "ALOAD 0",
                        "GETFIELD xyz/pyrehaven/happyartillery/Hud.viewerAccess Lxyz/pyrehaven/happyartillery/Hud$ViewerAccess;",
                        "INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Hud.remove(Ljava/lang/Object;Lxyz/pyrehaven/happyartillery/Hud$ViewerAccess;)V",
                        "RETURN"), wrapperShape(remove));
        assertEquals(List.of(
                        "ALOAD 0",
                        "ALOAD 0",
                        "GETFIELD xyz/pyrehaven/happyartillery/Hud.viewerAccess Lxyz/pyrehaven/happyartillery/Hud$ViewerAccess;",
                        "INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Hud.clear(Lxyz/pyrehaven/happyartillery/Hud$ViewerAccess;)V",
                        "RETURN"), wrapperShape(clear));

        for (MethodNode wrapper : List.of(remove, clear)) {
            assertFalse(wrapperShape(wrapper).stream().anyMatch(operation ->
                    operation.contains("java/util/Map")
                            || operation.contains("Hud$Session")
                            || operation.contains("ServerBossEvent.removePlayer")
                            || operation.startsWith("CHECKCAST")), wrapperShape(wrapper)::toString);
        }
    }

    private static MethodNode exactMethod(
            org.objectweb.asm.tree.ClassNode owner, String name, String descriptor) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .toList();
        assertEquals(1, matches.size(), owner.name + "." + name + descriptor);
        return matches.getFirst();
    }

    private static List<ClassNode> hudClassFamily() throws Exception {
        ClassNode root = BytecodeTestSupport.classNode(Hud.class.getName());
        Set<String> authoritative = authoritativeHudClassNames(root);
        Set<String> innerClasses = new TreeSet<>();
        innerClasses.add(root.name);
        root.innerClasses.stream().map(inner -> inner.name)
                .filter(java.util.Objects::nonNull)
                .filter(name -> name.startsWith(root.name + "$"))
                .forEach(innerClasses::add);
        Set<String> compiledClasses = compiledHudClassNames(root);

        String synthetic = root.name + "$1";
        assertTrue(authoritative.contains(synthetic), authoritative::toString);
        assertTrue(innerClasses.contains(synthetic), innerClasses::toString);
        assertTrue(compiledClasses.contains(synthetic), compiledClasses::toString);
        assertEquals(authoritative, innerClasses,
                "Hud InnerClasses entries must exactly match its authoritative nest");
        assertEquals(authoritative, compiledClasses,
                "compiled Hud class resources must exactly match its authoritative nest");

        List<ClassNode> family = new ArrayList<>();
        for (String name : authoritative) {
            family.add(BytecodeTestSupport.classNode(name.replace('/', '.')));
        }
        return family;
    }

    private static Set<String> authoritativeHudClassNames(ClassNode root) {
        assertTrue(root.nestMembers != null,
                () -> root.name + " must declare a complete NestMembers list");
        Set<String> names = new TreeSet<>(root.nestMembers);
        names.add(root.name);
        return names;
    }

    private static Set<String> compiledHudClassNames(ClassNode root) throws Exception {
        var resource = Hud.class.getResource("Hud.class");
        assertTrue(resource != null, "compiled Hud.class resource must exist");
        URI uri = resource.toURI();
        assertEquals("file", uri.getScheme(),
                () -> "compiled Hud.class resource must be a file URI: " + uri);
        Path rootClass = Path.of(uri);
        assertTrue(Files.isRegularFile(rootClass),
                () -> "compiled Hud.class resource must be a regular file: " + rootClass);
        Path directory = rootClass.getParent();
        assertTrue(directory != null && Files.isDirectory(directory),
                () -> "compiled Hud.class resource must have a file directory: " + rootClass);

        String simpleName = root.name.substring(root.name.lastIndexOf('/') + 1);
        String packagePrefix = root.name.substring(0, root.name.lastIndexOf('/') + 1);
        Set<String> names = new TreeSet<>();
        try (var classes = Files.newDirectoryStream(directory, candidate -> {
            if (!Files.isRegularFile(candidate)) {
                return false;
            }
            String basename = candidate.getFileName().toString();
            return basename.equals(simpleName + ".class")
                    || basename.startsWith(simpleName + "$") && basename.endsWith(".class");
        })) {
            for (Path candidate : classes) {
                String basename = candidate.getFileName().toString();
                names.add(packagePrefix + basename.substring(0, basename.length() - ".class".length()));
            }
        }
        return names;
    }

    private static void assertNoBiomeClassReferences(ClassNode owner) {
        List<String> references = biomeClassReferences(owner);
        assertEquals(List.of(), references,
                () -> owner.name + " must not reference BiomeClass: " + references);
    }

    private static List<String> biomeClassReferences(ClassNode owner) {
        List<String> references = new ArrayList<>();
        reference(references, "class signature", owner.signature);
        reference(references, "superclass", owner.superName);
        owner.interfaces.forEach(value -> reference(references, "interface", value));
        reference(references, "outer class", owner.outerClass);
        reference(references, "outer method descriptor", owner.outerMethodDesc);
        reference(references, "nest host", owner.nestHostClass);
        if (owner.nestMembers != null) {
            owner.nestMembers.forEach(value -> reference(references, "nest member", value));
        }
        if (owner.permittedSubclasses != null) {
            owner.permittedSubclasses.forEach(value ->
                    reference(references, "permitted subclass", value));
        }
        owner.innerClasses.forEach(inner -> {
            reference(references, "inner class", inner.name);
            reference(references, "inner class owner", inner.outerName);
        });
        annotations(references, "class", owner.visibleAnnotations);
        annotations(references, "class", owner.invisibleAnnotations);
        annotations(references, "class type", owner.visibleTypeAnnotations);
        annotations(references, "class type", owner.invisibleTypeAnnotations);

        if (owner.recordComponents != null) {
            owner.recordComponents.forEach(component -> {
                String path = "record component " + component.name;
                reference(references, path + " descriptor", component.descriptor);
                reference(references, path + " signature", component.signature);
                annotations(references, path, component.visibleAnnotations);
                annotations(references, path, component.invisibleAnnotations);
                annotations(references, path + " type", component.visibleTypeAnnotations);
                annotations(references, path + " type", component.invisibleTypeAnnotations);
            });
        }
        owner.fields.forEach(field -> {
            String path = "field " + field.name;
            reference(references, path + " descriptor", field.desc);
            reference(references, path + " signature", field.signature);
            constant(references, path + " constant", field.value);
            annotations(references, path, field.visibleAnnotations);
            annotations(references, path, field.invisibleAnnotations);
            annotations(references, path + " type", field.visibleTypeAnnotations);
            annotations(references, path + " type", field.invisibleTypeAnnotations);
        });
        owner.methods.forEach(method -> scanMethod(references, method));
        return references;
    }

    private static void scanMethod(List<String> references, MethodNode method) {
        String path = "method " + method.name + method.desc;
        reference(references, path + " descriptor", method.desc);
        reference(references, path + " signature", method.signature);
        method.exceptions.forEach(value -> reference(references, path + " exception", value));
        annotations(references, path, method.visibleAnnotations);
        annotations(references, path, method.invisibleAnnotations);
        annotations(references, path + " type", method.visibleTypeAnnotations);
        annotations(references, path + " type", method.invisibleTypeAnnotations);
        parameterAnnotations(references, path, method.visibleParameterAnnotations);
        parameterAnnotations(references, path, method.invisibleParameterAnnotations);
        constant(references, path + " annotation default", method.annotationDefault);
        method.tryCatchBlocks.forEach(block -> {
            reference(references, path + " try/catch type", block.type);
            annotations(references, path + " try/catch", block.visibleTypeAnnotations);
            annotations(references, path + " try/catch", block.invisibleTypeAnnotations);
        });
        if (method.localVariables != null) {
            method.localVariables.forEach(local -> {
                reference(references, path + " local " + local.name + " descriptor", local.desc);
                reference(references, path + " local " + local.name + " signature", local.signature);
            });
        }
        annotations(references, path + " local", method.visibleLocalVariableAnnotations);
        annotations(references, path + " local", method.invisibleLocalVariableAnnotations);

        for (AbstractInsnNode instruction : method.instructions) {
            String instructionPath = path + " instruction " + instruction.getOpcode();
            if (instruction instanceof TypeInsnNode type) {
                reference(references, instructionPath + " type instruction", type.desc);
            } else if (instruction instanceof FieldInsnNode field) {
                reference(references, instructionPath + " field owner", field.owner);
                reference(references, instructionPath + " field descriptor", field.desc);
            } else if (instruction instanceof MethodInsnNode call) {
                reference(references, instructionPath + " method owner", call.owner);
                reference(references, instructionPath + " method descriptor", call.desc);
            } else if (instruction instanceof LdcInsnNode ldc) {
                constant(references, instructionPath + " ldc", ldc.cst);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                reference(references, instructionPath + " invokedynamic descriptor", dynamic.desc);
                constant(references, instructionPath + " bootstrap", dynamic.bsm);
                for (Object argument : dynamic.bsmArgs) {
                    constant(references, instructionPath + " bootstrap argument", argument);
                }
            } else if (instruction instanceof MultiANewArrayInsnNode array) {
                reference(references, instructionPath + " multiarray descriptor", array.desc);
            } else if (instruction instanceof FrameNode frame) {
                constant(references, instructionPath + " frame local", frame.local);
                constant(references, instructionPath + " frame stack", frame.stack);
            }
            annotations(references, instructionPath, instruction.visibleTypeAnnotations);
            annotations(references, instructionPath, instruction.invisibleTypeAnnotations);
        }
    }

    private static void parameterAnnotations(
            List<String> references, String path, List<AnnotationNode>[] parameters) {
        if (parameters == null) {
            return;
        }
        for (int index = 0; index < parameters.length; index++) {
            annotations(references, path + " parameter " + index, parameters[index]);
        }
    }

    private static void annotations(
            List<String> references, String path, List<? extends AnnotationNode> values) {
        if (values == null) {
            return;
        }
        for (AnnotationNode annotation : values) {
            reference(references, path + " annotation descriptor", annotation.desc);
            if (annotation.values != null) {
                annotation.values.forEach(value -> constant(
                        references, path + " annotation value", value));
            }
        }
    }

    private static void constant(List<String> references, String path, Object value) {
        if (value instanceof String text) {
            reference(references, path, text);
        } else if (value instanceof Type type) {
            reference(references, path + " type", type.getDescriptor());
        } else if (value instanceof Handle handle) {
            reference(references, path + " handle owner", handle.getOwner());
            reference(references, path + " handle descriptor", handle.getDesc());
        } else if (value instanceof ConstantDynamic dynamic) {
            reference(references, path + " constant-dynamic descriptor", dynamic.getDescriptor());
            constant(references, path + " constant-dynamic bootstrap", dynamic.getBootstrapMethod());
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                constant(references, path + " constant-dynamic argument",
                        dynamic.getBootstrapMethodArgument(index));
            }
        } else if (value instanceof AnnotationNode annotation) {
            annotations(references, path, List.of(annotation));
        } else if (value instanceof List<?> list) {
            list.forEach(element -> constant(references, path, element));
        } else if (value instanceof String[] array) {
            Arrays.stream(array).forEach(element -> reference(references, path, element));
        }
    }

    private static void reference(List<String> references, String path, String value) {
        if (value != null && value.contains("xyz/pyrehaven/happyartillery/BiomeClass")) {
            references.add(path + ": " + value);
        }
    }

    private static List<String> wrapperShape(MethodNode method) {
        List<String> shape = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            if (instruction instanceof VarInsnNode variable
                    && instruction.getOpcode() == org.objectweb.asm.Opcodes.ALOAD) {
                shape.add("ALOAD " + variable.var);
            } else if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD) {
                shape.add("GETFIELD " + field.owner + "." + field.name + " " + field.desc);
            } else if (instruction instanceof MethodInsnNode call) {
                String opcode = instruction.getOpcode() == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                        ? "INVOKEVIRTUAL" : "UNEXPECTED-OPCODE-" + instruction.getOpcode();
                shape.add(opcode + " " + call.owner + "." + call.name + call.desc);
            } else if (instruction.getOpcode() == org.objectweb.asm.Opcodes.RETURN) {
                shape.add("RETURN");
            } else if (instruction instanceof TypeInsnNode type) {
                shape.add("CHECKCAST " + type.desc);
            } else {
                shape.add("UNEXPECTED-OPCODE-" + instruction.getOpcode());
            }
        }
        return shape;
    }

    private static Config configWithCooling(Config.Cooling cooling) {
        Config defaults = Config.defaults();
        return new Config(defaults.controls(), defaults.fire(), defaults.heat(),
                defaults.water(), defaults.overheat(), defaults.cry(),
                new Config.Hud(defaults.hud().bossBar(), defaults.hud().actionBar(),
                        defaults.hud().refreshTicks(), defaults.hud().warningFromPercent(), cooling));
    }

    private static String deliveredAction(Hud.Mode mode, Config config, double heat) {
        RecordingAccess access = new RecordingAccess();
        Hud<UUID, String> hud = new Hud<>(access);
        RiderState state = hud.update(RIDER_ID, RIDER_ID, GHAST_ID, RiderState.fresh(), 0L,
                new Hud.Snapshot(heat, mode, java.util.Optional.empty(), false), config, access);
        hud.update(RIDER_ID, RIDER_ID, GHAST_ID, state, 4L,
                new Hud.Snapshot(heat, mode, java.util.Optional.empty(), false), config, access);
        return access.actionEvents().getFirst();
    }

    private static Config configWithHud(boolean bossBar, boolean actionBar, int refreshTicks) {
        Config defaults = Config.defaults();
        return new Config(defaults.controls(), defaults.fire(), defaults.heat(),
                defaults.water(), defaults.overheat(), defaults.cry(),
                new Config.Hud(
                        bossBar, actionBar, refreshTicks, defaults.hud().warningFromPercent(),
                        defaults.hud().cooling()));
    }

    private static Hud.Snapshot snapshot(double heat, BiomeClass biomeClass, Hud.Mode mode) {
        return snapshot(heat, biomeClass, mode, java.util.Optional.empty(), false);
    }

    private static Hud.Snapshot snapshot(
            double heat, BiomeClass biomeClass, Hud.Mode mode,
            java.util.Optional<Controls.InventorySnapshot> pilotControls) {
        return snapshot(heat, biomeClass, mode, pilotControls, false);
    }

    private static Hud.Snapshot snapshot(
            double heat, BiomeClass biomeClass, Hud.Mode mode,
            java.util.Optional<Controls.InventorySnapshot> pilotControls,
            boolean activeFireControl) {
        Hud.Mode effectiveMode = mode instanceof Hud.Cooling ? switch (biomeClass) {
            case COLD -> new Hud.Cooling(2.0);
            case BASE, END -> new Hud.Cooling(1.0);
            case HOT -> new Hud.Cooling(0.5);
            case NETHER -> new Hud.Cooling(0.0);
        } : mode;
        return new Hud.Snapshot(heat, effectiveMode, pilotControls, activeFireControl);
    }

    private static Config configWithWarningThreshold(int warningFromPercent) {
        Config defaults = Config.defaults();
        return new Config(defaults.controls(), defaults.fire(), defaults.heat(),
                defaults.water(), defaults.overheat(), defaults.cry(),
                new Config.Hud(defaults.hud().bossBar(), defaults.hud().actionBar(),
                        defaults.hud().refreshTicks(), warningFromPercent,
                        defaults.hud().cooling()));
    }

    private static Controls.InventorySnapshot controlSnapshot(
            Controls.ControlLocation fire, Controls.ControlLocation cry) {
        return new Controls.InventorySnapshot(fire, cry, 0);
    }

    private record PassengerObservation(int bossAdds, int actions, List<RiderState.HudCache> caches) {
    }


    private record ActionObservation(List<String> events, RiderState.HudCache cache) {
    }

    private record TestViewer(String name) {
    }

    private static final class ViewerRecordingAccess
            implements Hud.PresentationAccess<TestViewer, String> {
        private final List<String> events = new ArrayList<>();

        @Override
        public String createBossBar(double progress, Config.Color color) {
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
        public void setColor(String handle, Config.Color color) {
            events.add("color");
        }

        @Override public boolean hasCurrentName(String handle) { return true; }
        @Override public void setName(String handle) { throw new AssertionError("name unchanged"); }

        @Override
        public void removeViewer(String handle, TestViewer rider) {
            events.add("remove:" + rider.name());
        }

        @Override
        public void actionBar(TestViewer rider, String text, Config.Color color) {
            events.add("action:" + rider.name());
        }

        @Override
        public void warningParticle(TestViewer rider) {
            events.add("particle:" + rider.name());
        }
    }

    private static final class RecordingAccess implements Hud.PresentationAccess<UUID, String> {
        private final List<String> events = new ArrayList<>();
        private String currentName = "HappyGhast";
        private String renderedName = currentName;

        @Override
        public String createBossBar(double progress, Config.Color color) {
            renderedName = currentName;
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
        public void setColor(String handle, Config.Color color) {
            events.add("color:" + color);
        }

        @Override
        public boolean hasCurrentName(String handle) {
            return renderedName.equals(currentName);
        }

        @Override
        public void setName(String handle) {
            renderedName = currentName;
            events.add("name:" + currentName);
        }

        @Override
        public void removeViewer(String handle, UUID rider) {
            events.add("remove");
        }

        @Override
        public void actionBar(UUID rider, String text, Config.Color color) {
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
