package xyz.pyrehaven.happyartillery;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ControlsTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID RIDE = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(initializer -> initializer.apply());
    }

    @Test
    void markerRoundTripsTypeOwnerAndRideWhilePreservingUnrelatedData() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("another-mod:owner", "kept");
        unrelated.putInt("sequence", 42);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
        Components.Marker expected = new Components.Marker(Components.Control.FIRE, OWNER, RIDE);

        Components.mark(stack, expected);

        Components.MarkerRead read = Components.marker(stack);
        assertTrue(read instanceof Components.Valid);
        assertEquals(expected, read.marker().orElseThrow());
        CompoundTag data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        assertEquals("kept", data.getString("another-mod:owner").orElseThrow());
        assertEquals(42, data.getInt("sequence").orElseThrow());
    }

    @Test
    void markerIdentitySurvivesVanillaPersistenceAndNetworkRoundTrips() {
        for (ItemStack original : List.of(
                Controls.fireControl(OWNER, RIDE), Controls.cryControl(OWNER, RIDE))) {
            ItemStack persisted = ItemStack.CODEC.parse(JsonOps.INSTANCE,
                    ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow()).getOrThrow();
            assertEquals(Components.marker(original).marker(), Components.marker(persisted).marker());
            assertTrue(ItemStack.matches(original, persisted));

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                    Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            try {
                ItemStack.STREAM_CODEC.encode(buffer, original);
                ItemStack synchronizedStack = ItemStack.STREAM_CODEC.decode(buffer);
                assertEquals(Components.marker(original).marker(),
                        Components.marker(synchronizedStack).marker());
                assertTrue(ItemStack.matches(original, synchronizedStack));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void markerParsingFailsClosedForPlainPartialMalformedAndUnknownData() {
        assertTrue(Components.marker(new ItemStack(Items.FIRE_CHARGE)).isAbsent());
        for (CompoundTag data : List.of(
                markerData("fire", OWNER.toString(), null),
                markerData("unknown", OWNER.toString(), RIDE.toString()),
                markerData("fire", "not-a-uuid", RIDE.toString()))) {
            ItemStack stack = new ItemStack(Items.FIRE_CHARGE);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
            assertSame(Components.Malformed.INSTANCE, Components.marker(stack));
        }
    }

    @ParameterizedTest(name = "{0} marker attempt on {1}")
    @MethodSource("malformedConfiguredControlItems")
    void malformedMarkerAttemptsNeverAuthorizeAsPlainConfiguredItems(
            String shape, ItemStack attemptedControl) {
        TestPilot pilot = TestPilot.riding();
        pilot.main = attemptedControl;
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        Config.Controls plainEnabled = new Config.Controls(
                "minecraft:fire_charge", "minecraft:ghast_tear", false, true);

        Controls.Admission admission = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 11L, plainEnabled, pilot);

        assertInstanceOf(Controls.Ignored.class, admission, shape);
        assertSame(state, admission.state(), shape);
    }

    @Test
    void factoriesResolveLiveConfiguredItemsAndCreateFreshNamedGlintingOwnerRideControls() {
        ItemStack first = Controls.fireControl(OWNER, RIDE);
        ItemStack second = Controls.fireControl(OWNER, RIDE);
        ItemStack cry = Controls.cryControl(OWNER, RIDE);
        assertNotSame(first, second);
        assertTrue(first.is(Items.FIRE_CHARGE));
        assertTrue(cry.is(Items.GHAST_TEAR));
        assertEquals(new Components.Marker(Components.Control.FIRE, OWNER, RIDE),
                Components.marker(first).marker().orElseThrow());
        assertEquals(new Components.Marker(Components.Control.CRY, OWNER, RIDE),
                Components.marker(cry).marker().orElseThrow());
        assertEquals("Fire Control", first.getHoverName().getString());
        assertEquals("Cry Control", cry.getHoverName().getString());
        assertTrue(first.hasFoil());
        assertNotNull(first.get(DataComponents.CONSUMABLE));
    }

    @Test
    void factoriesSeeValidLiveConfigChangesAndReturnDefensiveFreshStacks(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        try {
            Files.writeString(file, """
                    {"controls":{"fireItem":"minecraft:snowball","cryItem":"minecraft:feather"}}
                    """);
            Config.reload(file);

            ItemStack first = Controls.fireControl(OWNER, RIDE);
            ItemStack second = Controls.fireControl(OWNER, RIDE);
            ItemStack cry = Controls.cryControl(OWNER, RIDE);
            first.setCount(0);
            assertTrue(second.is(Items.SNOWBALL));
            assertFalse(second.isEmpty());
            assertTrue(cry.is(Items.FEATHER));
            assertEquals("Fire Control", second.getHoverName().getString());
            assertTrue(second.hasFoil());
            assertNotNull(second.get(DataComponents.CONSUMABLE));
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void allocationReservesFirstTwoFreeHotbarThenMainCandidatesBeforeWriting() {
        RecordingInventory inventory = RecordingInventory.filled();
        inventory.seed(7, ItemStack.EMPTY);
        inventory.seed(20, ItemStack.EMPTY);

        RiderState mounted = Controls.reconcile(
                inventory, RiderState.fresh(), Optional.of(RIDE), inventory);

        assertEquals(Optional.of(RIDE), mounted.riddenGhastId());
        assertEquals(List.of(7, 20), inventory.writeSlots());
        assertEquals(Components.Control.FIRE,
                Components.marker(inventory.peek(7)).marker().orElseThrow().control());
        assertEquals(Components.Control.CRY,
                Components.marker(inventory.peek(20)).marker().orElseThrow().control());
        assertTrue(inventory.messages.isEmpty());
    }

    @Test
    void secondControlConstructionFailurePerformsZeroInventoryWrites(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        RecordingInventory inventory = RecordingInventory.filled();
        inventory.seed(1, ItemStack.EMPTY);
        inventory.seed(2, ItemStack.EMPTY);
        List<ItemStack> before = inventory.copyStacks();
        try {
            Files.writeString(file, """
                    {"controls":{"fireItem":"minecraft:fire_charge","cryItem":"minecraft:not_registered"}}
                    """);
            Config.reload(file, ignored -> true);

            assertThrows(IllegalStateException.class, () -> Controls.reconcile(
                    inventory, RiderState.fresh(), Optional.of(RIDE), inventory));

            assertTrue(inventory.writeSlots().isEmpty());
            assertInventoryMatches(before, inventory.copyStacks());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void fewerThanTwoCandidatesWritesNothingRecordsRideAndSendsExactRedRefusalOnce() {
        for (int empties : List.of(0, 1)) {
            RecordingInventory inventory = RecordingInventory.filled();
            if (empties == 1) inventory.seed(3, ItemStack.EMPTY);
            List<ItemStack> before = inventory.copyStacks();

            RiderState mounted = Controls.reconcile(
                    inventory, RiderState.fresh(), Optional.of(RIDE), inventory);
            RiderState sameRide = Controls.reconcile(
                    inventory, mounted, Optional.of(RIDE), inventory);

            assertEquals(before.size(), inventory.copyStacks().size());
            for (int slot = 0; slot < before.size(); slot++) {
                assertTrue(ItemStack.matches(before.get(slot), inventory.copyStacks().get(slot)),
                        "slot " + slot);
            }
            assertEquals(Optional.of(RIDE), mounted.riddenGhastId());
            assertSame(mounted, sameRide);
            assertEquals(1, inventory.messages.size());
            Component refusal = inventory.messages.getFirst();
            assertEquals("Controls need 2 free slots.", refusal.getString());
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), refusal.getStyle().getColor());
            assertTrue(inventory.writeSlots().isEmpty());
        }
    }

    @Test
    void invalidPersistedHudCacheUsesNamedOwnerFailureWithRideIdentity() {
        RecordingInventory inventory = RecordingInventory.empty();
        RiderState invalid = new RiderState(Optional.of(RIDE), 10L,
                Optional.of(new RiderState.HudCache(Double.NaN, "PURPLE", "bad", 9L)));

        Controls.InvalidRiderState failure = assertThrows(
                Controls.InvalidRiderState.class,
                () -> Controls.reconcile(inventory, invalid, Optional.of(RIDE), inventory));

        assertEquals(Optional.of(RIDE), failure.rideId());
        assertEquals("invalid persisted HUD cache", failure.getMessage());
        assertTrue(inventory.writeSlots().isEmpty());
    }

    @Test
    void invalidStateRecoveryWithoutTrustedRideRemovesEveryValidOwnedControlInOneBoundedScan()
            throws Exception {
        UUID otherRide = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID foreignOwner = UUID.fromString("44444444-4444-4444-8444-444444444444");
        TestServerPlayer player = allocate(TestServerPlayer.class);
        player.id = OWNER;
        player.inventory = new RecordingPlayerInventory(player);
        ItemStack plainConfigured = new ItemStack(Items.FIRE_CHARGE);
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("another-mod:owner", "kept");
        plainConfigured.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
        ItemStack malformed = configuredAttempt(Items.GHAST_TEAR,
                markerData("cry", OWNER.toString(), null));
        ItemStack foreign = Controls.fireControl(foreignOwner, RIDE);
        player.inventory.setItem(4, Controls.fireControl(OWNER, RIDE));
        player.inventory.setItem(40, Controls.cryControl(OWNER, otherRide));
        player.inventory.setItem(9, plainConfigured);
        player.inventory.setItem(10, malformed);
        player.inventory.setItem(11, foreign);
        player.inventory.clearObservations();
        ItemStack plainBefore = plainConfigured.copy();
        ItemStack malformedBefore = malformed.copy();
        ItemStack foreignBefore = foreign.copy();

        RiderState recovered = Controls.recoverInvalidState(
                player, new Controls.InvalidRiderState(Optional.empty(), "invalid persisted state"));

        assertEquals(RiderState.fresh(), recovered);
        assertTrue(player.inventory.peek(4).isEmpty());
        assertTrue(player.inventory.peek(40).isEmpty());
        assertSame(plainConfigured, player.inventory.peek(9));
        assertSame(malformed, player.inventory.peek(10));
        assertSame(foreign, player.inventory.peek(11));
        assertTrue(ItemStack.matches(plainBefore, plainConfigured));
        assertTrue(ItemStack.matches(malformedBefore, malformed));
        assertTrue(ItemStack.matches(foreignBefore, foreign));
        assertEquals(Stream.concat(java.util.stream.IntStream.rangeClosed(0, 35).boxed(), Stream.of(40)).toList(),
                player.inventory.readSlots());
        assertEquals(List.of(4, 40), player.inventory.writeSlots());
    }

    @Test
    void heldAdmissionConsumesTheSharedSnapshotAndCannotAcceptMissingGeneratedControl() {
        TestPilot pilot = TestPilot.riding();
        ItemStack fire = Controls.fireControl(OWNER, RIDE);
        pilot.observed = new Controls.ObservedUse(true, InteractionHand.MAIN_HAND, fire);
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        Controls.InventorySnapshot missing = new Controls.InventorySnapshot(
                Controls.ControlLocation.MISSING,
                Controls.ControlLocation.HAND_ACCESSIBLE, 0);

        Controls.Admission admission = Controls.sampleHeld(pilot, state, 11L, missing, pilot);

        assertInstanceOf(Controls.Ignored.class, admission);
        assertSame(state, admission.state());
    }

    @Test
    void replacingGeneratedControlBeforeDismountIsNeverOverwrittenOrDeleted() {
        RecordingInventory inventory = RecordingInventory.filled();
        inventory.seed(4, ItemStack.EMPTY);
        inventory.seed(5, ItemStack.EMPTY);
        RiderState mounted = Controls.reconcile(
                inventory, RiderState.fresh(), Optional.of(RIDE), inventory);
        ItemStack replacement = new ItemStack(Items.DIAMOND_SWORD);
        replacement.setDamageValue(17);
        inventory.seed(4, replacement);
        inventory.clearWrites();

        RiderState cleared = Controls.reconcile(
                inventory, mounted, Optional.empty(), inventory);

        assertTrue(ItemStack.matches(replacement, inventory.peek(4)));
        assertTrue(inventory.peek(5).isEmpty());
        assertEquals(List.of(5), inventory.writeSlots());
        assertEquals(Optional.empty(), cleared.riddenGhastId());
    }

    @Test
    void boundedSnapshotReadsOnlyZeroThroughThirtyFiveAndOffhandAndClassifiesControls() {
        RecordingInventory inventory = RecordingInventory.empty();
        inventory.seed(8, Controls.fireControl(OWNER, RIDE));
        inventory.seed(21, Controls.cryControl(OWNER, RIDE));
        inventory.seed(30, Controls.fireControl(UUID.randomUUID(), RIDE));
        inventory.seed(40, Controls.cryControl(OWNER, UUID.randomUUID()));

        Controls.InventorySnapshot snapshot = Controls.snapshot(inventory, OWNER, RIDE, inventory);

        assertEquals(Controls.ControlLocation.HAND_ACCESSIBLE, snapshot.fire());
        assertEquals(Controls.ControlLocation.MAIN_INVENTORY_ONLY, snapshot.cry());
        assertEquals(2, snapshot.staleOrForeignCount());
        assertEquals(Stream.concat(java.util.stream.IntStream.rangeClosed(0, 35).boxed(), Stream.of(40)).toList(),
                inventory.readSlots());
    }

    @Test
    void snapshotReportsMissingWithoutSearchingMenusWorldEntitiesOrOtherPlayers() {
        RecordingInventory inventory = RecordingInventory.empty();
        Controls.InventorySnapshot snapshot = Controls.snapshot(inventory, OWNER, RIDE, inventory);
        assertEquals(Controls.ControlLocation.MISSING, snapshot.fire());
        assertEquals(Controls.ControlLocation.MISSING, snapshot.cry());
        assertEquals(37, inventory.readSlots().size());
    }

    @Test
    void snapshotTreatsEveryHotbarAndOffhandAsHandAccessibleButMainAsInventoryOnly() {
        for (int handAccessible : List.of(0, 8, 40)) {
            RecordingInventory inventory = RecordingInventory.empty();
            inventory.seed(handAccessible, Controls.fireControl(OWNER, RIDE));
            inventory.seed(9, Controls.cryControl(OWNER, RIDE));

            Controls.InventorySnapshot snapshot = Controls.snapshot(inventory, OWNER, RIDE, inventory);

            assertEquals(Controls.ControlLocation.HAND_ACCESSIBLE, snapshot.fire(), "slot " + handAccessible);
            assertEquals(Controls.ControlLocation.MAIN_INVENTORY_ONLY, snapshot.cry());
        }
    }

    @Test
    void matchingOwnerRideControlsAuthorizeFromActualMainHandAndOffhand() {
        TestPilot pilot = TestPilot.riding();
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        pilot.main = Controls.cryControl(OWNER, RIDE);
        pilot.offhand = Controls.cryControl(OWNER, RIDE);

        Controls.Admission mainAccepted = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 11L, pilot);
        Controls.Admission offhandAccepted = Controls.handleUseItem(
                pilot, InteractionHand.OFF_HAND, state, 12L, pilot);

        assertEquals(Controls.ControlIntent.CRY,
                assertInstanceOf(Controls.Accepted.class, mainAccepted).intent());
        assertEquals(Controls.ControlIntent.CRY,
                assertInstanceOf(Controls.Accepted.class, offhandAccepted).intent());
        assertEquals(11L, mainAccepted.state().lastHandledTick());
        assertEquals(12L, offhandAccepted.state().lastHandledTick());
    }

    @Test
    void riderAndTargetGatesSpendNoTickBeforeLaterValidInput() {
        RiderState state = new RiderState(Optional.of(RIDE), 20L, Optional.empty());
        TestPilot pilot = TestPilot.riding();
        pilot.main = Controls.cryControl(OWNER, RIDE);

        pilot.riding = false;
        Controls.Admission noRider = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 21L, pilot);
        pilot.riding = true;
        pilot.controllingFirstPassenger = false;
        Controls.Admission nonPilot = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 21L, pilot);
        pilot.controllingFirstPassenger = true;
        Controls.Admission wrongTarget = Controls.handleUseEntity(
                pilot, UUID.randomUUID(), InteractionHand.MAIN_HAND, state, 21L, pilot);
        Controls.Admission accepted = Controls.handleUseEntity(
                pilot, RIDE, InteractionHand.MAIN_HAND, state, 21L, pilot);

        for (Controls.Admission denied : List.of(noRider, nonPilot, wrongTarget)) {
            assertInstanceOf(Controls.Ignored.class, denied);
            assertSame(state, denied.state());
            assertEquals(20L, denied.state().lastHandledTick());
        }
        assertEquals(Controls.ControlIntent.CRY,
                assertInstanceOf(Controls.Accepted.class, accepted).intent());
        assertEquals(21L, accepted.state().lastHandledTick());
    }

    @Test
    void holdSamplingAndCallbackShareSameTickDeduplication() {
        TestPilot pilot = TestPilot.riding();
        ItemStack fire = Controls.fireControl(OWNER, RIDE);
        pilot.observed = new Controls.ObservedUse(true, InteractionHand.OFF_HAND, fire);
        pilot.main = Controls.cryControl(OWNER, RIDE);
        RiderState state = new RiderState(Optional.of(RIDE), 30L, Optional.empty());

        Controls.InventorySnapshot present = new Controls.InventorySnapshot(
                Controls.ControlLocation.HAND_ACCESSIBLE,
                Controls.ControlLocation.HAND_ACCESSIBLE, 0);
        Controls.Admission held = Controls.sampleHeld(pilot, state, 31L, present, pilot);
        Controls.Admission callbackSameTick = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, held.state(), 31L, pilot);
        Controls.Admission heldNextTick = Controls.sampleHeld(
                pilot, held.state(), 32L, present, pilot);

        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Accepted.class, held).intent());
        assertInstanceOf(Controls.Ignored.class, callbackSameTick);
        assertSame(held.state(), callbackSameTick.state());
        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Accepted.class, heldNextTick).intent());
    }

    @Test
    void foreignAndPriorRideControlsNeverAuthorize() {
        TestPilot pilot = TestPilot.riding();
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        for (ItemStack denied : List.of(
                Controls.cryControl(UUID.randomUUID(), RIDE),
                Controls.cryControl(OWNER, UUID.randomUUID()))) {
            pilot.main = denied;
            assertInstanceOf(Controls.Ignored.class, Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND, state, 11L, pilot));
        }
    }

    @Test
    void plainItemsAreAdmissionOnlyAndNeverGeneratedPresenceOrCleanupTargets() {
        TestPilot pilot = TestPilot.riding();
        pilot.main = new ItemStack(Items.GHAST_TEAR);
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        Config.Controls enabled = new Config.Controls(
                "minecraft:fire_charge", "minecraft:ghast_tear", true, true);
        assertEquals(Controls.ControlIntent.CRY, assertInstanceOf(Controls.Accepted.class,
                Controls.handleUseItem(
                        pilot, InteractionHand.MAIN_HAND, state, 11L, enabled, pilot)).intent());

        RecordingInventory inventory = RecordingInventory.empty();
        inventory.seed(2, pilot.main);
        Controls.removeMatching(inventory, OWNER, RIDE, inventory);
        assertTrue(inventory.peek(2).is(Items.GHAST_TEAR));
        assertEquals(Controls.ControlLocation.MISSING,
                Controls.snapshot(inventory, OWNER, RIDE, inventory).cry());
    }

    @Test
    void cleanupConsumesOnlyValidMatchingMarkersAndPreservesMalformedAttempts() {
        RecordingInventory inventory = RecordingInventory.empty();
        ItemStack malformed = configuredAttempt(Items.FIRE_CHARGE,
                markerData("fire", OWNER.toString(), null));
        inventory.seed(1, malformed);
        inventory.seed(2, Controls.fireControl(OWNER, RIDE));

        Controls.removeMatching(inventory, OWNER, RIDE, inventory);

        assertTrue(ItemStack.matches(malformed, inventory.peek(1)));
        assertTrue(inventory.peek(2).isEmpty());
        assertEquals(List.of(2), inventory.writeSlots());
    }

    @Test
    void externalMutationPreservesOnlyOwningPlayerInventoryAndConsumesEveryOtherDestination() {
        ItemStack ownerDestination = Controls.fireControl(OWNER, RIDE);
        ItemStack otherPlayer = Controls.fireControl(OWNER, RIDE);
        ItemStack chest = Controls.fireControl(OWNER, RIDE);
        ItemStack ordinary = new ItemStack(Items.DIAMOND);

        assertFalse(Controls.consumeExternalControl(ownerDestination, OWNER));
        assertTrue(Controls.consumeExternalControl(otherPlayer, UUID.randomUUID()));
        assertTrue(Controls.consumeExternalControl(chest, null));
        assertFalse(Controls.consumeExternalControl(ordinary, null));
        assertFalse(ownerDestination.isEmpty());
        assertTrue(otherPlayer.isEmpty());
        assertTrue(chest.isEmpty());
        assertFalse(ordinary.isEmpty());
    }

    @Test
    void productionDropConsumptionUsesReturnedEntityAndPassesOrdinaryDrops() throws Exception {
        TestItemEntity markedEntity = allocate(TestItemEntity.class);
        TestItemEntity ordinaryEntity = allocate(TestItemEntity.class);

        Controls.consumeDroppedControl(Controls.fireControl(OWNER, RIDE), markedEntity);
        Controls.consumeDroppedControl(new ItemStack(Items.DIAMOND), ordinaryEntity);

        assertTrue(markedEntity.discarded);
        assertFalse(ordinaryEntity.discarded);
    }

    @Test
    void productionSlotAdapterMutatesActualExternalDestinationAndItsLiveMergedStack() {
        ItemStack mergedDestination = Controls.fireControl(OWNER, RIDE);
        mergedDestination.setCount(2);
        SimpleContainer chest = new SimpleContainer(1);
        chest.setItem(0, mergedDestination);
        Slot destination = new Slot(chest, 0, 0, 0);
        ItemStack live = destination.getItem();

        Controls.consumeExternalControl(destination);

        assertSame(live, destination.getItem());
        assertTrue(live.isEmpty());
        assertTrue(chest.getItem(0).isEmpty());

        ItemStack ordinary = new ItemStack(Items.DIAMOND, 3);
        chest.setItem(0, ordinary);
        Controls.consumeExternalControl(destination);
        assertSame(ordinary, destination.getItem());
        assertEquals(3, destination.getItem().getCount());
    }

    @Test
    void productionSlotAdapterPreservesSameOwnerInventoryAndConsumesOtherPlayerInventory()
            throws Exception {
        TestPlayer owner = testPlayer(OWNER);
        TestPlayer other = testPlayer(UUID.randomUUID());
        Inventory ownerInventory = new Inventory(owner, new EntityEquipment());
        Inventory otherInventory = new Inventory(other, new EntityEquipment());
        ownerInventory.setItem(0, Controls.fireControl(OWNER, RIDE));
        otherInventory.setItem(0, Controls.fireControl(OWNER, RIDE));

        Controls.consumeExternalControl(new Slot(ownerInventory, 0, 0, 0));
        Controls.consumeExternalControl(new Slot(otherInventory, 0, 0, 0));

        assertFalse(ownerInventory.getItem(0).isEmpty());
        assertTrue(otherInventory.getItem(0).isEmpty());
    }

    @Test
    void observedUseSamplesAllBoundaryValuesAndDefensivelyOwnsItsStack() {
        ItemStack source = Controls.fireControl(OWNER, RIDE);
        Controls.ObservedUse observed = Controls.observeUse(source,
                new Controls.UseObservation<>() {
                    @Override public boolean isUsingItem(ItemStack ignored) { return true; }
                    @Override public InteractionHand getUsedItemHand(ItemStack ignored) {
                        return InteractionHand.OFF_HAND;
                    }
                    @Override public ItemStack getUseItem(ItemStack value) { return value; }
                });
        source.setCount(0);
        ItemStack firstRead = observed.stack();
        firstRead.setCount(0);

        assertTrue(observed.using());
        assertEquals(InteractionHand.OFF_HAND, observed.hand());
        assertFalse(observed.stack().isEmpty());
    }

    @Test
    void productionPilotAndUseObservationAdaptersBindExactMinecraftApis() throws Exception {
        ClassNode pilot = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Controls$ServerPlayerControlAccess");
        assertCalls(method(pilot, "riddenHappyGhast"),
                "net/minecraft/server/level/ServerPlayer", "getVehicle");
        MethodNode controlling = method(pilot, "isControllingFirstPassenger");
        assertCalls(controlling,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getFirstPassenger");
        assertCalls(controlling,
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getControllingPassenger");
        assertCalls(method(pilot, "ghastId"),
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getUUID");
        assertCalls(method(pilot, "playerId"),
                "net/minecraft/server/level/ServerPlayer", "getUUID");
        assertCalls(method(pilot, "itemInHand"),
                "net/minecraft/server/level/ServerPlayer", "getItemInHand");

        ClassNode use = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Controls$LivingEntityUseObservation");
        assertCalls(method(use, "isUsingItem"),
                "net/minecraft/world/entity/LivingEntity", "isUsingItem");
        assertCalls(method(use, "getUsedItemHand"),
                "net/minecraft/world/entity/LivingEntity", "getUsedItemHand");
        assertCalls(method(use, "getUseItem"),
                "net/minecraft/world/entity/LivingEntity", "getUseItem");
    }

    @Test
    void mixinsTargetExactFailClosedPostMutationBoundariesAndDelegatePolicyOnly() throws Exception {
        ClassNode drop = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.mixin.PlayerDropMixin");
        assertEquals(List.of(Type.getType(ServerPlayer.class)), annotationValue(
                annotation(drop.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;"), "value"));
        MethodNode dropHandler = injectedHandler(drop);
        AnnotationNode dropInject = annotation(dropHandler.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("drop(Lnet/minecraft/world/item/ItemStack;ZZ)"
                        + "Lnet/minecraft/world/entity/item/ItemEntity;"),
                annotationValue(dropInject, "method"));
        assertEquals(1, annotationValue(dropInject, "require"));
        assertEquals("RETURN", annotationValue((AnnotationNode)
                ((List<?>) annotationValue(dropInject, "at")).getFirst(), "value"));
        assertSingleControlsCall(dropHandler, "consumeDroppedControl",
                "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/item/ItemEntity;)V");

        ClassNode external = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.mixin.ExternalContainerMixin");
        assertEquals(List.of(Type.getType(Slot.class)), annotationValue(
                annotation(external.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;"), "value"));
        MethodNode externalHandler = injectedHandler(external);
        AnnotationNode externalInject = annotation(externalHandler.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("setChanged()V"), annotationValue(externalInject, "method"));
        assertEquals(1, annotationValue(externalInject, "require"));
        assertEquals("HEAD", annotationValue((AnnotationNode)
                ((List<?>) annotationValue(externalInject, "at")).getFirst(), "value"));
        assertSingleControlsCall(externalHandler, "consumeExternalControl",
                "(Lnet/minecraft/world/inventory/Slot;)V");
    }

    @Test
    void mixinMetadataDeclaresExactlyTheTwoNarrowMixins() throws Exception {
        try (InputStream input = ControlsTest.class.getResourceAsStream("/happy-artillery.mixins.json")) {
            assertNotNull(input);
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(metadata.get("required").getAsBoolean());
            assertEquals(1, metadata.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            assertEquals(List.of("PlayerDropMixin", "ExternalContainerMixin"),
                    metadata.getAsJsonArray("mixins").asList().stream()
                            .map(com.google.gson.JsonElement::getAsString).toList());
        }
    }

    private static CompoundTag markerData(String type, String owner, String ride) {
        CompoundTag data = new CompoundTag();
        if (type != null) data.putString("happy-artillery:control_type", type);
        if (owner != null) data.putString("happy-artillery:control_owner", owner);
        if (ride != null) data.putString("happy-artillery:control_ride", ride);
        return data;
    }

    private static TestPlayer testPlayer(UUID id) throws Exception {
        TestPlayer player = allocate(TestPlayer.class);
        player.id = id;
        return player;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(field.get(null), type));
    }

    private static void assertInventoryMatches(List<ItemStack> expected, List<ItemStack> actual) {
        assertEquals(expected.size(), actual.size());
        for (int slot = 0; slot < expected.size(); slot++) {
            assertTrue(ItemStack.matches(expected.get(slot), actual.get(slot)), "slot " + slot);
        }
    }

    private static Stream<Arguments> malformedConfiguredControlItems() {
        List<NamedMarkerAttempt> attempts = new ArrayList<>();
        attempts.add(new NamedMarkerAttempt("type only", markerData("fire", null, null)));
        attempts.add(new NamedMarkerAttempt("owner only", markerData(null, OWNER.toString(), null)));
        attempts.add(new NamedMarkerAttempt("ride only", markerData(null, null, RIDE.toString())));
        attempts.add(new NamedMarkerAttempt("type and owner", markerData("fire", OWNER.toString(), null)));
        attempts.add(new NamedMarkerAttempt("type and ride", markerData("fire", null, RIDE.toString())));
        attempts.add(new NamedMarkerAttempt("owner and ride", markerData(null, OWNER.toString(), RIDE.toString())));
        attempts.add(new NamedMarkerAttempt("unknown type",
                markerData("unknown", OWNER.toString(), RIDE.toString())));
        attempts.add(new NamedMarkerAttempt("malformed owner UUID",
                markerData("fire", "not-a-uuid", RIDE.toString())));
        attempts.add(new NamedMarkerAttempt("malformed ride UUID",
                markerData("fire", OWNER.toString(), "not-a-uuid")));
        for (String key : List.of("happy-artillery:control_type", "happy-artillery:control_owner",
                "happy-artillery:control_ride")) {
            CompoundTag wrongType = markerData("fire", OWNER.toString(), RIDE.toString());
            wrongType.putInt(key, 7);
            attempts.add(new NamedMarkerAttempt("wrong NBT type for " + key, wrongType));
        }
        return attempts.stream().flatMap(attempt -> Stream.of(
                Arguments.of(attempt.name(), configuredAttempt(Items.FIRE_CHARGE, attempt.data())),
                Arguments.of(attempt.name(), configuredAttempt(Items.GHAST_TEAR, attempt.data()))));
    }

    private static ItemStack configuredAttempt(net.minecraft.world.item.Item item, CompoundTag data) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        return stack;
    }

    private record NamedMarkerAttempt(String name, CompoundTag data) {}

    private static final class TestItemEntity extends ItemEntity {
        private boolean discarded;
        private TestItemEntity() { super((Level) null, 0.0, 0.0, 0.0, ItemStack.EMPTY); }
        @Override public void remove(Entity.RemovalReason reason) { discarded = true; }
    }

    private static final class TestPlayer extends Player {
        private UUID id;
        private TestPlayer() { super(null, new GameProfile(UUID.randomUUID(), "unused")); }
        @Override public UUID getUUID() { return id; }
        @Override public GameType gameMode() { return GameType.SURVIVAL; }
    }

    private static final class TestServerPlayer extends ServerPlayer {
        private UUID id;
        private RecordingPlayerInventory inventory;
        private TestServerPlayer() {
            super(null, null, new GameProfile(UUID.randomUUID(), "unused"),
                    ClientInformation.createDefault());
        }
        @Override public UUID getUUID() { return id; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final class RecordingPlayerInventory extends Inventory {
        private final List<Integer> reads = new ArrayList<>();
        private final List<Integer> writes = new ArrayList<>();

        private RecordingPlayerInventory(Player player) {
            super(player, new EntityEquipment());
        }
        void clearObservations() { reads.clear(); writes.clear(); }
        ItemStack peek(int slot) { return super.getItem(slot); }
        List<Integer> readSlots() { return List.copyOf(reads); }
        List<Integer> writeSlots() { return List.copyOf(writes); }
        @Override public ItemStack getItem(int slot) {
            reads.add(slot);
            return super.getItem(slot);
        }
        @Override public void setItem(int slot, ItemStack stack) {
            writes.add(slot);
            super.setItem(slot, stack);
        }
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        return annotations == null ? null : annotations.stream()
                .filter(value -> value.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        assertNotNull(annotation);
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(name)) return annotation.values.get(index + 1);
        }
        throw new AssertionError("Missing annotation value " + name);
    }

    private static MethodNode injectedHandler(ClassNode mixin) {
        return mixin.methods.stream().filter(method -> annotation(method.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;") != null).findFirst().orElseThrow();
    }

    private static MethodNode method(ClassNode owner, String name) {
        return owner.methods.stream().filter(candidate -> candidate.name.equals(name))
                .findFirst().orElseThrow();
    }

    private static void assertCalls(MethodNode method, String owner, String name) {
        assertTrue(Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.owner.equals(owner) && call.name.equals(name)),
                () -> method.name + " does not call " + owner + "." + name);
    }

    private static void assertSingleControlsCall(MethodNode method, String name, String descriptor) {
        List<MethodInsnNode> calls = Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls") && call.name.equals(name)
                && call.desc.equals(descriptor)).count());
        assertEquals(0, calls.stream().filter(call -> call.owner.startsWith(
                "xyz/pyrehaven/happyartillery/")
                && !call.owner.equals("xyz/pyrehaven/happyartillery/Controls")).count());
    }

    private static final class RecordingInventory implements Controls.InventoryAccess<RecordingInventory> {
        private final ItemStack[] stacks = new ItemStack[41];
        private final List<Integer> reads = new ArrayList<>();
        private final List<Integer> writes = new ArrayList<>();
        private final List<Component> messages = new ArrayList<>();

        private RecordingInventory(boolean filled) {
            Arrays.setAll(stacks, ignored -> filled ? new ItemStack(Items.STONE) : ItemStack.EMPTY);
        }
        static RecordingInventory filled() { return new RecordingInventory(true); }
        static RecordingInventory empty() { return new RecordingInventory(false); }
        void seed(int slot, ItemStack stack) { stacks[slot] = stack.copy(); }
        ItemStack peek(int slot) { return stacks[slot].copy(); }
        List<Integer> readSlots() { return List.copyOf(reads); }
        List<Integer> writeSlots() { return List.copyOf(writes); }
        void clearWrites() { writes.clear(); }
        List<ItemStack> copyStacks() { return Arrays.stream(stacks).map(ItemStack::copy).toList(); }
        @Override public ItemStack read(RecordingInventory inventory, int slot) {
            reads.add(slot); return stacks[slot].copy();
        }
        @Override public void write(RecordingInventory inventory, int slot, ItemStack stack) {
            writes.add(slot); stacks[slot] = stack.copy();
        }
        @Override public UUID ownerId(RecordingInventory inventory) { return OWNER; }
        @Override public void message(RecordingInventory inventory, Component message) { messages.add(message); }
    }

    private static final class TestPilot implements Controls.ControlAccess<TestPilot, UUID> {
        private ItemStack main = ItemStack.EMPTY;
        private ItemStack offhand = ItemStack.EMPTY;
        private boolean riding = true;
        private boolean controllingFirstPassenger = true;
        private Controls.ObservedUse observed = new Controls.ObservedUse(
                false, InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        static TestPilot riding() { return new TestPilot(); }
        @Override public Optional<UUID> riddenHappyGhast(TestPilot player) {
            return riding ? Optional.of(RIDE) : Optional.empty();
        }
        @Override public boolean isControllingFirstPassenger(TestPilot player, UUID ghast) {
            return controllingFirstPassenger;
        }
        @Override public UUID ghastId(UUID ghast) { return ghast; }
        @Override public UUID playerId(TestPilot player) { return OWNER; }
        @Override public ItemStack itemInHand(TestPilot player, InteractionHand hand) {
            return (hand == InteractionHand.MAIN_HAND ? main : offhand).copy();
        }
        @Override public Controls.ObservedUse observedUse(TestPilot player) {
            return observed;
        }
    }
}

final class BytecodeTestSupport {
    private BytecodeTestSupport() {}
    static ClassNode classNode(String className) throws IOException {
        String resource = "/" + className.replace('.', '/') + ".class";
        try (InputStream input = BytecodeTestSupport.class.getResourceAsStream(resource)) {
            assertNotNull(input, "missing compiled class " + className);
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }
}
