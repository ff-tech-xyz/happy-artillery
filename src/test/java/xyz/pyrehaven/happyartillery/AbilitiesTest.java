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
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AbilitiesTest {
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
                java.util.OptionalLong.of(600L));

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
                java.util.OptionalLong.of(600L));

        Abilities.CryOutcome outcome = cry(state, 100L, Config.defaults(), access);

        assertEquals(new Abilities.CryRejected(Abilities.CryRejection.ON_COOLDOWN), outcome);
        assertEquals(0, access.sounds + access.replacements);
        assertEquals(101L, state.cryReadyTick());
    }

    @Test
    void acceptedCryPlaysConfiguredSoundThenCommitsOnlyCryDeadline() {
        RecordingCryAccess access = new RecordingCryAccess();
        GhastState original = new GhastState(20.0, 50L, 75L, 90L, 100L,
                java.util.OptionalLong.of(600L));

        Abilities.CryOutcome outcome = cry(
                original, 100L, configWithCry(true, 7.5, 2.25), access);

        GhastState committed = new GhastState(20.0, 50L, 75L, 90L, 145L,
                java.util.OptionalLong.of(600L));
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
                java.util.OptionalLong.of(600L));

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
        assertEquals(Set.of("Fired", "OverheatCrossing", "Rejected"),
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
                java.util.OptionalLong.of(400L));

        Abilities.FireOutcome outcome = fire(state, 100L, access);

        assertTrue(outcome instanceof Abilities.Fired);
        GhastState expected = new GhastState(
                19.75, 100L, 120L, 105L, 300L, java.util.OptionalLong.of(400L));
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
                java.util.OptionalLong.of(400L));

        Abilities.FireOutcome outcome = fire(state, 100L, access);

        assertEquals(new Abilities.Rejected(Abilities.FireRejection.EFFECT_FAILED), outcome);
        assertEquals(List.of("add"), access.events);
        assertEquals(1, access.waterChecks);
        assertEquals(1, access.adds);
        assertEquals(0, access.replacements);
        assertEquals(null, access.replacedState);
        assertEquals(new GhastState(20.0, 50L, 50L, 0L, 300L,
                java.util.OptionalLong.of(400L)), state);
    }

    @Test
    void equalityCrossingProposesStateWithoutOrdinaryProjectileOrAttachmentCommit() {
        RecordingAccess access = new RecordingAccess();
        Config config = configWithHeat(1.25, 100.0);
        GhastState original = new GhastState(98.75, 100L, 100L, 0L, 300L,
                java.util.OptionalLong.of(400L));

        Abilities.FireOutcome outcome = fire(original, 100L, config, access);

        GhastState proposed = new GhastState(
                100.0, 100L, 120L, 105L, 300L, java.util.OptionalLong.of(400L));
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
        for (String root : List.of("Abilities", "Config", "GhastState", "Heat")) {
            String rootName = "xyz/pyrehaven/happyartillery/" + root;
            ClassNode rootType = BytecodeTestSupport.classNode(rootName.replace('/', '.'));
            changedClasses.add(rootType.name);
            rootType.innerClasses.stream()
                    .map(inner -> inner.name)
                    .filter(java.util.Objects::nonNull)
                    .filter(name -> name.startsWith(rootName + "$"))
                    .forEach(changedClasses::add);
        }
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
