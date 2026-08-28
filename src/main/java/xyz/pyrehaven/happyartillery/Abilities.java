package xyz.pyrehaven.happyartillery;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;


/** Sole fire, cry, and detonation owner. */
public final class Abilities {
    private Abilities() {
    }

    static FireOutcome fire(
            ServerPlayer pilot,
            HappyGhast ghast,
            GhastState state,
            long now,
            ResourceKey<Level> dimension,
            double baseTemperature) {
        Config config = Config.current();
        BiomeClass biomeClass = BiomeClass.classify(dimension, baseTemperature, config);
        return fire(pilot, ghast, state, now, config, biomeClass, ServerPlayerFireAccess.INSTANCE);
    }

    static <P, G> FireOutcome fire(
            P pilot,
            G ghast,
            GhastState state,
            long now,
            Config config,
            BiomeClass biomeClass,
            FireAccess<P, G> access) {
        Objects.requireNonNull(pilot, "pilot");
        Objects.requireNonNull(ghast, "ghast");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(biomeClass, "biomeClass");
        Objects.requireNonNull(access, "access");
        if (!access.isPilot(pilot, ghast)) {
            return new Rejected(FireRejection.NOT_PILOT);
        }
        boolean inWater = access.inWater(ghast);
        if (inWater && config.water().blocksFiring()) {
            return new Rejected(FireRejection.IN_WATER);
        }
        if (now < state.fireReadyTick()) {
            return new Rejected(FireRejection.ON_COOLDOWN);
        }
        Heat.ShotResult shot = Heat.addShot(
                state, now, biomeClass.profile(config), config.heat(), inWater, config.water());
        GhastState heated = shot.state();
        GhastState committed = new GhastState(
                heated.heat(),
                heated.heatAnchorTick(),
                heated.firingWindowEndTick(),
                cooldownDeadline(now, config.fire().shotCooldownSeconds()),
                heated.cryReadyTick(),
                heated.detonateAtTick());
        if (shot.detonates()) {
            return new OverheatCrossing(state, committed);
        }
        if (!access.addProjectile(pilot, ghast, config.fire().explosionPower())) {
            return new Rejected(FireRejection.EFFECT_FAILED);
        }
        access.replaceState(ghast, committed);
        return new Fired(committed);
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

    sealed interface FireOutcome permits Fired, OverheatCrossing, Rejected {
    }

    record Fired(GhastState state) implements FireOutcome {
        Fired {
            Objects.requireNonNull(state, "state");
        }
    }

    record OverheatCrossing(GhastState originalState, GhastState proposedState)
            implements FireOutcome {
        OverheatCrossing {
            Objects.requireNonNull(originalState, "originalState");
            Objects.requireNonNull(proposedState, "proposedState");
        }
    }

    record Rejected(FireRejection reason) implements FireOutcome {
        Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum FireRejection {
        NOT_PILOT,
        IN_WATER,
        ON_COOLDOWN,
        EFFECT_FAILED
    }

    interface FireAccess<P, G> {
        boolean isPilot(P pilot, G ghast);

        boolean inWater(G ghast);

        boolean addProjectile(P pilot, G ghast, int explosionPower);

        void replaceState(G ghast, GhastState state);
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
            Vec3 direction = pilot.getViewVector(1.0F).normalize();
            LargeFireball projectile = new LargeFireball(level, ghast, direction, explosionPower);
            projectile.setPos(
                    ghast.getX() + direction.x * 4.0,
                    ghast.getY(0.5) + 0.5,
                    ghast.getZ() + direction.z * 4.0);
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
}
