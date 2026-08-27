package xyz.pyrehaven.happyartillery;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sole pilot-input, control-item, and indexed inventory owner. */
public final class Controls {
    private static final Consumable HOLD_USE = Consumable.builder()
            .consumeSeconds(Float.MAX_VALUE)
            .animation(ItemUseAnimation.NONE)
            .sound(Holder.direct(SoundEvents.EMPTY))
            .hasConsumeParticles(false)
            .build();

    private Controls() {
    }

    static ItemStack fireControl() {
        Config.Controls settings = Config.current().controls();
        return control(settings.fireItem(), "Fire Control", Components.FIRE_CONTROL);
    }

    static ItemStack cryControl() {
        Config.Controls settings = Config.current().controls();
        return control(settings.cryItem(), "Cry Control", Components.CRY_CONTROL);
    }

    static RiderState mount(ServerPlayer player, RiderState state, UUID riddenGhastId) {
        return mount(player, state, riddenGhastId, ServerPlayerInventoryAccess.INSTANCE);
    }

    static <T> RiderState mount(
            T inventory,
            RiderState state,
            UUID riddenGhastId,
            InventoryAccess<T> access) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(riddenGhastId, "riddenGhastId");
        Objects.requireNonNull(access, "access");
        if (state.fireStash().isPresent() || state.cryStash().isPresent()) {
            throw new IllegalStateException("Cannot mount while an inventory stash is active");
        }

        Config.Controls settings = Config.current().controls();
        int fireSlot = settings.fireSlot();
        int crySlot = settings.crySlot();
        if (fireSlot == crySlot) {
            throw new IllegalStateException("Configured control slots must be distinct");
        }
        int size = access.size(inventory);
        if (fireSlot < 0 || fireSlot >= size || crySlot < 0 || crySlot >= size) {
            throw new IndexOutOfBoundsException("Configured control slot is outside inventory");
        }

        ItemStack fireOriginal = access.read(inventory, fireSlot);
        ItemStack cryOriginal = access.read(inventory, crySlot);
        access.write(inventory, fireSlot, fireControl());
        access.write(inventory, crySlot, cryControl());
        return new RiderState(
                Optional.of(new RiderState.StashedStack(fireSlot, fireOriginal)),
                Optional.of(new RiderState.StashedStack(crySlot, cryOriginal)),
                Optional.of(riddenGhastId),
                state.lastHandledTick(),
                state.hudCache());
    }

    static RiderState restore(ServerPlayer player, RiderState state) {
        return restore(player, state, ServerPlayerInventoryAccess.INSTANCE);
    }

    static <T> RiderState restore(T inventory, RiderState state, InventoryAccess<T> access) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(access, "access");
        RiderState.StashedStack fire = state.fireStash().orElseThrow(
                () -> new IllegalStateException("Cannot restore without a complete inventory stash"));
        RiderState.StashedStack cry = state.cryStash().orElseThrow(
                () -> new IllegalStateException("Cannot restore without a complete inventory stash"));
        if (state.riddenGhastId().isEmpty()) {
            throw new IllegalStateException("Cannot restore a stash without a ridden ghast id");
        }
        if (fire.slotIndex() == cry.slotIndex()) {
            throw new IllegalStateException("Persisted control slots must be distinct");
        }
        int size = access.size(inventory);
        if (fire.slotIndex() < 0 || fire.slotIndex() >= size
                || cry.slotIndex() < 0 || cry.slotIndex() >= size) {
            throw new IndexOutOfBoundsException("Persisted control slot is outside inventory");
        }

        access.write(inventory, fire.slotIndex(), fire.stack());
        access.write(inventory, cry.slotIndex(), cry.stack());
        for (int slot = 0; slot < size; slot++) {
            if (slot == fire.slotIndex() || slot == cry.slotIndex()) {
                continue;
            }
            ItemStack stack = access.read(inventory, slot);
            if (stack.has(Components.FIRE_CONTROL) || stack.has(Components.CRY_CONTROL)) {
                access.write(inventory, slot, ItemStack.EMPTY);
            }
        }
        return new RiderState(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                state.lastHandledTick(),
                state.hudCache());
    }

    static RiderState reconcile(
            ServerPlayer player,
            RiderState state,
            Optional<UUID> pilotGhastId) {
        return reconcile(player, state, pilotGhastId, ServerPlayerInventoryAccess.INSTANCE);
    }

    static <T> RiderState reconcile(
            T inventory,
            RiderState state,
            Optional<UUID> pilotGhastId,
            InventoryAccess<T> access) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pilotGhastId, "pilotGhastId");
        boolean fireStashed = state.fireStash().isPresent();
        boolean cryStashed = state.cryStash().isPresent();
        if (fireStashed != cryStashed) {
            throw new IllegalStateException("Rider inventory stash must be complete or empty");
        }
        if (!fireStashed) {
            if (state.riddenGhastId().isPresent()) {
                throw new IllegalStateException("Ridden ghast id cannot exist without a stash");
            }
            return pilotGhastId.map(id -> mount(inventory, state, id, access)).orElse(state);
        }
        if (state.riddenGhastId().isEmpty()) {
            throw new IllegalStateException("Active stash requires a ridden ghast id");
        }
        return pilotGhastId.equals(state.riddenGhastId())
                ? state
                : restore(inventory, state, access);
    }

    static boolean isLockedSlot(RiderState state, int slot) {
        Objects.requireNonNull(state, "state");
        Optional<RiderState.StashedStack> fire = state.fireStash();
        Optional<RiderState.StashedStack> cry = state.cryStash();
        if (fire.isPresent() != cry.isPresent()) {
            throw new IllegalStateException("Rider inventory stash must be complete or empty");
        }
        return fire.map(stash -> stash.slotIndex() == slot).orElse(false)
                || cry.map(stash -> stash.slotIndex() == slot).orElse(false);
    }

    static Optional<ItemStack> fireControl(ServerPlayer player, RiderState state) {
        return fireControl(player, state, ServerPlayerInventoryAccess.INSTANCE);
    }

    static <T> Optional<ItemStack> fireControl(
            T inventory,
            RiderState state,
            InventoryAccess<T> access) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(access, "access");
        return state.fireStash().flatMap(stash -> {
            ItemStack stack = access.read(inventory, stash.slotIndex());
            return isFireControl(stack) ? Optional.of(stack.copy()) : Optional.empty();
        });
    }

    static Optional<ItemStack> cryControl(ServerPlayer player, RiderState state) {
        return cryControl(player, state, ServerPlayerInventoryAccess.INSTANCE);
    }

    static <T> Optional<ItemStack> cryControl(
            T inventory,
            RiderState state,
            InventoryAccess<T> access) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(access, "access");
        return state.cryStash().flatMap(stash -> {
            ItemStack stack = access.read(inventory, stash.slotIndex());
            return isCryControl(stack) ? Optional.of(stack.copy()) : Optional.empty();
        });
    }

    private static ItemStack control(
            String itemId,
            String name,
            DataComponentType<Unit> marker) {
        ItemStack control = new ItemStack(BuiltInRegistries.ITEM
                .getOptional(Identifier.parse(itemId))
                .orElseThrow(() -> new IllegalStateException(
                        "Validated control item is no longer registered: " + itemId)));
        control.set(marker, Unit.INSTANCE);
        control.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        control.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        control.set(DataComponents.CONSUMABLE, HOLD_USE);
        return control;
    }

    static ObservedUse observeUse(LivingEntity entity) {
        return observeUse(Objects.requireNonNull(entity, "entity"), LivingEntityUseObservation.INSTANCE);
    }

    static <T> ObservedUse observeUse(T source, UseObservation<T> observation) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(observation, "observation");
        return new ObservedUse(
                observation.isUsingItem(source),
                observation.getUsedItemHand(source),
                observation.getUseItem(source));
    }


    static Admission handleUseItem(
            ServerPlayer player,
            InteractionHand hand,
            RiderState state,
            long gameTick) {
        return handleUseItem(player, hand, state, gameTick, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission handleUseItem(
            P player,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            ControlAccess<P, G> access) {
        Objects.requireNonNull(hand, "hand");
        return admit(player, CallbackSource.CALLBACK, hand, access.itemInHand(player, hand),
                Optional.empty(), state, gameTick, access);
    }

    static Admission handleUseEntity(
            ServerPlayer player,
            Entity target,
            InteractionHand hand,
            RiderState state,
            long gameTick) {
        return handleUseEntity(
                player, target, hand, state, gameTick, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission handleUseEntity(
            P player,
            Object target,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            ControlAccess<P, G> access) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hand, "hand");
        return admit(player, CallbackSource.CALLBACK, hand, access.itemInHand(player, hand),
                Optional.of(target), state, gameTick, access);
    }

    static Admission sampleHeld(ServerPlayer player, RiderState state, long gameTick) {
        return sampleHeld(player, state, gameTick, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission sampleHeld(
            P player,
            RiderState state,
            long gameTick,
            ControlAccess<P, G> access) {
        ObservedUse observed = access.observedUse(player);
        ItemStack input = observed.using() ? observed.stack() : ItemStack.EMPTY;
        return admit(player, CallbackSource.SERVER_TICK, observed.hand(), input,
                Optional.empty(), state, gameTick, access);
    }

    private static <P, G> Admission admit(
            P player,
            CallbackSource source,
            InteractionHand hand,
            ItemStack input,
            Optional<Object> clickedTarget,
            RiderState state,
            long gameTick,
            ControlAccess<P, G> access) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(clickedTarget, "clickedTarget");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(access, "access");

        Optional<G> ridden = access.riddenHappyGhast(player);
        if (ridden.isEmpty() || clickedTarget.filter(target -> target != ridden.get()).isPresent()
                || !access.isControllingFirstPassenger(player, ridden.get())
                || !state.riddenGhastId().equals(Optional.of(access.ghastId(ridden.get())))) {
            return new Ignored(state);
        }
        ControlIntent intent = classify(player, source, input, state, access);
        if (intent == ControlIntent.NONE || state.lastHandledTick() == gameTick) {
            return new Ignored(state);
        }
        RiderState updated = new RiderState(
                state.fireStash(), state.cryStash(), state.riddenGhastId(), gameTick, state.hudCache());
        return new Accepted(intent, updated);
    }

    private static <P, G> ControlIntent classify(
            P player,
            CallbackSource source,
            ItemStack input,
            RiderState state,
            ControlAccess<P, G> access) {
        Config.Controls settings = Config.current().controls();
        boolean markedFire = isFireControl(input);
        boolean markedCry = isCryControl(input);
        boolean plainFire = settings.allowPlainItems() && isPlainConfiguredItem(input, settings.fireItem());
        boolean plainCry = settings.allowPlainItems() && isPlainConfiguredItem(input, settings.cryItem());
        if (plainFire && plainCry) {
            plainFire = false;
            plainCry = false;
        }
        if (source == CallbackSource.SERVER_TICK) {
            return settings.holdToFire() && activeFireControl(player, state, access)
                    && (markedFire || plainFire)
                    ? ControlIntent.FIRE
                    : ControlIntent.NONE;
        }
        if ((markedCry || plainCry) && activeCryControl(player, state, access)) {
            return ControlIntent.CRY;
        }
        return !settings.holdToFire() && (markedFire || plainFire)
                && activeFireControl(player, state, access)
                ? ControlIntent.FIRE
                : ControlIntent.NONE;
    }

    private static boolean isPlainConfiguredItem(ItemStack stack, String itemId) {
        return !stack.has(Components.FIRE_CONTROL)
                && !stack.has(Components.CRY_CONTROL)
                && stack.is(BuiltInRegistries.ITEM
                        .getOptional(Identifier.parse(itemId))
                        .orElseThrow(() -> new IllegalStateException(
                                "Validated control item is no longer registered: " + itemId)));
    }

    private static <P, G> boolean activeFireControl(
            P player, RiderState state, ControlAccess<P, G> access) {
        return state.fireStash()
                .map(stash -> isFireControl(access.itemAt(player, stash.slotIndex())))
                .orElse(false);
    }

    private static <P, G> boolean activeCryControl(
            P player, RiderState state, ControlAccess<P, G> access) {
        return state.cryStash()
                .map(stash -> isCryControl(access.itemAt(player, stash.slotIndex())))
                .orElse(false);
    }


    private static boolean isFireControl(ItemStack stack) {
        return stack.has(Components.FIRE_CONTROL) && !stack.has(Components.CRY_CONTROL);
    }

    private static boolean isCryControl(ItemStack stack) {
        return stack.has(Components.CRY_CONTROL) && !stack.has(Components.FIRE_CONTROL);
    }


    enum CallbackSource {
        CALLBACK,
        SERVER_TICK
    }

    enum ControlIntent {
        FIRE,
        CRY,
        NONE
    }

    sealed interface Admission permits Accepted, Ignored {
        ControlIntent intent();

        RiderState state();
    }

    record Accepted(ControlIntent intent, RiderState state) implements Admission {
        Accepted {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(state, "state");
            if (intent == ControlIntent.NONE) {
                throw new IllegalArgumentException("Accepted admission requires a control intent");
            }
        }
    }

    record Ignored(RiderState state) implements Admission {
        Ignored {
            Objects.requireNonNull(state, "state");
        }

        @Override
        public ControlIntent intent() {
            return ControlIntent.NONE;
        }
    }

    record ObservedUse(boolean using, InteractionHand hand, ItemStack stack) {
        ObservedUse {
            Objects.requireNonNull(hand, "hand");
            stack = Objects.requireNonNull(stack, "stack").copy();
        }

        @Override
        public ItemStack stack() {
            return stack.copy();
        }
    }


    interface UseObservation<T> {
        boolean isUsingItem(T source);

        InteractionHand getUsedItemHand(T source);

        ItemStack getUseItem(T source);
    }

    interface ControlAccess<P, G> {
        Optional<G> riddenHappyGhast(P player);

        boolean isControllingFirstPassenger(P player, G ghast);

        UUID ghastId(G ghast);

        ItemStack itemInHand(P player, InteractionHand hand);

        ItemStack itemAt(P player, int slot);

        ObservedUse observedUse(P player);
    }

    enum ServerPlayerControlAccess implements ControlAccess<ServerPlayer, HappyGhast> {
        INSTANCE;

        @Override
        public Optional<HappyGhast> riddenHappyGhast(ServerPlayer player) {
            Entity vehicle = player.getVehicle();
            return vehicle instanceof HappyGhast ghast ? Optional.of(ghast) : Optional.empty();
        }

        @Override
        public boolean isControllingFirstPassenger(ServerPlayer player, HappyGhast ghast) {
            return ghast.getFirstPassenger() == player && ghast.getControllingPassenger() == player;
        }

        @Override
        public UUID ghastId(HappyGhast ghast) {
            return ghast.getUUID();
        }

        @Override
        public ItemStack itemInHand(ServerPlayer player, InteractionHand hand) {
            return player.getItemInHand(hand).copy();
        }

        @Override
        public ItemStack itemAt(ServerPlayer player, int slot) {
            return player.getInventory().getItem(slot).copy();
        }

        @Override
        public ObservedUse observedUse(ServerPlayer player) {
            return observeUse(player);
        }
    }

    interface InventoryAccess<T> {
        int size(T inventory);

        ItemStack read(T inventory, int slot);

        void write(T inventory, int slot, ItemStack stack);
    }

    enum ServerPlayerInventoryAccess implements InventoryAccess<ServerPlayer> {
        INSTANCE;

        @Override
        public int size(ServerPlayer player) {
            return player.getInventory().getContainerSize();
        }

        @Override
        public ItemStack read(ServerPlayer player, int slot) {
            return player.getInventory().getItem(slot).copy();
        }

        @Override
        public void write(ServerPlayer player, int slot, ItemStack stack) {
            player.getInventory().setItem(slot, stack.copy());
        }
    }

    enum LivingEntityUseObservation implements UseObservation<LivingEntity> {
        INSTANCE;

        @Override
        public boolean isUsingItem(LivingEntity entity) {
            return entity.isUsingItem();
        }

        @Override
        public InteractionHand getUsedItemHand(LivingEntity entity) {
            return entity.getUsedItemHand();
        }

        @Override
        public ItemStack getUseItem(LivingEntity entity) {
            return entity.getUseItem();
        }
    }
}
