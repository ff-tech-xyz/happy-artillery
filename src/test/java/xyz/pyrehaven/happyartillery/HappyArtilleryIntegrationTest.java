package xyz.pyrehaven.happyartillery;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void missingConfigIsCreatedAndLoadedBeforeRegistrationAndTheExactGuard() throws Exception {
        RecordingRegistrar registrar = new RecordingRegistrar();
        Path configPath = tempDir.resolve("happy-artillery.json");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> HappyArtillery.initialize(configPath, registrar));

        assertEquals("Happy Artillery structural groundwork is not a playable build", failure.getMessage());
        assertEquals(true, Files.isRegularFile(configPath));
        assertEquals(Config.defaults(), Config.current());
        assertEquals(Map.of(
                "components", 1,
                "ghast-state", 1,
                "rider-state", 1,
                "use-item", 1,
                "use-entity", 1,
                "ghast-load", 1,
                "player-available", 1,
                "player-tick", 1,
                "server-stop", 1), registrar.calls);
        assertEquals("components", registrar.order.getFirst());
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

        HappyArtillery.tick(access);

        assertEquals(1, access.configReads);
        assertEquals(1, access.classifications);
        assertEquals(2, access.ghastProcesses);
        assertEquals(1, access.fireCalls);
        assertEquals(List.of("pilot:7.0", "passenger:7.0"), access.hudSnapshots);
        assertEquals(true, access.order.indexOf("fuses") > access.order.lastIndexOf("hud:passenger"));
    }

    @Test
    void callbackAdmissionUsesOneExplicitConfigThroughThePilotTransition() {
        RecordingDriver access = RecordingDriver.ridden();
        HappyArtillery.PlayerView<String, String> pilot = access.inspectPlayer("pilot");
        HappyArtillery.PlayerView<String, String> passenger = access.inspectPlayer("passenger");
        Config config = access.config();

        HappyArtillery.processPilot(access, List.of(pilot, passenger), pilot, 41L, config,
                new Controls.Accepted(Controls.ControlIntent.FIRE, pilot.state()));

        assertEquals(1, access.configReads);
        assertEquals(1, access.fireCalls);
        assertEquals(List.of("pilot:7.0", "passenger:7.0"), access.hudSnapshots);
    }

    @Test
    void acceptedConsumedFailureRendersTheAuthoritativeResetAttachment() {
        RecordingDriver access = RecordingDriver.consumedFailure();
        HappyArtillery.PlayerView<String, String> pilot = access.inspectPlayer("pilot");
        HappyArtillery.PlayerView<String, String> passenger = access.inspectPlayer("passenger");
        Config config = access.config();

        HappyArtillery.processPilot(access, List.of(pilot, passenger), pilot, 41L, config,
                new Controls.Accepted(Controls.ControlIntent.FIRE, pilot.state()));

        assertEquals(2, access.ghastProcesses);
        assertEquals(List.of("pilot:0.0", "passenger:0.0"), access.hudSnapshots);
    }

    @Test
    void acceptedTickSurvivesHudPersistenceAndRejectsTheSameTickDriverAdmission() {
        RecordingDriver access = RecordingDriver.dedupRegression();
        HappyArtillery.PlayerView<String, String> pilot = access.inspectPlayer("pilot");
        HappyArtillery.PlayerView<String, String> passenger = access.inspectPlayer("passenger");
        Config config = access.config();
        Controls.Admission accepted = access.controls("pilot", pilot.state(), 41L, config);

        HappyArtillery.processPilot(
                access, List.of(pilot, passenger), pilot, 41L, config, accepted);

        RiderState persisted = access.riderStates.get("pilot");
        assertEquals(41L, persisted.lastHandledTick());
        assertEquals(true, persisted.hudCache().isPresent());
        assertEquals(Long.MIN_VALUE, access.riderStates.get("passenger").lastHandledTick());

        HappyArtillery.tick(access);

        assertEquals(1, access.cryCalls);
        assertEquals(41L, access.riderStates.get("pilot").lastHandledTick());
    }

    @Test
    void productionCallbackCollectsBoundedOnlineViewsAndUsesExplicitControlConfig() throws IOException {
        ClassNode root = BytecodeTestSupport.classNode(HappyArtillery.class.getName());
        MethodNode callback = method(root, "handleCallback",
                "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity;"
                        + "Lnet/minecraft/world/InteractionHand;)V");
        List<MethodInsnNode> calls = Stream.iterate(
                        callback.instructions.getFirst(), java.util.Objects::nonNull,
                        instruction -> instruction.getNext())
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .toList();

        assertEquals(1, calls.stream().filter(call -> call.name.equals("onlinePlayers")).count());
        assertEquals(1, calls.stream().filter(call -> call.name.equals("inspectPlayer")).count());
        assertEquals(0, calls.stream().filter(call ->
                call.owner.equals("java/util/List") && call.name.equals("of")).count());
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Config") && call.name.equals("controls")).count());
        assertEquals(2, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls")
                && (call.name.equals("handleUseItem") || call.name.equals("handleUseEntity"))
                && call.desc.contains("Lxyz/pyrehaven/happyartillery/Config$Controls;")).count());
        assertEquals(1, calls.stream().filter(call -> call.name.equals("processPilot")).count());
    }

    @Test
    void productionInspectionReconcilesOnceAndRoutesDismountHudCleanupToHud() throws IOException {
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
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Hud") && call.name.equals("remove")).count());
        assertEquals(0, calls.stream().filter(call -> call.owner.equals(
                "net/minecraft/world/entity/player/Inventory")).count());
    }

    @Test
    void productionStartupUsesExactFabricConfigPathBeforeRegistrationAndGuard() throws IOException {
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
                "xyz/pyrehaven/happyartillery/HappyArtillery$Registrar", "registerComponents");
        assertEquals(true, load >= 0 && load < firstRegistration);
        assertEquals(1, Stream.iterate(initialize.instructions.getFirst(), java.util.Objects::nonNull,
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
                "registerServerStop", List.of("net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.SERVER_STOPPED"));

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            MethodNode method = method(registrar, entry.getKey(), "()V");
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
                        + "RiderState;JL" + PACKAGE + "Config;)L" + PACKAGE
                        + "Controls$Admission;"));
        assertEquals(1, controlsCalls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls") && call.name.equals("sampleHeld")
                && call.desc.contains("Lxyz/pyrehaven/happyartillery/Config$Controls;")).count());
        assertEquals(0, controlsCalls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Config") && call.name.equals("current")).count());
    }

    @Test
    void productionStopDelegatesOnlyToAbilitiesAndHudAndRootConstructsNoProjectile() throws IOException {
        ClassNode lifecycle = BytecodeTestSupport.classNode(
                HappyArtillery.class.getName() + "$MinecraftLifecycleAccess");
        assertEquals(List.of("xyz/pyrehaven/happyartillery/Abilities.onServerStop()V"),
                methodCalls(method(lifecycle, "clearFuses", "()V")).stream()
                        .map(HappyArtilleryIntegrationTest::callIdentity).toList());
        assertEquals(List.of("xyz/pyrehaven/happyartillery/Hud.clear()V"),
                methodCalls(method(lifecycle, "clearHud", "()V")).stream()
                        .map(HappyArtilleryIntegrationTest::callIdentity).toList());

        for (String className : List.of(HappyArtillery.class.getName(),
                HappyArtillery.class.getName() + "$MinecraftDriverAccess",
                HappyArtillery.class.getName() + "$FabricRegistrar")) {
            ClassNode node = BytecodeTestSupport.classNode(className);
            assertEquals(0, node.methods.stream().flatMap(method -> Stream.iterate(
                            method.instructions.getFirst(), java.util.Objects::nonNull,
                            instruction -> instruction.getNext()))
                    .filter(TypeInsnNode.class::isInstance).map(TypeInsnNode.class::cast)
                    .filter(instruction -> instruction.desc.endsWith("LargeFireball")).count());
        }
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
        private int ghastProcesses;
        private int fireCalls;
        private int cryCalls;
        private Config expectedConfig;
        private final boolean consumedFailure;
        private final boolean dedupRegression;
        private GhastState authoritativeState;
        private final List<String> hudSnapshots = new ArrayList<>();
        private final Map<String, RiderState> riderStates = new LinkedHashMap<>();
        private final Hud hud = new Hud();

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
                        Optional.of(new RiderState.StashedStack(4, ItemStack.EMPTY)),
                        Optional.of(new RiderState.StashedStack(5, ItemStack.EMPTY)),
                        Optional.of(RegressionControlAccess.GHAST_ID),
                        Long.MIN_VALUE, Optional.empty()));
                riderStates.put("passenger", RiderState.fresh());
            }
        }

        private static RecordingDriver ridden() {
            return new RecordingDriver(List.of("pilot", "passenger"));
        }

        private static RecordingDriver consumedFailure() {
            return new RecordingDriver(List.of("pilot", "passenger"), true);
        }

        private static RecordingDriver dedupRegression() {
            return new RecordingDriver(List.of("pilot", "passenger"), false, true);
        }

        @Override public long gameTime() { clockReads++; order.add("clock"); return 41L; }
        @Override public List<String> onlinePlayers() { order.add("players"); return players; }
        @Override public HappyArtillery.PlayerView<String, String> inspectPlayer(String player) {
            playerChecks.merge(player, 1, Integer::sum);
            order.add("check:" + player);
            if (players.size() == 2) {
                return new HappyArtillery.PlayerView<>(player,
                        riderStates.getOrDefault(player, RiderState.fresh()),
                        Optional.of("ghast"), player.equals("pilot"));
            }
            return new HappyArtillery.PlayerView<>(player, RiderState.fresh(), Optional.empty(), false);
        }
        @Override public void runDueFuses(long now) { order.add("fuses"); }
        @Override public Object ghastId(String ghast) { return ghast; }
        @Override public Config config() {
            configReads++;
            expectedConfig = Config.defaults();
            return expectedConfig;
        }
        @Override public BiomeClass classify(String ghast, Config config) {
            assertSame(expectedConfig, config);
            classifications++;
            return BiomeClass.HOT;
        }
        @Override public GhastState ghastState(String ghast) {
            ghastProcesses++;
            if (authoritativeState != null) {
                return authoritativeState;
            }
            return GhastState.fresh();
        }
        @Override public GhastState advance(
                String ghast, GhastState state, long now, Config config, BiomeClass biomeClass) {
            assertSame(expectedConfig, config);
            return new GhastState(5.0, now, now, 0L, 0L,
                    java.util.OptionalLong.empty(), Optional.empty());
        }
        @Override public Controls.Admission controls(
                String pilot, RiderState state, long now, Config config) {
            assertSame(expectedConfig, config);
            if (dedupRegression) {
                return Controls.handleUseItem(
                        pilot, InteractionHand.MAIN_HAND, state, now,
                        config.controls(), RegressionControlAccess.INSTANCE);
            }
            return new Controls.Accepted(Controls.ControlIntent.FIRE, state);
        }
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
                GhastState state, long now, Config config, BiomeClass biomeClass) {
            assertSame(expectedConfig, config);
            hudSnapshots.add(rider.player() + ":" + state.heat());
            order.add("hud:" + rider.player());
            if (dedupRegression) {
                RiderState updated = hud.update(
                        rider.player(), rider.player(), ghast, rider.state(), now,
                        new Hud.Snapshot(state.heat(), biomeClass, Hud.Status.COOLING),
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
        @Override public ItemStack itemInHand(String player, InteractionHand hand) {
            return Controls.cryControl();
        }
        @Override public ItemStack itemAt(String player, int slot) {
            return slot == 4 ? Controls.fireControl() : Controls.cryControl();
        }
        @Override public Controls.ObservedUse observedUse(String player) {
            return new Controls.ObservedUse(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    private enum NoopPresentationAccess implements Hud.PresentationAccess<String, String> {
        INSTANCE;

        @Override public String createBossBar(double progress, Hud.Color color) { return "bar"; }
        @Override public void addViewer(String handle, String rider) { }
        @Override public void setProgress(String handle, double progress) { }
        @Override public void setColor(String handle, Hud.Color color) { }
        @Override public void removeViewer(String handle, String rider) { }
        @Override public void actionBar(String rider, String text, Hud.Color color) { }
        @Override public void warningParticle(String rider) { }
    }

    private static final class RecordingRegistrar implements HappyArtillery.Registrar {
        private final Map<String, Integer> calls = new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();

        private void record(String name) {
            calls.merge(name, 1, Integer::sum);
            order.add(name);
        }

        @Override public void registerComponents() { record("components"); }
        @Override public void registerGhastState() { record("ghast-state"); }
        @Override public void registerRiderState() { record("rider-state"); }
        @Override public void registerUseItem() { record("use-item"); }
        @Override public void registerUseEntity() { record("use-entity"); }
        @Override public void registerGhastLoad() { record("ghast-load"); }
        @Override public void registerPlayerAvailable() { record("player-available"); }
        @Override public void registerPlayerTick() { record("player-tick"); }
        @Override public void registerServerStop() { record("server-stop"); }
    }

    private static MethodNode method(ClassNode owner, String name, String descriptor) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .toList();
        assertEquals(1, matches.size(), owner.name + "." + name + descriptor);
        return matches.getFirst();
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
