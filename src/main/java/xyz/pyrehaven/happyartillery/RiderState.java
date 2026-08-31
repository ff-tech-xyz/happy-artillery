package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable persistent rider identity, input deduplication, and HUD cache. */
public record RiderState(
        Optional<UUID> riddenGhastId,
        long lastHandledTick,
        Optional<HudCache> hudCache) {
    private static AttachmentType<RiderState> type;
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<RiderState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUID_CODEC.optionalFieldOf("ridden_ghast_id").forGetter(RiderState::riddenGhastId),
            Codec.LONG.fieldOf("last_handled_tick").forGetter(RiderState::lastHandledTick),
            HudCache.CODEC.optionalFieldOf("hud_cache").forGetter(RiderState::hudCache)
    ).apply(instance, RiderState::new));

    public RiderState {
        riddenGhastId = Objects.requireNonNull(riddenGhastId, "riddenGhastId");
        hudCache = Objects.requireNonNull(hudCache, "hudCache");
    }

    public static RiderState fresh() {
        return new RiderState(Optional.empty(), Long.MIN_VALUE, Optional.empty());
    }

    static synchronized AttachmentType<RiderState> register() {
        if (type == null) {
            type = AttachmentRegistry.createPersistent(
                    Identifier.fromNamespaceAndPath("happy-artillery", "rider_state"), CODEC);
        }
        return type;
    }

    static RiderState replace(AttachmentTarget target, RiderState newState) {
        return target.setAttached(register(), newState);
    }

    RiderState withRide(Optional<UUID> ride) {
        return new RiderState(ride, lastHandledTick, hudCache);
    }

    RiderState withLastHandledTick(long tick) {
        return new RiderState(riddenGhastId, tick, hudCache);
    }

    RiderState withHudCache(HudCache cache) {
        return new RiderState(riddenGhastId, lastHandledTick, Optional.of(cache));
    }

    public record HudCache(
            double bossProgress,
            String bossColor,
            String actionBarText,
            long lastActionBarTick) {
        public static final Codec<HudCache> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.DOUBLE.fieldOf("boss_progress").forGetter(HudCache::bossProgress),
                Codec.STRING.fieldOf("boss_color").forGetter(HudCache::bossColor),
                Codec.STRING.fieldOf("action_bar_text").forGetter(HudCache::actionBarText),
                Codec.LONG.fieldOf("last_action_bar_tick").forGetter(HudCache::lastActionBarTick)
        ).apply(instance, HudCache::new));

        public HudCache {
            bossColor = Objects.requireNonNull(bossColor, "bossColor");
            actionBarText = Objects.requireNonNull(actionBarText, "actionBarText");
        }
    }
}
