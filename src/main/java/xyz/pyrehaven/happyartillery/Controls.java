package xyz.pyrehaven.happyartillery;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sole owner of generated controls, bounded inventory policy, and pilot admission. */
public final class Controls {
    static final String ALLOCATION_REFUSAL = "Happy Artillery needs 2 free inventory slots.";
    private static final int HOTBAR_END = 8;
    private static final int MAIN_END = 35;
    private static final int OFFHAND = 40;
    private static final Consumable HOLD_USE = Consumable.builder()
            .consumeSeconds(Float.MAX_VALUE)
            .animation(ItemUseAnimation.NONE)
            .sound(Holder.direct(SoundEvents.EMPTY))
            .hasConsumeParticles(false)
            .build();

    private Controls() {
    }

    static ItemStack fireControl(UUID ownerId, UUID rideId) {
        return fireControl(Config.current().controls(), ownerId, rideId);
    }

    private static ItemStack fireControl(Config.Controls settings, UUID ownerId, UUID rideId) {
        return control(settings.fireItem(), "Fire Control",
                new Components.Marker(Components.Control.FIRE, ownerId, rideId));
    }

    static ItemStack cryControl(UUID ownerId, UUID rideId) {
        return cryControl(Config.current().controls(), ownerId, rideId);
    }

    private static ItemStack cryControl(Config.Controls settings, UUID ownerId, UUID rideId) {
        return control(settings.cryItem(), "Cry Control",
                new Components.Marker(Components.Control.CRY, ownerId, rideId));
    }

    static RiderState reconcile(ServerPlayer player, RiderState state, Optional<UUID> pilotRideId) {
        return reconcile(player, state, pilotRideId, ServerPlayerInventoryAccess.INSTANCE);
    }

    static <T> RiderState reconcile(
            T inventory, RiderState state, Optional<UUID> pilotRideId, InventoryAccess<T> access) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(pilotRideId, "pilotRideId");
        Objects.requireNonNull(access, "access");
        UUID ownerId = access.ownerId(inventory);
        Optional<UUID> previousRide = state.riddenGhastId();
        if (pilotRideId.equals(previousRide)) {
            return state;
        }
        previousRide.ifPresent(rideId -> removeMatching(inventory, ownerId, rideId, access));
        if (pilotRideId.isEmpty()) {
            return state.withRide(Optional.empty());
        }
        UUID rideId = pilotRideId.orElseThrow();
        int first = -1;
        int second = -1;
        for (int slot = 0; slot <= MAIN_END; slot++) {
            if (access.read(inventory, slot).isEmpty()) {
                if (first < 0) {
                    first = slot;
                } else {
                    second = slot;
                    break;
                }
            }
        }
        RiderState mounted = state.withRide(Optional.of(rideId));
        if (second < 0) {
            access.message(inventory, ALLOCATION_REFUSAL);
            return mounted;
        }
        Config.Controls settings = Config.current().controls();
        ItemStack fire = fireControl(settings, ownerId, rideId);
        ItemStack cry = cryControl(settings, ownerId, rideId);
        access.write(inventory, first, fire);
        access.write(inventory, second, cry);
        return mounted;
    }

    static <T> InventorySnapshot snapshot(
            T inventory, UUID ownerId, UUID rideId, InventoryAccess<T> access) {
        ControlLocation fire = ControlLocation.MISSING;
        ControlLocation cry = ControlLocation.MISSING;
        int staleOrForeign = 0;
        for (int slot = 0; slot <= MAIN_END; slot++) {
            Components.Marker marker = Components.marker(access.read(inventory, slot)).marker().orElse(null);
            if (marker == null) {
                continue;
            }
            if (!marker.ownerId().equals(ownerId) || !marker.rideId().equals(rideId)) {
                staleOrForeign++;
                continue;
            }
            ControlLocation location = slot <= HOTBAR_END
                    ? ControlLocation.HAND_ACCESSIBLE : ControlLocation.MAIN_INVENTORY_ONLY;
            if (marker.control() == Components.Control.FIRE) {
                fire = mergeLocation(fire, location);
            } else {
                cry = mergeLocation(cry, location);
            }
        }
        Components.Marker offhand = Components.marker(access.read(inventory, OFFHAND)).marker().orElse(null);
        if (offhand != null) {
            if (!offhand.ownerId().equals(ownerId) || !offhand.rideId().equals(rideId)) {
                staleOrForeign++;
            } else if (offhand.control() == Components.Control.FIRE) {
                fire = ControlLocation.HAND_ACCESSIBLE;
            } else {
                cry = ControlLocation.HAND_ACCESSIBLE;
            }
        }
        return new InventorySnapshot(fire, cry, staleOrForeign);
    }

    static InventorySnapshot snapshot(ServerPlayer player, UUID rideId) {
        return snapshot(player, player.getUUID(), rideId, ServerPlayerInventoryAccess.INSTANCE);
    }

    private static ControlLocation mergeLocation(ControlLocation current, ControlLocation found) {
        return current == ControlLocation.HAND_ACCESSIBLE ? current : found;
    }

    static <T> void removeMatching(
            T inventory, UUID ownerId, UUID rideId, InventoryAccess<T> access) {
        for (int slot = 0; slot <= MAIN_END; slot++) {
            if (Components.matches(access.read(inventory, slot), ownerId, rideId)) {
                access.write(inventory, slot, ItemStack.EMPTY);
            }
        }
        if (Components.matches(access.read(inventory, OFFHAND), ownerId, rideId)) {
            access.write(inventory, OFFHAND, ItemStack.EMPTY);
        }
    }

    public static void consumeDroppedControl(ItemStack droppedStack, ItemEntity returnedEntity) {
        Objects.requireNonNull(droppedStack, "droppedStack");
        if (returnedEntity != null && Components.marker(droppedStack).marker().isPresent()) {
            returnedEntity.discard();
        }
    }

    public static void consumeExternalControl(Slot destination) {
        Objects.requireNonNull(destination, "destination");
        UUID destinationOwnerId = destination.container instanceof Inventory inventory
                ? inventory.player.getUUID() : null;
        consumeExternalControl(destination.getItem(), destinationOwnerId);
    }

    static boolean consumeExternalControl(ItemStack stack, UUID destinationOwnerId) {
        Components.Marker marker = Components.marker(stack).marker().orElse(null);
        if (marker == null || destinationOwnerId != null && marker.ownerId().equals(destinationOwnerId)) {
            return false;
        }
        stack.setCount(0);
        return true;
    }

    static ObservedUse observeUse(LivingEntity entity) {
        return observeUse(entity, LivingEntityUseObservation.INSTANCE);
    }

    static <T> ObservedUse observeUse(T source, UseObservation<T> observation) {
        return new ObservedUse(observation.isUsingItem(source), observation.getUsedItemHand(source),
                observation.getUseItem(source));
    }

    static Admission handleUseItem(
            ServerPlayer player, InteractionHand hand, RiderState state, long gameTick) {
        return handleUseItem(player, hand, state, gameTick, Config.current().controls(),
                ServerPlayerControlAccess.INSTANCE);
    }

    static Admission handleUseItem(
            ServerPlayer player, InteractionHand hand, RiderState state, long gameTick,
            Config.Controls settings) {
        return handleUseItem(player, hand, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission handleUseItem(
            P player, InteractionHand hand, RiderState state, long gameTick, ControlAccess<P, G> access) {
        return handleUseItem(player, hand, state, gameTick, Config.current().controls(), access);
    }

    static <P, G> Admission handleUseItem(
            P player, InteractionHand hand, RiderState state, long gameTick,
            Config.Controls settings, ControlAccess<P, G> access) {
        return admit(player, CallbackSource.CALLBACK, access.itemInHand(player, hand), Optional.empty(),
                state, gameTick, settings, access);
    }

    static Admission handleUseEntity(
            ServerPlayer player, Entity target, InteractionHand hand, RiderState state, long gameTick) {
        return handleUseEntity(player, target, hand, state, gameTick, Config.current().controls(),
                ServerPlayerControlAccess.INSTANCE);
    }

    static Admission handleUseEntity(
            ServerPlayer player, Entity target, InteractionHand hand, RiderState state, long gameTick,
            Config.Controls settings) {
        return handleUseEntity(player, target, hand, state, gameTick, settings,
                ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission handleUseEntity(
            P player, Object target, InteractionHand hand, RiderState state, long gameTick,
            ControlAccess<P, G> access) {
        return handleUseEntity(player, target, hand, state, gameTick, Config.current().controls(), access);
    }

    static <P, G> Admission handleUseEntity(
            P player, Object target, InteractionHand hand, RiderState state, long gameTick,
            Config.Controls settings, ControlAccess<P, G> access) {
        return admit(player, CallbackSource.CALLBACK, access.itemInHand(player, hand), Optional.of(target),
                state, gameTick, settings, access);
    }

    static Admission sampleHeld(ServerPlayer player, RiderState state, long gameTick) {
        return sampleHeld(player, state, gameTick, Config.current().controls(),
                ServerPlayerControlAccess.INSTANCE);
    }

    static Admission sampleHeld(
            ServerPlayer player, RiderState state, long gameTick, Config.Controls settings) {
        return sampleHeld(player, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission sampleHeld(
            P player, RiderState state, long gameTick, ControlAccess<P, G> access) {
        return sampleHeld(player, state, gameTick, Config.current().controls(), access);
    }

    static <P, G> Admission sampleHeld(
            P player, RiderState state, long gameTick, Config.Controls settings,
            ControlAccess<P, G> access) {
        ObservedUse observed = access.observedUse(player);
        ItemStack input = observed.using() ? observed.stack() : ItemStack.EMPTY;
        return admit(player, CallbackSource.SERVER_TICK, input, Optional.empty(), state, gameTick,
                settings, access);
    }

    private static <P, G> Admission admit(
            P player, CallbackSource source, ItemStack input, Optional<Object> clickedTarget,
            RiderState state, long gameTick, Config.Controls settings, ControlAccess<P, G> access) {
        Optional<G> ridden = access.riddenHappyGhast(player);
        if (ridden.isEmpty() || clickedTarget.filter(target -> target != ridden.get()).isPresent()
                || !access.isControllingFirstPassenger(player, ridden.get())) {
            return new Ignored(state);
        }
        UUID rideId = access.ghastId(ridden.get());
        if (!state.riddenGhastId().equals(Optional.of(rideId))) {
            return new Ignored(state);
        }
        ControlIntent intent = classify(player, source, input, rideId, settings, access);
        if (intent == ControlIntent.NONE || state.lastHandledTick() == gameTick) {
            return new Ignored(state);
        }
        return new Accepted(intent, state.withLastHandledTick(gameTick));
    }

    private static <P, G> ControlIntent classify(
            P player, CallbackSource source, ItemStack input, UUID rideId,
            Config.Controls settings, ControlAccess<P, G> access) {
        UUID ownerId = access.playerId(player);
        Components.MarkerRead markerRead = Components.marker(input);
        Optional<Components.Marker> marker = markerRead.marker();
        boolean markedFire = marker.filter(value -> value.control() == Components.Control.FIRE
                && value.ownerId().equals(ownerId) && value.rideId().equals(rideId)).isPresent();
        boolean markedCry = marker.filter(value -> value.control() == Components.Control.CRY
                && value.ownerId().equals(ownerId) && value.rideId().equals(rideId)).isPresent();
        boolean plainFire = settings.allowPlainItems() && markerRead.isAbsent()
                && isConfiguredItem(input, settings.fireItem());
        boolean plainCry = settings.allowPlainItems() && markerRead.isAbsent()
                && isConfiguredItem(input, settings.cryItem());
        if (plainFire && plainCry) {
            plainFire = false;
            plainCry = false;
        }
        if (source == CallbackSource.SERVER_TICK) {
            return settings.holdToFire() && (markedFire || plainFire)
                    ? ControlIntent.FIRE : ControlIntent.NONE;
        }
        if (markedCry || plainCry) {
            return ControlIntent.CRY;
        }
        return !settings.holdToFire() && (markedFire || plainFire)
                ? ControlIntent.FIRE : ControlIntent.NONE;
    }

    private static boolean isConfiguredItem(ItemStack stack, String itemId) {
        return stack.is(BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId))
                .orElseThrow(() -> new IllegalStateException(
                        "Validated control item is no longer registered: " + itemId)));
    }

    private static ItemStack control(String itemId, String name, Components.Marker marker) {
        ItemStack control = new ItemStack(BuiltInRegistries.ITEM
                .getOptional(Identifier.parse(itemId))
                .orElseThrow(() -> new IllegalStateException(
                        "Validated control item is no longer registered: " + itemId)));
        Components.mark(control, marker);
        control.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        control.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        control.set(DataComponents.CONSUMABLE, HOLD_USE);
        return control;
    }

    enum ControlLocation { HAND_ACCESSIBLE, MAIN_INVENTORY_ONLY, MISSING }

    record InventorySnapshot(ControlLocation fire, ControlLocation cry, int staleOrForeignCount) {
        InventorySnapshot {
            Objects.requireNonNull(fire, "fire");
            Objects.requireNonNull(cry, "cry");
            if (staleOrForeignCount < 0) throw new IllegalArgumentException("negative marker count");
        }
    }

    enum CallbackSource { CALLBACK, SERVER_TICK }
    enum ControlIntent { FIRE, CRY, NONE }

    sealed interface Admission permits Accepted, Ignored {
        ControlIntent intent();
        RiderState state();
    }

    record Accepted(ControlIntent intent, RiderState state) implements Admission {
        Accepted {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(state, "state");
            if (intent == ControlIntent.NONE) throw new IllegalArgumentException("intent required");
        }
    }

    record Ignored(RiderState state) implements Admission {
        Ignored { Objects.requireNonNull(state, "state"); }
        @Override public ControlIntent intent() { return ControlIntent.NONE; }
    }

    record ObservedUse(boolean using, InteractionHand hand, ItemStack stack) {
        ObservedUse {
            Objects.requireNonNull(hand, "hand");
            stack = Objects.requireNonNull(stack, "stack").copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
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
        UUID playerId(P player);
        ItemStack itemInHand(P player, InteractionHand hand);
        ObservedUse observedUse(P player);
    }

    enum ServerPlayerControlAccess implements ControlAccess<ServerPlayer, HappyGhast> {
        INSTANCE;
        @Override public Optional<HappyGhast> riddenHappyGhast(ServerPlayer player) {
            return player.getVehicle() instanceof HappyGhast ghast ? Optional.of(ghast) : Optional.empty();
        }
        @Override public boolean isControllingFirstPassenger(ServerPlayer player, HappyGhast ghast) {
            return ghast.getFirstPassenger() == player && ghast.getControllingPassenger() == player;
        }
        @Override public UUID ghastId(HappyGhast ghast) { return ghast.getUUID(); }
        @Override public UUID playerId(ServerPlayer player) { return player.getUUID(); }
        @Override public ItemStack itemInHand(ServerPlayer player, InteractionHand hand) {
            return player.getItemInHand(hand).copy();
        }
        @Override public ObservedUse observedUse(ServerPlayer player) { return observeUse(player); }
    }

    interface InventoryAccess<T> {
        ItemStack read(T inventory, int slot);
        void write(T inventory, int slot, ItemStack stack);
        UUID ownerId(T inventory);
        void message(T inventory, String text);
    }

    enum ServerPlayerInventoryAccess implements InventoryAccess<ServerPlayer> {
        INSTANCE;
        @Override public ItemStack read(ServerPlayer player, int slot) {
            return player.getInventory().getItem(slot).copy();
        }
        @Override public void write(ServerPlayer player, int slot, ItemStack stack) {
            player.getInventory().setItem(slot, stack.copy());
        }
        @Override public UUID ownerId(ServerPlayer player) { return player.getUUID(); }
        @Override public void message(ServerPlayer player, String text) {
            player.sendSystemMessage(Component.literal(text));
        }
    }

    enum LivingEntityUseObservation implements UseObservation<LivingEntity> {
        INSTANCE;
        @Override public boolean isUsingItem(LivingEntity entity) { return entity.isUsingItem(); }
        @Override public InteractionHand getUsedItemHand(LivingEntity entity) {
            return entity.getUsedItemHand();
        }
        @Override public ItemStack getUseItem(LivingEntity entity) { return entity.getUseItem(); }
    }
}
