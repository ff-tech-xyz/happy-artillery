package xyz.pyrehaven.happyartillery;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    public static void beforeDeathDrops(ServerPlayer player, Runnable createDrops) {
        beforeDeathDrops(player, ServerPlayerDeathDropAccess.INSTANCE, createDrops);
    }

    static <T> void beforeDeathDrops(
            T player,
            DeathDropAccess<T> access,
            Runnable createDrops) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(createDrops, "createDrops");
        Optional<RiderState> attached = access.state(player);
        if (attached.isPresent()) {
            RiderState state = attached.get();
            boolean fireStashed = state.fireStash().isPresent();
            boolean cryStashed = state.cryStash().isPresent();
            if (fireStashed != cryStashed) {
                throw new IllegalStateException("Rider inventory stash must be complete or empty");
            }
            if (!fireStashed && state.riddenGhastId().isPresent()) {
                throw new IllegalStateException("Ridden ghast id cannot exist without a stash");
            }
            if (fireStashed) {
                access.replaceState(player, restore(player, state, access));
            }
        }
        createDrops.run();
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

    public static boolean shouldCancelContainerMutation(
            AbstractContainerMenu menu,
            int slotId,
            int button,
            ContainerInput input,
            ServerPlayer player,
            QuickCraftSnapshot quickCraft) {
        return shouldCancelContainerMutation(
                player, menu, slotId, button, input, quickCraft,
                ServerPlayerContainerDecisionAccess.INSTANCE);
    }

    public static boolean shouldCancelSelectedSlotDrop(ServerPlayer player) {
        return shouldCancelSelectedSlotDrop(player, ServerPlayerContainerDecisionAccess.INSTANCE);
    }

    static <P, G> boolean shouldCancelSelectedSlotDrop(
            P player, SelectedSlotDecisionAccess<P, G> access) {
        RiderState state = activePilotState(player, access).orElse(null);
        return state != null
                && Config.current().controls().lockControlSlots()
                && isLockedSlot(state, access.selectedSlot(player));
    }

    static <P, G, M, S, I> boolean shouldCancelContainerMutation(
            P player,
            M menu,
            int slotId,
            int button,
            ContainerInput input,
            QuickCraftSnapshot quickCraft,
            ContainerDecisionAccess<P, G, M, S, I> access) {
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(quickCraft, "quickCraft");
        RiderState state = activePilotState(player, access).orElse(null);
        if (state == null || !Config.current().controls().lockControlSlots()) {
            return false;
        }
        if (slotId < 0 || slotId >= access.slotCount(menu)) {
            return false;
        }
        S clicked = access.slot(menu, slotId);
        if (input == ContainerInput.QUICK_CRAFT) {
            ItemStack carried = access.carried(menu);
            return (button & 3) == 1
                    && quickCraft.status() == 1
                    && (quickCraft.type() == 0 || quickCraft.type() == 1
                            || quickCraft.type() == 2 && access.hasInfiniteMaterials(player))
                    && !carried.isEmpty()
                    && lockedPlayerSlot(player, clicked, state, access)
                    && access.canItemQuickReplace(clicked, carried, true)
                    && access.mayPlace(clicked, carried)
                    && (quickCraft.type() == 2
                            || carried.getCount() > quickCraft.selectedSlots().size())
                    && access.canDragTo(menu, clicked);
        }
        if (input == ContainerInput.PICKUP_ALL) {
            return pickupAllWouldReachLockedSlot(
                    player, menu, clicked, button, state, access);
        }
        if (input == ContainerInput.SWAP) {
            return swapWouldMutateLockedSlot(player, button, clicked, state, access);
        }
        if (input == ContainerInput.THROW) {
            return access.carried(menu).isEmpty()
                    && access.canDropItems(player)
                    && access.hasItem(clicked)
                    && access.mayPickup(clicked, player)
                    && access.removableAmount(clicked, button == 0 ? 1 : access.item(clicked).getCount()) > 0
                    && lockedPlayerSlot(player, clicked, state, access);
        }
        if (input == ContainerInput.CLONE) {
            return access.hasInfiniteMaterials(player)
                    && access.carried(menu).isEmpty()
                    && access.hasItem(clicked)
                    && lockedPlayerSlot(player, clicked, state, access);
        }
        if (button != 0 && button != 1) {
            return false;
        }
        if (input == ContainerInput.QUICK_MOVE) {
            return lockedPlayerSlot(player, clicked, state, access)
                    && access.hasItem(clicked)
                    && access.mayPickup(clicked, player)
                    && access.canQuickMove(menu, player, slotId, clicked);
        }
        return input == ContainerInput.PICKUP
                && lockedPlayerSlot(player, clicked, state, access)
                && pickupWouldMutate(player, menu, clicked, button, access);
    }

    private static <P, G, M, S, I> boolean pickupWouldMutate(
            P player, M menu, S slot, int button,
            ContainerDecisionAccess<P, G, M, S, I> access) {
        ItemStack clicked = access.item(slot);
        ItemStack carried = access.carried(menu);
        if (clicked.isEmpty()) {
            int requested = button == 0 ? carried.getCount() : 1;
            return !carried.isEmpty() && access.mayPlace(slot, carried)
                    && requested > 0 && access.maxStackSize(slot, carried) > 0;
        }
        if (!access.mayPickup(slot, player)) {
            return false;
        }
        if (carried.isEmpty()) {
            int requested = button == 0 ? clicked.getCount() : (clicked.getCount() + 1) / 2;
            return access.removableAmount(slot, requested) > 0;
        }
        if (access.mayPlace(slot, carried)) {
            if (ItemStack.isSameItemSameComponents(clicked, carried)) {
                int requested = button == 0 ? carried.getCount() : 1;
                return requested > 0 && access.maxStackSize(slot, carried) > clicked.getCount();
            }
            return carried.getCount() <= access.maxStackSize(slot, carried);
        }
        if (!ItemStack.isSameItemSameComponents(clicked, carried)) {
            return false;
        }
        int capacity = carried.getMaxStackSize() - carried.getCount();
        return capacity > 0
                && (access.allowModification(slot, player) || capacity >= clicked.getCount())
                && access.removableAmount(slot, Math.min(clicked.getCount(), capacity)) > 0;
    }

    private static <P, G, M, S, I> boolean swapWouldMutateLockedSlot(
            P player, int button, S clickedSlot, RiderState state,
            ContainerDecisionAccess<P, G, M, S, I> access) {
        if (!((button >= 0 && button < 9) || button == 40)) {
            return false;
        }
        ItemStack target = access.inventoryItem(player, button);
        ItemStack clicked = access.item(clickedSlot);
        if (target.isEmpty() && clicked.isEmpty()) {
            return false;
        }
        boolean clickedLocked = lockedPlayerSlot(player, clickedSlot, state, access);
        boolean targetLocked = button < 9 && isLockedSlot(state, button);
        if (target.isEmpty()) {
            return access.mayPickup(clickedSlot, player) && (clickedLocked || targetLocked);
        }
        if (clicked.isEmpty()) {
            return access.mayPlace(clickedSlot, target)
                    && access.maxStackSize(clickedSlot, target) > 0
                    && (clickedLocked || targetLocked);
        }
        return access.mayPickup(clickedSlot, player)
                && access.mayPlace(clickedSlot, target)
                && access.maxStackSize(clickedSlot, target) > 0
                && (clickedLocked || targetLocked);
    }

    private static <P, G, M, S, I> boolean pickupAllWouldReachLockedSlot(
            P player, M menu, S clicked, int button, RiderState state,
            ContainerDecisionAccess<P, G, M, S, I> access) {
        ItemStack carried = access.carried(menu);
        if (carried.isEmpty() || access.hasItem(clicked) && access.mayPickup(clicked, player)) {
            return false;
        }
        int count = carried.getCount();
        int maximum = carried.getMaxStackSize();
        int slotCount = access.slotCount(menu);
        int first = button == 0 ? 0 : slotCount - 1;
        int step = button == 0 ? 1 : -1;
        int[] remaining = new int[slotCount];
        java.util.Arrays.fill(remaining, -1);
        for (int pass = 0; pass < 2 && count < maximum; pass++) {
            for (int candidateId = first;
                    candidateId >= 0 && candidateId < slotCount && count < maximum;
                    candidateId += step) {
                S candidate = access.slot(menu, candidateId);
                if (!access.hasItem(candidate)
                        || !access.canItemQuickReplace(candidate, carried, true)
                        || !access.mayPickup(candidate, player)
                        || !access.canTakeForPickupAll(menu, carried, candidate)) {
                    continue;
                }
                ItemStack candidateStack = access.item(candidate);
                if (remaining[candidateId] < 0) {
                    remaining[candidateId] = candidateStack.getCount();
                }
                int candidateCount = remaining[candidateId];
                if (candidateCount <= 0
                        || !ItemStack.isSameItemSameComponents(carried, candidateStack)
                        || pass == 0 && candidateCount == candidateStack.getMaxStackSize()) {
                    continue;
                }
                int capacity = maximum - count;
                if (!access.allowModification(candidate, player)
                        && capacity < candidateCount) {
                    continue;
                }
                int requested = Math.min(candidateCount, capacity);
                int removed = Math.min(requested, access.removableAmount(candidate, requested));
                if (removed <= 0) {
                    continue;
                }
                if (lockedPlayerSlot(player, candidate, state, access)) {
                    return true;
                }
                remaining[candidateId] -= removed;
                count += removed;
            }
        }
        return false;
    }

    private static <P, G> Optional<RiderState> activePilotState(
            P player, PilotLockAccess<P, G> access) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(access, "access");
        Optional<RiderState> attached = Objects.requireNonNull(access.state(player), "state");
        if (attached.isEmpty()) {
            return Optional.empty();
        }
        RiderState state = attached.get();
        boolean fire = state.fireStash().isPresent();
        boolean cry = state.cryStash().isPresent();
        if (fire != cry) {
            throw new IllegalStateException("Rider inventory stash must be complete or empty");
        }
        if (!fire) {
            if (state.riddenGhastId().isPresent()) {
                throw new IllegalStateException("Ridden ghast id cannot exist without a stash");
            }
            return Optional.empty();
        }
        UUID persistedGhast = state.riddenGhastId().orElseThrow(
                () -> new IllegalStateException("Active stash requires a ridden ghast id"));
        int fireSlot = state.fireStash().orElseThrow().slotIndex();
        int crySlot = state.cryStash().orElseThrow().slotIndex();
        if (fireSlot == crySlot) {
            throw new IllegalStateException("Persisted control slots must be distinct");
        }
        Optional<G> ridden = Objects.requireNonNull(access.riddenHappyGhast(player), "riddenHappyGhast");
        if (ridden.isEmpty()) {
            return Optional.empty();
        }
        G ghast = ridden.get();
        return persistedGhast.equals(access.ghastId(ghast))
                && access.isControllingFirstPassenger(player, ghast)
                ? Optional.of(state)
                : Optional.empty();
    }

    private static <P, G, M, S, I> boolean lockedPlayerSlot(
            P player, S slot, RiderState state, ContainerDecisionAccess<P, G, M, S, I> access) {
        I inventory = access.playerInventory(player);
        return access.slotContainer(slot) == inventory
                && isLockedSlot(state, access.containerSlot(slot));
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
        Config.Controls settings = Config.current().controls();
        return handleUseItem(
                player, hand, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static Admission handleUseItem(
            ServerPlayer player,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            Config.Controls settings) {
        return handleUseItem(
                player, hand, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission handleUseItem(
            P player,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            ControlAccess<P, G> access) {
        return handleUseItem(player, hand, state, gameTick, Config.current().controls(), access);
    }

    static <P, G> Admission handleUseItem(
            P player,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            Config.Controls settings,
            ControlAccess<P, G> access) {
        Objects.requireNonNull(hand, "hand");
        return admit(player, CallbackSource.CALLBACK, hand, access.itemInHand(player, hand),
                Optional.empty(), state, gameTick, settings, access);
    }

    static Admission handleUseEntity(
            ServerPlayer player,
            Entity target,
            InteractionHand hand,
            RiderState state,
            long gameTick) {
        Config.Controls settings = Config.current().controls();
        return handleUseEntity(
                player, target, hand, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static Admission handleUseEntity(
            ServerPlayer player,
            Entity target,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            Config.Controls settings) {
        return handleUseEntity(
                player, target, hand, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission handleUseEntity(
            P player,
            Object target,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            ControlAccess<P, G> access) {
        return handleUseEntity(
                player, target, hand, state, gameTick, Config.current().controls(), access);
    }

    static <P, G> Admission handleUseEntity(
            P player,
            Object target,
            InteractionHand hand,
            RiderState state,
            long gameTick,
            Config.Controls settings,
            ControlAccess<P, G> access) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hand, "hand");
        return admit(player, CallbackSource.CALLBACK, hand, access.itemInHand(player, hand),
                Optional.of(target), state, gameTick, settings, access);
    }

    static Admission sampleHeld(ServerPlayer player, RiderState state, long gameTick) {
        Config.Controls settings = Config.current().controls();
        return sampleHeld(player, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static Admission sampleHeld(
            ServerPlayer player,
            RiderState state,
            long gameTick,
            Config.Controls settings) {
        return sampleHeld(player, state, gameTick, settings, ServerPlayerControlAccess.INSTANCE);
    }

    static <P, G> Admission sampleHeld(
            P player,
            RiderState state,
            long gameTick,
            ControlAccess<P, G> access) {
        return sampleHeld(player, state, gameTick, Config.current().controls(), access);
    }

    static <P, G> Admission sampleHeld(
            P player,
            RiderState state,
            long gameTick,
            Config.Controls settings,
            ControlAccess<P, G> access) {
        ObservedUse observed = access.observedUse(player);
        ItemStack input = observed.using() ? observed.stack() : ItemStack.EMPTY;
        return admit(player, CallbackSource.SERVER_TICK, observed.hand(), input,
                Optional.empty(), state, gameTick, settings, access);
    }

    private static <P, G> Admission admit(
            P player,
            CallbackSource source,
            InteractionHand hand,
            ItemStack input,
            Optional<Object> clickedTarget,
            RiderState state,
            long gameTick,
            Config.Controls settings,
            ControlAccess<P, G> access) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(clickedTarget, "clickedTarget");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(access, "access");

        Optional<G> ridden = access.riddenHappyGhast(player);
        if (ridden.isEmpty() || clickedTarget.filter(target -> target != ridden.get()).isPresent()
                || !access.isControllingFirstPassenger(player, ridden.get())
                || !state.riddenGhastId().equals(Optional.of(access.ghastId(ridden.get())))) {
            return new Ignored(state);
        }
        ControlIntent intent = classify(player, source, input, state, settings, access);
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
            Config.Controls settings,
            ControlAccess<P, G> access) {
        Objects.requireNonNull(settings, "settings");
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

    public record QuickCraftSnapshot(int status, int type, Set<?> selectedSlots) {
        public QuickCraftSnapshot {
            selectedSlots = Set.copyOf(Objects.requireNonNull(selectedSlots, "selectedSlots"));
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

    interface PilotLockAccess<P, G> {
        Optional<RiderState> state(P player);

        Optional<G> riddenHappyGhast(P player);

        boolean isControllingFirstPassenger(P player, G ghast);

        UUID ghastId(G ghast);
    }

    interface SelectedSlotDecisionAccess<P, G> extends PilotLockAccess<P, G> {
        int selectedSlot(P player);
    }

    interface ContainerDecisionAccess<P, G, M, S, I> extends SelectedSlotDecisionAccess<P, G> {
        I playerInventory(P player);

        int slotCount(M menu);

        S slot(M menu, int slotId);

        I slotContainer(S slot);

        int containerSlot(S slot);

        ItemStack carried(M menu);

        boolean hasItem(S slot);

        ItemStack item(S slot);

        boolean mayPickup(S slot, P player);

        boolean mayPlace(S slot, ItemStack stack);

        boolean allowModification(S slot, P player);

        int maxStackSize(S slot, ItemStack stack);

        int removableAmount(S slot, int requested);

        boolean canItemQuickReplace(S slot, ItemStack stack, boolean allowOverflow);

        boolean canDragTo(M menu, S slot);

        boolean canQuickMove(M menu, P player, int slotId, S slot);

        ItemStack inventoryItem(P player, int slot);

        boolean canTakeForPickupAll(M menu, ItemStack carried, S slot);

        boolean hasInfiniteMaterials(P player);

        boolean canDropItems(P player);
    }

    enum ServerPlayerContainerDecisionAccess
            implements ContainerDecisionAccess<ServerPlayer, HappyGhast, AbstractContainerMenu, Slot, Inventory> {
        INSTANCE;

        @Override public Optional<RiderState> state(ServerPlayer player) {
            AttachmentTarget target = (AttachmentTarget) (Object) player;
            return Optional.ofNullable(target.getAttached(RiderState.register()));
        }

        @Override public Optional<HappyGhast> riddenHappyGhast(ServerPlayer player) {
            return ServerPlayerControlAccess.INSTANCE.riddenHappyGhast(player);
        }

        @Override public boolean isControllingFirstPassenger(ServerPlayer player, HappyGhast ghast) {
            ServerPlayerControlAccess controlAccess = ServerPlayerControlAccess.INSTANCE;
            return controlAccess.isControllingFirstPassenger(player, ghast);
        }

        @Override public UUID ghastId(HappyGhast ghast) {
            return ServerPlayerControlAccess.INSTANCE.ghastId(ghast);
        }

        @Override public int selectedSlot(ServerPlayer player) {
            return player.getInventory().getSelectedSlot();
        }

        @Override public Inventory playerInventory(ServerPlayer player) {
            return player.getInventory();
        }

        @Override public int slotCount(AbstractContainerMenu menu) { return menu.slots.size(); }
        @Override public Slot slot(AbstractContainerMenu menu, int slotId) { return menu.getSlot(slotId); }
        @Override public Inventory slotContainer(Slot slot) {
            return slot.container instanceof Inventory inventory ? inventory : null;
        }
        @Override public int containerSlot(Slot slot) { return slot.getContainerSlot(); }
        @Override public ItemStack carried(AbstractContainerMenu menu) { return menu.getCarried().copy(); }
        @Override public boolean hasItem(Slot slot) { return slot.hasItem(); }
        @Override public ItemStack item(Slot slot) { return slot.getItem().copy(); }
        @Override public boolean mayPickup(Slot slot, ServerPlayer player) { return slot.mayPickup(player); }
        @Override public boolean mayPlace(Slot slot, ItemStack stack) { return slot.mayPlace(stack); }
        @Override public boolean allowModification(Slot slot, ServerPlayer player) {
            return slot.allowModification(player);
        }
        @Override public int maxStackSize(Slot slot, ItemStack stack) {
            return slot.getMaxStackSize(stack);
        }
        @Override public int removableAmount(Slot slot, int requested) {
            return Math.min(requested, slot.getItem().getCount());
        }
        @Override public boolean canItemQuickReplace(
                Slot slot, ItemStack stack, boolean allowOverflow) {
            return AbstractContainerMenu.canItemQuickReplace(slot, stack, allowOverflow);
        }
        @Override public boolean canDragTo(AbstractContainerMenu menu, Slot slot) {
            return menu.canDragTo(slot);
        }
        @Override public boolean canQuickMove(
                AbstractContainerMenu menu, ServerPlayer player, int slotId, Slot slot) {
            return slot.hasItem();
        }
        @Override public ItemStack inventoryItem(ServerPlayer player, int slot) {
            return player.getInventory().getItem(slot).copy();
        }
        @Override public boolean canTakeForPickupAll(
                AbstractContainerMenu menu, ItemStack carried, Slot slot) {
            return menu.canTakeItemForPickAll(carried, slot);
        }
        @Override public boolean hasInfiniteMaterials(ServerPlayer player) {
            return player.hasInfiniteMaterials();
        }
        @Override public boolean canDropItems(ServerPlayer player) { return player.canDropItems(); }
    }

    interface InventoryAccess<T> {
        int size(T inventory);

        ItemStack read(T inventory, int slot);

        void write(T inventory, int slot, ItemStack stack);
    }

    interface DeathDropAccess<T> extends InventoryAccess<T> {
        Optional<RiderState> state(T player);

        void replaceState(T player, RiderState state);
    }

    enum ServerPlayerDeathDropAccess implements DeathDropAccess<ServerPlayer> {
        INSTANCE;

        @Override
        public int size(ServerPlayer player) {
            return ServerPlayerInventoryAccess.INSTANCE.size(player);
        }

        @Override
        public ItemStack read(ServerPlayer player, int slot) {
            return ServerPlayerInventoryAccess.INSTANCE.read(player, slot);
        }

        @Override
        public void write(ServerPlayer player, int slot, ItemStack stack) {
            ServerPlayerInventoryAccess.INSTANCE.write(player, slot, stack);
        }

        @Override
        public Optional<RiderState> state(ServerPlayer player) {
            AttachmentTarget target = (AttachmentTarget) (Object) player;
            return Optional.ofNullable(target.getAttached(RiderState.register()));
        }

        @Override
        public void replaceState(ServerPlayer player, RiderState state) {
            RiderState.replace((AttachmentTarget) (Object) player, state);
        }
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
