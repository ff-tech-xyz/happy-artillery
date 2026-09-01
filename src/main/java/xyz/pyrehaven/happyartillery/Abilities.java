package xyz.pyrehaven.happyartillery;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/** Sole fire, cry, and detonation owner. */
public final class Abilities {
    private static final FuseQueue<ServerPlayer, HappyGhast> FUSES = new FuseQueue<>();
    private static final double LAUNCH_CLEARANCE = 0.01;

    private Abilities() {
    }

    static void onGhastLoad(HappyGhast ghast, long now) {
        ServerPlayerDetonationAccess access = ServerPlayerDetonationAccess.INSTANCE;
        FUSES.onGhastLoad(ghast, access.attachedState(ghast), access);
    }

    static void onRiderAvailable(ServerPlayer rider) {
        FUSES.onRiderAvailable(Objects.requireNonNull(rider, "rider").getUUID());
    }

    static int runDueFuses(long now, MinecraftServer server) {
        return FUSES.runDue(now, Config.current(), new ServerPlayerDetonationAccess(server));
    }

    static void onServerStop() {
        FUSES.clear();
    }

    static FireOutcome fire(
            ServerPlayer pilot,
            HappyGhast ghast,
            GhastState state,
            long now,
            Config config,
            BiomeClass biomeClass) {
        return fire(pilot, ghast, state, now, config, biomeClass,
                ServerPlayerFireAccess.INSTANCE, FUSES, ServerPlayerDetonationAccess.INSTANCE);
    }

    static <P, G> FireOutcome fire(
            P pilot,
            G ghast,
            GhastState state,
            long now,
            Config config,
            BiomeClass biomeClass,
            FireAccess<P, G> access,
            FuseQueue<P, G> fuses,
            DetonationAccess<P, G> detonationAccess) {
        Objects.requireNonNull(pilot, "pilot");
        Objects.requireNonNull(ghast, "ghast");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(biomeClass, "biomeClass");
        Objects.requireNonNull(access, "access");
        if (!access.isPilot(pilot, ghast)) {
            return new Rejected(FireRejection.NOT_PILOT);
        }
        if (state.detonateAtTick().isPresent()) {
            return new Rejected(FireRejection.DETONATION_PENDING);
        }
        boolean inWater = access.inWater(ghast);
        if (inWater && config.water().blocksFiring()) {
            return new Rejected(FireRejection.IN_WATER);
        }
        if (now < state.fireReadyTick()) {
            return new Rejected(FireRejection.ON_COOLDOWN);
        }
        Heat.ShotResult shot = Heat.addShot(
                state, now, biomeClass.profile(config), config.heat());
        GhastState heated = shot.state();
        GhastState committed = new GhastState(
                heated.heat(),
                heated.heatAnchorTick(),
                heated.firingWindowEndTick(),
                cooldownDeadline(now, config.fire().shotCooldownSeconds()),
                heated.cryReadyTick(),
                heated.detonateAtTick(),
                heated.detonatingRiderId());
        if (shot.detonates()) {
            DetonationAccess<P, G> executionAccess = detonationAccess.executionAccess(ghast);
            long deadline = saturatedAdd(now, config.overheat().fuseTicks());
            GhastState pending = new GhastState(
                    committed.heat(), committed.heatAnchorTick(), committed.firingWindowEndTick(),
                    committed.fireReadyTick(), committed.cryReadyTick(),
                    java.util.OptionalLong.of(deadline),
                    java.util.Optional.of(executionAccess.riderId(pilot)));
            executionAccess.replaceState(ghast, pending);
            Optional<DetonationOutcome> immediate = fuses.submit(
                    ghast, pending, now, config, executionAccess);
            if (immediate.isPresent()) {
                DetonationOutcome detonation = immediate.orElseThrow();
                return detonation instanceof DetonationConsumed
                        ? new Detonated()
                        : new Rejected(FireRejection.EFFECT_FAILED);
            }
            return new DetonationPending(pending);
        }
        if (!access.addProjectile(pilot, ghast, config.fire().explosionPower())) {
            return new Rejected(FireRejection.EFFECT_FAILED);
        }
        access.replaceState(ghast, committed);
        return new Fired(committed);
    }

    static <P, G> DetonationOutcome executeDetonation(
            G ghast,
            long now,
            Config config,
            DetonationAccess<P, G> access) {
        Optional<GhastState> attached = access.attachedState(ghast);
        if (attached.isEmpty()) {
            return new DetonationIgnored();
        }
        GhastState current = attached.orElseThrow();
        if (current.detonateAtTick().isEmpty() || current.detonatingRiderId().isEmpty()) {
            return new DetonationIgnored();
        }
        ExecutionEvidence evidence = new ExecutionEvidence();
        return executeDetonation(ghast, current.detonateAtTick().getAsLong(),
                current.detonatingRiderId().orElseThrow(), now, config, access, evidence, attached);
    }

    private static <P, G> DetonationOutcome executeDetonation(
            G ghast,
            long expectedDeadline,
            UUID expectedRiderId,
            long now,
            Config config,
            DetonationAccess<P, G> access,
            ExecutionEvidence evidence,
            Optional<GhastState> attached) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(access, "access");
        if (attached.isEmpty()) {
            evidence.stale = true;
            return new DetonationIgnored();
        }
        GhastState current = attached.orElseThrow();
        if (current.detonateAtTick().isEmpty()
                || current.detonateAtTick().getAsLong() != expectedDeadline
                || current.detonatingRiderId().isEmpty()
                || !current.detonatingRiderId().orElseThrow().equals(expectedRiderId)
                || now < expectedDeadline) {
            evidence.stale = true;
            return new DetonationIgnored();
        }
        P storedRider = access.resolveRider(ghast, expectedRiderId);
        if (storedRider == null) {
            return new DetonationDeferred(DetonationDeferral.RIDER_UNAVAILABLE);
        }
        Config.Overheat overheat = config.overheat();
        GhastState consumed = overheat.killsGhast()
                ? new GhastState(current.heat(), current.heatAnchorTick(),
                        current.firingWindowEndTick(), current.fireReadyTick(),
                        current.cryReadyTick(), java.util.OptionalLong.empty(),
                        java.util.Optional.empty())
                : new GhastState(0.0, now, now, now, current.cryReadyTick(),
                        java.util.OptionalLong.empty(), java.util.Optional.empty());
        access.replaceState(ghast, consumed);
        access.explode(storedRider, ghast, overheat.explosionPower(), overheat.breaksBlocks());
        int rejectedAttempts = 0;
        for (int index = 0; index < overheat.fireballCount(); index++) {
            if (!access.spawnFireball(ghast, sphereDirection(index, overheat.fireballCount()),
                    overheat.fireballSpeed(), overheat.fireballPower())) {
                rejectedAttempts++;
            }
        }
        if (overheat.breaksBlocks()) {
            for (int index = 0; index < overheat.fireAttempts(); index++) {
                Vec3 offset = fireOffset(index, overheat.fireAttempts(), overheat.fireRadius());
                if (access.placeFire(ghast, offset) == FireAttempt.REJECTED) {
                    rejectedAttempts++;
                }
            }
        }
        if (overheat.killsGhast()) {
            access.remove(ghast);
        }
        return rejectedAttempts == 0
                ? new DetonationConsumed()
                : new DetonationConsumedWithFailures(rejectedAttempts);
    }

    private static final class ExecutionEvidence {
        private boolean stale;
    }

    private static Vec3 sphereDirection(int index, int count) {
        double y = 1.0 - 2.0 * (index + 0.5) / count;
        double radius = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double angle = index * Math.PI * (3.0 - Math.sqrt(5.0));
        return new Vec3(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
    }

    private static Vec3 fireOffset(int index, int count, double radius) {
        if (count == 0 || radius == 0.0) {
            return Vec3.ZERO;
        }
        double distance = radius * Math.sqrt((index + 0.5) / count);
        double angle = index * Math.PI * (3.0 - Math.sqrt(5.0));
        return new Vec3(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
    }

    static Optional<Launch> launch(Vec3 start, Vec3 aim, AABB occupied, AABB projectileAtOrigin) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(aim, "aim");
        Objects.requireNonNull(occupied, "occupied");
        Objects.requireNonNull(projectileAtOrigin, "projectileAtOrigin");
        if (!finite(start) || !finite(aim) || !finite(occupied) || !finite(projectileAtOrigin)) {
            throw new IllegalArgumentException("fireball launch requires a finite non-zero aim");
        }
        if (aim.lengthSqr() <= 1.0E-12) {
            return Optional.empty();
        }
        Vec3 direction = aim.normalize();
        AABB expanded = occupied.inflate(
                projectileAtOrigin.getXsize() * 0.5 + LAUNCH_CLEARANCE,
                projectileAtOrigin.getYsize() * 0.5 + LAUNCH_CLEARANCE,
                projectileAtOrigin.getZsize() * 0.5 + LAUNCH_CLEARANCE);
        if (start.x < expanded.minX || start.x > expanded.maxX
                || start.y < expanded.minY || start.y > expanded.maxY
                || start.z < expanded.minZ || start.z > expanded.maxZ) {
            return Optional.empty();
        }
        double exit = Double.POSITIVE_INFINITY;
        exit = exitDistance(start.x, direction.x, expanded.minX, expanded.maxX, exit);
        exit = exitDistance(start.y, direction.y, expanded.minY, expanded.maxY, exit);
        exit = exitDistance(start.z, direction.z, expanded.minZ, expanded.maxZ, exit);
        if (!Double.isFinite(exit) || exit < 0.0) {
            return Optional.empty();
        }
        Vec3 center = start.add(direction.scale(exit));
        return Optional.of(new Launch(center.subtract(projectileAtOrigin.getCenter()), direction));
    }

    private static double exitDistance(
            double start, double direction, double minimum, double maximum, double current) {
        if (direction > 0.0) {
            return Math.min(current, (maximum - start) / direction);
        }
        if (direction < 0.0) {
            return Math.min(current, (minimum - start) / direction);
        }
        return current;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean finite(AABB bounds) {
        return Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY)
                && Double.isFinite(bounds.minZ) && Double.isFinite(bounds.maxX)
                && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ);
    }

    private static AABB occupiedBounds(HappyGhast ghast) {
        return ghast.getSelfAndPassengers()
                .map(Entity::getBoundingBox)
                .reduce(AABB::minmax)
                .orElseThrow();
    }

    record Launch(Vec3 origin, Vec3 direction) {
        Launch {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(direction, "direction");
        }
    }

    static <P, G> CryOutcome cry(
            P pilot,
            G ghast,
            GhastState state,
            long now,
            Config config,
            CryAccess<P, G> access) {
        Objects.requireNonNull(pilot, "pilot");
        Objects.requireNonNull(ghast, "ghast");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(access, "access");
        if (!access.isPilot(pilot, ghast)) {
            return new CryRejected(CryRejection.NOT_PILOT);
        }
        if (access.inWater(ghast)) {
            return new CryRejected(CryRejection.IN_WATER);
        }
        if (!config.cry().enabled()) {
            return new CryRejected(CryRejection.DISABLED);
        }
        if (now < state.cryReadyTick()) {
            return new CryRejected(CryRejection.ON_COOLDOWN);
        }
        GhastState committed = new GhastState(
                state.heat(),
                state.heatAnchorTick(),
                state.firingWindowEndTick(),
                state.fireReadyTick(),
                cooldownDeadline(now, config.cry().cooldownSeconds()),
                state.detonateAtTick(),
                state.detonatingRiderId());
        access.playCry(ghast, config.cry().volume());
        access.replaceState(ghast, committed);
        return new Cried(committed);
    }


    private static long cooldownDeadline(long now, double seconds) {
        double tickCount = seconds * 20.0;
        if (!Double.isFinite(tickCount)) {
            return Long.MAX_VALUE;
        }
        BigDecimal deadline = BigDecimal.valueOf(now).add(
                new BigDecimal(tickCount).setScale(0, RoundingMode.CEILING));
        return deadline.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE
                : deadline.longValueExact();
    }

    private static long saturatedAdd(long value, long increment) {
        return increment > 0L && value > Long.MAX_VALUE - increment
                ? Long.MAX_VALUE
                : value + increment;
    }

    static final class FuseQueue<P, G> {
        private final PriorityQueue<FuseTask> tasks = new PriorityQueue<>(
                java.util.Comparator.comparingLong(FuseTask::deadline)
                        .thenComparingLong(FuseTask::sequence));
        private final Map<UUID, FuseTask> scheduled = new HashMap<>();
        private final Map<UUID, Set<FuseTask>> riderDeferred = new HashMap<>();
        private long nextSequence;

        void schedule(G ghast, GhastState state, DetonationAccess<P, G> access) {
            schedule(ghast, Optional.of(Objects.requireNonNull(state, "state")), access);
        }

        private void schedule(
                G ghast, Optional<GhastState> attached, DetonationAccess<P, G> access) {
            Objects.requireNonNull(access, "access");
            UUID ghastId = access.ghastId(ghast);
            FuseTask existing = scheduled.get(ghastId);
            if (attached.isEmpty() || attached.orElseThrow().detonateAtTick().isEmpty()
                    || attached.orElseThrow().detonatingRiderId().isEmpty()) {
                removeOwner(existing);
                return;
            }
            GhastState state = attached.orElseThrow();
            long deadline = state.detonateAtTick().getAsLong();
            UUID riderId = state.detonatingRiderId().orElseThrow();
            if (existing != null && existing.deadline() == deadline
                    && existing.riderId().equals(riderId)) {
                removeDeferred(existing);
                if (!tasks.contains(existing)) {
                    tasks.add(existing);
                }
                return;
            }
            removeOwner(existing);
            FuseTask task = new FuseTask(ghastId, deadline, riderId, nextSequence++);
            scheduled.put(ghastId, task);
            tasks.add(task);
        }

        Optional<DetonationOutcome> submit(
                G ghast,
                GhastState state,
                long now,
                Config config,
                DetonationAccess<P, G> access) {
            schedule(ghast, state, access);
            FuseTask task = scheduled.get(access.ghastId(ghast));
            if (task == null || task.deadline() > now) {
                return Optional.empty();
            }
            tasks.remove(task);
            return Optional.of(executeOwnedTask(task, now, config, access));
        }

        void onGhastLoad(
                G ghast, Optional<GhastState> state, DetonationAccess<P, G> access) {
            schedule(ghast, state, access);
        }

        int runDue(
                long now,
                Config config,
                DetonationAccess<P, G> access) {
            int detonations = 0;
            Throwable firstFailure = null;
            ArrayList<FuseTask> batch = new ArrayList<>();
            while (!tasks.isEmpty() && tasks.peek().deadline() <= now) {
                batch.add(tasks.remove());
            }
            for (FuseTask task : batch) {
                if (scheduled.get(task.ghastId()) != task) {
                    continue;
                }
                tasks.remove(task);
                try {
                    DetonationOutcome outcome = executeOwnedTask(task, now, config, access);
                    if (outcome instanceof DetonationConsumed
                            || outcome instanceof DetonationConsumedWithFailures) {
                        detonations++;
                    }
                } catch (RuntimeException | Error failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    } else if (firstFailure != failure) {
                        firstFailure.addSuppressed(failure);
                    }
                }
            }
            if (firstFailure instanceof Error error) {
                throw error;
            }
            if (firstFailure instanceof RuntimeException runtime) {
                throw runtime;
            }
            return detonations;
        }

        private DetonationOutcome executeOwnedTask(
                FuseTask task,
                long now,
                Config config,
                DetonationAccess<P, G> access) {
            ExecutionEvidence evidence = new ExecutionEvidence();
            try {
                G ghast = access.resolveGhast(task.ghastId());
                if (ghast == null) {
                    return new DetonationDeferred(DetonationDeferral.GHAST_UNLOADED);
                }
                return executeOwnedTask(task, ghast, now, config, access, evidence);
            } catch (RuntimeException | Error failure) {
                if (evidence.stale) {
                    removeOwner(task);
                } else {
                    makeDormant(task);
                }
                throw failure;
            }
        }

        private DetonationOutcome executeOwnedTask(
                FuseTask task,
                G ghast,
                long now,
                Config config,
                DetonationAccess<P, G> access,
                ExecutionEvidence evidence) {
            DetonationOutcome outcome = executeDetonation(
                    ghast, task.deadline(), task.riderId(), now, config, access, evidence,
                    access.attachedState(ghast));
            if (outcome instanceof DetonationDeferred deferred
                    && deferred.reason() == DetonationDeferral.RIDER_UNAVAILABLE) {
                riderDeferred.computeIfAbsent(task.riderId(), ignored -> new LinkedHashSet<>())
                        .add(task);
            } else if (!(outcome instanceof DetonationDeferred)) {
                removeOwner(task);
            }
            return outcome;
        }

        int onRiderAvailable(UUID riderId) {
            Set<FuseTask> deferred = riderDeferred.remove(riderId);
            if (deferred == null) {
                return 0;
            }
            int reactivated = 0;
            for (FuseTask task : deferred) {
                if (scheduled.get(task.ghastId()) == task && !tasks.contains(task)) {
                    tasks.add(task);
                    reactivated++;
                }
            }
            return reactivated;
        }

        void clear() {
            tasks.clear();
            scheduled.clear();
            riderDeferred.clear();
        }

        private void removeOwner(FuseTask task) {
            if (task == null) {
                return;
            }
            tasks.remove(task);
            removeDeferred(task);
            scheduled.remove(task.ghastId(), task);
        }

        private void makeDormant(FuseTask task) {
            tasks.remove(task);
            removeDeferred(task);
        }

        private void removeDeferred(FuseTask task) {
            Set<FuseTask> deferred = riderDeferred.get(task.riderId());
            if (deferred == null) {
                return;
            }
            deferred.remove(task);
            if (deferred.isEmpty()) {
                riderDeferred.remove(task.riderId());
            }
        }
    }

    private record FuseTask(UUID ghastId, long deadline, UUID riderId, long sequence) {
        private FuseTask {
            Objects.requireNonNull(ghastId, "ghastId");
            Objects.requireNonNull(riderId, "riderId");
        }
    }

    sealed interface DetonationOutcome permits DetonationIgnored, DetonationDeferred,
            DetonationConsumed, DetonationConsumedWithFailures {
    }

    record DetonationIgnored() implements DetonationOutcome {
    }

    record DetonationDeferred(DetonationDeferral reason) implements DetonationOutcome {
        DetonationDeferred {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record DetonationConsumed() implements DetonationOutcome {
    }

    record DetonationConsumedWithFailures(int rejectedAttempts) implements DetonationOutcome {
        DetonationConsumedWithFailures {
            if (rejectedAttempts < 1) {
                throw new IllegalArgumentException("rejectedAttempts must be positive");
            }
        }
    }

    enum DetonationDeferral {
        GHAST_UNLOADED,
        RIDER_UNAVAILABLE
    }

    enum FireAttempt {
        ACCEPTED,
        SKIPPED,
        REJECTED
    }

    sealed interface FireOutcome permits Fired, Detonated, DetonationPending, Rejected {
    }

    record Fired(GhastState state) implements FireOutcome {
        Fired {
            Objects.requireNonNull(state, "state");
        }
    }

    record DetonationPending(GhastState state) implements FireOutcome {
        DetonationPending {
            Objects.requireNonNull(state, "state");
        }
    }

    record Detonated() implements FireOutcome {
    }

    record Rejected(FireRejection reason) implements FireOutcome {
        Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FireRejection {
        NOT_PILOT,
        DETONATION_PENDING,
        IN_WATER,
        ON_COOLDOWN,
        EFFECT_FAILED
    }

    sealed interface CryOutcome permits Cried, CryRejected {
    }

    record Cried(GhastState state) implements CryOutcome {
        Cried {
            Objects.requireNonNull(state, "state");
        }
    }

    record CryRejected(CryRejection reason) implements CryOutcome {
        CryRejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum CryRejection {
        NOT_PILOT,
        IN_WATER,
        DISABLED,
        ON_COOLDOWN
    }

    interface CryAccess<P, G> {
        boolean isPilot(P pilot, G ghast);

        boolean inWater(G ghast);

        void playCry(G ghast, double volume);

        void replaceState(G ghast, GhastState state);
    }

    interface FireAccess<P, G> {
        boolean isPilot(P pilot, G ghast);

        boolean inWater(G ghast);

        boolean addProjectile(P pilot, G ghast, int explosionPower);

        void replaceState(G ghast, GhastState state);
    }

    interface DetonationAccess<P, G> {
        default DetonationAccess<P, G> executionAccess(G ghast) {
            return this;
        }

        UUID riderId(P rider);

        UUID ghastId(G ghast);

        G resolveGhast(UUID ghastId);

        P resolveRider(G ghast, UUID riderId);

        Optional<GhastState> attachedState(G ghast);

        void replaceState(G ghast, GhastState state);

        void explode(P pilot, G ghast, double power, boolean breaksBlocks);

        boolean spawnFireball(G ghast, Vec3 direction, double speed, int power);

        FireAttempt placeFire(G ghast, Vec3 offset);

        void remove(G ghast);
    }


    enum ServerPlayerCryAccess implements CryAccess<ServerPlayer, HappyGhast> {
        INSTANCE;

        @Override
        public boolean isPilot(ServerPlayer pilot, HappyGhast ghast) {
            return Controls.ServerPlayerControlAccess.INSTANCE
                    .isControllingFirstPassenger(pilot, ghast);
        }

        @Override
        public boolean inWater(HappyGhast ghast) {
            return ghast.isInWater();
        }

        @Override
        public void playCry(HappyGhast ghast, double volume) {
            Level level = ghast.level();
            level.playSound(
                    null,
                    ghast.blockPosition(),
                    SoundEvents.GHAST_SCREAM,
                    SoundSource.HOSTILE,
                    (float) volume,
                    0.8F);
        }

        @Override
        public void replaceState(HappyGhast ghast, GhastState state) {
            GhastState.replace((AttachmentTarget) (Object) ghast, state);
        }
    }

    enum ServerPlayerFireAccess implements FireAccess<ServerPlayer, HappyGhast> {
        INSTANCE;

        @Override
        public boolean isPilot(ServerPlayer pilot, HappyGhast ghast) {
            return Controls.ServerPlayerControlAccess.INSTANCE
                    .isControllingFirstPassenger(pilot, ghast);
        }

        @Override
        public boolean inWater(HappyGhast ghast) {
            return ghast.isInWater();
        }

        @Override
        public boolean addProjectile(ServerPlayer pilot, HappyGhast ghast, int explosionPower) {
            ServerLevel level = (ServerLevel) ghast.level();
            Optional<Launch> candidate = launch(
                    pilot.getEyePosition(), pilot.getViewVector(1.0F), occupiedBounds(ghast),
                    EntityTypes.FIREBALL.getSpawnAABB(0.0, 0.0, 0.0));
            if (candidate.isEmpty()) {
                return false;
            }
            Launch launch = candidate.get();
            LargeFireball projectile = new LargeFireball(
                    level, ghast, launch.direction(), explosionPower);
            projectile.setPos(launch.origin());
            if (!level.addFreshEntity(projectile)) {
                return false;
            }
            if (!ghast.isSilent()) {
                level.levelEvent(null, 1016, ghast.blockPosition(), 0);
            }
            return true;
        }

        @Override
        public void replaceState(HappyGhast ghast, GhastState state) {
            GhastState.replace((AttachmentTarget) (Object) ghast, state);
        }
    }

    private static final class ServerPlayerDetonationAccess
            implements DetonationAccess<ServerPlayer, HappyGhast> {
        private static final ServerPlayerDetonationAccess INSTANCE =
                new ServerPlayerDetonationAccess(null);
        private final MinecraftServer server;

        private ServerPlayerDetonationAccess(MinecraftServer server) {
            this.server = server;
        }

        @Override
        public DetonationAccess<ServerPlayer, HappyGhast> executionAccess(HappyGhast ghast) {
            return new ServerPlayerDetonationAccess(ghast.level().getServer());
        }

        @Override
        public UUID riderId(ServerPlayer rider) {
            return rider.getUUID();
        }

        @Override
        public UUID ghastId(HappyGhast ghast) {
            return ghast.getUUID();
        }

        @Override
        public HappyGhast resolveGhast(UUID ghastId) {
            if (server == null) {
                throw new IllegalStateException("UUID resolution requires server context");
            }
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(ghastId);
                if (entity instanceof HappyGhast ghast) {
                    return ghast;
                }
            }
            return null;
        }

        @Override
        public ServerPlayer resolveRider(HappyGhast ghast, UUID riderId) {
            ServerLevel level = (ServerLevel) ghast.level();
            return level.getServer().getPlayerList().getPlayer(riderId);
        }

        @Override
        public Optional<GhastState> attachedState(HappyGhast ghast) {
            AttachmentTarget target = (AttachmentTarget) (Object) ghast;
            return Optional.ofNullable(target.getAttached(GhastState.register()));
        }

        @Override
        public void replaceState(HappyGhast ghast, GhastState state) {
            GhastState.replace((AttachmentTarget) (Object) ghast, state);
        }
        @Override
        public void explode(
                ServerPlayer pilot, HappyGhast ghast, double power, boolean breaksBlocks) {
            Level level = ghast.level();
            level.explode(pilot, ghast.getX(), ghast.getY(), ghast.getZ(), (float) power,
                    breaksBlocks ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE);
        }

        @Override
        public boolean spawnFireball(HappyGhast ghast, Vec3 direction, double speed, int power) {
            ServerLevel level = (ServerLevel) ghast.level();
            Optional<Launch> candidate = launch(
                    ghast.getBoundingBox().getCenter(), direction, occupiedBounds(ghast),
                    EntityTypes.FIREBALL.getSpawnAABB(0.0, 0.0, 0.0));
            if (candidate.isEmpty()) {
                return false;
            }
            Launch launch = candidate.get();
            LargeFireball fireball = new LargeFireball(level, ghast, launch.direction(), power);
            fireball.setPos(launch.origin());
            fireball.setDeltaMovement(launch.direction().scale(speed));
            return level.addFreshEntity(fireball);
        }

        @Override
        public FireAttempt placeFire(HappyGhast ghast, Vec3 offset) {
            ServerLevel serverLevel = (ServerLevel) ghast.level();
            if (!serverLevel.getGameRules().get(GameRules.MOB_GRIEFING)) {
                return FireAttempt.SKIPPED;
            }
            Level level = serverLevel;
            BlockPos position = BlockPos.containing(ghast.position().add(offset));
            if (!level.isEmptyBlock(position)
                    || !BaseFireBlock.canBePlacedAt(level, position, Direction.UP)) {
                return FireAttempt.SKIPPED;
            }
            return level.setBlockAndUpdate(position, BaseFireBlock.getState(level, position))
                    ? FireAttempt.ACCEPTED
                    : FireAttempt.REJECTED;
        }

        @Override
        public void remove(HappyGhast ghast) {
            ghast.discard();
        }
    }

}
