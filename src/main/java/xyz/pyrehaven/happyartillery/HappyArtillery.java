package xyz.pyrehaven.happyartillery;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Composition root for the guarded owner graph. */
public final class HappyArtillery implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("happy-artillery");
    private static final Hud<ServerPlayer, net.minecraft.server.level.ServerBossEvent> HUD = Hud.minecraft();

    @Override
    public void onInitialize() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("happy-artillery.json");
        try {
            initialize(configPath, FabricRegistrar.INSTANCE);
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to load Happy Artillery config " + configPath, failure);
        }
    }

    static void initialize(Path configPath, Registrar registrar) throws IOException {
        Config.load(Objects.requireNonNull(configPath, "configPath"));
        Objects.requireNonNull(registrar, "registrar");
        registrar.registerGhastState();
        registrar.registerRiderState();
        registrar.registerUseItem();
        registrar.registerUseEntity();
        registrar.registerGhastLoad();
        registrar.registerPlayerAvailable();
        registrar.registerPlayerTick();
        registrar.registerServerStop();
        registrar.registerReload(configPath);
        registrar.registerConfigValidation(() -> Config.resolveConfiguredItems(
                Config.current(), Config::isRegisteredItem));
    }

    static int executeReload(Path configPath, ReloadFeedback feedback) {
        Objects.requireNonNull(configPath, "configPath");
        Objects.requireNonNull(feedback, "feedback");
        try {
            Config.reload(configPath);
            feedback.success("Happy Artillery config reloaded.");
            return Command.SINGLE_SUCCESS;
        } catch (IllegalArgumentException | IOException failure) {
            feedback.failure("Happy Artillery config reload failed: " + failure.getMessage());
            return 0;
        }
    }

    static <P, G> void tick(DriverAccess<P, G> access) {
        Objects.requireNonNull(access, "access");
        long now = access.gameTime();
        Map<Object, List<PlayerView<P, G>>> groups = new LinkedHashMap<>();
        for (P player : access.onlinePlayers()) {
            PlayerView<P, G> view;
            try {
                view = access.inspectPlayer(player);
            } catch (Controls.InvalidRiderState failure) {
                access.recoverInvalidRiderState(player, failure);
                continue;
            }
            if (view.riddenGhast().isEmpty()) {
                access.removeHud(player);
                continue;
            }
            G ghast = view.riddenGhast().orElseThrow();
            groups.computeIfAbsent(access.ghastId(ghast), ignored -> new java.util.ArrayList<>())
                    .add(view);
        }
        for (List<PlayerView<P, G>> riders : groups.values()) {
            PlayerView<P, G> pilot = riders.stream().filter(PlayerView::pilot).findFirst().orElse(null);
            if (pilot == null) {
                riders.forEach(rider -> access.removeHud(rider.player()));
                continue;
            }
            G ghast = pilot.riddenGhast().orElseThrow();
            Controls.InventorySnapshot snapshot = access.snapshot(pilot.player(), ghast);
            processPilot(access, riders, pilot, now, snapshot);
        }
        access.runDueFuses(now);
    }

    private static <P, G> void processPilot(
            DriverAccess<P, G> access, List<PlayerView<P, G>> riders,
            PlayerView<P, G> pilot, long now, Controls.InventorySnapshot snapshot) {
        G ghast = pilot.riddenGhast().orElseThrow();
        Config config = access.config();
        BiomeClass biomeClass = access.classify(ghast, config);
        boolean inWater = access.inWater(ghast);
        GhastState state = access.ghastState(ghast);
        GhastState advanced = access.advance(ghast, state, now, config, biomeClass, inWater);
        Controls.Admission admission = access.controls(
                pilot.player(), pilot.state(), now, config, snapshot);
        processPilot(access, riders, pilot, now, config, biomeClass, inWater,
                state, advanced, admission, snapshot);
    }

    static <P, G> void processPilot(
            DriverAccess<P, G> access, List<PlayerView<P, G>> riders,
            PlayerView<P, G> pilot, long now, Config config, Controls.Admission admission,
            Controls.InventorySnapshot snapshot) {
        G ghast = pilot.riddenGhast().orElseThrow();
        Objects.requireNonNull(config, "config");
        BiomeClass biomeClass = access.classify(ghast, config);
        boolean inWater = access.inWater(ghast);
        GhastState state = access.ghastState(ghast);
        GhastState advanced = access.advance(ghast, state, now, config, biomeClass, inWater);
        processPilot(access, riders, pilot, now, config, biomeClass, inWater,
                state, advanced, admission, snapshot);
    }

    private static <P, G> void processPilot(
            DriverAccess<P, G> access, List<PlayerView<P, G>> riders,
            PlayerView<P, G> pilot, long now, Config config, BiomeClass biomeClass,
            boolean inWater, GhastState state, GhastState advanced, Controls.Admission admission,
            Controls.InventorySnapshot snapshot) {
        G ghast = pilot.riddenGhast().orElseThrow();
        Object ghastId = access.ghastId(ghast);
        if (!advanced.equals(state)) {
            access.replaceGhastState(ghast, advanced);
        }
        GhastState post = advanced;
        RiderState acceptedPilotState = null;
        boolean activeFireControl = admission instanceof Controls.Accepted accepted
                && accepted.intent() == Controls.ControlIntent.FIRE;
        if (admission instanceof Controls.Accepted accepted) {
            acceptedPilotState = accepted.state();
            access.replaceRiderState(pilot.player(), acceptedPilotState);
            switch (accepted.intent()) {
                case FIRE -> access.fire(pilot.player(), ghast, advanced, now, config, biomeClass);
                case CRY -> access.cry(pilot.player(), ghast, advanced, now, config);
            }
            post = access.ghastState(ghast);
        }
        Hud.Mode mode = presentationMode(inWater, post, now, config, biomeClass);
        for (PlayerView<P, G> rider : riders) {
            if (rider.riddenGhast().isPresent()
                    && ghastId.equals(access.ghastId(rider.riddenGhast().orElseThrow()))) {
                PlayerView<P, G> renderView = rider == pilot && acceptedPilotState != null
                        ? new PlayerView<>(
                                rider.player(), acceptedPilotState, rider.riddenGhast(), rider.pilot())
                        : rider;
                access.render(renderView, ghast, post, now, config, mode,
                        rider == pilot ? Optional.of(snapshot) : Optional.empty(),
                        rider == pilot && activeFireControl);
            }
        }
    }

    static Hud.Mode presentationMode(
            boolean inWater, GhastState state, long now, Config config, BiomeClass biomeClass) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(biomeClass, "biomeClass");
        if (inWater) {
            return new Hud.Cooling(config.water().coolPerSecond());
        }
        if (now <= state.firingWindowEndTick()) {
            return Hud.Firing.FIRING;
        }
        return new Hud.Cooling(biomeClass.profile(config).coolPerSecond());
    }

    interface DriverAccess<P, G> {
        long gameTime();
        List<P> onlinePlayers();
        PlayerView<P, G> inspectPlayer(P player);
        void runDueFuses(long now);
        Object ghastId(G ghast);
        boolean inWater(G ghast);
        Config config();
        BiomeClass classify(G ghast, Config config);
        GhastState ghastState(G ghast);
        GhastState advance(
                G ghast, GhastState state, long now, Config config,
                BiomeClass biomeClass, boolean inWater);
        Controls.InventorySnapshot snapshot(P pilot, G ghast);
        Controls.Admission controls(
                P pilot, RiderState state, long now, Config config,
                Controls.InventorySnapshot snapshot);
        Controls.Admission callbackControls(
                P pilot, Object target, InteractionHand hand,
                RiderState state, long now, Config config);
        void recoverInvalidRiderState(P player, Controls.InvalidRiderState failure);
        void removeHud(P player);
        void replaceRiderState(P player, RiderState state);
        void replaceGhastState(G ghast, GhastState state);
        Abilities.FireOutcome fire(
                P pilot, G ghast, GhastState state, long now, Config config, BiomeClass biomeClass);
        Abilities.CryOutcome cry(P pilot, G ghast, GhastState state, long now, Config config);
        void render(
                PlayerView<P, G> rider, G ghast, GhastState state, long now,
                Config config, Hud.Mode mode,
                Optional<Controls.InventorySnapshot> pilotSnapshot,
                boolean activeFireControl);
    }

    record PlayerView<P, G>(P player, RiderState state, Optional<G> riddenGhast, boolean pilot) {
        PlayerView {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(riddenGhast, "riddenGhast");
            if (pilot && riddenGhast.isEmpty()) {
                throw new IllegalArgumentException("A pilot must have a ridden Happy Ghast");
            }
        }
    }

    static <P, G> void ghastLoaded(G ghast, long now, LifecycleAccess<P, G> access) {
        access.wakeGhast(ghast, now);
    }

    static <P, G> void riderAvailable(P rider, LifecycleAccess<P, G> access) {
        access.wakeRider(rider);
    }

    static <P, G> void serverStopped(LifecycleAccess<P, G> access) {
        access.clearFuses();
        access.clearHud();
    }

    interface LifecycleAccess<P, G> {
        void wakeGhast(G ghast, long now);
        void wakeRider(P rider);
        void clearFuses();
        void clearHud();
    }

    interface Registrar {
        void registerGhastState();
        void registerRiderState();
        void registerUseItem();
        void registerUseEntity();
        void registerGhastLoad();
        void registerPlayerAvailable();
        void registerPlayerTick();
        void registerServerStop();
        void registerReload(Path configPath);
        void registerConfigValidation(Runnable validation);
    }

    interface ReloadFeedback {
        void success(String message);
        void failure(String message);
    }

    private enum FabricRegistrar implements Registrar {
        INSTANCE;

        @Override public void registerGhastState() { GhastState.register(); }
        @Override public void registerRiderState() { RiderState.register(); }

        @Override
        public void registerUseItem() {
            UseItemCallback.EVENT.register(HappyArtillery::onUseItem);
        }

        @Override
        public void registerUseEntity() {
            UseEntityCallback.EVENT.register(HappyArtillery::onUseEntity);
        }

        @Override
        public void registerGhastLoad() {
            ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
                if (entity instanceof HappyGhast ghast) {
                    ghastLoaded(ghast, level.getServer().overworld().getGameTime(),
                            MinecraftLifecycleAccess.INSTANCE);
                }
            });
        }

        @Override
        public void registerPlayerAvailable() {
            ServerPlayConnectionEvents.JOIN.register((listener, sender, server) ->
                    riderAvailable(listener.getPlayer(), MinecraftLifecycleAccess.INSTANCE));
            ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
                    HUD.remove(listener.getPlayer()));
        }

        @Override
        public void registerPlayerTick() {
            ServerTickEvents.END_SERVER_TICK.register(server ->
                    tick(new MinecraftDriverAccess(server)));
        }

        @Override
        public void registerServerStop() {
            ServerLifecycleEvents.SERVER_STOPPED.register(server ->
                    serverStopped(MinecraftLifecycleAccess.INSTANCE));
        }

        @Override
        public void registerReload(Path configPath) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                    dispatcher.register(Commands.literal("ha")
                            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                            .then(Commands.literal("reload")
                                    .executes(context -> executeReload(configPath,
                                            new CommandSourceReloadFeedback(context.getSource()))))));
        }

        @Override
        public void registerConfigValidation(Runnable validation) {
            Objects.requireNonNull(validation, "validation");
            ServerLifecycleEvents.SERVER_STARTED.register(server -> validation.run());
        }
    }

    private static final class CommandSourceReloadFeedback implements ReloadFeedback {
        private final CommandSourceStack source;

        private CommandSourceReloadFeedback(CommandSourceStack source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        @Override
        public void success(String message) {
            source.sendSuccess(() -> Component.literal(message), false);
        }

        @Override
        public void failure(String message) {
            source.sendFailure(Component.literal(message));
        }
    }

    private enum MinecraftLifecycleAccess
            implements LifecycleAccess<ServerPlayer, HappyGhast> {
        INSTANCE;

        @Override public void wakeGhast(HappyGhast ghast, long now) {
            Abilities.onGhastLoad(ghast, now);
        }
        @Override public void wakeRider(ServerPlayer rider) {
            Abilities.onRiderAvailable(rider);
        }
        @Override public void clearFuses() {
            Abilities.onServerStop();
        }
        @Override public void clearHud() {
            HUD.clear();
        }
    }

    private static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            handleCallback(serverPlayer, null, hand);
        }
        return InteractionResult.PASS;
    }

    private static InteractionResult onUseEntity(
            Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            handleCallback(serverPlayer, entity, hand);
        }
        return InteractionResult.PASS;
    }

    private static void handleCallback(ServerPlayer player, Entity target, InteractionHand hand) {
        MinecraftDriverAccess access = new MinecraftDriverAccess(player.level().getServer());
        handleCallback(access, player, target, hand);
    }

    static <P, G> void handleCallback(
            DriverAccess<P, G> access, P player, Object target, InteractionHand hand) {
        long now = access.gameTime();
        Config config = access.config();
        PlayerView<P, G> view;
        try {
            view = access.inspectPlayer(player);
        } catch (Controls.InvalidRiderState failure) {
            access.recoverInvalidRiderState(player, failure);
            return;
        }
        if (!view.pilot()) {
            return;
        }
        Controls.Admission admission = access.callbackControls(
                player, target, hand, view.state(), now, config);
        processActorInput(access, view, now, config, admission);
    }

    private static <P, G> void processActorInput(
            DriverAccess<P, G> access, PlayerView<P, G> pilot, long now,
            Config config, Controls.Admission admission) {
        if (!(admission instanceof Controls.Accepted accepted)) {
            return;
        }
        G ghast = pilot.riddenGhast().orElseThrow();
        BiomeClass biomeClass = access.classify(ghast, config);
        boolean inWater = access.inWater(ghast);
        GhastState state = access.ghastState(ghast);
        GhastState advanced = access.advance(ghast, state, now, config, biomeClass, inWater);
        if (!advanced.equals(state)) {
            access.replaceGhastState(ghast, advanced);
        }
        access.replaceRiderState(pilot.player(), accepted.state());
        switch (accepted.intent()) {
            case FIRE -> access.fire(pilot.player(), ghast, advanced, now, config, biomeClass);
            case CRY -> access.cry(pilot.player(), ghast, advanced, now, config);
        }
    }

    private static final class MinecraftDriverAccess
            implements DriverAccess<ServerPlayer, HappyGhast> {
        private final MinecraftServer server;

        private MinecraftDriverAccess(MinecraftServer server) {
            this.server = Objects.requireNonNull(server, "server");
        }

        @Override
        public long gameTime() {
            return server.overworld().getGameTime();
        }

        @Override
        public List<ServerPlayer> onlinePlayers() {
            return server.getPlayerList().getPlayers();
        }

        @Override
        public PlayerView<ServerPlayer, HappyGhast> inspectPlayer(ServerPlayer player) {
            AttachmentTarget target = (AttachmentTarget) (Object) player;
            RiderState attached = target.getAttached(RiderState.register());
            RiderState state = attached == null ? RiderState.fresh() : attached;
            Entity vehicle = player.getVehicle();
            Optional<HappyGhast> ridden = vehicle instanceof HappyGhast ghast
                    ? Optional.of(ghast) : Optional.empty();
            boolean pilot = ridden.filter(ghast ->
                    Controls.ServerPlayerControlAccess.INSTANCE
                            .isControllingFirstPassenger(player, ghast)).isPresent();
            Optional<UUID> pilotGhastId = pilot
                    ? Optional.of(ridden.orElseThrow().getUUID()) : Optional.empty();
            RiderState reconciled = Controls.reconcile(player, state, pilotGhastId);
            if (reconciled != state) {
                RiderState.replace(target, reconciled);
            }
            return new PlayerView<>(player, reconciled, ridden, pilot);
        }

        @Override
        public void runDueFuses(long now) {
            Abilities.runDueFuses(now, server);
        }

        @Override
        public Object ghastId(HappyGhast ghast) {
            return ghast.getUUID();
        }

        @Override
        public boolean inWater(HappyGhast ghast) {
            return ghast.isInWater();
        }

        @Override
        public Config config() {
            return Config.current();
        }

        @Override
        public BiomeClass classify(HappyGhast ghast, Config config) {
            return BiomeClass.classify(
                    ghast.level().dimension(),
                    ghast.level().getBiome(ghast.blockPosition()).value().getBaseTemperature(),
                    config);
        }

        @Override
        public GhastState ghastState(HappyGhast ghast) {
            AttachmentTarget target = (AttachmentTarget) (Object) ghast;
            GhastState state = target.getAttached(GhastState.register());
            if (state == null) {
                state = GhastState.fresh();
                GhastState.replace(target, state);
            }
            return state;
        }

        @Override
        public GhastState advance(
                HappyGhast ghast, GhastState state, long now,
                Config config, BiomeClass biomeClass, boolean inWater) {
            return Heat.advance(state, now, biomeClass.profile(config), inWater, config.water());
        }

        @Override
        public Controls.InventorySnapshot snapshot(ServerPlayer pilot, HappyGhast ghast) {
            return Controls.snapshot(pilot, ghast.getUUID());
        }

        @Override
        public Controls.Admission controls(
                ServerPlayer pilot, RiderState state, long now, Config config,
                Controls.InventorySnapshot snapshot) {
            return Controls.sampleHeld(pilot, state, now, config.controls(), snapshot);
        }

        @Override
        public Controls.Admission callbackControls(
                ServerPlayer pilot, Object target, InteractionHand hand,
                RiderState state, long now, Config config) {
            return target == null
                    ? Controls.handleUseItem(pilot, hand, state, now, config.controls())
                    : Controls.handleUseEntity(
                            pilot, target, hand, state, now, config.controls(),
                            Controls.ServerPlayerControlAccess.INSTANCE);
        }

        @Override
        public void recoverInvalidRiderState(
                ServerPlayer player, Controls.InvalidRiderState failure) {
            LOGGER.warn("Resetting invalid persisted rider state for {}: {}",
                    player.getUUID(), failure.getMessage());
            RiderState.replace((AttachmentTarget) (Object) player,
                    Controls.recoverInvalidState(player, failure));
            HUD.remove(player);
        }

        @Override
        public void removeHud(ServerPlayer player) {
            HUD.remove(player);
        }

        @Override
        public void replaceRiderState(ServerPlayer player, RiderState state) {
            RiderState.replace((AttachmentTarget) (Object) player, state);
        }

        @Override
        public void replaceGhastState(HappyGhast ghast, GhastState state) {
            GhastState.replace((AttachmentTarget) (Object) ghast, state);
        }

        @Override
        public Abilities.FireOutcome fire(
                ServerPlayer pilot, HappyGhast ghast, GhastState state, long now,
                Config config, BiomeClass biomeClass) {
            Abilities.FireOutcome outcome = Abilities.fire(
                    pilot, ghast, state, now, config, biomeClass);
            if (outcome instanceof Abilities.Rejected rejected
                    && rejected.reason() == Abilities.FireRejection.IN_WATER) {
                Feedback.presentWaterBlocked(pilot);
            }
            return outcome;
        }

        @Override
        public Abilities.CryOutcome cry(
                ServerPlayer pilot, HappyGhast ghast, GhastState state, long now, Config config) {
            Abilities.CryOutcome outcome = Abilities.cry(
                    pilot, ghast, state, now, config, Abilities.ServerPlayerCryAccess.INSTANCE);
            if (outcome instanceof Abilities.CryRejected rejected
                    && rejected.reason() == Abilities.CryRejection.IN_WATER) {
                Feedback.presentWaterBlocked(pilot);
            }
            return outcome;
        }

        @Override
        public void render(
                PlayerView<ServerPlayer, HappyGhast> rider, HappyGhast ghast,
                GhastState state, long now, Config config, Hud.Mode mode,
                Optional<Controls.InventorySnapshot> pilotSnapshot,
                boolean activeFireControl) {
            if (!(ghast.level() instanceof net.minecraft.server.level.ServerLevel level)) {
                throw new IllegalArgumentException("HUD requires a loaded server Happy Ghast");
            }
            RiderState updated = HUD.update(
                    rider.player().getUUID(), rider.player(), ghast.getUUID(), rider.state(), now,
                    new Hud.Snapshot(
                            state.heat(), mode, pilotSnapshot, activeFireControl),
                    config, Hud.minecraftPresentation(level, ghast));
            if (!updated.equals(rider.state())) {
                replaceRiderState(rider.player(), updated);
            }
        }
    }
}
