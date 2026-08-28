package xyz.pyrehaven.happyartillery;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AbilitiesTest {
    private static final java.util.UUID RIDER_ID =
            java.util.UUID.fromString("acaa4238-f209-4370-b4c2-adabf234d966");
    private static final java.util.UUID GHAST_ID =
            java.util.UUID.fromString("59242a5a-2402-4f37-99e7-bb8db118af15");

    @Test
    void cryBoundaryIsSealedAndExposesOneEffectAccess() throws Exception {
        Class<?> outcome = Class.forName("xyz.pyrehaven.happyartillery.Abilities$CryOutcome");
        Class<?> access = Class.forName("xyz.pyrehaven.happyartillery.Abilities$CryAccess");

        assertTrue(outcome.isSealed());
        assertEquals(Set.of("Cried", "CryRejected"),
                Stream.of(outcome.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .collect(Collectors.toSet()));
        assertEquals(Set.of("isPilot", "inWater", "playCry", "replaceState"),
                Stream.of(access.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(Collectors.toSet()));
        assertEquals(outcome, Abilities.class.getDeclaredMethod(
                "cry", Object.class, Object.class, GhastState.class, long.class, Config.class, access)
                .getReturnType());
    }

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
    void rejectedSoundDoesNotCommitCryDeadlineOrAnyOtherState() {
        RecordingCryAccess access = new RecordingCryAccess();
        access.soundAccepted = false;
        GhastState original = new GhastState(20.0, 50L, 75L, 90L, 100L,
                java.util.OptionalLong.of(600L), java.util.Optional.of(RIDER_ID));

        Abilities.CryOutcome outcome = cry(original, 100L, Config.defaults(), access);

        assertEquals(new Abilities.CryRejected(Abilities.CryRejection.EFFECT_FAILED), outcome);
        assertEquals(List.of("sound"), access.events);
        assertEquals(1, access.sounds);
        assertEquals(0, access.replacements);
        assertEquals(100L, original.cryReadyTick());
    }

    @Test
    void feedbackBoundaryOwnsActionBarAndBlockedSoundEffects() throws Exception {
        Class<?> access = Class.forName("xyz.pyrehaven.happyartillery.Feedback$Access");

        assertEquals(Set.of("actionBar", "blockedSound"),
                Stream.of(access.getDeclaredMethods())
                        .map(java.lang.reflect.Method::getName)
                        .collect(Collectors.toSet()));
        assertEquals(void.class, Feedback.class.getDeclaredMethod(
                "present", Abilities.CryRejection.class, Object.class, access).getReturnType());
    }

    @Test
    void inWaterCryRejectionMapsToOneShortActionBarLineAndDistinctSound() {
        RecordingFeedbackAccess access = new RecordingFeedbackAccess();
        Object player = new Object();

        Feedback.present(Abilities.CryRejection.IN_WATER, player, access);

        assertEquals(List.of("action:Can't use artillery in water", "sound"), access.events);
    }

    @Test
    void cooldownAndAuthorizationCryRejectionsStaySilent() {
        RecordingFeedbackAccess access = new RecordingFeedbackAccess();
        Object player = new Object();

        Feedback.present(Abilities.CryRejection.ON_COOLDOWN, player, access);
        Feedback.present(Abilities.CryRejection.NOT_PILOT, player, access);

        assertEquals(List.of(), access.events);
    }

    @Test
    void minecraftCryAdapterUsesGhastScreamHostileConfiguredVolumeAndPointEightPitch()
            throws Exception {
        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        MethodNode real = exactMethod(abilities, "cry",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;"
                        + "Lxyz/pyrehaven/happyartillery/GhastState;J)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$CryOutcome;");
        assertEquals(1, callsTo(real, "xyz/pyrehaven/happyartillery/Config", "current").size());

        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerCryAccess");
        MethodNode play = exactMethod(adapter, "playCry",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;D)Z");
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
        MethodNode real = exactMethod(feedback, "present",
                "(Lxyz/pyrehaven/happyartillery/Abilities$CryRejection;"
                        + "Lnet/minecraft/server/level/ServerPlayer;)V");
        assertEquals(1, callsTo(real, "xyz/pyrehaven/happyartillery/Feedback", "present").size());

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
    void fireOutcomesIncludeAnExplicitOverheatCrossingBoundary() throws Exception {
        Class<?> outcome = Class.forName("xyz.pyrehaven.happyartillery.Abilities$FireOutcome");

        assertTrue(outcome.isSealed());
        assertEquals(Set.of("Fired", "OverheatCrossing", "Detonated", "DetonationPending", "Rejected"),
                Stream.of(outcome.getPermittedSubclasses())
                        .map(Class::getSimpleName)
                        .collect(Collectors.toSet()));
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
    void pendingDetonationLocksOutFurtherShotsBeforeHeatOrProjectileSpend() {
        RecordingAccess access = new RecordingAccess();
        GhastState pending = new GhastState(99.0, 100L, 120L, 0L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));

        Abilities.FireOutcome outcome = fire(pending, 110L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.DETONATION_PENDING), outcome);
        assertEquals(0, access.waterChecks + access.adds + access.replacements);
    }

    @Test
    void crossingWithFuseCommitsAbsoluteDeadlineAndQueuesOneTask() {
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        Config config = configWithOverheat(40, 6.0, 3, 0.4, 2, 4, 8.0, true, true);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), new Object(), original, 100L, config, BiomeClass.BASE, fire,
                queue, detonation);

        assertTrue(outcome instanceof Abilities.DetonationPending);
        GhastState pending = ((Abilities.DetonationPending) outcome).state();
        assertEquals(java.util.OptionalLong.of(140L), pending.detonateAtTick());
        assertEquals(java.util.Optional.of(RIDER_ID), pending.detonatingRiderId());
        assertEquals(pending, detonation.replacedState);
        assertEquals(List.of("replace"), detonation.events);
        assertEquals(1, queue.queuedTasks());
        assertEquals(1, queue.ownedTasks());
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
        assertEquals(0, queue.ownedTasks());
    }

    @Test
    void zeroFuseReportsEffectFailureAfterQueueConsumesPendingEvent() {
        RecordingAccess fire = new RecordingAccess();
        RecordingDetonationAccess detonation = new RecordingDetonationAccess();
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        detonation.explosionAccepted = false;
        Config config = configWithOverheat(0, 6.0, 0, 0.4, 2, 0, 8.0,
                true, true);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = Abilities.fire(
                new Object(), new Object(), original, 100L, config, BiomeClass.BASE, fire,
                queue, detonation);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.EFFECT_FAILED), outcome);
        assertEquals(java.util.OptionalLong.empty(), detonation.state.detonateAtTick());
        assertEquals(java.util.Optional.empty(), detonation.state.detonatingRiderId());
        assertEquals(0, queue.ownedTasks());
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
        assertEquals(0, queue.queuedTasks());
        assertEquals(1, queue.deferredTasks());
        assertEquals(1, queue.ownedTasks());
        detonation.riderAvailable = true;
        assertEquals(1, queue.onRiderAvailable(RIDER_ID));
        assertEquals(1, queue.runDue(101L, config, detonation));
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
        assertEquals(0, queue.ownedTasks());
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
        assertEquals(0, queue.ownedTasks());
        detonation.loaded = true;
        queue.onGhastLoad(ghast, detonation.state, 101L, detonation);
        queue.onGhastLoad(ghast, detonation.state, 101L, detonation);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());
        assertEquals(1, queue.runDue(101L, config, detonation));
        assertEquals(1, detonation.explosions);
        assertEquals(1, detonation.removals);
        assertEquals(0, queue.ownedTasks());
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
        assertEquals(java.util.OptionalLong.empty(), access.replacedState.detonateAtTick());
        assertEquals(0, access.removals);
    }

    @Test
    void rejectedEffectsStillRunOneCompletePassAfterConsumptionThenAttemptRemoval() {
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.explosionAccepted = false;
        access.fireballAccepted = false;
        access.fireAttempts.addAll(List.of(
                Abilities.FireAttempt.SKIPPED,
                Abilities.FireAttempt.REJECTED));
        access.removalAccepted = false;
        Config config = configWithOverheat(0, 6.0, 2, 0.4, 2, 2, 8.0,
                true, true);

        Abilities.DetonationOutcome outcome = Abilities.executeDetonation(
                new Object(), 140L, config, access);

        assertEquals(new Abilities.DetonationConsumedWithFailures(5), outcome);
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
        queue.onGhastLoad(ghast, pending, 120L, access);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertEquals("attachment write failed", failure.getMessage());
        assertEquals(List.of("replace"), access.events);
        assertEquals(0, access.explosions + access.directions.size()
                + access.fireOffsets.size() + access.removals);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());
        assertEquals(0, queue.deferredTasks());
        queue.onGhastLoad(ghast, pending, 141L, access);
        queue.onGhastLoad(ghast, pending, 141L, access);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());

        access.replacementFailure = null;
        assertEquals(1, queue.runDue(150L, config, access));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
        assertEquals(0, queue.ownedTasks());
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
        queue.onGhastLoad(ghast, pending, 120L, access);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertSame(access.stateFailure, failure);
        assertEquals(1, access.stateReads);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());
        assertEquals(0, queue.deferredTasks());
        queue.onGhastLoad(ghast, pending, 141L, access);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());

        access.stateFailure = null;
        assertEquals(1, queue.runDue(150L, config, access));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
        assertEquals(0, queue.ownedTasks());
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
        queue.onGhastLoad(ghast, pending, 120L, access);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertSame(access.loadedFailure, failure);
        assertEquals(0, access.stateReads);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());
        assertEquals(0, queue.deferredTasks());
        queue.onGhastLoad(ghast, pending, 141L, access);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());

        access.loadedFailure = null;
        assertEquals(1, queue.runDue(150L, config, access));
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
        assertEquals(0, queue.ownedTasks());
    }

    @Test
    void unexpectedPostConsumptionEffectExceptionCleansOnNextDueRunWithoutReplay() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        GhastState pending = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.state = pending;
        access.explosionFailure = new IllegalStateException("effect exploded unexpectedly");
        Config config = configWithOverheat(0, 6.0, 1, 0.4, 2, 1, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();
        queue.onGhastLoad(ghast, pending, 120L, access);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> queue.runDue(140L, config, access));

        assertEquals("effect exploded unexpectedly", failure.getMessage());
        assertEquals(java.util.OptionalLong.empty(), access.state.detonateAtTick());
        assertEquals(java.util.Optional.empty(), access.state.detonatingRiderId());
        assertEquals(1, access.explosions);
        assertEquals(0, access.directions.size() + access.fireOffsets.size() + access.removals);
        assertEquals(1, queue.ownedTasks());
        assertEquals(1, queue.queuedTasks());
        assertEquals(0, queue.deferredTasks());
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(0, access.directions.size() + access.fireOffsets.size() + access.removals);
        assertEquals(0, queue.ownedTasks());
        queue.onGhastLoad(ghast, access.state, 150L, access);
        queue.onGhastLoad(ghast, access.state, 150L, access);
        assertEquals(0, queue.runDue(150L, config, access));
        assertEquals(1, access.explosions);
        assertEquals(0, queue.ownedTasks());
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
        assertEquals(1, queue.ownedTasks());
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

        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        assertEquals(1, abilities.methods.stream()
                .flatMap(method -> instructions(method).stream())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals(
                        "xyz/pyrehaven/happyartillery/Abilities$FuseQueue")
                        && call.name.equals("submit"))
                .count());
        assertEquals(0, abilities.methods.stream()
                .flatMap(method -> instructions(method).stream())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals("xyz/pyrehaven/happyartillery/Abilities")
                        && call.name.equals("executeDetonation"))
                .count());

    }

    @Test
    void detonationAccessExposesPersistedGhastUuidFromMinecraftEntity() throws Exception {
        Class<?> access = Class.forName(
                "xyz.pyrehaven.happyartillery.Abilities$DetonationAccess");
        assertEquals(java.util.UUID.class,
                access.getDeclaredMethod("ghastId", Object.class).getReturnType());
        assertEquals(void.class, access.getDeclaredMethod(
                "replaceState", Object.class, GhastState.class).getReturnType());

        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities$ServerPlayerDetonationAccess");
        MethodNode ghastId = exactMethod(adapter, "ghastId",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)Ljava/util/UUID;");
        assertEquals(1, callsTo(ghastId,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getUUID").size());
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

        restartedQueue.onGhastLoad(ghast, access.state, 120L, access);
        restartedQueue.onGhastLoad(ghast, access.state, 120L, access);
        assertEquals(1, restartedQueue.queuedTasks());
        assertEquals(0, restartedQueue.runDue(139L, config, access));
        access.loaded = false;
        assertEquals(0, restartedQueue.runDue(140L, config, access));
        assertEquals(java.util.OptionalLong.of(140L), access.state.detonateAtTick());

        access.loaded = true;
        restartedQueue.onGhastLoad(ghast, access.state, 150L, access);
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

        queue.onGhastLoad(staleGhast, access.state, 120L, access);
        queue.onGhastLoad(middleGhast, access.state, 123L, access);
        queue.onGhastLoad(newestGhast, access.state, 125L, access);

        assertEquals(1, queue.queuedTasks());
        assertEquals(1, queue.runDue(140L, config, access));
        assertEquals(List.of(newestGhast), access.explodedGhasts);
        assertEquals(0, queue.queuedTasks() + queue.deferredTasks());
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

        queue.onGhastLoad(staleGhast, access.state, 120L, access);
        assertEquals(0, queue.runDue(140L, config, access));
        assertEquals(1, queue.deferredTasks());
        queue.onGhastLoad(newestGhast, access.state, 150L, access);

        assertEquals(1, queue.queuedTasks() + queue.deferredTasks());
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

        queue.onGhastLoad(staleGhast, original, 120L, access);
        assertEquals(0, queue.runDue(140L, config, access));
        access.state = replacement;
        queue.onGhastLoad(newestGhast, replacement, 150L, access);

        assertEquals(1, queue.queuedTasks() + queue.deferredTasks());
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

        queue.onGhastLoad(firstGhast, pending, 120L, access);
        queue.onGhastLoad(secondGhast, pending, 120L, access);

        assertEquals(2, queue.queuedTasks());
        assertEquals(2, queue.runDue(140L, config, access));
        assertEquals(Set.of(firstGhast, secondGhast), Set.copyOf(access.explodedGhasts));
        assertEquals(0, queue.queuedTasks() + queue.deferredTasks());
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

        queue.onGhastLoad(ghast, access.state, 120L, access);
        assertEquals(0, queue.runDue(140L, config, access));
        assertEquals(1, access.riderResolutions);
        assertEquals(0, queue.queuedTasks());
        assertEquals(1, queue.deferredTasks());
        assertEquals(0, queue.runDue(200L, config, access));
        assertEquals(1, access.riderResolutions);

        access.riderAvailable = true;
        assertEquals(1, queue.onRiderAvailable(RIDER_ID));
        assertEquals(0, queue.onRiderAvailable(RIDER_ID));
        assertEquals(1, queue.queuedTasks());
        assertEquals(0, queue.deferredTasks());
        assertEquals(1, queue.runDue(200L, config, access));
        assertEquals(2, access.riderResolutions);
        assertEquals(1, access.explosions);
        assertEquals(1, access.removals);
    }

    @Test
    void consumedEffectFailureNeverReplaysOnLaterRunsOrLoadCallbacks() {
        Object ghast = new Object();
        RecordingDetonationAccess access = new RecordingDetonationAccess();
        access.state = new GhastState(101.0, 100L, 120L, 105L, 300L,
                java.util.OptionalLong.of(140L), java.util.Optional.of(RIDER_ID));
        access.explosionAccepted = false;
        Config config = configWithOverheat(40, 6.0, 1, 0.4, 2, 1, 8.0,
                true, true);
        Abilities.FuseQueue<Object, Object> queue = new Abilities.FuseQueue<>();

        queue.onGhastLoad(ghast, access.state, 120L, access);
        assertEquals(1, queue.runDue(140L, config, access));
        queue.onGhastLoad(ghast, access.state, 200L, access);
        queue.onGhastLoad(ghast, access.state, 200L, access);
        assertEquals(0, queue.runDue(200L, config, access));

        assertEquals(java.util.OptionalLong.empty(), access.state.detonateAtTick());
        assertEquals(1, access.explosions);
        assertEquals(1, access.directions.size());
        assertEquals(1, access.fireOffsets.size());
        assertEquals(1, access.removals);
        assertEquals(0, queue.queuedTasks() + queue.deferredTasks());
    }

    @Test
    void productionExposesUuidAndServerPlayerRiderAvailabilityWakeupBoundaries() throws Exception {
        assertEquals(int.class, Abilities.class.getDeclaredMethod(
                "onRiderAvailable", java.util.UUID.class).getReturnType());
        assertEquals(void.class, Abilities.class.getDeclaredMethod(
                "onRiderAvailable", net.minecraft.server.level.ServerPlayer.class).getReturnType());
    }

    @Test
    void equalityCrossingProposesStateWithoutOrdinaryProjectileOrAttachmentCommit() {
        RecordingAccess access = new RecordingAccess();
        Config config = configWithHeat(1.25, 100.0);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = fire(original, 100L, config, access);

        GhastState proposed = new GhastState(
                100.0, 100L, 120L, 105L, 300L, java.util.OptionalLong.empty());
        assertEquals(new Abilities.OverheatCrossing(original, proposed), outcome);
        assertSame(original, ((Abilities.OverheatCrossing) outcome).originalState());
        assertEquals(List.of(), access.events);
        assertEquals(0, access.adds + access.replacements);
        assertEquals(98.75, original.heat());
    }

    @Test
    void overLimitCrossingProposesStateWithoutOrdinaryProjectileOrAttachmentCommit() {
        RecordingAccess access = new RecordingAccess();
        Config config = configWithHeat(2.0, 100.0);
        GhastState original = new GhastState(99.0, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.empty());

        Abilities.FireOutcome outcome = fire(original, 100L, config, access);

        GhastState proposed = new GhastState(
                101.0, 100L, 120L, 105L, 300L, java.util.OptionalLong.empty());
        assertEquals(new Abilities.OverheatCrossing(original, proposed), outcome);
        assertSame(original, ((Abilities.OverheatCrossing) outcome).originalState());
        assertEquals(List.of(), access.events);
        assertEquals(0, access.adds + access.replacements);
        assertEquals(99.0, original.heat());
    }

    @Test
    void realFireOverloadReadsExactlyOneConfigSnapshotAndGenericSeamReadsNone() throws Exception {
        ClassNode abilities = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Abilities");
        MethodNode real = exactMethod(abilities, "fire",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;"
                        + "Lxyz/pyrehaven/happyartillery/GhastState;J"
                        + "Lnet/minecraft/resources/ResourceKey;D)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireOutcome;");
        MethodNode generic = exactMethod(abilities, "fire",
                "(Ljava/lang/Object;Ljava/lang/Object;"
                        + "Lxyz/pyrehaven/happyartillery/GhastState;J"
                        + "Lxyz/pyrehaven/happyartillery/Config;"
                        + "Lxyz/pyrehaven/happyartillery/BiomeClass;"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireAccess;)"
                        + "Lxyz/pyrehaven/happyartillery/Abilities$FireOutcome;");

        assertEquals(1, callsTo(real, "xyz/pyrehaven/happyartillery/Config", "current").size());
        assertEquals(1, callsTo(real, "xyz/pyrehaven/happyartillery/BiomeClass", "classify").size());
        assertEquals(0, callsTo(generic, "xyz/pyrehaven/happyartillery/Config", "current").size());
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

        assertEquals(List.of(
                "ALOAD 2", "INVOKEVIRTUAL HappyGhast.level ()Lnet/minecraft/world/level/Level;",
                "CHECKCAST net/minecraft/server/level/ServerLevel", "ASTORE 4",
                "ALOAD 1", "FCONST_1", "INVOKEVIRTUAL ServerPlayer.getViewVector (F)Lnet/minecraft/world/phys/Vec3;",
                "INVOKEVIRTUAL Vec3.normalize ()Lnet/minecraft/world/phys/Vec3;", "ASTORE 5",
                "NEW net/minecraft/world/entity/projectile/hurtingprojectile/LargeFireball", "DUP",
                "ALOAD 4", "ALOAD 2", "ALOAD 5", "ILOAD 3",
                "INVOKESPECIAL LargeFireball.<init> (Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;I)V",
                "ASTORE 6", "ALOAD 6", "ALOAD 2", "INVOKEVIRTUAL HappyGhast.getX ()D",
                "ALOAD 5", "GETFIELD Vec3.x D", "LDC 4.0", "DMUL", "DADD",
                "ALOAD 2", "LDC 0.5", "INVOKEVIRTUAL HappyGhast.getY (D)D", "LDC 0.5", "DADD",
                "ALOAD 2", "INVOKEVIRTUAL HappyGhast.getZ ()D", "ALOAD 5", "GETFIELD Vec3.z D",
                "LDC 4.0", "DMUL", "DADD", "INVOKEVIRTUAL LargeFireball.setPos (DDD)V",
                "ALOAD 4", "ALOAD 6", "INVOKEVIRTUAL ServerLevel.addFreshEntity (Lnet/minecraft/world/entity/Entity;)Z",
                "IFNE -> 44", "ICONST_0", "IRETURN",
                "ALOAD 2", "INVOKEVIRTUAL HappyGhast.isSilent ()Z", "IFNE -> 54",
                "ALOAD 4", "ACONST_NULL", "SIPUSH 1016", "ALOAD 2",
                "INVOKEVIRTUAL HappyGhast.blockPosition ()Lnet/minecraft/core/BlockPos;", "ICONST_0",
                "INVOKEVIRTUAL ServerLevel.levelEvent (Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V",
                "ICONST_1", "IRETURN"), instructionShape(method));
    }

    @Test
    void changedProductionContainsNoCustomProjectileImpactPersistenceChunkRayOrFallbackPath()
            throws Exception {
        Set<String> forbiddenMethods = Set.of(
                "onHit", "onHitEntity", "onHitBlock", "onImpact", "explode", "hurtServer",
                "discard", "addAdditionalSaveData", "readAdditionalSaveData", "readSpawnData",
                "recreateFromPacket", "restoreFrom", "getAddEntityPacket", "getChunk", "getChunkAt",
                "loadChunk", "hasChunk", "setChunkForced", "clip", "rayTrace", "raycast",
                "fallback", "spawnFallback", "tryFallback");
        Set<String> changedClasses = new java.util.TreeSet<>();
        for (String root : List.of("Config", "GhastState", "Heat")) {
            String rootName = "xyz/pyrehaven/happyartillery/" + root;
            ClassNode rootType = BytecodeTestSupport.classNode(rootName.replace('/', '.'));
            changedClasses.add(rootType.name);
            rootType.innerClasses.stream()
                    .map(inner -> inner.name)
                    .filter(java.util.Objects::nonNull)
                    .filter(name -> name.startsWith(rootName + "$"))
                    .forEach(changedClasses::add);
        }
        changedClasses.add(
                "xyz/pyrehaven/happyartillery/Abilities$ServerPlayerFireAccess");
        for (String className : changedClasses) {
            ClassNode type = BytecodeTestSupport.classNode(className.replace('/', '.'));
            assertTrue(type.methods.stream().noneMatch(method -> forbiddenMethods.contains(method.name)),
                    className + " declares a forbidden projectile path");
            assertTrue(type.methods.stream().flatMap(method -> instructions(method).stream())
                    .filter(MethodInsnNode.class::isInstance)
                    .map(MethodInsnNode.class::cast)
                    .noneMatch(call -> forbiddenMethods.contains(call.name)),
                    className + " calls a forbidden projectile path");
            assertTrue(type.methods.stream().flatMap(method -> instructions(method).stream())
                    .filter(TypeInsnNode.class::isInstance)
                    .map(TypeInsnNode.class::cast)
                    .noneMatch(node -> node.desc.startsWith("xyz/pyrehaven/happyartillery/")
                            && (node.desc.toLowerCase(java.util.Locale.ROOT).contains("projectile")
                            || node.desc.toLowerCase(java.util.Locale.ROOT).contains("fireball"))),
                    className + " creates a custom projectile type");
        }
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
                BiomeClass.BASE, access);
    }

    private static Config configWithCry(boolean enabled, double volume, double cooldownSeconds) {
        Config defaults = Config.defaults();
        return new Config(defaults.preset(), defaults.controls(), defaults.fire(), defaults.heat(),
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
        return new Config(defaults.preset(), defaults.controls(), defaults.fire(), heat,
                defaults.water(), defaults.overheat(), defaults.cry(), defaults.hud());
    }

    private static Config configWithOverheat(
            int fuseTicks, double explosionPower, int fireballCount, double fireballSpeed,
            int fireballPower, int fireAttempts, double fireRadius, boolean killsGhast,
            boolean breaksBlocks) {
        Config defaults = Config.defaults();
        Config.Overheat overheat = new Config.Overheat(
                fuseTicks, explosionPower, fireballCount, fireballSpeed, fireballPower,
                fireAttempts, fireRadius, killsGhast, breaksBlocks);
        return new Config(defaults.preset(), defaults.controls(), defaults.fire(), defaults.heat(),
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
        private boolean soundAccepted = true;
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
        public boolean playCry(Object ghast, double volume) {
            sounds++;
            this.volume = volume;
            events.add("sound");
            return soundAccepted;
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
        private boolean loaded = true;
        private boolean riderAvailable = true;
        private boolean explosionAccepted = true;
        private boolean fireballAccepted = true;
        private boolean removalAccepted = true;
        private RuntimeException replacementFailure;
        private RuntimeException explosionFailure;
        private RuntimeException stateFailure;
        private RuntimeException loadedFailure;
        private int stateReads;
        private int riderResolutions;
        private int explosions;
        private boolean explosionBreaksBlocks;
        private int removals;
        private final List<Abilities.FireAttempt> fireAttempts = new ArrayList<>();
        private final List<net.minecraft.world.phys.Vec3> directions = new ArrayList<>();
        private final List<net.minecraft.world.phys.Vec3> fireOffsets = new ArrayList<>();
        private final List<Object> explodedGhasts = new ArrayList<>();
        private final Map<Object, java.util.UUID> ghastIds = new java.util.IdentityHashMap<>();
        private final Map<Object, GhastState> states = new java.util.IdentityHashMap<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public java.util.UUID riderId(Object rider) {
            return RIDER_ID;
        }

        @Override
        public java.util.UUID ghastId(Object ghast) {
            return ghastIds.getOrDefault(ghast, GHAST_ID);
        }

        @Override
        public Object resolveRider(Object ghast, java.util.UUID riderId) {
            riderResolutions++;
            return riderAvailable ? new Object() : null;
        }

        @Override
        public GhastState state(Object ghast) {
            stateReads++;
            if (stateFailure != null) {
                throw stateFailure;
            }
            return states.getOrDefault(ghast, state);
        }

        @Override
        public boolean loaded(Object ghast) {
            if (loadedFailure != null) {
                throw loadedFailure;
            }
            return loaded;
        }

        @Override
        public void replaceState(Object ghast, GhastState state) {
            events.add("replace");
            if (replacementFailure != null) {
                throw replacementFailure;
            }
            this.state = state;
            states.put(ghast, state);
            replacedState = state;
        }

        @Override
        public boolean explode(Object pilot, Object ghast, double power, boolean breaksBlocks) {
            explosions++;
            explodedGhasts.add(ghast);
            explosionBreaksBlocks = breaksBlocks;
            events.add("explode:" + power + ":" + breaksBlocks);
            if (explosionFailure != null) {
                throw explosionFailure;
            }
            return explosionAccepted;
        }

        @Override
        public boolean spawnFireball(Object ghast, net.minecraft.world.phys.Vec3 direction,
                double speed, int power) {
            directions.add(direction);
            events.add("fireball");
            return fireballAccepted;
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
        public boolean remove(Object ghast) {
            removals++;
            events.add("remove");
            return removalAccepted;
        }
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

    private static List<AbstractInsnNode> instructions(MethodNode method) {
        return Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                        AbstractInsnNode::getNext)
                .filter(instruction -> instruction.getOpcode() >= 0)
                .toList();
    }

    private static List<String> instructionShape(MethodNode method) {
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
        return semantic.stream().map(instruction -> render(instruction, targets)).toList();
    }

    private static String render(AbstractInsnNode instruction, Map<LabelNode, Integer> targets) {
        String opcode = opcodeName(instruction.getOpcode());
        if (instruction instanceof VarInsnNode variable) {
            return opcode + " " + variable.var;
        }
        if (instruction instanceof TypeInsnNode type) {
            return opcode + " " + type.desc;
        }
        if (instruction instanceof MethodInsnNode method) {
            return opcode + " " + simpleOwner(method.owner) + "." + method.name + " " + method.desc;
        }
        if (instruction instanceof FieldInsnNode field) {
            return opcode + " " + simpleOwner(field.owner) + "." + field.name + " " + field.desc;
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
            case Opcodes.ASTORE -> "ASTORE";
            case Opcodes.ILOAD -> "ILOAD";
            case Opcodes.FCONST_1 -> "FCONST_1";
            case Opcodes.NEW -> "NEW";
            case Opcodes.CHECKCAST -> "CHECKCAST";
            case Opcodes.DUP -> "DUP";
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
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
            default -> "OPCODE_" + opcode;
        };
    }

}
