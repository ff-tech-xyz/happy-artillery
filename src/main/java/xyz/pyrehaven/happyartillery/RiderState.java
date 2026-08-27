package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable persistent rider state. Live HUD packet handles are deliberately excluded. */
public record RiderState(
        Optional<StashedStack> fireStash,
        Optional<StashedStack> cryStash,
        Optional<UUID> riddenGhastId,
        long lastHandledTick,
        Optional<HudCache> hudCache) {
    private static AttachmentType<RiderState> type;
    private static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

    public static final Codec<RiderState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            StashedStack.CODEC.optionalFieldOf("fire_stash").forGetter(RiderState::fireStash),
            StashedStack.CODEC.optionalFieldOf("cry_stash").forGetter(RiderState::cryStash),
            UUID_CODEC.optionalFieldOf("ridden_ghast_id").forGetter(RiderState::riddenGhastId),
            Codec.LONG.fieldOf("last_handled_tick").forGetter(RiderState::lastHandledTick),
            HudCache.CODEC.optionalFieldOf("hud_cache").forGetter(RiderState::hudCache)
    ).apply(instance, RiderState::new));

    public RiderState {
        fireStash = copyOf(fireStash);
        cryStash = copyOf(cryStash);
        riddenGhastId = Objects.requireNonNull(riddenGhastId, "riddenGhastId");
        hudCache = Objects.requireNonNull(hudCache, "hudCache");
    }

    public static RiderState fresh() {
        return new RiderState(
                Optional.empty(), Optional.empty(), Optional.empty(), Long.MIN_VALUE, Optional.empty());
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

    @Override
    public Optional<StashedStack> fireStash() {
        return copyOf(fireStash);
    }

    @Override
    public Optional<StashedStack> cryStash() {
        return copyOf(cryStash);
    }

    private static Optional<StashedStack> copyOf(Optional<StashedStack> stash) {
        return Objects.requireNonNull(stash, "stash").map(StashedStack::copy);
    }

    public record StashedStack(int slotIndex, ItemStack stack) {
        public static final Codec<StashedStack> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("slot_index").forGetter(StashedStack::slotIndex),
                ItemStack.CODEC.fieldOf("stack").forGetter(StashedStack::stack)
        ).apply(instance, StashedStack::new));

        public StashedStack {
            stack = Objects.requireNonNull(stack, "stack").copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }

        private StashedStack copy() {
            return new StashedStack(slotIndex, stack);
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof StashedStack that
                    && slotIndex == that.slotIndex
                    && ItemStack.matches(stack, that.stack);
        }

        @Override
        public int hashCode() {
            return 31 * slotIndex + 31 * ItemStack.hashItemAndComponents(stack) + stack.getCount();
        }
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
