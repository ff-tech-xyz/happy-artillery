package xyz.pyrehaven.happyartillery;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HappyArtilleryIntegrationTest {
    private static final String PACKAGE = "xyz/pyrehaven/happyartillery/";
    private static final String ROOT = PACKAGE + "HappyArtillery";

    @TempDir
    Path tempDir;
    private Config previousConfig;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(initializer -> initializer.apply());
    }

    @BeforeEach
    void captureConfig() {
        previousConfig = Config.current();
    }

    @AfterEach
    void restoreConfig() throws IOException {
        Path restorePath = tempDir.resolve("restore-config.json");
        Files.writeString(restorePath, new com.google.gson.Gson().toJson(previousConfig));
        assertEquals(previousConfig, Config.load(restorePath));
    }

    @Test
    void presentationModeUsesWaterThenInclusiveFiringThenCapturedProfileRate() {
        Config defaults = Config.defaults();
        Config config = new Config(
                defaults.controls(), defaults.fire(),
                new Config.Heat(
                        defaults.heat().limit(), defaults.heat().firingWindowSeconds(),
                        defaults.heat().cold(), defaults.heat().base(), defaults.heat().hot(),
                        new Config.HeatProfile(3.0, 0.75),
                        new Config.HeatProfile(0.7, 0.0),
                        defaults.heat().coldMaxTemperature(), defaults.heat().hotMinTemperature(),
                        defaults.heat().unknownDimensionUsesTemperature()),
                new Config.Water(4.25, defaults.water().floor(), defaults.water().blocksFiring()),
                defaults.overheat(), defaults.cry(), defaults.hud());
        GhastState state = new GhastState(
                40.0, 90L, 100L, 0L, 0L,
                java.util.OptionalLong.empty(), Optional.empty());

        assertEquals(new Hud.Cooling(4.25),
                HappyArtillery.presentationMode(true, state, 99L, config, BiomeClass.END));
        assertEquals(Hud.Firing.FIRING,
                HappyArtillery.presentationMode(false, state, 100L, config, BiomeClass.NETHER));
        assertEquals(new Hud.Cooling(0.75),
                HappyArtillery.presentationMode(false, state, 101L, config, BiomeClass.NETHER));
        assertEquals(new Hud.Cooling(0.0),
                HappyArtillery.presentationMode(false, state, 101L, config, BiomeClass.END));
    }

    @Test
    void missingConfigIsCreatedAndLoadedBeforeEachRegistrationExactlyOnce() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Path configPath = tempDir.resolve("happy-artillery.json");

        assertDoesNotThrow(() -> HappyArtillery.initialize(configPath, registrar));

        assertEquals(true, Files.isRegularFile(configPath));
        assertEquals(Config.defaults(), Config.current());
        assertEquals(Map.of(
                "ghast-state", 1,
                "rider-state", 1,
                "use-item", 1,
                "use-entity", 1,
                "ghast-load", 1,
                "player-available", 1,
                "player-tick", 1,
                "server-stop", 1,
                "reload", 1,
                "config-validation", 1), registrar.calls);
        assertSame(configPath, registrar.reloadPath);
        assertEquals(10, registrar.order.size());
        assertEquals("ghast-state", registrar.order.getFirst());
        assertEquals("config-validation", registrar.order.getLast());
        assertDoesNotThrow(registrar.startupValidation::run);
    }

    @Test
    void startupLifecycleCallbackRejectsMissingConfiguredItemWithExactPathAndId() throws Exception {
        Path configPath = tempDir.resolve("happy-artillery.json");
        String missing = "happy-artillery:missing_after_initializers";
        byte[] configured = ("{\"controls\":{\"fireItem\":\"" + missing + "\"}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(configPath, configured);
        RecordingRegistrar registrar = new RecordingRegistrar();

        HappyArtillery.initialize(configPath, registrar);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, registrar.startupValidation::run);

        assertEquals("Missing configured item controls.fireItem: " + missing, failure.getMessage());
        assertEquals(1, registrar.calls.get("config-validation"));
        assertEquals(new com.google.gson.Gson().toJsonTree(Config.current()),
                com.google.gson.JsonParser.parseString(Files.readString(configPath)));
    }

    @Test
    void malformedExistingConfigFailsUnchangedBeforeAnyRegistration() throws Exception {
        Path configPath = tempDir.resolve("happy-artillery.json");
        Files.writeString(configPath, "{ malformed");
        RecordingRegistrar registrar = new RecordingRegistrar();

        assertThrows(IllegalArgumentException.class,
                () -> HappyArtillery.initialize(configPath, registrar));

        assertEquals("{ malformed", Files.readString(configPath));
        assertEquals(Map.of(), registrar.calls);
    }

    @Test
    void checkedConfigIoFailureEscapesBeforeAnyRegistration() {
        RecordingRegistrar registrar = new RecordingRegistrar();

        assertThrows(IOException.class, () -> HappyArtillery.initialize(tempDir, registrar));

        assertEquals(Map.of(), registrar.calls);
    }

    @Test
    void successfulReloadRewritesSwapsReportsOnceAndReturnsBrigadierSuccess() throws Exception {
        Path configPath = tempDir.resolve("happy-artillery.json");
        Files.writeString(configPath, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(configPath);
        Files.writeString(configPath, "{\"controls\":{\"holdToFire\":true}}");
        RecordingReloadFeedback feedback = new RecordingReloadFeedback();

        int result = HappyArtillery.executeReload(configPath, feedback);

        assertEquals(1, result);
        assertNotSame(previous, Config.current());
        assertEquals(true, Config.current().controls().holdToFire());
        assertEquals(new com.google.gson.Gson().toJsonTree(Config.current()),
                com.google.gson.JsonParser.parseString(Files.readString(configPath)));
        assertEquals(List.of("success:Happy Artillery config reloaded."), feedback.messages);
    }

    @Test
    void malformedReloadReportsOnceReturnsFailureAndPreservesStateAndBytes() throws Exception {
        Path configPath = tempDir.resolve("happy-artillery.json");
        Files.writeString(configPath, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(configPath);
        byte[] invalid = "{ malformed".getBytes();
        Files.write(configPath, invalid);
        RecordingReloadFeedback feedback = new RecordingReloadFeedback();

        int result = HappyArtillery.executeReload(configPath, feedback);

        assertEquals(0, result);
        assertSame(previous, Config.current());
        org.junit.jupiter.api.Assertions.assertArrayEquals(invalid, Files.readAllBytes(configPath));
        assertEquals(List.of(
                "failure:Happy Artillery config reload failed: Invalid config JSON"),
                feedback.messages);
    }

    @Test
    void invalidReloadReportsOnceReturnsFailureAndPreservesStateAndBytes() throws Exception {
        Path configPath = tempDir.resolve("happy-artillery.json");
        Files.writeString(configPath, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(configPath);
        byte[] invalid = "{\"controls\":{\"fireSlot\":9}}".getBytes();
        Files.write(configPath, invalid);
        RecordingReloadFeedback feedback = new RecordingReloadFeedback();

        int result = HappyArtillery.executeReload(configPath, feedback);

        assertEquals(0, result);
        assertSame(previous, Config.current());
        org.junit.jupiter.api.Assertions.assertArrayEquals(invalid, Files.readAllBytes(configPath));
        assertEquals(List.of(
                "failure:Happy Artillery config reload failed: "
                        + "Removed config setting: controls.fireSlot"),
                feedback.messages);
    }

    @Test
    void reloadIoFailureReportsOnceReturnsFailureAndPreservesState() throws Exception {
        Path validPath = tempDir.resolve("valid.json");
        Files.writeString(validPath, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(validPath);
        RecordingReloadFeedback feedback = new RecordingReloadFeedback();

        int result = HappyArtillery.executeReload(tempDir, feedback);

        assertEquals(0, result);
        assertSame(previous, Config.current());
        assertEquals(1, feedback.messages.size());
        assertEquals(true, feedback.messages.getFirst().startsWith(
                "failure:Happy Artillery config reload failed: "));
    }

    @Test
    void removedPresetReloadsReportOnceAndPreserveStateAndBytes() throws Exception {
        List<String> invalidDocuments = List.of(
                "{\"preset\":\"survival\"}", "{\"preset\":null}",
                "{\"preset\":[]}", "{\"preset\":{}}");
        for (int index = 0; index < invalidDocuments.size(); index++) {
            Path configPath = tempDir.resolve("preset-type-" + index + ".json");
            Files.writeString(configPath, "{\"controls\":{\"holdToFire\":false}}");
            Config previous = Config.load(configPath);
            byte[] invalid = invalidDocuments.get(index).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(configPath, invalid);
            RecordingReloadFeedback feedback = new RecordingReloadFeedback();

            int result = HappyArtillery.executeReload(configPath, feedback);

            assertEquals(0, result);
            assertSame(previous, Config.current());
            org.junit.jupiter.api.Assertions.assertArrayEquals(invalid, Files.readAllBytes(configPath));
            assertEquals(List.of(
                    "failure:Happy Artillery config reload failed: Removed config setting: preset"),
                    feedback.messages);
        }
    }

    @Test
    void productBuildInputsUseDeployableHappyArtilleryIdentity() throws Exception {
        String metadataBytes = Files.readString(Path.of("src/main/resources/fabric.mod.json"));
        com.google.gson.JsonObject metadata = com.google.gson.JsonParser.parseString(metadataBytes)
                .getAsJsonObject();
        String buildBytes = Files.readString(Path.of("build.gradle"));

        assertEquals("Happy Artillery", metadata.get("name").getAsString());
        assertEquals("CC0-1.0", metadata.get("license").getAsString());
        String licenseSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("LICENSE"))));
        assertEquals("7abc65c185d25f94d129f580a8015025af33f09c9e44d9ba288889ab2d36855c",
                licenseSha256);
        assertFalse(metadata.get("description").getAsString().toLowerCase().contains("structural"));
        assertFalse(metadata.get("description").getAsString().toLowerCase().contains("no gameplay"));
        assertFalse(metadataBytes.toLowerCase().contains("non-deployable"));
        assertFalse(buildBytes.toLowerCase().contains("non-deployable"));
        assertEquals(1, buildBytes.lines()
                .filter(line -> line.strip().equals(
                        "archivesName = \"${project.archives_base_name}\""))
                .count());
    }

    @Test
    void noRiderTickReadsDurableClockOnceAndChecksEachOnlinePlayerOnce() {
        RecordingDriver access = new RecordingDriver(List.of("one", "two", "three"));

        HappyArtillery.tick(access);

        assertEquals(1, access.clockReads);
        assertEquals(Map.of("one", 1, "two", 1, "three", 1), access.playerChecks);
        assertEquals(List.of("clock", "players", "check:one", "check:two", "check:three", "fuses"),
                access.order);
    }

    @Test
    void riddenGhastUsesOnePilotConfigAndBiomeThenSharesPostTransitionHudSnapshot() {
        RecordingDriver access = RecordingDriver.ridden();
        access.configuredInWater = true;

        HappyArtillery.tick(access);

        assertEquals(1, access.snapshotCalls);
        assertEquals(1, access.configReads);
        assertEquals(1, access.classifications);
        assertEquals(2, access.ghastProcesses);
        assertEquals(1, access.fireCalls);
        assertEquals(List.of(true), access.advancedInWater);
        assertEquals(1, access.inWaterCalls);
        assertEquals(List.of(new Hud.Cooling(access.configured.water().coolPerSecond()),
                new Hud.Cooling(access.configured.water().coolPerSecond())), access.renderedModes);
        assertSame(access.renderedModes.getFirst(), access.renderedModes.getLast());
        assertEquals(List.of("pilot:7.0", "passenger:7.0"), access.hudSnapshots);
        assertEquals(true, access.order.indexOf("fuses") > access.order.lastIndexOf("hud:passenger"));
    }

    @Test
    void acceptedTickSurvivesHudPersistenceAndRejectsSameTickControlAdmission() {
        RecordingDriver access = RecordingDriver.dedupRegression();

        HappyArtillery.tick(access);

        RiderState persisted = access.riderStates.get("pilot");
        assertEquals(41L, persisted.lastHandledTick());
        assertEquals(true, persisted.hudCache().isPresent());
        assertEquals(Long.MIN_VALUE, access.riderStates.get("passenger").lastHandledTick());
        assertEquals(1, access.cryCalls);

        HappyArtillery.tick(access);

        assertEquals(1, access.cryCalls);
        assertEquals(41L, access.riderStates.get("pilot").lastHandledTick());
    }

    @Test
    void consumedFailedEffectRendersAuthoritativeResetStateForEveryRider() {
        RecordingDriver access = RecordingDriver.consumedFailure();

        HappyArtillery.tick(access);

        assertEquals(1, access.fireCalls);
        assertEquals(2, access.ghastProcesses);
        assertEquals(List.of("pilot:0.0", "passenger:0.0"), access.hudSnapshots);
    }

    @Test
    void capturedPassiveModeIsComputedOnceAndSharedByPilotAndPassenger() {
        Config defaults = Config.defaults();
        Config config = new Config(
                defaults.controls(), defaults.fire(),
                new Config.Heat(
                        defaults.heat().limit(), defaults.heat().firingWindowSeconds(),
                        defaults.heat().cold(), defaults.heat().base(), defaults.heat().hot(),
                        new Config.HeatProfile(3.0, 0.75), defaults.heat().end(),
                        defaults.heat().coldMaxTemperature(), defaults.heat().hotMinTemperature(),
                        defaults.heat().unknownDimensionUsesTemperature()),
                defaults.water(), defaults.overheat(), defaults.cry(), defaults.hud());
        RecordingDriver access = RecordingDriver.passive(config, BiomeClass.NETHER);

        HappyArtillery.tick(access);

        assertEquals(1, access.configReads);
        assertEquals(1, access.classifications);
        assertEquals(1, access.inWaterCalls);
        assertEquals(List.of(new Hud.Cooling(0.75), new Hud.Cooling(0.75)), access.renderedModes);
        assertSame(access.renderedModes.getFirst(), access.renderedModes.getLast());
    }

    @Test
    void callbackTouchesOnlyTheActorAndDefersSnapshotAndHudUntilTick() {
        RecordingDriver access = RecordingDriver.ridden();
        access.onlinePlayersForbidden = true;

        HappyArtillery.handleCallback(access, "pilot", null, InteractionHand.MAIN_HAND);

        assertEquals(1, access.playerChecks.get("pilot"));
        assertEquals(1, access.fireCalls);
        assertEquals(0, access.snapshotCalls);
        assertEquals(List.of(), access.hudSnapshots);
    }

    @Test
    void pilotlessRiddenGroupRemovesEveryRiderHudWithoutAdvancingAbilities() {
        RecordingDriver access = RecordingDriver.pilotless();

        HappyArtillery.tick(access);

        assertEquals(List.of("one", "two"), access.removedHud);
        assertEquals(0, access.snapshotCalls);
        assertEquals(0, access.ghastProcesses);
        assertEquals(0, access.fireCalls);
        assertEquals(0, access.cryCalls);
    }

    @Test
    void namedInvalidRiderStateRecoversOnlyThatPlayerWhileUnexpectedFailuresPropagate() {
        RecordingDriver invalid = RecordingDriver.invalid("one");
        HappyArtillery.tick(invalid);
        assertEquals(List.of("one"), invalid.recovered);
        assertEquals(List.of("one", "two", "three"), new ArrayList<>(invalid.playerChecks.keySet()));

        RecordingDriver unexpected = RecordingDriver.unexpected("two");
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> HappyArtillery.tick(unexpected));
        assertEquals("unexpected world failure", failure.getMessage());
        assertEquals(List.of(), unexpected.recovered);
    }
    @Test
    void productionCallbackIsActorLocalAndDefersHudFanOut() throws IOException {
        ClassNode root = BytecodeTestSupport.classNode(HappyArtillery.class.getName());
        MethodNode callback = method(root, "handleCallback",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/world/InteractionHand;)V");
        List<MethodInsnNode> calls = methodCalls(callback);

        assertEquals(0, calls.stream().filter(call -> call.name.equals("onlinePlayers")).count());
        assertEquals(1, calls.stream().filter(call -> call.name.equals("handleCallback")).count());
        assertEquals(0, calls.stream().filter(call -> call.name.equals("render")).count());
    }

    @Test
    void productionInspectionReconcilesOnceWithoutOwningHudFanOut() throws IOException {
        ClassNode access = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$MinecraftDriverAccess");
        MethodNode inspect = method(access, "inspectPlayer",
                "(Lnet/minecraft/server/level/ServerPlayer;)L" + ROOT + "$PlayerView;");
        List<MethodInsnNode> calls = Stream.iterate(
                        inspect.instructions.getFirst(), java.util.Objects::nonNull,
                        instruction -> instruction.getNext())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .toList();

        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls") && call.name.equals("reconcile")).count());
        assertEquals(0, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Hud") && call.name.equals("remove")).count());
        assertEquals(0, calls.stream().filter(call -> call.owner.equals(
                "net/minecraft/world/entity/player/Inventory")).count());
    }

    @Test
    void productionStartupUsesExactFabricConfigPathBeforeRegistration() throws IOException {
        ClassNode root = BytecodeTestSupport.classNode(HappyArtillery.class.getName());
        MethodNode onInitialize = method(root, "onInitialize", "()V");
        List<MethodInsnNode> entryCalls = methodCalls(onInitialize);
        assertEquals(List.of(
                        "net/fabricmc/loader/api/FabricLoader.getInstance()Lnet/fabricmc/loader/api/FabricLoader;",
                        "net/fabricmc/loader/api/FabricLoader.getConfigDir()Ljava/nio/file/Path;",
                        "java/nio/file/Path.resolve(Ljava/lang/String;)Ljava/nio/file/Path;",
                        "xyz/pyrehaven/happyartillery/HappyArtillery.initialize(Ljava/nio/file/Path;Lxyz/pyrehaven/happyartillery/HappyArtillery$Registrar;)V"),
                entryCalls.stream().filter(call -> Set.of(
                                "getInstance", "getConfigDir", "resolve", "initialize").contains(call.name))
                        .map(HappyArtilleryIntegrationTest::callIdentity).toList());
        assertEquals(1, Stream.iterate(onInitialize.instructions.getFirst(), java.util.Objects::nonNull,
                        instruction -> instruction.getNext())
                .filter(LdcInsnNode.class::isInstance).map(LdcInsnNode.class::cast)
                .filter(instruction -> "happy-artillery.json".equals(instruction.cst)).count());

        MethodNode initialize = method(root, "initialize",
                "(Ljava/nio/file/Path;L" + ROOT + "$Registrar;)V");
        List<MethodInsnNode> initializeCalls = methodCalls(initialize);
        int load = callIndex(initializeCalls, "xyz/pyrehaven/happyartillery/Config", "load");
        int firstRegistration = callIndex(initializeCalls,
                "xyz/pyrehaven/happyartillery/HappyArtillery$Registrar", "registerGhastState");
        assertEquals(true, load >= 0 && load < firstRegistration);
        assertEquals(0, Stream.iterate(initialize.instructions.getFirst(), java.util.Objects::nonNull,
                        instruction -> instruction.getNext())
                .filter(TypeInsnNode.class::isInstance).map(TypeInsnNode.class::cast)
                .filter(instruction -> instruction.desc.equals("java/lang/IllegalStateException")).count());
    }

    @Test
    void fabricRegistrarBindsEachRequiredProductionEventIdentityExactlyOnce() throws IOException {
        ClassNode registrar = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$FabricRegistrar");
        Map<String, List<String>> expected = Map.of(
                "registerUseItem", List.of("net/fabricmc/fabric/api/event/player/UseItemCallback.EVENT"),
                "registerUseEntity", List.of("net/fabricmc/fabric/api/event/player/UseEntityCallback.EVENT"),
                "registerGhastLoad", List.of("net/fabricmc/fabric/api/event/lifecycle/v1/ServerEntityEvents.ENTITY_LOAD"),
                "registerPlayerAvailable", List.of(
                        "net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.JOIN",
                        "net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.DISCONNECT"),
                "registerPlayerTick", List.of("net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents.END_SERVER_TICK"),
                "registerServerStop", List.of("net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.SERVER_STOPPED"),
                "registerConfigValidation", List.of("net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.SERVER_STARTED"),
                "registerReload", List.of("net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback.EVENT"));

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            String descriptor = switch (entry.getKey()) {
                case "registerReload" -> "(Ljava/nio/file/Path;)V";
                case "registerConfigValidation" -> "(Ljava/lang/Runnable;)V";
                default -> "()V";
            };
            MethodNode method = method(registrar, entry.getKey(), descriptor);
            List<String> events = Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                            instruction -> instruction.getNext())
                    .filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast)
                    .filter(field -> field.owner.startsWith("net/fabricmc/fabric/api/"))
                    .map(field -> field.owner + "." + field.name).toList();
            assertEquals(entry.getValue(), events, entry.getKey());
            assertEquals(entry.getValue().size(), methodCalls(method).stream()
                    .filter(call -> call.owner.equals("net/fabricmc/fabric/api/event/Event")
                            && call.name.equals("register")).count(), entry.getKey());
        }
    }

    @Test
    void reloadCommandGraphUsesExactPathRootChildPermissionAndExecutionBoundary() throws IOException {
        ClassNode registrar = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$FabricRegistrar");
        String callbackDescriptor = "(Ljava/nio/file/Path;Lcom/mojang/brigadier/CommandDispatcher;"
                + "Lnet/minecraft/commands/CommandBuildContext;"
                + "Lnet/minecraft/commands/Commands$CommandSelection;)V";
        MethodNode callback = method(registrar, "lambda$registerReload$0", callbackDescriptor);
        assertEquals(List.of(
                        "ALOAD 1",
                        "LDC ha",
                        "INVOKESTATIC net/minecraft/commands/Commands.literal"
                                + "(Ljava/lang/String;)Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;",
                        "GETSTATIC net/minecraft/commands/Commands.LEVEL_GAMEMASTERS"
                                + " Lnet/minecraft/server/permissions/PermissionCheck;",
                        "INVOKESTATIC net/minecraft/commands/Commands.hasPermission"
                                + "(Lnet/minecraft/server/permissions/PermissionCheck;)"
                                + "Lnet/minecraft/server/permissions/PermissionProviderCheck;",
                        "INVOKEVIRTUAL com/mojang/brigadier/builder/LiteralArgumentBuilder.requires"
                                + "(Ljava/util/function/Predicate;)"
                                + "Lcom/mojang/brigadier/builder/ArgumentBuilder;",
                        "CHECKCAST com/mojang/brigadier/builder/LiteralArgumentBuilder",
                        "LDC reload",
                        "INVOKESTATIC net/minecraft/commands/Commands.literal"
                                + "(Ljava/lang/String;)Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;",
                        "ALOAD 0",
                        "INVOKEDYNAMIC run(Ljava/nio/file/Path;)Lcom/mojang/brigadier/Command;",
                        "INVOKEVIRTUAL com/mojang/brigadier/builder/LiteralArgumentBuilder.executes"
                                + "(Lcom/mojang/brigadier/Command;)"
                                + "Lcom/mojang/brigadier/builder/ArgumentBuilder;",
                        "INVOKEVIRTUAL com/mojang/brigadier/builder/LiteralArgumentBuilder.then"
                                + "(Lcom/mojang/brigadier/builder/ArgumentBuilder;)"
                                + "Lcom/mojang/brigadier/builder/ArgumentBuilder;",
                        "CHECKCAST com/mojang/brigadier/builder/LiteralArgumentBuilder",
                        "INVOKEVIRTUAL com/mojang/brigadier/CommandDispatcher.register"
                                + "(Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;)"
                                + "Lcom/mojang/brigadier/tree/LiteralCommandNode;",
                        "POP",
                        "RETURN"),
                semanticShape(callback));

        MethodNode execution = method(registrar, "lambda$registerReload$1",
                "(Ljava/nio/file/Path;Lcom/mojang/brigadier/context/CommandContext;)I");
        assertEquals(List.of(
                        "com/mojang/brigadier/context/CommandContext.getSource()Ljava/lang/Object;",
                        "xyz/pyrehaven/happyartillery/HappyArtillery.executeReload(Ljava/nio/file/Path;"
                                + "Lxyz/pyrehaven/happyartillery/HappyArtillery$ReloadFeedback;)I"),
                methodCalls(execution).stream()
                        .filter(call -> call.name.equals("getSource") || call.name.equals("executeReload"))
                        .map(HappyArtilleryIntegrationTest::callIdentity).toList());
    }

    @Test
    void productionDriverAndTickUseOnlyBoundedPlayerApisAndExplicitHeldConfig() throws IOException {
        ClassNode root = BytecodeTestSupport.classNode(HappyArtillery.class.getName());
        List<MethodInsnNode> tickCalls = methodCalls(method(
                root, "tick", "(L" + ROOT + "$DriverAccess;)V"));
        assertEquals(1, tickCalls.stream().filter(call -> call.name.equals("onlinePlayers")).count());
        assertEquals(1, tickCalls.stream().filter(call -> call.name.equals("inspectPlayer")).count());
        assertEquals(0, tickCalls.stream().filter(call -> Set.of(
                "getAllLevels", "getEntities", "getAllEntities").contains(call.name)).count());

        ClassNode access = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$MinecraftDriverAccess");
        List<MethodInsnNode> onlineCalls = methodCalls(method(
                access, "onlinePlayers", "()Ljava/util/List;"));
        assertEquals(List.of("getPlayerList", "getPlayers"), onlineCalls.stream()
                .map(call -> call.name).toList());
        List<MethodInsnNode> controlsCalls = methodCalls(method(access, "controls",
                "(Lnet/minecraft/server/level/ServerPlayer;L" + PACKAGE
                        + "RiderState;JL" + PACKAGE + "Config;L" + PACKAGE
                        + "Controls$InventorySnapshot;)L" + PACKAGE
                        + "Controls$Admission;"));
        assertEquals(1, controlsCalls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls") && call.name.equals("sampleHeld")
                && call.desc.contains("Lxyz/pyrehaven/happyartillery/Config$Controls;")).count());
        assertEquals(0, controlsCalls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Config") && call.name.equals("current")).count());
        List<MethodInsnNode> snapshotCalls = methodCalls(method(access, "snapshot",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)L"
                        + PACKAGE + "Controls$InventorySnapshot;"));
        assertEquals(1, snapshotCalls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls") && call.name.equals("snapshot")).count());
    }
    @Test
    void productionRiderLossAndRenderStayConnectedToTypedHudBoundaries() throws IOException {
        ClassNode driverOwner = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$MinecraftDriverAccess");
        assertEquals(List.of("xyz/pyrehaven/happyartillery/Hud.remove"
                        + "(Lnet/minecraft/server/level/ServerPlayer;)V"),
                methodCalls(method(driverOwner, "removeHud",
                        "(Lnet/minecraft/server/level/ServerPlayer;)V")).stream()
                        .map(HappyArtilleryIntegrationTest::callIdentity).toList());

        ClassNode registrar = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$FabricRegistrar");
        assertEquals(List.of(
                        "net/minecraft/server/network/ServerGamePacketListenerImpl.getPlayer"
                                + "()Lnet/minecraft/server/level/ServerPlayer;",
                        "xyz/pyrehaven/happyartillery/Hud.remove"
                                + "(Lnet/minecraft/server/level/ServerPlayer;)V"),
                methodCalls(method(registrar, "lambda$registerPlayerAvailable$1",
                        "(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                                + "Lnet/minecraft/server/MinecraftServer;)V")).stream()
                        .map(HappyArtilleryIntegrationTest::callIdentity).toList());

        MethodNode render = method(driverOwner, "render",
                "(L" + ROOT + "$PlayerView;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;L" + PACKAGE
                        + "GhastState;JL" + PACKAGE + "Config;L" + PACKAGE + "Hud$Mode;"
                        + "Ljava/util/Optional;Z)V");
        assertEquals(1, methodCalls(render).stream().filter(call -> call.owner.equals(PACKAGE + "Hud")
                && call.name.equals("minecraftPresentation")).count());
        assertEquals(1, methodCalls(render).stream().filter(call -> call.owner.equals(PACKAGE + "Hud")
                && call.name.equals("update")
                && call.desc.endsWith("L" + PACKAGE + "Hud$PresentationAccess;)L"
                        + PACKAGE + "RiderState;")).count());
    }
    @Test
    void productionFireAndCryRouteWaterFeedbackDirectlyWithoutEnumConversion() throws IOException {
        ClassNode access = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$MinecraftDriverAccess");
        MethodNode fire = method(access, "fire",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;L" + PACKAGE
                        + "GhastState;JL" + PACKAGE + "Config;L" + PACKAGE + "BiomeClass;)L"
                        + PACKAGE + "Abilities$FireOutcome;");
        MethodNode cry = method(access, "cry",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;L" + PACKAGE
                        + "GhastState;JL" + PACKAGE + "Config;)L" + PACKAGE
                        + "Abilities$CryOutcome;");

        for (MethodNode ability : List.of(fire, cry)) {
            List<MethodInsnNode> calls = methodCalls(ability);
            assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                    PACKAGE + "Feedback") && call.name.equals("presentWaterBlocked")).count());
            assertEquals(0, calls.stream().filter(call -> call.owner.equals(
                    PACKAGE + "Feedback") && call.name.equals("present")).count());
        }
        assertEquals(0, Stream.iterate(fire.instructions.getFirst(), java.util.Objects::nonNull,
                        AbstractInsnNode::getNext)
                .filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast)
                .filter(field -> field.owner.equals(PACKAGE + "Abilities$CryRejection")).count());
    }

    @Test
    void loadAvailabilityAndStopCallbacksDelegateToTheirExistingOwners() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();

        HappyArtillery.ghastLoaded("ghast", 73L, lifecycle);
        HappyArtillery.riderAvailable("rider", lifecycle);
        HappyArtillery.serverStopped(lifecycle);

        assertEquals(List.of(
                "wake-ghast:ghast@73", "wake-rider:rider", "clear-fuses", "clear-hud"),
                lifecycle.calls);
    }

    private static final class RecordingLifecycle
            implements HappyArtillery.LifecycleAccess<String, String> {
        private final List<String> calls = new ArrayList<>();

        @Override public void wakeGhast(String ghast, long now) {
            calls.add("wake-ghast:" + ghast + "@" + now);
        }
        @Override public void wakeRider(String rider) { calls.add("wake-rider:" + rider); }
        @Override public void clearFuses() { calls.add("clear-fuses"); }
        @Override public void clearHud() { calls.add("clear-hud"); }
    }

    private static final class RecordingDriver
            implements HappyArtillery.DriverAccess<String, String> {
        private final List<String> players;
        private final Map<String, Integer> playerChecks = new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();
        private int clockReads;

        private int configReads;
        private int classifications;
        private int inWaterCalls;
        private int snapshotCalls;
        private int ghastProcesses;
        private int fireCalls;
        private int cryCalls;
        private Config expectedConfig;
        private Config configured = Config.defaults();
        private BiomeClass configuredBiome = BiomeClass.HOT;
        private boolean acceptedFire = true;
        private boolean configuredInWater;
        private long advancedFiringWindowEnd = 41L;
        private final boolean consumedFailure;
        private final boolean dedupRegression;
        private boolean pilotless;
        private String invalidPlayer;
        private String unexpectedPlayer;
        private boolean onlinePlayersForbidden;
        private GhastState authoritativeState;
        private final List<String> hudSnapshots = new ArrayList<>();
        private final List<Hud.Mode> renderedModes = new ArrayList<>();
        private final List<Boolean> advancedInWater = new ArrayList<>();
        private final List<Boolean> activeFireControls = new ArrayList<>();
        private final List<String> removedHud = new ArrayList<>();
        private final List<String> recovered = new ArrayList<>();
        private final Map<String, RiderState> riderStates = new LinkedHashMap<>();
        private final Hud<String, String> hud = new Hud<>(NoopPresentationAccess.INSTANCE);
        private final Controls.InventorySnapshot inventorySnapshot = new Controls.InventorySnapshot(
                Controls.ControlLocation.HAND_ACCESSIBLE,
                Controls.ControlLocation.HAND_ACCESSIBLE, 0);

        private RecordingDriver(List<String> players) {
            this(players, false, false);
        }

        private RecordingDriver(List<String> players, boolean consumedFailure) {
            this(players, consumedFailure, false);
        }

        private RecordingDriver(
                List<String> players, boolean consumedFailure, boolean dedupRegression) {
            this.players = List.copyOf(players);
            this.consumedFailure = consumedFailure;
            this.dedupRegression = dedupRegression;
            if (dedupRegression) {
                riderStates.put("pilot", new RiderState(
                        Optional.of(RegressionControlAccess.GHAST_ID),
                        Long.MIN_VALUE, Optional.empty()));
                riderStates.put("passenger", RiderState.fresh());
            }
        }

        private static RecordingDriver ridden() {
            return new RecordingDriver(List.of("pilot", "passenger"));
        }

        private static RecordingDriver passive(Config config, BiomeClass biomeClass) {
            RecordingDriver access = ridden();
            access.configured = config;
            access.configuredBiome = biomeClass;
            access.acceptedFire = false;
            access.advancedFiringWindowEnd = 40L;
            return access;
        }

        private static RecordingDriver consumedFailure() {
            return new RecordingDriver(List.of("pilot", "passenger"), true);
        }

        private static RecordingDriver dedupRegression() {
            return new RecordingDriver(List.of("pilot", "passenger"), false, true);
        }

        private static RecordingDriver pilotless() {
            RecordingDriver access = new RecordingDriver(List.of("one", "two"));
            access.pilotless = true;
            return access;
        }

        private static RecordingDriver invalid(String player) {
            RecordingDriver access = new RecordingDriver(List.of("one", "two", "three"));
            access.invalidPlayer = player;
            return access;
        }

        private static RecordingDriver unexpected(String player) {
            RecordingDriver access = new RecordingDriver(List.of("one", "two", "three"));
            access.unexpectedPlayer = player;
            return access;
        }

        @Override public long gameTime() { clockReads++; order.add("clock"); return 41L; }
        @Override public List<String> onlinePlayers() {
            if (onlinePlayersForbidden) throw new AssertionError("callback scanned online players");
            order.add("players");
            return players;
        }
        @Override public HappyArtillery.PlayerView<String, String> inspectPlayer(String player) {
            playerChecks.merge(player, 1, Integer::sum);
            order.add("check:" + player);
            if (player.equals(invalidPlayer)) {
                throw new Controls.InvalidRiderState(Optional.empty(), "invalid test state");
            }
            if (player.equals(unexpectedPlayer)) {
                throw new RuntimeException("unexpected world failure");
            }
            if (pilotless) {
                return new HappyArtillery.PlayerView<>(
                        player, RiderState.fresh(), Optional.of("ghast"), false);
            }
            if (players.size() == 2) {
                return new HappyArtillery.PlayerView<>(player,
                        riderStates.getOrDefault(player, RiderState.fresh()),
                        Optional.of("ghast"), player.equals("pilot"));
            }
            return new HappyArtillery.PlayerView<>(player, RiderState.fresh(), Optional.empty(), false);
        }
        @Override public void runDueFuses(long now) { order.add("fuses"); }
        @Override public Object ghastId(String ghast) { return ghast; }
        @Override public boolean inWater(String ghast) {
            inWaterCalls++;
            return configuredInWater;
        }
        @Override public Config config() {
            configReads++;
            expectedConfig = configured;
            return expectedConfig;
        }
        @Override public BiomeClass classify(String ghast, Config config) {
            assertSame(expectedConfig, config);
            classifications++;
            return configuredBiome;
        }
        @Override public GhastState ghastState(String ghast) {
            ghastProcesses++;
            if (authoritativeState != null) {
                return authoritativeState;
            }
            return GhastState.fresh();
        }
        @Override public GhastState advance(
                String ghast, GhastState state, long now, Config config,
                BiomeClass biomeClass, boolean inWater) {
            assertSame(expectedConfig, config);
            assertSame(configuredBiome, biomeClass);
            advancedInWater.add(inWater);
            return new GhastState(5.0, now, advancedFiringWindowEnd, 0L, 0L,
                    java.util.OptionalLong.empty(), Optional.empty());
        }
        @Override public Controls.InventorySnapshot snapshot(String pilot, String ghast) {
            snapshotCalls++;
            return inventorySnapshot;
        }
        @Override public Controls.Admission controls(
                String pilot, RiderState state, long now, Config config,
                Controls.InventorySnapshot snapshot) {
            assertSame(expectedConfig, config);
            assertSame(inventorySnapshot, snapshot);
            if (dedupRegression) {
                return Controls.handleUseItem(
                        pilot, InteractionHand.MAIN_HAND, state, now,
                        config.controls(), RegressionControlAccess.INSTANCE);
            }
            if (!acceptedFire) {
                return new Controls.Ignored(state);
            }
            return new Controls.Accepted(Controls.ControlIntent.FIRE, state);
        }
        @Override public Controls.Admission callbackControls(
                String pilot, Object target, InteractionHand hand,
                RiderState state, long now, Config config) {
            assertSame(expectedConfig, config);
            return new Controls.Accepted(Controls.ControlIntent.FIRE, state.withLastHandledTick(now));
        }
        @Override public void recoverInvalidRiderState(
                String player, Controls.InvalidRiderState failure) {
            riderStates.put(player, RiderState.fresh());
            recovered.add(player);
            removedHud.add(player);
        }
        @Override public void removeHud(String player) { removedHud.add(player); }
        @Override public void replaceRiderState(String player, RiderState state) {
            if (dedupRegression) {
                riderStates.put(player, state);
            }
        }
        @Override public void replaceGhastState(String ghast, GhastState state) { }
        @Override public Abilities.FireOutcome fire(
                String pilot, String ghast, GhastState state, long now,
                Config config, BiomeClass biomeClass) {
            assertSame(expectedConfig, config);
            assertSame(BiomeClass.HOT, biomeClass);
            fireCalls++;
            if (consumedFailure) {
                authoritativeState = new GhastState(0.0, 41L, 41L, 41L, 0L,
                        java.util.OptionalLong.empty(), Optional.empty());
                return new Abilities.Rejected(Abilities.FireRejection.EFFECT_FAILED);
            }
            authoritativeState = new GhastState(7.0, now, now, 0L, 0L,
                    java.util.OptionalLong.empty(), Optional.empty());
            return new Abilities.Fired(authoritativeState);
        }
        @Override public Abilities.CryOutcome cry(
                String pilot, String ghast, GhastState state, long now, Config config) {
            if (dedupRegression) {
                cryCalls++;
                return new Abilities.Cried(state);
            }
            throw new AssertionError("cry not requested");
        }

        @Override public void render(
                HappyArtillery.PlayerView<String, String> rider, String ghast,
                GhastState state, long now, Config config, Hud.Mode mode,
                Optional<Controls.InventorySnapshot> pilotSnapshot,
                boolean activeFireControl) {
            assertSame(expectedConfig, config);
            renderedModes.add(mode);
            if (rider.pilot()) {
                assertSame(inventorySnapshot, pilotSnapshot.orElseThrow());
            } else {
                assertEquals(Optional.empty(), pilotSnapshot);
            }
            hudSnapshots.add(rider.player() + ":" + state.heat());
            activeFireControls.add(activeFireControl);
            order.add("hud:" + rider.player());
            if (dedupRegression) {
                RiderState updated = hud.update(
                        rider.player(), rider.player(), ghast, rider.state(), now,
                        new Hud.Snapshot(state.heat(), new Hud.Cooling(1.0),
                                Optional.empty(), false),
                        config, NoopPresentationAccess.INSTANCE);
                if (!updated.equals(rider.state())) {
                    replaceRiderState(rider.player(), updated);
                }
            }
        }
    }

    private enum RegressionControlAccess implements Controls.ControlAccess<String, String> {
        INSTANCE;

        private static final UUID GHAST_ID = UUID.fromString(
                "53f9ae89-1b78-48af-bcb5-bbeddef599ae");

        @Override public Optional<String> riddenHappyGhast(String player) {
            return Optional.of("ghast");
        }
        @Override public boolean isControllingFirstPassenger(String player, String ghast) {
            return player.equals("pilot");
        }
        @Override public UUID ghastId(String ghast) { return GHAST_ID; }
        @Override public UUID playerId(String player) { return new UUID(0L, 0L); }
        @Override public ItemStack itemInHand(String player, InteractionHand hand) {
            ItemStack control = new ItemStack(Items.GHAST_TEAR);
            Components.mark(control, new Components.Marker(
                    Components.Control.CRY, new UUID(0L, 0L), GHAST_ID));
            return control;
        }
        @Override public Controls.ObservedUse observedUse(String player) {
            return new Controls.ObservedUse(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    private enum NoopPresentationAccess implements Hud.PresentationAccess<String, String> {
        INSTANCE;

        @Override public String createBossBar(double progress, Config.Color color) { return "bar"; }
        @Override public void addViewer(String handle, String rider) { }
        @Override public void setProgress(String handle, double progress) { }
        @Override public void setColor(String handle, Config.Color color) { }
        @Override public boolean hasCurrentName(String handle) { return true; }
        @Override public void setName(String handle) { }
        @Override public void removeViewer(String handle, String rider) { }
        @Override public void actionBar(String rider, String text, Config.Color color) { }
        @Override public void warningParticle(String rider) { }
    }

    private static final class RecordingRegistrar implements HappyArtillery.Registrar {
        private final Map<String, Integer> calls = new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();
        private Path reloadPath;
        private Runnable startupValidation;

        private void record(String name) {
            calls.merge(name, 1, Integer::sum);
            order.add(name);
        }

        @Override public void registerGhastState() { record("ghast-state"); }
        @Override public void registerRiderState() { record("rider-state"); }
        @Override public void registerUseItem() { record("use-item"); }
        @Override public void registerUseEntity() { record("use-entity"); }
        @Override public void registerGhastLoad() { record("ghast-load"); }
        @Override public void registerPlayerAvailable() { record("player-available"); }
        @Override public void registerPlayerTick() { record("player-tick"); }
        @Override public void registerServerStop() { record("server-stop"); }
        public void registerReload(Path path) {
            reloadPath = path;
            record("reload");
        }
        public void registerConfigValidation(Runnable validation) {
            startupValidation = validation;
            record("config-validation");
        }
    }

    private static final class RecordingReloadFeedback implements HappyArtillery.ReloadFeedback {
        private final List<String> messages = new ArrayList<>();

        @Override public void success(String message) { messages.add("success:" + message); }
        @Override public void failure(String message) { messages.add("failure:" + message); }
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .toList();
        assertEquals(1, matches.size(), owner.name + "." + name + descriptor);
        return matches.getFirst();
    }

    private static List<String> semanticShape(MethodNode method) {
        List<String> shape = new ArrayList<>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            String opcode = switch (instruction.getOpcode()) {
                case Opcodes.ALOAD -> "ALOAD";
                case Opcodes.LDC -> "LDC";
                case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
                case Opcodes.GETSTATIC -> "GETSTATIC";
                case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
                case Opcodes.CHECKCAST -> "CHECKCAST";
                case Opcodes.INVOKEDYNAMIC -> "INVOKEDYNAMIC";
                case Opcodes.POP -> "POP";
                case Opcodes.RETURN -> "RETURN";
                default -> throw new AssertionError(
                        "Unexpected command instruction opcode " + instruction.getOpcode());
            };
            if (instruction instanceof VarInsnNode variable) {
                shape.add(opcode + " " + variable.var);
            } else if (instruction instanceof LdcInsnNode constant) {
                shape.add(opcode + " " + constant.cst);
            } else if (instruction instanceof FieldInsnNode field) {
                shape.add(opcode + " " + field.owner + "." + field.name + " " + field.desc);
            } else if (instruction instanceof MethodInsnNode call) {
                shape.add(opcode + " " + call.owner + "." + call.name + call.desc);
            } else if (instruction instanceof TypeInsnNode type) {
                shape.add(opcode + " " + type.desc);
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                shape.add(opcode + " " + dynamic.name + dynamic.desc);
            } else {
                shape.add(opcode);
            }
        }
        return shape;
    }

    private static List<String> previousOperands(AbstractInsnNode boundary, int count) {
        List<String> operands = new ArrayList<>();
        for (AbstractInsnNode instruction = boundary.getPrevious();
                instruction != null && operands.size() < count;
                instruction = instruction.getPrevious()) {
            if (instruction.getOpcode() < 0) {
                continue;
            }
            String operand;
            if (instruction instanceof VarInsnNode variable) {
                String opcode = switch (instruction.getOpcode()) {
                    case Opcodes.ALOAD -> "ALOAD";
                    case Opcodes.LLOAD -> "LLOAD";
                    default -> throw new AssertionError(
                            "Unexpected local-load opcode " + instruction.getOpcode());
                };
                operand = opcode + " " + variable.var;
            } else if (instruction instanceof FieldInsnNode field
                    && instruction.getOpcode() == Opcodes.GETSTATIC) {
                operand = "GETSTATIC " + field.owner + "." + field.name + " " + field.desc;
            } else {
                throw new AssertionError(
                        "Unexpected fire-boundary operand opcode " + instruction.getOpcode());
            }
            operands.add(0, operand);
        }
        assertEquals(count, operands.size());
        return operands;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static List<VarInsnNode> previousVariableLoads(AbstractInsnNode boundary, int count) {
        List<VarInsnNode> loads = new ArrayList<>();
        for (AbstractInsnNode instruction = boundary.getPrevious();
                instruction != null && loads.size() < count; instruction = instruction.getPrevious()) {
            if (instruction instanceof VarInsnNode variable
                    && (instruction.getOpcode() == Opcodes.ALOAD
                    || instruction.getOpcode() == Opcodes.ILOAD
                    || instruction.getOpcode() == Opcodes.LLOAD)) {
                loads.add(0, variable);
            }
        }
        assertEquals(count, loads.size());
        return loads;
    }

    private static List<MethodInsnNode> methodCalls(MethodNode method) {
        return Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                        instruction -> instruction.getNext())
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }

    private static int callIndex(List<MethodInsnNode> calls, String owner, String name) {
        for (int index = 0; index < calls.size(); index++) {
            MethodInsnNode call = calls.get(index);
            if (call.owner.equals(owner) && call.name.equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static String callIdentity(MethodInsnNode call) {
        return call.owner + "." + call.name + call.desc;
    }
}
