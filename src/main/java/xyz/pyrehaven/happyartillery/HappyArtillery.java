package xyz.pyrehaven.happyartillery;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Composition root for the guarded owner graph. */
public final class HappyArtillery implements ModInitializer {
    private static final Hud HUD = new Hud();

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
        registrar.registerComponents();
        registrar.registerGhastState();
        registrar.registerRiderState();
        registrar.registerUseItem();
        registrar.registerUseEntity();
        registrar.registerGhastLoad();
        registrar.registerPlayerAvailable();
        registrar.registerPlayerTick();
        registrar.registerServerStop();
        throw new IllegalStateException(
                "Happy Artillery structural groundwork is not a playable build");
    }

    static <P, G> void tick(DriverAccess<P, G> access) {
        Objects.requireNonNull(access, "access");
        long now = access.gameTime();
        List<PlayerView<P, G>> players = new ArrayList<>();
        for (P player : access.onlinePlayers()) {
            players.add(access.inspectPlayer(player));
        }
        LinkedHashSet<Object> processed = new LinkedHashSet<>();
        for (PlayerView<P, G> pilot : players) {
            if (!pilot.pilot()) {
                continue;
            }
            G ghast = pilot.riddenGhast().orElseThrow();
            Object ghastId = access.ghastId(ghast);
            if (!processed.add(ghastId)) {
                continue;
            }
            processPilot(access, players, pilot, now);
        }
        access.runDueFuses(now);
    }

    private static <P, G> void processPilot(
            DriverAccess<P, G> access, List<PlayerView<P, G>> riders,
            PlayerView<P, G> pilot, long now) {
        G ghast = pilot.riddenGhast().orElseThrow();
        Config config = access.config();
        BiomeClass biomeClass = access.classify(ghast, config);
        GhastState state = access.ghastState(ghast);
        GhastState advanced = access.advance(ghast, state, now, config, biomeClass);
        Controls.Admission admission = access.controls(pilot.player(), pilot.state(), now, config);
        processPilot(access, riders, pilot, now, config, biomeClass, state, advanced, admission);
    }

    static <P, G> void processPilot(
            DriverAccess<P, G> access, List<PlayerView<P, G>> riders,
            PlayerView<P, G> pilot, long now, Config config, Controls.Admission admission) {
        G ghast = pilot.riddenGhast().orElseThrow();
        Objects.requireNonNull(config, "config");
        BiomeClass biomeClass = access.classify(ghast, config);
        GhastState state = access.ghastState(ghast);
        GhastState advanced = access.advance(ghast, state, now, config, biomeClass);
        processPilot(access, riders, pilot, now, config, biomeClass, state, advanced, admission);
    }

    private static <P, G> void processPilot(
            DriverAccess<P, G> access, List<PlayerView<P, G>> riders,
            PlayerView<P, G> pilot, long now, Config config, BiomeClass biomeClass,
            GhastState state, GhastState advanced, Controls.Admission admission) {
        G ghast = pilot.riddenGhast().orElseThrow();
        Object ghastId = access.ghastId(ghast);
        if (!advanced.equals(state)) {
            access.replaceGhastState(ghast, advanced);
        }
        GhastState post = advanced;
        RiderState acceptedPilotState = null;
        if (admission instanceof Controls.Accepted accepted) {
            acceptedPilotState = accepted.state();
            access.replaceRiderState(pilot.player(), acceptedPilotState);
            switch (accepted.intent()) {
                case FIRE -> access.fire(pilot.player(), ghast, advanced, now, config, biomeClass);
                case CRY -> access.cry(pilot.player(), ghast, advanced, now, config);
                case NONE -> throw new IllegalStateException("Accepted control intent cannot be NONE");
            }
            post = access.ghastState(ghast);
        }
        for (PlayerView<P, G> rider : riders) {
            if (rider.riddenGhast().isPresent()
                    && ghastId.equals(access.ghastId(rider.riddenGhast().orElseThrow()))) {
                PlayerView<P, G> renderView = rider == pilot && acceptedPilotState != null
                        ? new PlayerView<>(
                                rider.player(), acceptedPilotState, rider.riddenGhast(), rider.pilot())
                        : rider;
                access.render(renderView, ghast, post, now, config, biomeClass);
            }
        }
    }

    interface DriverAccess<P, G> {
        long gameTime();
        List<P> onlinePlayers();
        PlayerView<P, G> inspectPlayer(P player);
        void runDueFuses(long now);
        Object ghastId(G ghast);
        Config config();
        BiomeClass classify(G ghast, Config config);
        GhastState ghastState(G ghast);
        GhastState advance(G ghast, GhastState state, long now, Config config, BiomeClass biomeClass);
        Controls.Admission controls(P pilot, RiderState state, long now, Config config);
        void replaceRiderState(P player, RiderState state);
        void replaceGhastState(G ghast, GhastState state);
        Abilities.FireOutcome fire(
                P pilot, G ghast, GhastState state, long now, Config config, BiomeClass biomeClass);
        Abilities.CryOutcome cry(P pilot, G ghast, GhastState state, long now, Config config);
        void render(
                PlayerView<P, G> rider, G ghast, GhastState state, long now,
                Config config, BiomeClass biomeClass);
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
        void registerComponents();
        void registerGhastState();
        void registerRiderState();
        void registerUseItem();
        void registerUseEntity();
        void registerGhastLoad();
        void registerPlayerAvailable();
        void registerPlayerTick();
        void registerServerStop();
    }

    private enum FabricRegistrar implements Registrar {
        INSTANCE;

        @Override public void registerComponents() { Components.register(); }
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
        long now = access.gameTime();
        Config config = access.config();
        List<PlayerView<ServerPlayer, HappyGhast>> players = new ArrayList<>();
        PlayerView<ServerPlayer, HappyGhast> view = null;
        for (ServerPlayer online : access.onlinePlayers()) {
            PlayerView<ServerPlayer, HappyGhast> inspected = access.inspectPlayer(online);
            players.add(inspected);
            if (online == player) {
                view = inspected;
            }
        }
        if (view == null) {
            return;
        }
        if (!view.pilot()) {
            return;
        }
        Config.Controls controls = config.controls();
        Controls.Admission admission = target == null
                ? Controls.handleUseItem(player, hand, view.state(), now, controls)
                : Controls.handleUseEntity(
                        player, target, hand, view.state(), now, controls);
        if (admission instanceof Controls.Accepted) {
            processPilot(access, players, view, now, config, admission);
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
            if (ridden.isEmpty()) {
                HUD.remove(player);
            }
            return new PlayerView<>(player, reconciled, ridden, pilot);
        }

        @Override
        public void runDueFuses(long now) {
            Abilities.runDueFuses(now);
        }

        @Override
        public Object ghastId(HappyGhast ghast) {
            return ghast.getUUID();
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
                Config config, BiomeClass biomeClass) {
            return Heat.advance(state, now, biomeClass.profile(config), ghast.isInWater(), config.water());
        }

        @Override
        public Controls.Admission controls(
                ServerPlayer pilot, RiderState state, long now, Config config) {
            return Controls.sampleHeld(pilot, state, now, config.controls());
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
                Feedback.present(Abilities.CryRejection.IN_WATER, pilot);
            }
            return outcome;
        }

        @Override
        public Abilities.CryOutcome cry(
                ServerPlayer pilot, HappyGhast ghast, GhastState state, long now, Config config) {
            Abilities.CryOutcome outcome = Abilities.cry(
                    pilot, ghast, state, now, config, Abilities.ServerPlayerCryAccess.INSTANCE);
            if (outcome instanceof Abilities.CryRejected rejected) {
                Feedback.present(rejected.reason(), pilot);
            }
            return outcome;
        }


        @Override
        public void render(
                PlayerView<ServerPlayer, HappyGhast> rider, HappyGhast ghast,
                GhastState state, long now, Config config, BiomeClass biomeClass) {
            Hud.Status status = biomeClass == BiomeClass.NETHER
                    ? Hud.Status.NO_COOLING
                    : now <= state.firingWindowEndTick() ? Hud.Status.FIRING : Hud.Status.COOLING;
            RiderState updated = HUD.update(
                    rider.player(), ghast, rider.state(), now,
                    new Hud.Snapshot(state.heat(), biomeClass, status), config);
            if (!updated.equals(rider.state())) {
                replaceRiderState(rider.player(), updated);
            }
        }
    }
}
