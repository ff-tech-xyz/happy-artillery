package xyz.pyrehaven.happyartillery;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AbilitiesTest {
    private static final java.util.UUID RIDER_ID =
            java.util.UUID.fromString("acaa4238-f209-4370-b4c2-adabf234d966");
    private static final java.util.UUID GHAST_ID =
            java.util.UUID.fromString("59242a5a-2402-4f37-99e7-bb8db118af15");
    @Test
    void cryRejectsNonPilotBeforeWaterSoundOrStateMutation() {
        RecordingCryAccess access = new RecordingCryAccess();
        access.pilot = false;

        Abilities.CryOutcome outcome = cry(GhastState.fresh(), 100L, Config.defaults(), access);

        assertEquals(new Abilities.CryRejected(Abilities.CryRejection.NOT_PILOT), outcome);
        assertEquals(1, access.pilotChecks);
        assertEquals(0, access.waterChecks + access.sounds + access.replacements);
    }

    @Test
    void cryRejectsWaterBeforeSoundOrStateMutation() {
        RecordingCryAccess access = new RecordingCryAccess();
        access.inWater = true;

        Abilities.CryOutcome outcome = cry(GhastState.fresh(), 100L, Config.defaults(), access);

        assertEquals(new Abilities.CryRejected(Abilities.CryRejection.IN_WATER), outcome);
        assertEquals(1, access.pilotChecks);
        assertEquals(1, access.waterChecks);
        assertEquals(0, access.sounds + access.replacements);
    }

    @Test
    void disabledCryRejectsBeforeCooldownSoundOrStateMutation() {
        RecordingCryAccess access = new RecordingCryAccess();
        GhastState state = new GhastState(20.0, 50L, 75L, 0L, 500L,
                java.util.OptionalLong.of(600L), java.util.Optional.of(RIDER_ID));

        Abilities.CryOutcome outcome = cry(state, 100L, configWithCry(false, 10.0, 10.0), access);

        assertEquals(new Abilities.CryRejected(Abilities.CryRejection.DISABLED), outcome);
        assertEquals(1, access.waterChecks);
        assertEquals(0, access.sounds + access.replacements);
        assertEquals(500L, state.cryReadyTick());
    }

    @Test
    void cryReadyTickRejectsSavedGameTimeBeforeSoundOrStateMutation() {
        RecordingCryAccess access = new RecordingCryAccess();
        GhastState state = new GhastState(20.0, 50L, 75L, 0L, 101L,
                java.util.OptionalLong.of(600L), java.util.Optional.of(RIDER_ID));

        Abilities.CryOutcome outcome = cry(state, 100L, Config.defaults(), access);

        assertEquals(new Abilities.CryRejected(Abilities.CryRejection.ON_COOLDOWN), outcome);
        assertEquals(0, access.sounds + access.replacements);
        assertEquals(101L, state.cryReadyTick());
    }

    @Test
    void acceptedCryPlaysConfiguredSoundThenCommitsOnlyCryDeadline() {
        RecordingCryAccess access = new RecordingCryAccess();
        GhastState original = new GhastState(20.0, 50L, 75L, 90L, 100L,
                java.util.OptionalLong.of(600L), java.util.Optional.of(RIDER_ID));

        Abilities.CryOutcome outcome = cry(
                original, 100L, configWithCry(true, 7.5, 2.25), access);

        GhastState committed = new GhastState(20.0, 50L, 75L, 90L, 145L,
                java.util.OptionalLong.of(600L), java.util.Optional.of(RIDER_ID));
        assertEquals(new Abilities.Cried(committed), outcome);
        assertEquals(committed, access.replacedState);
        assertEquals(List.of("sound", "replace"), access.events);
        assertEquals(1, access.sounds);
        assertEquals(1, access.replacements);
        assertEquals(7.5, access.volume);
        assertEquals(20.0, committed.heat());
        assertEquals(50L, committed.heatAnchorTick());
        assertEquals(75L, committed.firingWindowEndTick());
        assertEquals(90L, committed.fireReadyTick());
        assertEquals(java.util.OptionalLong.of(600L), committed.detonateAtTick());
    }
    @Test
    void inWaterCryRejectionMapsToOneShortActionBarLineAndDistinctSound() {
        RecordingFeedbackAccess access = new RecordingFeedbackAccess();
        Object player = new Object();

        Feedback.presentWaterBlocked(player, access);

        assertEquals(List.of("action:Can't use artillery in water", "sound"), access.events);
    }


    @Test
    void minecraftCryAdapterUsesGhastScreamHostileConfiguredVolumeAndPointEightPitch()
            throws Exception {
        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerCryAccess");
        MethodNode play = exactMethod(adapter, "playCry",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;D)V");
        List<FieldInsnNode> fields = instructions(play).stream()
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .toList();
        assertTrue(fields.stream().anyMatch(field -> field.owner.equals("net/minecraft/sounds/SoundEvents")
                && field.name.equals("GHAST_SCREAM")));
        assertTrue(fields.stream().anyMatch(field -> field.owner.equals("net/minecraft/sounds/SoundSource")
                && field.name.equals("HOSTILE")));
        assertEquals(List.of(0.8F), instructions(play).stream()
                .filter(LdcInsnNode.class::isInstance)
                .map(LdcInsnNode.class::cast)
                .map(node -> node.cst)
                .filter(Float.class::isInstance)
                .toList());
        assertEquals(1, callsTo(play, "net/minecraft/world/level/Level", "playSound").size());
        assertTrue(instructions(play).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .noneMatch(call -> Set.of(
                        "hurt", "hurtServer", "addEffect", "setHealth", "kill", "discard",
                        "explode", "addFreshEntity", "gameEvent", "setTarget", "setDeltaMovement")
                        .contains(call.name)));
        assertEquals(10.0, Config.defaults().cry().volume());
    }

    @Test
    void minecraftFeedbackAdapterUsesOverlayMessageAndASeparateBlockedSound() throws Exception {
        ClassNode feedback = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Feedback");
        MethodNode real = exactMethod(feedback, "presentWaterBlocked",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        assertEquals(1, callsTo(real,
                "xyz/pyrehaven/happyartillery/Feedback", "presentWaterBlocked").size());

        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Feedback$ServerPlayerFeedbackAccess");
        MethodNode actionBar = exactMethod(adapter, "actionBar",
                "(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;)V");
        assertEquals(1, callsTo(actionBar, "net/minecraft/network/chat/Component", "literal").size());
        assertEquals(1, callsTo(actionBar, "net/minecraft/server/level/ServerPlayer", "sendOverlayMessage").size());

        MethodNode blockedSound = exactMethod(adapter, "blockedSound",
                "(Lnet/minecraft/server/level/ServerPlayer;)V");
        List<FieldInsnNode> fields = instructions(blockedSound).stream()
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .toList();
        assertTrue(fields.stream().anyMatch(field -> field.owner.equals("net/minecraft/sounds/SoundEvents")
                && field.name.equals("FIRE_EXTINGUISH")));
        assertTrue(fields.stream().noneMatch(field -> field.owner.equals("net/minecraft/sounds/SoundEvents")
                && field.name.equals("GHAST_SCREAM")));
        assertEquals(1, callsTo(blockedSound, "net/minecraft/server/level/ServerPlayer", "playSound").size());
    }
    @Test
    void nonPilotIsRejectedBeforeAnyStateSpendOrEffect() throws Exception {
        RecordingAccess access = new RecordingAccess();
        access.pilot = false;
        GhastState state = GhastState.fresh();

        Object outcome = fire(state, 100L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.NOT_PILOT), outcome);
        assertEquals(1, access.pilotChecks);
        assertEquals(0, access.waterChecks + access.adds + access.replacements);
    }

    @Test
    void waterIsRejectedBeforeHeatCooldownOrProjectileSpend() {
        RecordingAccess access = new RecordingAccess();
        access.inWater = true;
        GhastState state = new GhastState(20.0, 50L, 75L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = fire(state, 100L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.IN_WATER), outcome);
        assertEquals(1, access.pilotChecks);
        assertEquals(1, access.waterChecks);
        assertEquals(0, access.adds + access.replacements);
    }

    @Test
    void independentFireReadyTickRejectsCooldownBeforeHeatOrProjectileSpend() {
        RecordingAccess access = new RecordingAccess();
        GhastState state = new GhastState(20.0, 50L, 75L, 101L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = fire(state, 100L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.ON_COOLDOWN), outcome);
        assertEquals(1, access.pilotChecks);
        assertEquals(1, access.waterChecks);
        assertEquals(0, access.adds + access.replacements);
    }

    @Test
    void successfulAddCommitsAdvancedHeatAndIndependentCooldownExactlyOnceAfterEffect() {
        RecordingAccess access = new RecordingAccess();
        GhastState state = new GhastState(20.0, 50L, 50L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = fire(state, 100L, access);

        assertTrue(outcome instanceof Abilities.Fired);
        GhastState expected = new GhastState(
                19.75, 100L, 120L, 105L, 300L, java.util.OptionalLong.empty());
        assertEquals(expected, ((Abilities.Fired) outcome).state());
        assertEquals(expected, access.replacedState);
        assertEquals(List.of("add", "replace"), access.events);
        assertEquals(1, access.adds);
        assertEquals(1, access.replacements);
        assertEquals(1, access.explosionPower);
    }

    @Test
    void failedAddRejectsWithoutCommittingHeatOrCooldownAndHasNoFallback() {
        RecordingAccess access = new RecordingAccess();
        access.addSucceeds = false;
        GhastState state = new GhastState(20.0, 50L, 50L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = fire(state, 100L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.EFFECT_FAILED), outcome);
        assertEquals(List.of("add"), access.events);
        assertEquals(1, access.waterChecks);
        assertEquals(1, access.adds);
        assertEquals(0, access.replacements);
        assertEquals(null, access.replacedState);
        assertEquals(new GhastState(20.0, 50L, 50L, 0L, 300L,
                java.util.OptionalLong.empty()), state);
    }

    @Test
    void productionGeometryRejectionUsesTheFireEffectBoundaryWithoutStateMutationOrFallback() {
        RecordingAccess access = new RecordingAccess();
        access.addSucceeds = false;
        GhastState original = new GhastState(20.0, 50L, 50L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = fire(original, 100L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.EFFECT_FAILED), outcome);
        assertEquals(List.of("add"), access.events);
        assertEquals(1, access.adds);
        assertEquals(0, access.replacements);
        assertEquals(20.0, original.heat());
        assertEquals(0L, original.fireReadyTick());
    }

    @Test
    void pendingDetonationLocksOutFurtherShotsBeforeHeatOrProjectileSpend() {
        RecordingAccess access = new RecordingAccess();
        GhastState pending = new GhastState(99.0, 100L, 120L, 0L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));

        Abilities.FireOutcome outcome = fire(pending, 110L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.DETONATION_PENDING), outcome);
        assertEquals(0, access.waterChecks + access.adds + access.replacements);
    }

    @Test
    void deferredFuseTaskStoresOnlyImmutableUuidDeadlineRiderAndSequence() throws Exception {
        Class<?> task = Class.forName("xyz.pyrehaven.happyartillery.Abilities$FuseTask");

        assertTrue(task.isRecord());
        assertEquals(List.of(
                        "ghastId:java.util.UUID",
                        "deadline:long",
                        "riderId:java.util.UUID",
                        "sequence:long"),
                Stream.of(task.getRecordComponents())
                        .map(component -> component.getName() + ":" + component.getType().getName())
                        .toList());
    }

    @Test
    void crossingWithFuseCommitsAbsoluteDeadlineAndExecutesOnceWhenDue() {
        Object ghast = new Object();
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        Config config = configWithOverheat(40, 6.0, 3, 0.4, 2, 4, 8.0, true, true);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), ghast, original, 100L, config, BiomeClass.BASE, fire,
                queue, detonation);

        assertTrue(outcome instanceof Abilities.DetonationPending);
        GhastState pending = ((Abilities.DetonationPending) outcome).state();
        assertEquals(java.util.OptionalLong.of(140L), pending.detonateAtTick());
        assertEquals(java.util.Optional.of(RIDER_ID), pending.detonatingRiderId());
        assertEquals(pending, detonation.replacedState);
        assertEquals(List.of("replace"), detonation.events);
        assertEquals(0, queue.runDue(139L, config, detonation));
        assertEquals(1, queue.runDue(140L, config, detonation));
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
        assertEquals(0, queue.runDue(140L, config, detonation));
    }

    @Test
    void zeroFuseConsumesAndDetonatesImmediatelyThroughQueueOwnership() {
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), new Object(), original, 100L, config, BiomeClass.BASE, fire,
                queue, detonation);

        assertEquals(new Abilities.Detonated(), outcome);
        assertEquals(List.of("replace", "replace", "explode:6.0:true", "remove"),
                detonation.events);
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
        assertEquals(0, queue.runDue(100L, config, detonation));
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
    }

    @Test
    void zeroFuseExplosionCannotInventARejectedOutcome() {
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), new Object(), original, 100L, config, BiomeClass.BASE, fire,
                queue, detonation);

        assertEquals(new Abilities.Detonated(), outcome);
        assertEquals(java.util.OptionalLong.empty(), detonation.state.detonateAtTick());
        assertEquals(java.util.Optional.empty(), detonation.state.detonatingRiderId());
        assertEquals(0, queue.runDue(100L, config, detonation));
        assertEquals(1, detonation.explosions);
    }

    @Test
    void zeroFuseUnavailableRiderIsQueueDeferredAndReactivatedWithoutGhastReload() {
        Object ghast = new Object();
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        detonation.riderAvailable = false;
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), ghast, original, 100L, config, BiomeClass.BASE, fire,
                queue, detonation);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.EFFECT_FAILED), outcome);
        detonation.riderAvailable = true;
        assertEquals(1, queue.onRiderAvailable(RIDER_ID));
        assertEquals(1, queue.runDue(101L, config, detonation));
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
        assertEquals(0, queue.onRiderAvailable(RIDER_ID));
        assertEquals(0, queue.runDue(101L, config, detonation));
    }

    @Test
    void zeroFuseUnloadedGhastAwaitsLoadWithoutInaccessibleQueueEntry() {
        Object ghast = new Object();
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        detonation.loaded = false;
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), ghast, original, 100L, config, BiomeClass.BASE, fire,
                queue, detonation);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.EFFECT_FAILED), outcome);
        assertEquals(java.util.OptionalLong.of(100L), detonation.state.detonateAtTick());
        assertEquals(0, queue.runDue(101L, config, detonation));
        assertEquals(0, detonation.explosions + detonation.removals);
        detonation.loaded = true;
        queue.onGhastLoad(ghast, java.util.Optional.of(detonation.state), detonation);
        queue.onGhastLoad(ghast, java.util.Optional.of(detonation.state), detonation);
        assertEquals(1, queue.runDue(101L, config, detonation));
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
        assertEquals(0, queue.runDue(101L, config, detonation));
    }

    @Test
    void detonationReadsTheOptionalAttachmentExactlyOnce() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));

        assertEquals(new Abilities.DetonationConsumed(), Abilities.executeDetonation(
                new Object(), 140L, configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                        true, true), access));
        assertEquals(1, access.stateReads);
    }

    @Test
    void retainedGhastDetonationRunsConfiguredEffectsInOrderThenResetsHeatAndPending() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        Config config = configWithOverheat(40, 6.0, 4, 0.4, 2, 3, 8.0,
                false, true);

        Abilities.DetonationOutcome outcome = Abilities.executeDetonation(
                new Object(), 140L, config, access);

        assertEquals(new Abilities.DetonationConsumed(), outcome);
        assertEquals(List.of("replace", "explode:6.0:true", "fireball", "fireball", "fireball",
                "fireball", "fire", "fire", "fire"), access.events);
        assertEquals(4, access.directions.size());
        assertTrue(access.directions.stream().allMatch(direction ->
                Math.abs(direction.length() - 1.0) < 1.0E-9));
        assertTrue(access.fireOffsets.stream().allMatch(offset -> offset.length() <= 8.0));
        assertEquals(0.0, access.replacedState.heat());
        assertEquals(140L, access.replacedState.heatAnchorTick());
        assertEquals(140L, access.replacedState.firingWindowEndTick());
        assertEquals(140L, access.replacedState.fireReadyTick());
        assertEquals(300L, access.replacedState.cryReadyTick());
        assertEquals(java.util.OptionalLong.empty(), access.replacedState.detonateAtTick());
        assertEquals(0, access.removals);
    }

    @Test
    void terrainDisabledSkipsConfiguredFireWithoutCountingItAsRejected() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.fireballAccepted = false;
        Config config = configWithOverheat(0, 6.0, 2, 0.4, 2, 5, 8.0,
                false, false);

        Abilities.DetonationOutcome outcome = Abilities.executeDetonation(
                new Object(), 140L, config, access);

        assertEquals(new Abilities.DetonationConsumedWithFailures(2), outcome);
        assertEquals(List.of("replace", "explode:6.0:false", "fireball", "fireball"),
                access.events);
        assertEquals(1, access.explosions);
        assertFalse(access.explosionBreaksBlocks);
        assertEquals(2, access.directions.size());
        assertEquals(0, access.fireOffsets.size());
        assertEquals(0, access.removals);
        assertEquals(java.util.OptionalLong.empty(), access.state.detonateAtTick());
        assertEquals(java.util.Optional.empty(), access.state.detonatingRiderId());
    }

    @Test
    void oneRejectedFireballCountsOnceAndDoesNotAbortRemainingConfiguredAttempts() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.fireballResults.addAll(List.of(false, true, true));
        Config config = configWithOverheat(0, 6.0, 3, 0.4, 2, 0, 8.0,
                false, true);

        Abilities.DetonationOutcome outcome = Abilities.executeDetonation(
                new Object(), 140L, config, access);

        assertEquals(new Abilities.DetonationConsumedWithFailures(1), outcome);
        assertEquals(List.of("replace", "explode:6.0:true", "fireball", "fireball", "fireball"),
                access.events);
        assertEquals(3, access.directions.size());
        assertEquals(java.util.OptionalLong.empty(), access.state.detonateAtTick());
        assertEquals(java.util.Optional.empty(), access.state.detonatingRiderId());
    }

    @Test
    void rejectedEffectsStillRunOneCompletePassAfterConsumptionThenAttemptRemoval() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.fireballAccepted = false;
        access.fireAttempts.addAll(List.of(
                Abilities.FireAttempt.SKIPPED,
                Abilities.FireAttempt.REJECTED));
        Config config = configWithOverheat(0, 6.0, 2, 0.4, 2, 2, 8.0,
                true, true);

        Abilities.DetonationOutcome outcome = Abilities.executeDetonation(
                new Object(), 140L, config, access);

        assertEquals(new Abilities.DetonationConsumedWithFailures(3), outcome);
        assertEquals(List.of("replace", "explode:6.0:true", "fireball", "fireball", "fire", "fire",
                "remove"), access.events);
        assertEquals(java.util.OptionalLong.empty(), access.state.detonateAtTick());
        assertEquals(java.util.Optional.empty(), access.state.detonatingRiderId());
        assertEquals(new Abilities.DetonationIgnored(), Abilities.executeDetonation(
                new Object(), 140L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(2, access.directions.size());
        assertEquals(2, access.fireOffsets.size());
        assertEquals(1, access.removals);
        assertEquals(101.0, access.stateAtRemoval.heat());
        assertEquals(100L, access.stateAtRemoval.heatAnchorTick());
        assertEquals(120L, access.stateAtRemoval.firingWindowEndTick());
        assertEquals(105L, access.stateAtRemoval.fireReadyTick());
        assertEquals(300L, access.stateAtRemoval.cryReadyTick());
        assertEquals(java.util.OptionalLong.empty(), access.stateAtRemoval.detonateAtTick());
        assertEquals("remove", access.events.getLast());
    }

    @Test
    void failedRequiredConsumptionThrowsBeforeAnyWorldEffectOrRemoval() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.replacementFailure = new IllegalStateException("attachment write failed");
        Config config = configWithOverheat(0, 6.0, 2, 0.4, 2, 2, 8.0,
                true, true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> Abilities.executeDetonation(new Object(), 140L, config, access));

        assertEquals("attachment write failed", failure.getMessage());
        assertEquals(List.of("replace"), access.events);
        assertEquals(0, access.explosions + access.directions.size()
                + access.fireOffsets.size() + access.removals);
    }

    @Test
    void failedQueueConsumptionRestoresOneActiveOwnerUntilLaterDueRunConsumesOnce() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.state = pending;
        access.replacementFailure = new IllegalStateException("attachment write failed");
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertEquals("attachment write failed", failure.getMessage());
        assertEquals(List.of("replace"), access.events);
        assertEquals(0, access.explosions + access.directions.size()
                + access.fireOffsets.size() + access.removals);
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);

        access.replacementFailure = null;
        assertEquals(1, queue.runDue(150L, config, access));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
    }

    @Test
    void missingAttachmentIsStaleAndLaterEqualDeadlineTaskStillConsumesInStableOrder() {
        Object staleGhast = new Object();
        Object liveGhast = new Object();
        java.util.UUID liveGhastId =
                java.util.UUID.fromString("aed83452-a90b-4ea6-b5c7-837116aca026");
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.ghastIds.put(staleGhast, GHAST_ID);
        access.ghastIds.put(liveGhast, liveGhastId);
        access.states.put(staleGhast, pending);
        access.states.put(liveGhast, pending);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(staleGhast, java.util.Optional.of(pending), access);
        queue.onGhastLoad(liveGhast, java.util.Optional.of(pending), access);
        access.missingAttachments.add(staleGhast);

        assertEquals(1, queue.runDue(140L, configWithOverheat(
                0, 6.0, 0, 0.4, 2, 0, 8.0, true, true), access));

        assertEquals(List.of(staleGhast, liveGhast), access.resolvedGhasts);
        assertEquals(List.of(liveGhast), access.explodedGhasts);
        assertEquals(0, queue.runDue(140L, Config.defaults(), access));
    }

    @Test
    void reentrantDueSchedulingWaitsForTheNextBatchWithoutDisplacingEntryWork() {
        Object firstGhast = new Object();
        Object entryGhast = new Object();
        Object reentrantGhast = new Object();
        java.util.UUID entryGhastId =
                java.util.UUID.fromString("aed83452-a90b-4ea6-b5c7-837116aca026");
        java.util.UUID reentrantGhastId =
                java.util.UUID.fromString("01e09991-f758-49db-93d2-c32a538cf308");
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.ghastIds.put(firstGhast, GHAST_ID);
        access.ghastIds.put(entryGhast, entryGhastId);
        access.ghastIds.put(reentrantGhast, reentrantGhastId);
        access.states.put(firstGhast, pending);
        access.states.put(entryGhast, pending);
        access.states.put(reentrantGhast, pending);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(firstGhast, java.util.Optional.of(pending), access);
        queue.onGhastLoad(entryGhast, java.util.Optional.of(pending), access);
        access.explosionActions.put(firstGhast,
                () -> queue.onGhastLoad(reentrantGhast, java.util.Optional.of(pending), access));

        assertEquals(2, queue.runDue(140L, configWithOverheat(
                0, 6.0, 0, 0.4, 2, 0, 8.0, true, true), access));
        assertEquals(List.of(firstGhast, entryGhast), access.explodedGhasts);

        assertEquals(1, queue.runDue(140L, configWithOverheat(
                0, 6.0, 0, 0.4, 2, 0, 8.0, true, true), access));
        assertEquals(List.of(firstGhast, entryGhast, reentrantGhast), access.explodedGhasts);
    }

    @Test
    void reentrantReplacementOfLaterEntryTaskRunsOnlyTheReplacementNextBatch() {
        Object firstGhast = new Object();
        Object replacedGhast = new Object();
        java.util.UUID replacedGhastId =
                java.util.UUID.fromString("aed83452-a90b-4ea6-b5c7-837116aca026");
        GhastState original = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        java.util.UUID replacementRider =
                java.util.UUID.fromString("f8e3fd37-1120-4334-9568-839815543dd1");
        GhastState replacement = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(139L), java.util.Optional.of(replacementRider));
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.ghastIds.put(firstGhast, GHAST_ID);
        access.ghastIds.put(replacedGhast, replacedGhastId);
        access.states.put(firstGhast, original);
        access.states.put(replacedGhast, original);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(firstGhast, java.util.Optional.of(original), access);
        queue.onGhastLoad(replacedGhast, java.util.Optional.of(original), access);
        access.explosionActions.put(firstGhast, () -> {
            access.states.put(replacedGhast, replacement);
            queue.onGhastLoad(replacedGhast, java.util.Optional.of(replacement), access);
        });

        assertEquals(1, queue.runDue(140L, configWithOverheat(
                0, 6.0, 0, 0.4, 2, 0, 8.0, true, true), access));
        assertEquals(List.of(firstGhast), access.explodedGhasts);

        assertEquals(1, queue.runDue(140L, configWithOverheat(
                0, 6.0, 0, 0.4, 2, 0, 8.0, true, true), access));
        assertEquals(List.of(firstGhast, replacedGhast), access.explodedGhasts);
    }

    @Test
    void firstUnexpectedFailureIsDormantWhileLaterDueTaskRunsBeforePropagation() {
        Object failingGhast = new Object();
        Object liveGhast = new Object();
        java.util.UUID liveGhastId =
                java.util.UUID.fromString("aed83452-a90b-4ea6-b5c7-837116aca026");
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.ghastIds.put(failingGhast, GHAST_ID);
        access.ghastIds.put(liveGhast, liveGhastId);
        access.states.put(failingGhast, pending);
        access.states.put(liveGhast, pending);
        IllegalStateException first = new IllegalStateException("first attachment write failed");
        access.replacementFailures.put(failingGhast, first);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(failingGhast, java.util.Optional.of(pending), access);
        queue.onGhastLoad(liveGhast, java.util.Optional.of(pending), access);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, configWithOverheat(
                        0, 6.0, 0, 0.4, 2, 0, 8.0, true, true), access));

        assertSame(first, thrown);
        assertEquals(List.of(liveGhast), access.explodedGhasts);
        assertEquals(0, queue.runDue(141L, Config.defaults(), access));
        access.replacementFailures.remove(failingGhast);
        queue.onGhastLoad(failingGhast, java.util.Optional.of(pending), access);
        assertEquals(1, queue.runDue(142L, configWithOverheat(
                0, 6.0, 0, 0.4, 2, 0, 8.0, true, true), access));
    }

    @Test
    void laterUnexpectedFailuresAreSuppressedOnTheFirstAfterTheWholeDueBatch() {
        Object firstGhast = new Object();
        Object secondGhast = new Object();
        java.util.UUID secondGhastId =
                java.util.UUID.fromString("aed83452-a90b-4ea6-b5c7-837116aca026");
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.ghastIds.put(firstGhast, GHAST_ID);
        access.ghastIds.put(secondGhast, secondGhastId);
        access.states.put(firstGhast, pending);
        access.states.put(secondGhast, pending);
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second");
        access.replacementFailures.put(firstGhast, first);
        access.replacementFailures.put(secondGhast, second);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(firstGhast, java.util.Optional.of(pending), access);
        queue.onGhastLoad(secondGhast, java.util.Optional.of(pending), access);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> queue.runDue(140L, Config.defaults(), access));

        assertSame(first, thrown);
        assertEquals(List.of(second), List.of(thrown.getSuppressed()));
        assertEquals(List.of(firstGhast, secondGhast), access.resolvedGhasts);
        assertEquals(0, queue.runDue(141L, Config.defaults(), access));
    }

    @Test
    void stateReadExceptionRestoresOneActiveOwnerWithoutRecoveryReread() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.state = pending;
        access.stateFailure = new IllegalStateException("attachment read failed");
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertSame(access.stateFailure, failure);
        assertEquals(1, access.stateReads);
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);

        access.stateFailure = null;
        assertEquals(1, queue.runDue(150L, config, access));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
    }

    @Test
    void loadedExceptionRestoresOneActiveOwnerWithoutStateRecoveryProbe() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.state = pending;
        access.loadedFailure = new IllegalStateException("loaded check failed");
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertSame(access.loadedFailure, failure);
        assertEquals(0, access.stateReads);
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);

        access.loadedFailure = null;
        assertEquals(1, queue.runDue(150L, config, access));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
    }

    @Test
    void unexpectedPostConsumptionEffectExceptionRetainsDormantOwnerUntilLifecycleCleanup()
            throws Exception {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.state = pending;
        access.explosionFailure = new IllegalStateException("effect exploded unexpectedly");
        Config config = configWithOverheat(0, 6.0, 1, 0.4, 2, 1, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);
        access.explosionActions.put(ghast,
                () -> queue.onGhastLoad(ghast, java.util.Optional.of(pending), access));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertEquals("effect exploded unexpectedly", failure.getMessage());
        assertEquals(java.util.OptionalLong.empty(), access.state.detonateAtTick());
        assertEquals(java.util.Optional.empty(), access.state.detonatingRiderId());
        assertEquals(1, access.explosions);
        assertEquals(0, access.directions.size() + access.fireOffsets.size() + access.removals);
        assertEquals(1, fieldSize(queue, "scheduled"));
        assertEquals(0, fieldSize(queue, "tasks"));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(0, access.directions.size() + access.fireOffsets.size() + access.removals);
        assertEquals(1, fieldSize(queue, "scheduled"));
        queue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        queue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        assertEquals(0, fieldSize(queue, "scheduled"));
        assertEquals(0, fieldSize(queue, "tasks"));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
    }

    @Test
    void unavailableStoredRiderLeavesPendingDetonationForLoadRetry() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.state = pending;
        access.riderAvailable = false;

        Abilities.DetonationOutcome outcome = Abilities.executeDetonation(
                new Object(), 140L, Config.defaults(), access);

        assertEquals(new Abilities.DetonationDeferred(
                Abilities.DetonationDeferral.RIDER_UNAVAILABLE), outcome);
        assertEquals(pending, access.state);
        assertEquals(List.of(), access.events);
    }

    @Test
    void staleAndDuplicateExecutionReReadsDeadlineBeforeAnyMutation() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(200L), java.util.Optional.of(RIDER_ID));
        Config config = configWithOverheat(40, 6.0, 2, 0.4, 3, 4, 8.0,
                true, true);

        assertEquals(new Abilities.DetonationIgnored(), Abilities.executeDetonation(
                new Object(), 140L, config, access));
        access.state = new GhastState(0.0, 140L, 140L, 140L, 300L,
                java.util.OptionalLong.empty());
        assertEquals(new Abilities.DetonationIgnored(), Abilities.executeDetonation(
                new Object(), 200L, config, access));

        assertEquals(0, access.explosions + access.directions.size()
                + access.fireOffsets.size() + access.removals);
        assertEquals(List.of(), access.events);
    }

    @Test
    void fuseDeadlineSaturatesAtLongMaxValue() {
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        GhastState original = new GhastState(98.75, Long.MAX_VALUE - 5L,
                Long.MAX_VALUE - 5L, 0L, 300L, java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(new Object(), new Object(), original,
                Long.MAX_VALUE - 5L, config, BiomeClass.BASE, fire, queue, detonation);

        assertTrue(outcome instanceof Abilities.DetonationPending);
        assertEquals(java.util.OptionalLong.of(Long.MAX_VALUE),
                ((Abilities.DetonationPending) outcome).state().detonateAtTick());
        assertEquals(List.of("replace"), detonation.events);
        assertEquals(0, queue.runDue(Long.MAX_VALUE - 1L, config, detonation));
        assertEquals(1, queue.runDue(Long.MAX_VALUE, config, detonation));
        assertEquals(0, queue.runDue(Long.MAX_VALUE, config, detonation));
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
    }

    @Test
    void minecraftDetonationAdapterBindsMappedTwentySixTwoMutationApis()
            throws Exception {
        ClassNode detonation = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerDetonationAccess");
        Set<String> detonationCalls = detonation.methods.stream()
                .flatMap(method -> instructions(method).stream())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .map(call -> call.owner + "." + call.name)
                .collect(Collectors.toSet());
        assertTrue(detonationCalls.contains("net/minecraft/world/level/Level.explode"));
        assertTrue(detonationCalls.contains("net/minecraft/server/level/ServerLevel.addFreshEntity"));
        assertTrue(detonationCalls.contains("net/minecraft/world/level/block/BaseFireBlock.canBePlacedAt"));
        assertTrue(detonationCalls.contains("net/minecraft/world/level/Level.setBlockAndUpdate"));
        assertTrue(detonationCalls.contains(
                "net/minecraft/world/entity/animal/happyghast/HappyGhast.discard"));
        assertTrue(detonationCalls.contains(
                "net/minecraft/server/players/PlayerList.getPlayer"));
        assertTrue(detonationCalls.stream().noneMatch(call -> call.endsWith("MinecraftServer.schedule")));

        Class<?> access = Class.forName(
                "xyz.pyrehaven.happyartillery.Abilities$DetonationAccess");
        assertEquals(void.class, access.getDeclaredMethod("remove", Object.class).getReturnType());
        MethodNode remove = exactMethod(detonation, "remove",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)V");
        assertEquals(List.of(
                        "ALOAD 1",
                        "INVOKEVIRTUAL net/minecraft/world/entity/animal/happyghast/HappyGhast.discard ()V",
                        "RETURN"),
                exactOwnerInstructionShape(remove));
        assertEquals(1, callsTo(remove,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "discard").size());
        assertEquals(0, callsTo(remove,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "isRemoved").size());
        assertEquals(0, instructions(remove).stream().filter(JumpInsnNode.class::isInstance).count());

        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        MethodNode effects = exactMethod(abilities, "executeDetonation",
                "(Ljava/lang/Object;JLjava/util/UUID;J"
                        + "Lxyz/pyrehaven/happyartillery/Config;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$DetonationAccess;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$ExecutionEvidence;"
                        + "Ljava/util/Optional;)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$DetonationOutcome;");
        List<MethodInsnNode> removals = callsTo(effects,
                "xyz/pyrehaven/happyartillery/Abilities$DetonationAccess", "remove");
        assertEquals(1, removals.size());
        assertEquals("(Ljava/lang/Object;)V", removals.getFirst().desc);
        assertEquals(1, abilities.methods.stream()
                .flatMap(method -> instructions(method).stream())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(
                        "xyz/pyrehaven/happyartillery/Abilities$FuseQueue")
                        && call.name.equals("submit"))
                .count());
        MethodNode fire = exactMethod(abilities, "fire",
                "(Ljava/lang/Object;Ljava/lang/Object;"
                        + "Lxyz/pyrehaven/happyartillery/GhastState;J"
                        + "Lxyz/pyrehaven/happyartillery/Config;"
                        + "Lxyz/pyrehaven/happyartillery/BiomeClass;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireAccess;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FuseQueue;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$DetonationAccess;)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireOutcome;");
        assertEquals(0, callsTo(fire,
                "xyz/pyrehaven/happyartillery/Abilities", "executeDetonation").size());

    }

    @Test
    void productionExplosionUsesNoneOrMobAndNeverOwnsBlockDestruction() throws Exception {
        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerDetonationAccess");
        MethodNode explode = exactMethod(adapter, "explode",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;DZ)V");
        List<String> interactions = instructions(explode).stream()
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .filter(field -> field.owner.equals(
                        "net/minecraft/world/level/Level$ExplosionInteraction"))
                .map(field -> field.name)
                .toList();

        assertEquals(List.of("MOB", "NONE"), interactions);
        assertFalse(interactions.contains("BLOCK"));
        assertEquals(1, callsTo(explode, "net/minecraft/world/level/Level", "explode").size());
    }

    @Test
    void detonationAccessExposesPersistedGhastUuidFromMinecraftEntity() throws Exception {
        Class<?> access = Class.forName(
                "xyz.pyrehaven.happyartillery.Abilities$DetonationAccess");
        assertEquals(java.util.UUID.class,
                access.getDeclaredMethod("ghastId", Object.class).getReturnType());
        assertEquals(Object.class,
                access.getDeclaredMethod("resolveGhast", java.util.UUID.class).getReturnType());
        assertEquals(java.util.Optional.class,
                access.getDeclaredMethod("attachedState", Object.class).getReturnType());
        assertEquals(void.class, access.getDeclaredMethod(
                "replaceState", Object.class, GhastState.class).getReturnType());
        assertEquals(void.class, access.getDeclaredMethod(
                "explode", Object.class, Object.class, double.class, boolean.class).getReturnType());

        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerDetonationAccess");
        MethodNode ghastId = exactMethod(adapter, "ghastId",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)Ljava/util/UUID;");
        assertEquals(1, callsTo(ghastId,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getUUID").size());
        MethodNode resolveGhast = exactMethod(adapter, "resolveGhast",
                "(Ljava/util/UUID;)Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;");
        assertEquals(1, callsTo(resolveGhast,
                "net/minecraft/server/MinecraftServer", "getAllLevels").size());
        assertEquals(1, callsTo(resolveGhast,
                "net/minecraft/server/level/ServerLevel", "getEntity").size());
    }

    @Test
    void fuseQueueSurvivesDismountNoOpsOnUnloadAndLoadWakeExecutesOnce() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> restartedQueue = new Abilities.FuseQueue<>();

        restartedQueue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        restartedQueue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        assertEquals(0, restartedQueue.runDue(139L, config, access));
        access.loaded = false;
        assertEquals(0, restartedQueue.runDue(140L, config, access));
        assertEquals(java.util.OptionalLong.of(140L), access.state.detonateAtTick());

        access.loaded = true;
        restartedQueue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        assertEquals(1, restartedQueue.runDue(150L, config, access));
        assertEquals(0, restartedQueue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
    }

    @Test
    void repeatedReloadObjectsForOnePersistedGhastReplaceActiveReference() {
        Object staleGhast = new Object();
        Object middleGhast = new Object();
        Object newestGhast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();

        queue.onGhastLoad(staleGhast, java.util.Optional.of(access.state), access);
        queue.onGhastLoad(middleGhast, java.util.Optional.of(access.state), access);
        queue.onGhastLoad(newestGhast, java.util.Optional.of(access.state), access);

        assertEquals(1, queue.runDue(140L, config, access));
        assertEquals(List.of(newestGhast), access.explodedGhasts);
    }

    @Test
    void reloadReferenceReplacesSamePersistedTaskWhileRiderDeferred() {
        Object staleGhast = new Object();
        Object newestGhast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.riderAvailable = false;
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();

        queue.onGhastLoad(staleGhast, java.util.Optional.of(access.state), access);
        assertEquals(0, queue.runDue(140L, config, access));
        queue.onGhastLoad(newestGhast, java.util.Optional.of(access.state), access);

        access.riderAvailable = true;
        assertEquals(0, queue.onRiderAvailable(RIDER_ID));
        assertEquals(1, queue.runDue(150L, config, access));
        assertEquals(List.of(newestGhast), access.explodedGhasts);
    }

    @Test
    void changedDeadlineAndRiderReplaceDeferredTaskForSamePersistedGhast() {
        Object staleGhast = new Object();
        Object newestGhast = new Object();
        java.util.UUID replacementRider =
                java.util.UUID.fromString("4471018d-320d-4346-8c93-22f73c7b59dd");
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState original = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        GhastState replacement = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(160L), java.util.Optional.of(replacementRider));
        access.state = original;
        access.riderAvailable = false;
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();

        queue.onGhastLoad(staleGhast, java.util.Optional.of(original), access);
        assertEquals(0, queue.runDue(140L, config, access));
        access.state = replacement;
        queue.onGhastLoad(newestGhast, java.util.Optional.of(replacement), access);

        assertEquals(0, queue.onRiderAvailable(RIDER_ID));
        access.riderAvailable = true;
        assertEquals(0, queue.runDue(159L, config, access));
        assertEquals(1, queue.runDue(160L, config, access));
        assertEquals(List.of(newestGhast), access.explodedGhasts);
    }

    @Test
    void differentPersistedGhastUuidsRemainIndependent() {
        Object firstGhast = new Object();
        Object secondGhast = new Object();
        java.util.UUID secondGhastId =
                java.util.UUID.fromString("aed83452-a90b-4ea6-b5c7-837116aca026");
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.ghastIds.put(firstGhast, GHAST_ID);
        access.ghastIds.put(secondGhast, secondGhastId);
        access.states.put(firstGhast, pending);
        access.states.put(secondGhast, pending);
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();

        queue.onGhastLoad(firstGhast, java.util.Optional.of(pending), access);
        queue.onGhastLoad(secondGhast, java.util.Optional.of(pending), access);

        assertEquals(2, queue.runDue(140L, config, access));
        assertEquals(Set.of(firstGhast, secondGhast), Set.copyOf(access.explodedGhasts));
    }

    @Test
    void riderAvailabilityCallbackReactivatesDeferredTaskWithoutPollingOrDuplication() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.riderAvailable = false;
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();

        queue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        assertEquals(0, queue.runDue(140L, config, access));
        assertEquals(1, access.riderResolutions);
        assertEquals(0, queue.runDue(200L, config, access));
        assertEquals(1, access.riderResolutions);

        access.riderAvailable = true;
        assertEquals(1, queue.onRiderAvailable(RIDER_ID));
        assertEquals(0, queue.onRiderAvailable(RIDER_ID));
        assertEquals(1, queue.runDue(200L, config, access));
        assertEquals(2, access.riderResolutions);
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
    }

    @Test
    void clearDropsActiveAndRiderDeferredTasksIdempotentlyWithoutLaterWork() throws Exception {
        Object activeGhast = new Object();
        Object deferredGhast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        java.util.UUID deferredGhastId =
                java.util.UUID.fromString("d744e380-44e7-4883-954d-637522221bc7");
        GhastState active = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(200L), java.util.Optional.of(RIDER_ID));
        GhastState due = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.ghastIds.put(activeGhast, GHAST_ID);
        access.ghastIds.put(deferredGhast, deferredGhastId);
        access.states.put(activeGhast, active);
        access.states.put(deferredGhast, due);
        access.riderAvailable = false;
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(activeGhast, java.util.Optional.of(active), access);
        queue.onGhastLoad(deferredGhast, java.util.Optional.of(due), access);
        assertEquals(0, queue.runDue(140L, config, access));
        int readsBeforeClear = access.stateReads;
        int resolutionsBeforeClear = access.riderResolutions;
        queue.clear();
        queue.clear();

        access.riderAvailable = true;
        assertEquals(0, queue.onRiderAvailable(RIDER_ID));
        assertEquals(0, queue.runDue(500L, config, access));
        assertEquals(readsBeforeClear, access.stateReads);
        assertEquals(resolutionsBeforeClear, access.riderResolutions);
        assertEquals(0, access.explosions + access.directions.size()
                + access.fireOffsets.size() + access.removals);
    }

    @Test
    void consumedEffectFailureNeverReplaysOnLaterRunsOrLoadCallbacks() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        Config config = configWithOverheat(40, 6.0, 1, 0.4, 2, 1, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();

        queue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        assertEquals(1, queue.runDue(140L, config, access));
        queue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        queue.onGhastLoad(ghast, java.util.Optional.of(access.state), access);
        assertEquals(0, queue.runDue(200L, config, access));

        assertEquals(java.util.OptionalLong.empty(), access.state.detonateAtTick());
        assertEquals(1, access.explosions);
        assertEquals(1, access.directions.size());
        assertEquals(1, access.fireOffsets.size());
        assertEquals(1, access.removals);
    }
    @Test
    void freshGhastLoadWithoutAttachmentSchedulesNothingWhilePresentStateRunsOnce() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        Config config = configWithOverheat(40, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);

        queue.onGhastLoad(ghast, java.util.Optional.empty(), access);
        assertEquals(1, access.ghastIdReads);
        assertEquals(0, queue.runDue(140L, config, access));
        assertEquals(0, access.explosions + access.removals);

        access.state = pending;
        queue.onGhastLoad(ghast, java.util.Optional.of(pending), access);
        assertEquals(2, access.ghastIdReads);
        assertEquals(0, queue.runDue(139L, config, access));
        assertEquals(1, queue.runDue(140L, config, access));
        assertEquals(0, queue.runDue(140L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
    }

    @Test
    void productionGhastLoadReadsOptionalAttachmentAndUsesTheSoleFuseQueue() throws Exception {
        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        MethodNode load = exactMethod(abilities, "onGhastLoad",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;J)V");
        assertEquals(List.of(
                        "GETSTATIC xyz/pyrehaven/happyartillery/"
                                + "Abilities$ServerPlayerDetonationAccess.INSTANCE "
                                + "Lxyz/pyrehaven/happyartillery/Abilities$ServerPlayerDetonationAccess;",
                        "ASTORE 3",
                        "GETSTATIC xyz/pyrehaven/happyartillery/Abilities.FUSES "
                                + "Lxyz/pyrehaven/happyartillery/Abilities$FuseQueue;",
                        "ALOAD 0",
                        "ALOAD 3",
                        "ALOAD 0",
                        "INVOKEVIRTUAL xyz/pyrehaven/happyartillery/"
                                + "Abilities$ServerPlayerDetonationAccess.attachedState "
                                + "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)"
                                + "Ljava/util/Optional;",
                        "ALOAD 3",
                        "INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Abilities$FuseQueue.onGhastLoad "
                                + "(Ljava/lang/Object;Ljava/util/Optional;"
                                + "Lxyz/pyrehaven/happyartillery/Abilities$DetonationAccess;)V",
                        "RETURN"),
                exactOwnerInstructionShape(load));

        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerDetonationAccess");
        MethodNode attachedState = exactMethod(adapter, "attachedState",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)Ljava/util/Optional;");
        assertEquals(1, callsTo(attachedState,
                "net/fabricmc/fabric/api/attachment/v1/AttachmentTarget", "getAttached").size());
        assertEquals(1, callsTo(attachedState, "java/util/Optional", "ofNullable").size());
        assertEquals(0, callsTo(attachedState, "java/util/Objects", "requireNonNull").size());
    }
    @Test
    void equalityCrossingCommitsPendingStateWithoutOrdinaryProjectile() {
        RecordingAccess access = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        Config config = configWithHeat(1.25, 100.0);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), new Object(), original, 100L, config, BiomeClass.BASE,
                access, queue, detonation);

        GhastState pending = new GhastState(
                100.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        assertEquals(new Abilities.DetonationPending(pending), outcome);
        assertEquals(List.of(), access.events);
        assertEquals(0, access.adds + access.replacements);
        assertEquals(List.of("replace"), detonation.events);
        assertEquals(pending, detonation.state);
        assertEquals(98.75, original.heat());
    }

    @Test
    void overLimitCrossingCommitsPendingStateWithoutOrdinaryProjectile() {
        RecordingAccess access = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        Config config = configWithHeat(2.0, 100.0);
        GhastState original = new GhastState(99.0, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), new Object(), original, 100L, config, BiomeClass.BASE,
                access, queue, detonation);

        GhastState pending = new GhastState(
                101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        assertEquals(new Abilities.DetonationPending(pending), outcome);
        assertEquals(List.of(), access.events);
        assertEquals(0, access.adds + access.replacements);
        assertEquals(List.of("replace"), detonation.events);
        assertEquals(pending, detonation.state);
        assertEquals(99.0, original.heat());
    }
    @Test
    void compositionRootOwnsConfigAndBiomeCaptureWhileBothFireBoundariesReadNeither() throws Exception {
        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        MethodNode real = exactMethod(abilities, "fire",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;"
                        + "Lxyz/pyrehaven/happyartillery/GhastState;J"
                        + "Lxyz/pyrehaven/happyartillery/Config;"
                        + "Lxyz/pyrehaven/happyartillery/BiomeClass;)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireOutcome;");
        MethodNode generic = exactMethod(abilities, "fire",
                "(Ljava/lang/Object;Ljava/lang/Object;"
                        + "Lxyz/pyrehaven/happyartillery/GhastState;J"
                        + "Lxyz/pyrehaven/happyartillery/Config;"
                        + "Lxyz/pyrehaven/happyartillery/BiomeClass;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireAccess;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FuseQueue;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$DetonationAccess;)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireOutcome;");

        assertEquals(0, callsTo(real, "xyz/pyrehaven/happyartillery/Config", "current").size());
        assertEquals(0, callsTo(real, "xyz/pyrehaven/happyartillery/BiomeClass", "classify").size());
        assertEquals(0, callsTo(generic, "xyz/pyrehaven/happyartillery/Config", "current").size());
        assertEquals(0, callsTo(generic, "xyz/pyrehaven/happyartillery/BiomeClass", "classify").size());
        assertEquals(1, callsTo(generic, "xyz/pyrehaven/happyartillery/BiomeClass", "profile").size());
    }

    @Test
    void productionAdapterUsesExactVanillaOperandsGeometryAndSuccessOnlyEventFlow()
            throws Exception {
        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerFireAccess");
        MethodNode method = exactMethod(adapter, "addProjectile",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;I)Z");
        assertEquals(0, method.access & Opcodes.ACC_BRIDGE);

        List<MethodInsnNode> calls = instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .toList();
        assertEquals(1, callsTo(method,
                "xyz/pyrehaven/happyartillery/Abilities", "occupiedBounds").size());
        assertEquals(0, callsTo(method,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast",
                "getSelfAndPassengers").size());
        assertEquals(1, callsTo(method, "net/minecraft/world/entity/EntityType", "getSpawnAABB").size());
        assertEquals(1, callsTo(method, "xyz/pyrehaven/happyartillery/Abilities", "launch").size());
        assertEquals(1, callsTo(method, "java/util/Optional", "isEmpty").size());
        assertEquals(1, callsTo(method, "java/util/Optional", "get").size());
        assertEquals(0, callsTo(method, "java/util/Optional", "orElseThrow").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball", "<init>").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball", "setPos").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/server/level/ServerLevel", "addFreshEntity").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/server/level/ServerLevel", "levelEvent").size());
        assertTrue(calls.indexOf(callsTo(method,
                "net/minecraft/server/level/ServerLevel", "addFreshEntity").getFirst())
                < calls.indexOf(callsTo(method,
                "net/minecraft/server/level/ServerLevel", "levelEvent").getFirst()));
        assertEquals(1, instructions(method).stream()
                .filter(IntInsnNode.class::isInstance).map(IntInsnNode.class::cast)
                .filter(instruction -> instruction.operand == 1016).count());
        assertTrue(calls.stream().noneMatch(call -> call.name.equals("setDeltaMovement")));
        assertOptionalLaunchBranch(method, 5, Opcodes.IFEQ);
        List<String> shape = instructionShape(method);
        int constructor = shape.indexOf("INVOKESPECIAL LargeFireball.<init> "
                + "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;"
                + "Lnet/minecraft/world/phys/Vec3;I)V");
        assertEquals(List.of(
                "NEW net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball", "DUP",
                "ALOAD 4", "ALOAD 2", "ALOAD 6",
                "INVOKEVIRTUAL Abilities$Launch.direction ()Lnet/minecraft/world/phys/Vec3;",
                "ILOAD 3"), shape.subList(constructor - 7, constructor));
        int setPosition = shape.indexOf("INVOKEVIRTUAL LargeFireball.setPos "
                + "(Lnet/minecraft/world/phys/Vec3;)V");
        assertEquals(List.of(
                "ALOAD 7", "ALOAD 6",
                "INVOKEVIRTUAL Abilities$Launch.origin ()Lnet/minecraft/world/phys/Vec3;"),
                shape.subList(setPosition - 3, setPosition));

        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        MethodNode occupiedBounds = exactMethod(abilities, "occupiedBounds",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)"
                        + "Lnet/minecraft/world/phys/AABB;");
        assertEquals(1, callsTo(occupiedBounds,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast",
                "getSelfAndPassengers").size());
        Set<String> streamTargets = instructions(occupiedBounds).stream()
                .filter(InvokeDynamicInsnNode.class::isInstance)
                .map(InvokeDynamicInsnNode.class::cast)
                .flatMap(dynamic -> java.util.Arrays.stream(dynamic.bsmArgs))
                .filter(Handle.class::isInstance)
                .map(Handle.class::cast)
                .map(handle -> handle.getOwner() + "." + handle.getName())
                .collect(Collectors.toSet());
        assertTrue(streamTargets.contains("net/minecraft/world/entity/Entity.getBoundingBox"));
        assertTrue(streamTargets.contains("net/minecraft/world/phys/AABB.minmax"));
        MethodNode launch = exactMethod(abilities, "launch",
                "(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;"
                        + "Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/phys/AABB;)"
                        + "Ljava/util/Optional;");
        assertEquals(1, callsTo(launch, "net/minecraft/world/phys/AABB", "inflate").size());
    }

    @Test
    void productionOverheatFireballUsesSharedLaunchForConstructorPositionAndSpeed()
            throws Exception {
        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerDetonationAccess");
        MethodNode method = exactMethod(adapter, "spawnFireball",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;"
                        + "Lnet/minecraft/world/phys/Vec3;DI)Z");
        assertEquals(0, method.access & Opcodes.ACC_BRIDGE);
        assertEquals(1, callsTo(method,
                "xyz/pyrehaven/happyartillery/Abilities", "occupiedBounds").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast",
                "getBoundingBox").size());
        assertEquals(1, callsTo(method, "net/minecraft/world/phys/AABB", "getCenter").size());
        assertEquals(1, callsTo(method, "net/minecraft/world/entity/EntityType", "getSpawnAABB").size());
        assertEquals(1, callsTo(method,
                "xyz/pyrehaven/happyartillery/Abilities", "launch").size());
        assertEquals(1, callsTo(method, "java/util/Optional", "isEmpty").size());
        assertEquals(1, callsTo(method, "java/util/Optional", "get").size());
        assertEquals(0, callsTo(method, "java/util/Optional", "orElseThrow").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball", "<init>").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball", "setPos").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball",
                "setDeltaMovement").size());
        assertEquals(1, callsTo(method,
                "net/minecraft/server/level/ServerLevel", "addFreshEntity").size());

        assertOptionalLaunchBranch(method, 7, Opcodes.IFEQ);
        List<String> shape = instructionShape(method);
        int constructor = shape.indexOf("INVOKESPECIAL LargeFireball.<init> "
                + "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;"
                + "Lnet/minecraft/world/phys/Vec3;I)V");
        assertEquals(List.of(
                "NEW net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball", "DUP",
                "ALOAD 6", "ALOAD 1", "ALOAD 8",
                "INVOKEVIRTUAL Abilities$Launch.direction ()Lnet/minecraft/world/phys/Vec3;",
                "ILOAD 5"), shape.subList(constructor - 7, constructor));
        int setPosition = shape.indexOf("INVOKEVIRTUAL LargeFireball.setPos "
                + "(Lnet/minecraft/world/phys/Vec3;)V");
        assertEquals(List.of(
                "ALOAD 9", "ALOAD 8",
                "INVOKEVIRTUAL Abilities$Launch.origin ()Lnet/minecraft/world/phys/Vec3;"),
                shape.subList(setPosition - 3, setPosition));
        int movement = shape.indexOf("INVOKEVIRTUAL LargeFireball.setDeltaMovement "
                + "(Lnet/minecraft/world/phys/Vec3;)V");
        assertEquals(List.of(
                "ALOAD 9", "ALOAD 8",
                "INVOKEVIRTUAL Abilities$Launch.direction ()Lnet/minecraft/world/phys/Vec3;",
                "OPCODE_24 3", "INVOKEVIRTUAL Vec3.scale (D)Lnet/minecraft/world/phys/Vec3;"),
                shape.subList(movement - 5, movement));
    }

    @Test
    void launchOriginClearsGhastAndTopBottomPassengersForEveryAimAxis() throws Exception {
        net.minecraft.world.phys.AABB ghast = new net.minecraft.world.phys.AABB(
                -2.0, 0.0, -2.0, 2.0, 4.0, 2.0);
        net.minecraft.world.phys.AABB topRider = new net.minecraft.world.phys.AABB(
                -0.4, 4.0, -0.4, 0.4, 6.0, 0.4);
        net.minecraft.world.phys.AABB bottomRider = new net.minecraft.world.phys.AABB(
                -0.4, -2.0, -0.4, 0.4, 0.0, 0.4);
        List<net.minecraft.world.phys.AABB> riddenBounds = List.of(ghast, topRider, bottomRider);
        net.minecraft.world.phys.AABB union = ghast.minmax(topRider).minmax(bottomRider);
        net.minecraft.world.phys.AABB projectileAtOrigin = new net.minecraft.world.phys.AABB(
                -0.5, 0.0, -0.5, 0.5, 1.0, 0.5);
        java.lang.reflect.Method launchMethod = Abilities.class.getDeclaredMethod(
                "launch", Vec3.class, Vec3.class,
                net.minecraft.world.phys.AABB.class, net.minecraft.world.phys.AABB.class);
        List<List<Vec3>> cases = List.of(
                List.of(new Vec3(0.0, 5.0, 0.0), new Vec3(0.0, 1.0, 0.0)),
                List.of(new Vec3(0.0, -1.0, 0.0), new Vec3(0.0, -1.0, 0.0)),
                List.of(new Vec3(0.0, 2.0, 0.0), new Vec3(1.0, 0.0, 0.0)),
                List.of(new Vec3(0.0, 2.0, 0.0), new Vec3(1.0, 1.0, 1.0)));

        for (List<Vec3> launchCase : cases) {
            Object launch = ((java.util.Optional<?>) launchMethod.invoke(
                    null, launchCase.get(0), launchCase.get(1), union, projectileAtOrigin))
                    .orElseThrow();
            Vec3 origin = (Vec3) launch.getClass().getDeclaredMethod("origin").invoke(launch);
            Vec3 direction = (Vec3) launch.getClass().getDeclaredMethod("direction").invoke(launch);
            assertEquals(launchCase.get(1).normalize(), direction);
            net.minecraft.world.phys.AABB spawned = projectileAtOrigin.move(origin);
            for (net.minecraft.world.phys.AABB ridden : riddenBounds) {
                assertFalse(spawned.intersects(ridden), launchCase + " intersects " + ridden);
            }
        }
    }

    @Test
    void allTwentyFourSphereLaunchesAreDistinctUnitAndClearCompleteRiddenBounds()
            throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.world.phys.AABB ghast = new net.minecraft.world.phys.AABB(
                -2.0, 0.0, -2.0, 2.0, 4.0, 2.0);
        net.minecraft.world.phys.AABB topRider = new net.minecraft.world.phys.AABB(
                -0.4, 4.0, -0.4, 0.4, 6.0, 0.4);
        net.minecraft.world.phys.AABB bottomRider = new net.minecraft.world.phys.AABB(
                -0.4, -2.0, -0.4, 0.4, 0.0, 0.4);
        net.minecraft.world.phys.AABB sideRider = new net.minecraft.world.phys.AABB(
                2.0, 1.0, -0.5, 5.0, 3.0, 0.5);
        List<net.minecraft.world.phys.AABB> riddenBounds =
                List.of(ghast, topRider, bottomRider, sideRider);
        net.minecraft.world.phys.AABB union = ghast.minmax(topRider)
                .minmax(bottomRider).minmax(sideRider);
        net.minecraft.world.phys.AABB projectileAtOrigin =
                net.minecraft.world.entity.EntityTypes.FIREBALL.getSpawnAABB(0.0, 0.0, 0.0);
        Vec3 start = ghast.getCenter();
        java.lang.reflect.Method sphereDirection = Abilities.class.getDeclaredMethod(
                "sphereDirection", int.class, int.class);
        sphereDirection.setAccessible(true);
        Set<Vec3> directions = new java.util.HashSet<>();
        Set<Vec3> origins = new java.util.HashSet<>();
        Set<net.minecraft.world.phys.AABB> spawnedBounds = new java.util.HashSet<>();

        for (int index = 0; index < 24; index++) {
            Vec3 direction = (Vec3) sphereDirection.invoke(null, index, 24);
            Abilities.Launch launch = Abilities.launch(
                    start, direction, union, projectileAtOrigin).orElseThrow();
            net.minecraft.world.phys.AABB spawned = projectileAtOrigin.move(launch.origin());

            assertTrue(Math.abs(direction.length() - 1.0) < 1.0E-12);
            assertTrue(launch.direction().distanceTo(direction) < 1.0E-12);
            for (net.minecraft.world.phys.AABB ridden : riddenBounds) {
                assertFalse(spawned.intersects(ridden), index + " intersects " + ridden);
            }
            directions.add(direction);
            origins.add(launch.origin());
            spawnedBounds.add(spawned);
        }

        assertEquals(24, directions.size());
        assertEquals(24, origins.size());
        assertEquals(24, spawnedBounds.size());
    }

    @Test
    void genericDetonationConstructsAndHandsOffEachSphereDirectionOnce() throws Exception {
        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        MethodNode effectLoop = exactMethod(abilities, "executeDetonation",
                "(Ljava/lang/Object;JLjava/util/UUID;J"
                        + "Lxyz/pyrehaven/happyartillery/Config;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$DetonationAccess;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$ExecutionEvidence;"
                        + "Ljava/util/Optional;)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$DetonationOutcome;");
        String sphereDescriptor = "(II)Lnet/minecraft/world/phys/Vec3;";
        String spawnDescriptor = "(Ljava/lang/Object;Lnet/minecraft/world/phys/Vec3;DI)Z";
        List<MethodInsnNode> sphereCalls = callsTo(effectLoop,
                "xyz/pyrehaven/happyartillery/Abilities", "sphereDirection");
        List<MethodInsnNode> spawnCalls = callsTo(effectLoop,
                "xyz/pyrehaven/happyartillery/Abilities$DetonationAccess", "spawnFireball");

        assertEquals(1, sphereCalls.size());
        assertEquals(sphereDescriptor, sphereCalls.getFirst().desc);
        assertEquals(1, spawnCalls.size());
        assertEquals(spawnDescriptor, spawnCalls.getFirst().desc);
        List<String> shape = exactOwnerInstructionShape(effectLoop);
        int spawn = shape.indexOf("INVOKEINTERFACE "
                + "xyz/pyrehaven/happyartillery/Abilities$DetonationAccess.spawnFireball "
                + spawnDescriptor);
        assertEquals(List.of(
                "ALOAD 7",
                "ALOAD 0",
                "ILOAD 15",
                "ALOAD 12",
                "INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Config$Overheat.fireballCount ()I",
                "INVOKESTATIC xyz/pyrehaven/happyartillery/Abilities.sphereDirection "
                        + sphereDescriptor,
                "ALOAD 12",
                "INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Config$Overheat.fireballSpeed ()D",
                "ALOAD 12",
                "INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Config$Overheat.fireballPower ()I",
                "INVOKEINTERFACE "
                        + "xyz/pyrehaven/happyartillery/Abilities$DetonationAccess.spawnFireball "
                        + spawnDescriptor),
                shape.subList(spawn - 10, spawn + 1));
        assertEquals(0, callsTo(effectLoop,
                "net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball",
                "setPos").size());
        assertEquals(0, callsTo(effectLoop,
                "net/minecraft/server/level/ServerLevel", "addFreshEntity").size());
    }

    @Test
    void launchReturnsEmptyForFiniteGameplayGeometryRejectionsButKeepsImpossibleDataLoud() {
        net.minecraft.world.phys.AABB occupied = new net.minecraft.world.phys.AABB(
                -2.0, -2.0, -2.0, 2.0, 2.0, 2.0);
        net.minecraft.world.phys.AABB projectile = new net.minecraft.world.phys.AABB(
                -0.5, -0.5, -0.5, 0.5, 0.5, 0.5);

        assertEquals(java.util.Optional.empty(),
                Abilities.launch(Vec3.ZERO, Vec3.ZERO, occupied, projectile));
        assertEquals(java.util.Optional.empty(), Abilities.launch(Vec3.ZERO,
                new Vec3(1.0E-7, 0.0, 0.0), occupied, projectile));
        assertEquals(java.util.Optional.empty(), Abilities.launch(new Vec3(20.0, 0.0, 0.0),
                new Vec3(1.0, 0.0, 0.0), occupied, projectile));
        assertEquals(java.util.Optional.empty(), Abilities.launch(
                new Vec3(-Double.MAX_VALUE, 0.0, 0.0), new Vec3(1.0, 0.0, 0.0),
                new net.minecraft.world.phys.AABB(
                        -Double.MAX_VALUE, -1.0, -1.0, Double.MAX_VALUE, 1.0, 1.0), projectile));
        assertThrows(IllegalArgumentException.class,
                () -> Abilities.launch(Vec3.ZERO,
                        new Vec3(Double.NaN, 0.0, 0.0), occupied, projectile));
        assertThrows(IllegalArgumentException.class,
                () -> Abilities.launch(new Vec3(Double.POSITIVE_INFINITY, 0.0, 0.0),
                        new Vec3(1.0, 0.0, 0.0), occupied, projectile));
        assertThrows(IllegalArgumentException.class,
                () -> Abilities.launch(Vec3.ZERO, new Vec3(1.0, 0.0, 0.0),
                        new net.minecraft.world.phys.AABB(
                                Double.NaN, 0.0, 0.0, 1.0, 1.0, 1.0), projectile));
        assertThrows(IllegalArgumentException.class,
                () -> Abilities.launch(Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), occupied,
                        new net.minecraft.world.phys.AABB(
                                0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, 1.0, 1.0)));
        assertThrows(NullPointerException.class,
                () -> Abilities.launch(null, Vec3.ZERO, occupied, projectile));
        assertThrows(NullPointerException.class,
                () -> Abilities.launch(Vec3.ZERO, null, occupied, projectile));
        assertThrows(NullPointerException.class,
                () -> Abilities.launch(Vec3.ZERO, Vec3.ZERO, null, projectile));
        assertThrows(NullPointerException.class,
                () -> Abilities.launch(Vec3.ZERO, Vec3.ZERO, occupied, null));
    }
    private static Abilities.CryOutcome cry(
            GhastState state, long now, Config config, RecordingCryAccess access) {
        return Abilities.cry(new Object(), new Object(), state, now, config, access);
    }

    private static Abilities.FireOutcome fire(
            GhastState state, long now, RecordingAccess access) {
        return fire(state, now, Config.defaults(), access);
    }

    private static Abilities.FireOutcome fire(
            GhastState state, long now, Config config, RecordingAccess access) {
        return Abilities.fire(
                new Object(), new Object(), state, now, config,
                BiomeClass.BASE, access, new Abilities.FuseQueue<>(),
                new RecordingDetonationAccess());
    }

    private static Config configWithCry(boolean enabled, double volume, double cooldownSeconds) {
        Config defaults = Config.defaults();
        return new Config(defaults.controls(), defaults.fire(), defaults.heat(),
                defaults.water(), defaults.overheat(), new Config.Cry(enabled, volume, cooldownSeconds),
                defaults.hud());
    }

    private static Config configWithHeat(double heatPerShot, double limit) {
        Config defaults = Config.defaults();
        Config.Heat original = defaults.heat();
        Config.HeatProfile profile = new Config.HeatProfile(heatPerShot, 0.0);
        Config.Heat heat = new Config.Heat(
                limit, original.firingWindowSeconds(), profile, profile, profile, profile, profile,
                original.coldMaxTemperature(), original.hotMinTemperature(),
                original.unknownDimensionUsesTemperature());
        Config.Overheat originalOverheat = defaults.overheat();
        Config.Overheat overheat = new Config.Overheat(
                40, originalOverheat.explosionPower(), originalOverheat.fireballCount(),
                originalOverheat.fireballSpeed(), originalOverheat.fireballPower(),
                originalOverheat.fireAttempts(), originalOverheat.fireRadius(),
                originalOverheat.killsGhast(), originalOverheat.breaksBlocks());
        return new Config(defaults.controls(), defaults.fire(), heat,
                defaults.water(), overheat, defaults.cry(), defaults.hud());
    }

    private static Config configWithOverheat(
            int fuseTicks, double explosionPower, int fireballCount, double fireballSpeed,
            int fireballPower, int fireAttempts, double fireRadius, boolean killsGhast,
            boolean breaksBlocks) {
        Config defaults = Config.defaults();
        Config.Overheat overheat = new Config.Overheat(
                fuseTicks, explosionPower, fireballCount, fireballSpeed, fireballPower,
                fireAttempts, fireRadius, killsGhast, breaksBlocks);
        return new Config(defaults.controls(), defaults.fire(), defaults.heat(),
                defaults.water(), overheat, defaults.cry(), defaults.hud());
    }

    private static final class RecordingFeedbackAccess implements Feedback.Access<Object> {
        private final List<String> events = new ArrayList<>();

        @Override
        public void actionBar(Object player, String message) {
            events.add("action:" + message);
        }

        @Override
        public void blockedSound(Object player) {
            events.add("sound");
        }
    }

    private static final class RecordingCryAccess implements Abilities.CryAccess<Object, Object> {
        private boolean pilot = true;
        private boolean inWater;
        private int pilotChecks;
        private int waterChecks;
        private int sounds;
        private int replacements;
        private double volume;
        private GhastState replacedState;
        private final List<String> events = new ArrayList<>();

        @Override
        public boolean isPilot(Object pilotObject, Object ghast) {
            pilotChecks++;
            return pilot;
        }

        @Override
        public boolean inWater(Object ghast) {
            waterChecks++;
            return inWater;
        }

        @Override
        public void playCry(Object ghast, double volume) {
            sounds++;
            this.volume = volume;
            events.add("sound");
        }

        @Override
        public void replaceState(Object ghast, GhastState state) {
            replacements++;
            replacedState = state;
            events.add("replace");
        }
    }

    private static final class RecordingAccess implements Abilities.FireAccess<Object, Object> {
        private boolean pilot = true;
        private boolean inWater;
        private boolean addSucceeds = true;
        private int pilotChecks;
        private int waterChecks;
        private int adds;
        private int replacements;
        private int explosionPower;
        private GhastState replacedState;
        private final List<String> events = new ArrayList<>();

        @Override
        public boolean isPilot(Object pilotObject, Object ghast) {
            pilotChecks++;
            return pilot;
        }

        @Override
        public boolean inWater(Object ghast) {
            waterChecks++;
            return inWater;
        }

        @Override
        public boolean addProjectile(Object pilotObject, Object ghast, int explosionPower) {
            adds++;
            this.explosionPower = explosionPower;
            events.add("add");
            return addSucceeds;
        }

        @Override
        public void replaceState(Object ghast, GhastState state) {
            replacements++;
            replacedState = state;
            events.add("replace");
        }
    }

    private static final class RecordingDetonationAccess
            implements Abilities.DetonationAccess<Object, Object> {
        private GhastState state = GhastState.fresh();
        private GhastState replacedState;
        private GhastState stateAtRemoval;
        private boolean loaded = true;
        private boolean riderAvailable = true;
        private boolean fireballAccepted = true;
        private final List<Boolean> fireballResults = new ArrayList<>();

        private boolean attachmentPresent = true;
        private RuntimeException replacementFailure;
        private RuntimeException explosionFailure;
        private RuntimeException stateFailure;
        private RuntimeException loadedFailure;
        private int stateReads;
        private int ghastIdReads;
        private int riderResolutions;
        private int explosions;
        private boolean explosionBreaksBlocks;
        private int removals;
        private final List<Abilities.FireAttempt> fireAttempts = new ArrayList<>();
        private final List<net.minecraft.world.phys.Vec3> directions = new ArrayList<>();
        private final List<net.minecraft.world.phys.Vec3> fireOffsets = new ArrayList<>();
        private final List<Object> explodedGhasts = new ArrayList<>();
        private final List<Object> resolvedGhasts = new ArrayList<>();
        private final Map<Object, java.util.UUID> ghastIds = new java.util.IdentityHashMap<>();
        private final Map<Object, GhastState> states = new java.util.IdentityHashMap<>();
        private final Map<java.util.UUID, Object> loadedGhasts = new HashMap<>();
        private final Set<Object> missingAttachments =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        private final Map<Object, RuntimeException> replacementFailures =
                new java.util.IdentityHashMap<>();
        private final Map<Object, Runnable> explosionActions =
                new java.util.IdentityHashMap<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public java.util.UUID riderId(Object rider) {
            return RIDER_ID;
        }

        @Override
        public java.util.UUID ghastId(Object ghast) {
            ghastIdReads++;
            java.util.UUID ghastId = ghastIds.getOrDefault(ghast, GHAST_ID);
            loadedGhasts.put(ghastId, ghast);
            return ghastId;
        }

        @Override
        public Object resolveGhast(java.util.UUID ghastId) {
            if (loadedFailure != null) {
                throw loadedFailure;
            }
            Object resolved = loaded ? loadedGhasts.get(ghastId) : null;
            if (resolved != null) {
                resolvedGhasts.add(resolved);
            }
            return resolved;
        }

        @Override
        public Object resolveRider(Object ghast, java.util.UUID riderId) {
            riderResolutions++;
            return riderAvailable ? new Object() : null;
        }

        @Override
        public java.util.Optional<GhastState> attachedState(Object ghast) {
            stateReads++;
            if (stateFailure != null) {
                throw stateFailure;
            }
            return attachmentPresent && !missingAttachments.contains(ghast)
                    ? java.util.Optional.of(states.getOrDefault(ghast, state))
                    : java.util.Optional.empty();
        }

        @Override
        public void replaceState(Object ghast, GhastState state) {
            events.add("replace");
            RuntimeException perGhastFailure = replacementFailures.get(ghast);
            if (perGhastFailure != null) {
                throw perGhastFailure;
            }
            if (replacementFailure != null) {
                throw replacementFailure;
            }
            this.state = state;
            states.put(ghast, state);
            replacedState = state;
        }

        @Override
        public void explode(Object pilot, Object ghast, double power, boolean breaksBlocks) {
            explosions++;
            explodedGhasts.add(ghast);
            explosionBreaksBlocks = breaksBlocks;
            events.add("explode:" + power + ":" + breaksBlocks);
            Runnable action = explosionActions.remove(ghast);
            if (action != null) {
                action.run();
            }
            if (explosionFailure != null) {
                throw explosionFailure;
            }
        }

        @Override
        public boolean spawnFireball(Object ghast, net.minecraft.world.phys.Vec3 direction,
                double speed, int power) {
            directions.add(direction);
            events.add("fireball");
            return fireballResults.isEmpty() ? fireballAccepted : fireballResults.removeFirst();
        }

        @Override
        public Abilities.FireAttempt placeFire(
                Object ghast, net.minecraft.world.phys.Vec3 offset) {
            fireOffsets.add(offset);
            events.add("fire");
            return fireAttempts.isEmpty()
                    ? Abilities.FireAttempt.ACCEPTED
                    : fireAttempts.removeFirst();
        }

        @Override
        public void remove(Object ghast) {
            removals++;
            stateAtRemoval = states.getOrDefault(ghast, state);
            events.add("remove");
        }
    }

    private static int fieldSize(Object owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(owner);
        return value instanceof Map<?, ?> map ? map.size()
                : ((java.util.Collection<?>) value).size();
    }

    private static MethodNode exactMethod(ClassNode owner, String name, String descriptor) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .toList();
        assertEquals(1, matches.size(), owner.name + "." + name + descriptor);
        return matches.getFirst();
    }

    private static List<MethodInsnNode> callsTo(MethodNode method, String owner, String name) {
        return instructions(method).stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(owner) && call.name.equals(name))
                .toList();
    }

    private static void assertOptionalLaunchBranch(
            MethodNode method, int candidateLocal, int expectedOpcode) {
        String launchDescriptor = "(Lnet/minecraft/world/phys/Vec3;"
                + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                + "Lnet/minecraft/world/phys/AABB;)Ljava/util/Optional;";
        List<AbstractInsnNode> semantic = instructions(method);
        List<MethodInsnNode> launchCalls = semantic.stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals("xyz/pyrehaven/happyartillery/Abilities")
                        && call.name.equals("launch") && call.desc.equals(launchDescriptor))
                .toList();
        assertEquals(1, launchCalls.size());
        int launchIndex = semantic.indexOf(launchCalls.getFirst());
        assertTrue(semantic.get(launchIndex + 1) instanceof VarInsnNode);
        VarInsnNode candidateStore = (VarInsnNode) semantic.get(launchIndex + 1);
        assertEquals(Opcodes.ASTORE, candidateStore.getOpcode());
        assertEquals(candidateLocal, candidateStore.var);
        assertEquals(1, semantic.stream()
                .filter(VarInsnNode.class::isInstance)
                .map(VarInsnNode.class::cast)
                .filter(instruction -> instruction.getOpcode() == Opcodes.ASTORE
                        && instruction.var == candidateLocal)
                .count());

        List<MethodInsnNode> emptyCalls = semantic.stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals("java/util/Optional")
                        && call.name.equals("isEmpty") && call.desc.equals("()Z"))
                .toList();
        assertEquals(1, emptyCalls.size());
        int emptyCallIndex = semantic.indexOf(emptyCalls.getFirst());
        assertTrue(semantic.get(emptyCallIndex - 1) instanceof VarInsnNode);
        VarInsnNode emptyReceiver = (VarInsnNode) semantic.get(emptyCallIndex - 1);
        assertEquals(Opcodes.ALOAD, emptyReceiver.getOpcode());
        assertEquals(candidateLocal, emptyReceiver.var);
        assertTrue(semantic.get(emptyCallIndex + 1) instanceof JumpInsnNode);
        JumpInsnNode branch = (JumpInsnNode) semantic.get(emptyCallIndex + 1);
        assertEquals(expectedOpcode, branch.getOpcode());

        AbstractInsnNode target = branch.label;
        while (target != null && target.getOpcode() < 0) {
            target = target.getNext();
        }
        int targetIndex = semantic.indexOf(target);
        assertTrue(targetIndex >= 0);
        int fallthroughIndex = emptyCallIndex + 2;
        int emptyIndex = expectedOpcode == Opcodes.IFEQ ? fallthroughIndex : targetIndex;
        int nonEmptyIndex = expectedOpcode == Opcodes.IFEQ ? targetIndex : fallthroughIndex;
        assertEquals(Opcodes.ICONST_0, semantic.get(emptyIndex).getOpcode());
        assertEquals(Opcodes.IRETURN, semantic.get(emptyIndex + 1).getOpcode());

        assertTrue(semantic.get(nonEmptyIndex) instanceof VarInsnNode);
        VarInsnNode getReceiver = (VarInsnNode) semantic.get(nonEmptyIndex);
        assertEquals(Opcodes.ALOAD, getReceiver.getOpcode());
        assertEquals(candidateLocal, getReceiver.var);
        assertTrue(semantic.get(nonEmptyIndex + 1) instanceof MethodInsnNode);
        MethodInsnNode get = (MethodInsnNode) semantic.get(nonEmptyIndex + 1);
        assertEquals("java/util/Optional", get.owner);
        assertEquals("get", get.name);
        assertEquals("()Ljava/lang/Object;", get.desc);
        assertEquals(1, semantic.stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals("java/util/Optional")
                        && call.name.equals("get")
                        && call.desc.equals("()Ljava/lang/Object;"))
                .count());
        List<TypeInsnNode> constructions = semantic.stream()
                .filter(TypeInsnNode.class::isInstance)
                .map(TypeInsnNode.class::cast)
                .filter(instruction -> instruction.getOpcode() == Opcodes.NEW
                        && instruction.desc.equals("net/minecraft/world/entity/projectile/"
                                + "hurtingprojectile/LargeFireball"))
                .toList();
        assertEquals(1, constructions.size());
        assertTrue(nonEmptyIndex + 1 < semantic.indexOf(constructions.getFirst()));
    }

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        return Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                        AbstractInsnNode::getNext)
                .filter(instruction -> instruction.getOpcode() >= 0)
                .toList();
    }

    private static List<String> instructionShape(MethodNode method) {
        return instructionShape(method, false);
    }

    private static List<String> exactOwnerInstructionShape(MethodNode method) {
        return instructionShape(method, true);
    }

    private static List<String> instructionShape(MethodNode method, boolean exactOwners) {
        List<AbstractInsnNode> semantic = instructions(method);
        Map<LabelNode, Integer> targets = new HashMap<>();
        List<LabelNode> pendingLabels = new ArrayList<>();
        int semanticIndex = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof LabelNode label) {
                pendingLabels.add(label);
            } else if (instruction.getOpcode() >= 0) {
                for (LabelNode label : pendingLabels) {
                    targets.put(label, semanticIndex);
                }
                pendingLabels.clear();
                semanticIndex++;
            }
        }
        return semantic.stream()
                .map(instruction -> render(instruction, targets, exactOwners))
                .toList();
    }

    private static String render(
            AbstractInsnNode instruction,
            Map<LabelNode, Integer> targets,
            boolean exactOwners) {
        String opcode = opcodeName(instruction.getOpcode());
        if (instruction instanceof VarInsnNode variable) {
            return opcode + " " + variable.var;
        }
        if (instruction instanceof TypeInsnNode type) {
            return opcode + " " + type.desc;
        }
        if (instruction instanceof MethodInsnNode method) {
            String owner = exactOwners ? method.owner : simpleOwner(method.owner);
            return opcode + " " + owner + "." + method.name + " " + method.desc;
        }
        if (instruction instanceof FieldInsnNode field) {
            String owner = exactOwners ? field.owner : simpleOwner(field.owner);
            return opcode + " " + owner + "." + field.name + " " + field.desc;
        }
        if (instruction instanceof LdcInsnNode constant) {
            return opcode + " " + constant.cst;
        }
        if (instruction instanceof IntInsnNode integer) {
            return opcode + " " + integer.operand;
        }
        if (instruction instanceof JumpInsnNode jump) {
            return opcode + " -> " + targets.get(jump.label);
        }
        return opcode;
    }

    private static String simpleOwner(String owner) {
        return owner.substring(owner.lastIndexOf('/') + 1);
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.ALOAD -> "ALOAD";
            case Opcodes.LLOAD -> "LLOAD";
            case Opcodes.ASTORE -> "ASTORE";
            case Opcodes.ILOAD -> "ILOAD";
            case Opcodes.FCONST_1 -> "FCONST_1";
            case Opcodes.NEW -> "NEW";
            case Opcodes.CHECKCAST -> "CHECKCAST";
            case Opcodes.DUP -> "DUP";
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.GETSTATIC -> "GETSTATIC";
            case Opcodes.GETFIELD -> "GETFIELD";
            case Opcodes.LDC -> "LDC";
            case Opcodes.DMUL -> "DMUL";
            case Opcodes.DADD -> "DADD";
            case Opcodes.IFNE -> "IFNE";
            case Opcodes.ICONST_0 -> "ICONST_0";
            case Opcodes.ICONST_1 -> "ICONST_1";
            case Opcodes.ACONST_NULL -> "ACONST_NULL";
            case Opcodes.SIPUSH -> "SIPUSH";
            case Opcodes.IRETURN -> "IRETURN";
            case Opcodes.ARETURN -> "ARETURN";
            case Opcodes.RETURN -> "RETURN";
            default -> "OPCODE_" + opcode;
        };
    }

}
