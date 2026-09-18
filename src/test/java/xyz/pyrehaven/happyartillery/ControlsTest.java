package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.BundleContents;

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
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
        HolderLookup.Provider registries = VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(initializer -> initializer.apply());
    }

    @Test
    void markedControlBlocksEveryVanillaUseWhileOrdinaryItemsPass() {
        for (ItemStack control : List.of(fireControl(OWNER, RIDE), cryControl(OWNER, RIDE))) {
            assertEquals(InteractionResult.FAIL, Controls.vanillaUseResult(control, false));
        }
        assertEquals(InteractionResult.PASS,
                Controls.vanillaUseResult(new ItemStack(Items.FIRE_CHARGE), false));
        assertEquals(InteractionResult.FAIL,
                Controls.vanillaUseResult(new ItemStack(Items.FIRE_CHARGE), true));
    }

    @Test
    void markedArrowAndRocketControlsAreNeverSelectableAsAmmunition() {
        for (ItemStack control : List.of(
                markedControl(Items.ARROW, Components.Control.FIRE, OWNER, RIDE),
                markedControl(Items.FIREWORK_ROCKET, Components.Control.CRY, OWNER, RIDE))) {
            assertFalse(Controls.allowsProjectileSelection(control, ignored -> true));
        }
        assertTrue(Controls.allowsProjectileSelection(
                new ItemStack(Items.ARROW), stack -> stack.is(Items.ARROW)));
        assertFalse(Controls.allowsProjectileSelection(
                new ItemStack(Items.DIAMOND), stack -> stack.is(Items.ARROW)));
    }

    @Test
    void blockUseStartsMarkedHoldControlAndAdmitsItsFirstShot() {
        TestPilot pilot = TestPilot.riding();
        pilot.main = fireControl(OWNER, RIDE);
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());

        Controls.Admission admission = Controls.handleUseBlock(
                pilot, InteractionHand.MAIN_HAND, state, 11L,
                Config.current().controls(), pilot);

        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Accepted.class, admission).intent());
        assertEquals(InteractionHand.MAIN_HAND, pilot.startedHand);
    }

    @Test
    void itemUseStartsMarkedHoldControlAndAdmitsItsFirstShotWithoutVanillaUse() {
        TestPilot pilot = TestPilot.riding();
        pilot.main = fireControl(OWNER, RIDE);
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());

        Controls.Admission admission = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 11L,
                Config.current().controls(), pilot);

        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Accepted.class, admission).intent());
        assertEquals(InteractionHand.MAIN_HAND, pilot.startedHand);
        assertEquals(InteractionResult.FAIL,
                Controls.vanillaUseResult(pilot.main, admission.handled()));
    }

    @Test
    void blockUseAdmitsAllowedPlainFireWithoutStartingGeneratedHoldState() {
        TestPilot pilot = TestPilot.riding();
        pilot.main = new ItemStack(Items.FIRE_CHARGE);
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        Config.Controls settings = new Config.Controls(
                "minecraft:fire_charge", "minecraft:ghast_tear", true, true);

        Controls.Admission admission = Controls.handleUseBlock(
                pilot, InteractionHand.MAIN_HAND, state, 11L, settings, pilot);

        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Accepted.class, admission).intent());
        assertSame(null, pilot.startedHand);
    }

    @Test
    void sameTickPlainBlockUseRemainsHandledWithoutASecondShot() {
        TestPilot pilot = TestPilot.riding();
        ItemStack plainFire = new ItemStack(Items.FIRE_CHARGE);
        pilot.main = plainFire.copy();
        RiderState state = new RiderState(Optional.of(RIDE), 11L, Optional.empty());
        Config.Controls settings = new Config.Controls(
                "minecraft:fire_charge", "minecraft:ghast_tear", true, true);

        Controls.Admission admission = Controls.handleUseBlock(
                pilot, InteractionHand.MAIN_HAND, state, 11L, settings, pilot);

        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Deduplicated.class, admission).intent());
        assertTrue(admission.handled());
        assertEquals(InteractionResult.FAIL,
                Controls.vanillaUseResult(plainFire, admission.handled()));
        assertSame(state, admission.state());
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
                fireControl(OWNER, RIDE), cryControl(OWNER, RIDE))) {
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
        RecordingInventory firstInventory = RecordingInventory.empty();
        RecordingInventory secondInventory = RecordingInventory.empty();
        Controls.reconcile(firstInventory, RiderState.fresh(), Optional.of(RIDE), firstInventory);
        Controls.reconcile(secondInventory, RiderState.fresh(), Optional.of(RIDE), secondInventory);
        ItemStack first = firstInventory.peek(0);
        ItemStack cry = firstInventory.peek(1);
        ItemStack second = secondInventory.peek(0);
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
        assertFalse(cry.has(DataComponents.CONSUMABLE));
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

            RecordingInventory inventory = RecordingInventory.empty();
            Controls.reconcile(inventory, RiderState.fresh(), Optional.of(RIDE), inventory);
            ItemStack first = inventory.peek(0);
            ItemStack cry = inventory.peek(1);
            ItemStack second = first.copy();
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
    void oneEnabledAbilityAllocatesItsOnlyControlWithOneFreeSlot(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        RecordingInventory inventory = RecordingInventory.filled();
        inventory.seed(7, ItemStack.EMPTY);
        try {
            Files.writeString(file, "{\"fire\":{\"enabled\":false}}");
            Config.reload(file);

            RiderState mounted = Controls.reconcile(
                    inventory, RiderState.fresh(), Optional.of(RIDE), inventory);

            assertEquals(Optional.of(RIDE), mounted.riddenGhastId());
            assertEquals(List.of(7), inventory.writeSlots());
            assertEquals(Components.Control.CRY,
                    Components.marker(inventory.peek(7)).marker().orElseThrow().control());
            assertTrue(inventory.messages.isEmpty());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void bothDisabledCreatesNoControlsAndSendsNoRefusal(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        RecordingInventory inventory = RecordingInventory.filled();
        try {
            Files.writeString(file, "{\"fire\":{\"enabled\":false},\"cry\":{\"enabled\":false}}");
            Config.reload(file);

            RiderState mounted = Controls.reconcile(
                    inventory, RiderState.fresh(), Optional.of(RIDE), inventory);

            assertEquals(Optional.of(RIDE), mounted.riddenGhastId());
            assertTrue(inventory.writeSlots().isEmpty());
            assertTrue(inventory.messages.isEmpty());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void oneEnabledAbilityRefusesAtomicallyWithExactSingularGrammar(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        RecordingInventory inventory = RecordingInventory.filled();
        try {
            Files.writeString(file, "{\"cry\":{\"enabled\":false}}");
            Config.reload(file);

            Controls.reconcile(inventory, RiderState.fresh(), Optional.of(RIDE), inventory);

            assertTrue(inventory.writeSlots().isEmpty());
            assertEquals(1, inventory.messages.size());
            Component refusal = inventory.messages.getFirst();
            assertEquals("Control needs 1 free slot.", refusal.getString());
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), refusal.getStyle().getColor());
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
                Optional.of(new RiderState.HudCache(Double.NaN, "PURPLE", "bad")));

        Controls.InvalidRiderState failure = assertThrows(
                Controls.InvalidRiderState.class,
                () -> Controls.reconcile(inventory, invalid, Optional.of(RIDE), inventory));

        assertEquals("invalid persisted HUD cache", failure.getMessage());
        assertTrue(inventory.writeSlots().isEmpty());
    }

    @Test
    void invalidStateRecoveryWithoutTrustedRideRemovesEveryValidOwnedControlInOneBoundedScan()
            throws Exception {
        UUID otherRide = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID foreignOwner = UUID.fromString("44444444-4444-4444-8444-444444444444");
        RecordingInventory player = RecordingInventory.empty();
        ItemStack plainConfigured = new ItemStack(Items.FIRE_CHARGE);
        CompoundTag unrelated = new CompoundTag();
        unrelated.putString("another-mod:owner", "kept");
        plainConfigured.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelated));
        ItemStack malformed = configuredAttempt(Items.GHAST_TEAR,
                markerData("cry", OWNER.toString(), null));
        ItemStack foreign = fireControl(foreignOwner, RIDE);
        player.seed(4, fireControl(OWNER, RIDE));
        player.seed(40, cryControl(OWNER, otherRide));
        player.seed(9, plainConfigured);
        player.seed(10, malformed);
        player.seed(11, foreign);
        ItemStack plainBefore = plainConfigured.copy();
        ItemStack malformedBefore = malformed.copy();
        ItemStack foreignBefore = foreign.copy();

        RiderState recovered = Controls.recoverInvalidState(player, OWNER, player);

        assertEquals(RiderState.fresh(), recovered);
        assertTrue(player.peek(4).isEmpty());
        assertTrue(player.peek(40).isEmpty());
        assertTrue(ItemStack.matches(plainBefore, player.peek(9)));
        assertTrue(ItemStack.matches(malformedBefore, player.peek(10)));
        assertTrue(ItemStack.matches(foreignBefore, player.peek(11)));
        assertEquals(Stream.concat(java.util.stream.IntStream.rangeClosed(0, 35).boxed(), Stream.of(40)).toList(),
                player.readSlots());
        assertEquals(List.of(4, 40), player.writeSlots());
    }

    @Test
    void heldAdmissionConsumesTheSharedSnapshotAndCannotAcceptMissingGeneratedControl() {
        TestPilot pilot = TestPilot.riding();
        ItemStack fire = fireControl(OWNER, RIDE);
        pilot.activeUse = fire.copy();
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        Controls.InventorySnapshot missing = new Controls.InventorySnapshot(
                Controls.ControlLocation.MISSING,
                Controls.ControlLocation.HAND_ACCESSIBLE, 0);

        Controls.Admission admission = Controls.sampleHeld(
                pilot, state, 11L, Config.current().controls(), missing, pilot);

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
        inventory.seed(8, fireControl(OWNER, RIDE));
        inventory.seed(21, cryControl(OWNER, RIDE));
        inventory.seed(30, fireControl(UUID.randomUUID(), RIDE));
        inventory.seed(40, cryControl(OWNER, UUID.randomUUID()));

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
            inventory.seed(handAccessible, fireControl(OWNER, RIDE));
            inventory.seed(9, cryControl(OWNER, RIDE));

            Controls.InventorySnapshot snapshot = Controls.snapshot(inventory, OWNER, RIDE, inventory);

            assertEquals(Controls.ControlLocation.HAND_ACCESSIBLE, snapshot.fire(), "slot " + handAccessible);
            assertEquals(Controls.ControlLocation.MAIN_INVENTORY_ONLY, snapshot.cry());
        }
    }

    @Test
    void matchingOwnerRideControlsAuthorizeFromActualMainHandAndOffhand() {
        TestPilot pilot = TestPilot.riding();
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        pilot.main = cryControl(OWNER, RIDE);
        pilot.offhand = cryControl(OWNER, RIDE);

        Controls.Admission mainAccepted = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 11L, Config.current().controls(), pilot);
        Controls.Admission offhandAccepted = Controls.handleUseItem(
                pilot, InteractionHand.OFF_HAND, state, 12L, Config.current().controls(), pilot);

        assertEquals(Controls.ControlIntent.CRY,
                assertInstanceOf(Controls.Accepted.class, mainAccepted).intent());
        assertEquals(Controls.ControlIntent.CRY,
                assertInstanceOf(Controls.Accepted.class, offhandAccepted).intent());
        assertEquals(11L, mainAccepted.state().lastHandledTick());
        assertEquals(12L, offhandAccepted.state().lastHandledTick());
    }

    @Test
    void disabledFireRejectsMarkedAndPlainConfiguredFireInputs(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        try {
            Files.writeString(file, """
                    {"controls":{"holdToFire":false,"allowPlainItems":true},
                     "fire":{"enabled":false}}
                    """);
            Config.reload(file);
            RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());

            for (ItemStack input : List.of(
                    fireControl(OWNER, RIDE), new ItemStack(Items.FIRE_CHARGE))) {
                TestPilot pilot = TestPilot.riding();
                pilot.main = input;

                Controls.Admission admission = Controls.handleUseItem(
                        pilot, InteractionHand.MAIN_HAND, state, 11L,
                        Config.current().controls(), pilot);

                assertInstanceOf(Controls.Ignored.class, admission);
                assertSame(state, admission.state());
            }
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void disabledCryRejectsMarkedAndPlainConfiguredCryInputs(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        try {
            Files.writeString(file, """
                    {"controls":{"allowPlainItems":true},
                     "cry":{"enabled":false}}
                    """);
            Config.reload(file);
            RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());

            for (ItemStack input : List.of(
                    cryControl(OWNER, RIDE), new ItemStack(Items.GHAST_TEAR))) {
                TestPilot pilot = TestPilot.riding();
                pilot.main = input;

                Controls.Admission admission = Controls.handleUseItem(
                        pilot, InteractionHand.MAIN_HAND, state, 11L,
                        Config.current().controls(), pilot);

                assertInstanceOf(Controls.Ignored.class, admission);
                assertSame(state, admission.state());
            }
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void riderAndTargetGatesSpendNoTickBeforeLaterValidInput() {
        RiderState state = new RiderState(Optional.of(RIDE), 20L, Optional.empty());
        TestPilot pilot = TestPilot.riding();
        pilot.main = cryControl(OWNER, RIDE);

        pilot.riding = false;
        Controls.Admission noRider = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 21L, Config.current().controls(), pilot);
        pilot.riding = true;
        pilot.controllingFirstPassenger = false;
        Controls.Admission nonPilot = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, state, 21L, Config.current().controls(), pilot);
        pilot.controllingFirstPassenger = true;
        Controls.Admission wrongTarget = Controls.handleUseEntity(
                pilot, UUID.randomUUID(), InteractionHand.MAIN_HAND, state, 21L,
                Config.current().controls(), pilot);
        Controls.Admission accepted = Controls.handleUseEntity(
                pilot, RIDE, InteractionHand.MAIN_HAND, state, 21L,
                Config.current().controls(), pilot);

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
        ItemStack fire = fireControl(OWNER, RIDE);
        pilot.activeUse = fire.copy();
        pilot.main = cryControl(OWNER, RIDE);
        RiderState state = new RiderState(Optional.of(RIDE), 30L, Optional.empty());

        Controls.InventorySnapshot present = new Controls.InventorySnapshot(
                Controls.ControlLocation.HAND_ACCESSIBLE,
                Controls.ControlLocation.HAND_ACCESSIBLE, 0);
        Controls.Admission held = Controls.sampleHeld(
                pilot, state, 31L, Config.current().controls(), present, pilot);
        Controls.Admission callbackSameTick = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND, held.state(), 31L,
                Config.current().controls(), pilot);
        Controls.Admission heldNextTick = Controls.sampleHeld(
                pilot, held.state(), 32L, Config.current().controls(), present, pilot);

        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Accepted.class, held).intent());
        assertEquals(Controls.ControlIntent.CRY,
                assertInstanceOf(Controls.Deduplicated.class, callbackSameTick).intent());
        assertSame(held.state(), callbackSameTick.state());
        assertEquals(Controls.ControlIntent.FIRE,
                assertInstanceOf(Controls.Accepted.class, heldNextTick).intent());
    }

    @Test
    void foreignAndPriorRideControlsNeverAuthorize() {
        TestPilot pilot = TestPilot.riding();
        RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());
        for (ItemStack denied : List.of(
                cryControl(UUID.randomUUID(), RIDE),
                cryControl(OWNER, UUID.randomUUID()))) {
            pilot.main = denied;
            assertInstanceOf(Controls.Ignored.class, Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND, state, 11L,
                    Config.current().controls(), pilot));
        }
    }

    @Test
    void plainFireRightClickIsAcceptedEvenWhenGeneratedControlsUseHoldToFire(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        try {
            Files.writeString(file, """
                    {"controls":{"holdToFire":true,"allowPlainItems":true}}
                    """);
            Config.reload(file);
            TestPilot pilot = TestPilot.riding();
            pilot.main = new ItemStack(Items.FIRE_CHARGE);
            RiderState state = new RiderState(Optional.of(RIDE), 10L, Optional.empty());

            Controls.Admission admission = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND, state, 11L,
                    Config.current().controls(), pilot);

            assertEquals(Controls.ControlIntent.FIRE,
                    assertInstanceOf(Controls.Accepted.class, admission).intent());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
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
        inventory.seed(2, fireControl(OWNER, RIDE));

        Controls.removeMatching(inventory, OWNER, RIDE, inventory);

        assertTrue(ItemStack.matches(malformed, inventory.peek(1)));
        assertTrue(inventory.peek(2).isEmpty());
        assertEquals(List.of(2), inventory.writeSlots());
    }

    @Test
    void dismountCleanupConsumesMatchingCursorAndCraftingControlsOnly() {
        RecordingInventory inventory = RecordingInventory.empty();
        ItemStack matchingCursor = fireControl(OWNER, RIDE);
        ItemStack matchingCrafting = cryControl(OWNER, RIDE);
        ItemStack foreignCrafting = fireControl(UUID.randomUUID(), RIDE);
        inventory.transientStacks.add(matchingCursor.copy());
        inventory.transientStacks.add(matchingCrafting.copy());
        inventory.transientStacks.add(foreignCrafting.copy());
        inventory.transientStacks.add(new ItemStack(Items.FIRE_CHARGE));

        Controls.removeMatching(inventory, OWNER, RIDE, inventory);

        assertTrue(inventory.transientStacks.get(0).isEmpty());
        assertTrue(inventory.transientStacks.get(1).isEmpty());
        assertTrue(ItemStack.matches(foreignCrafting, inventory.transientStacks.get(2)));
        assertTrue(inventory.transientStacks.get(3).is(Items.FIRE_CHARGE));
    }

    @Test
    void externalMutationPreservesOnlyOwningPlayerStorageAndOffhandSlots() {
        ItemStack control = fireControl(OWNER, RIDE);
        ItemStack ordinary = new ItemStack(Items.DIAMOND);

        for (int slot : List.of(0, 8, 9, 35, 40)) {
            assertFalse(Controls.shouldConsumeExternalControl(control, OWNER, slot));
        }
        for (int slot : List.of(36, 37, 38, 39)) {
            assertTrue(Controls.shouldConsumeExternalControl(control, OWNER, slot));
        }
        assertTrue(Controls.shouldConsumeExternalControl(control, UUID.randomUUID(), 0));
        assertTrue(Controls.shouldConsumeExternalControl(control, null, -1));
        assertFalse(Controls.shouldConsumeExternalControl(ordinary, null, -1));
        assertFalse(control.isEmpty());
        assertFalse(ordinary.isEmpty());
    }

    @Test
    void markerPreflightAvoidsCustomDataCopyForEmptyAndOrdinaryStacks() {
        java.util.concurrent.atomic.AtomicInteger copies = new java.util.concurrent.atomic.AtomicInteger();
        Components.CustomDataReader reader = customData -> {
            copies.incrementAndGet();
            return customData.copyTag();
        };

        assertSame(Components.Absent.INSTANCE, Components.marker(ItemStack.EMPTY, reader));
        assertSame(Components.Absent.INSTANCE,
                Components.marker(new ItemStack(Items.DIAMOND), reader));
        assertEquals(0, copies.get());

        assertTrue(Components.marker(fireControl(OWNER, RIDE), reader)
                instanceof Components.Valid);
        assertEquals(1, copies.get());
    }

    @Test
    void productionDropConsumptionUsesReturnedEntityAndPassesOrdinaryDrops() {
        RecordingDrop markedEntity = new RecordingDrop();
        RecordingDrop ordinaryEntity = new RecordingDrop();

        Controls.consumeDroppedControl(
                fireControl(OWNER, RIDE), markedEntity, RecordingDrop::discard);
        Controls.consumeDroppedControl(
                new ItemStack(Items.DIAMOND), ordinaryEntity, RecordingDrop::discard);

        assertTrue(markedEntity.discarded);
        assertFalse(ordinaryEntity.discarded);
    }

    @Test
    void appliedSlotMixinConsumesControlsBeforeContainerMutation() {
        for (SimpleContainer container : List.of(new SimpleContainer(1),
                new net.minecraft.world.inventory.PlayerEnderChestContainer())) {
            for (int index = 0; index < container.getContainerSize(); index++) {
                Slot slot = new Slot(container, index, 0, 0);
                for (ItemStack control : List.of(fireControl(OWNER, RIDE), cryControl(OWNER, RIDE))) {
                    slot.set(control.copy());
                    assertTrue(container.getItem(index).isEmpty());
                    assertTrue(slot.safeInsert(control.copy()).isEmpty());
                    assertTrue(container.getItem(index).isEmpty());
                }
                ItemStack ordinary = new ItemStack(Items.FIRE_CHARGE);
                slot.set(ordinary);
                assertSame(ordinary, container.getItem(index));
            }
        }
    }

    @Test
    void appliedSlotMixinConsumesControlsInEveryVanillaMenuBlockContainer() {
        Set<String> checked = new TreeSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof EntityBlock factory)) {
                continue;
            }
            var entity = factory.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
            if (!(entity instanceof Container container)
                    || !(entity instanceof MenuProvider)) {
                continue;
            }
            String name = BuiltInRegistries.BLOCK.getKey(block).toString();
            for (int index = 0; index < container.getContainerSize(); index++) {
                Slot slot = new Slot(container, index, 0, 0);
                for (ItemStack control : List.of(fireControl(OWNER, RIDE), cryControl(OWNER, RIDE))) {
                    slot.setByPlayer(control.copy());
                    assertTrue(container.getItem(index).isEmpty(), name + " slot " + index);
                }
                ItemStack ordinary = new ItemStack(Items.DIAMOND);
                assertSame(ordinary, Controls.transformExternalControlWrite(slot, ordinary),
                        name + " ordinary item left to vanilla validation");
            }
            checked.add(name);
        }
        assertTrue(checked.containsAll(List.of("minecraft:chest", "minecraft:trapped_chest",
                "minecraft:barrel", "minecraft:shulker_box", "minecraft:furnace",
                "minecraft:blast_furnace", "minecraft:smoker", "minecraft:brewing_stand",
                "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper",
                "minecraft:crafter")), checked.toString());
        System.out.println("Control containment block-container matrix: " + checked);
    }

    @Test
    void bundlesRejectBothControlsWithoutConsumingThemAndStillAcceptOrdinaryItems() {
        assertFalse(BundleContents.canItemBeInBundle(ItemStack.EMPTY));
        assertFalse(BundleContents.canItemBeInBundle(new ItemStack(Items.SHULKER_BOX)));
        for (ItemStack control : List.of(fireControl(OWNER, RIDE), cryControl(OWNER, RIDE))) {
            var bundle = new BundleContents.Mutable();
            assertFalse(BundleContents.canItemBeInBundle(control));
            assertEquals(0, bundle.tryInsert(control));
            assertEquals(1, control.getCount());
            assertTrue(bundle.toImmutable().isEmpty());
            SimpleContainer source = new SimpleContainer(control.copy());
            assertEquals(0, bundle.tryTransfer(new Slot(source, 0, 0, 0), null));
            assertTrue(ItemStack.matches(control, source.getItem(0)));
            assertTrue(bundle.toImmutable().isEmpty());
            ItemStack ordinary = new ItemStack(control.getItem());
            assertEquals(1, bundle.tryInsert(ordinary));
            assertTrue(ordinary.isEmpty());
        }
    }

    @Test
    void creativeSlotWritesStripControlsForgedInsideBundlesAndKeepOrdinaryContents() {
        ItemStack control = fireControl(OWNER, RIDE);
        ItemStack firstOrdinary = new ItemStack(Items.DIAMOND, 3);
        ItemStack lastOrdinary = new ItemStack(Items.EMERALD, 2);
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(
                ItemStackTemplate.fromNonEmptyStack(firstOrdinary),
                ItemStackTemplate.fromNonEmptyStack(control),
                ItemStackTemplate.fromNonEmptyStack(lastOrdinary))));
        Slot destination = new Slot(new SimpleContainer(1), 0, 0, 0);

        destination.setByPlayer(bundle);

        BundleContents stored = destination.getItem().get(DataComponents.BUNDLE_CONTENTS);
        assertNotNull(stored);
        List<ItemStack> contents = stored.itemCopies().toList();
        assertEquals(2, contents.size());
        assertTrue(ItemStack.matches(firstOrdinary, contents.get(0)));
        assertTrue(ItemStack.matches(lastOrdinary, contents.get(1)));
    }

    @Test
    void slotWriteTransformConsumesActualExternalDestinationsWithoutNestedMutation() {
        ItemStack mergedDestination = fireControl(OWNER, RIDE);
        mergedDestination.setCount(2);
        SimpleContainer chest = new SimpleContainer(1);
        Slot destination = new Slot(chest, 0, 0, 0);

        ItemStack transformed = Controls.transformExternalControlWrite(destination, mergedDestination);
        destination.set(transformed);

        assertSame(ItemStack.EMPTY, destination.getItem());
        assertEquals(2, mergedDestination.getCount());
        assertTrue(chest.getItem(0).isEmpty());

        ItemStack ordinary = new ItemStack(Items.DIAMOND, 3);
        destination.set(Controls.transformExternalControlWrite(destination, ordinary));
        assertSame(ordinary, destination.getItem());
        assertEquals(3, destination.getItem().getCount());
    }

    @Test
    void recipeBookExcludesGeneratedControlsButNotTheirOrdinaryIngredients() {
        RecordingInventory inventory = RecordingInventory.empty();
        Controls.reconcile(inventory, RiderState.fresh(), Optional.of(RIDE), inventory);
        for (int slot : List.of(0, 1)) {
            ItemStack control = inventory.read(inventory, slot);
            assertTrue(Components.marker(control).marker().isPresent());
            assertFalse(net.minecraft.world.entity.player.Inventory.isUsableForCrafting(control));
            assertTrue(net.minecraft.world.entity.player.Inventory.isUsableForCrafting(
                    new ItemStack(control.getItem())));
        }
    }

    @Test
    void appliedSlotMixinConsumesBothControlsInInventoryAndTableCrafting() {
        AbstractContainerMenu menu = new AbstractContainerMenu(null, 0) {
            @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player player) { return true; }
        };
        for (int width : List.of(2, 3)) {
            TransientCraftingContainer crafting = new TransientCraftingContainer(menu, width, width);
            for (int index = 0; index < width * width; index++) {
                Slot input = new Slot(crafting, index, 0, 0);
                for (ItemStack control : List.of(fireControl(OWNER, RIDE), cryControl(OWNER, RIDE))) {
                    input.setByPlayer(control.copy());
                    assertTrue(crafting.getItem(index).isEmpty());
                    assertTrue(input.safeInsert(control.copy()).isEmpty());
                    assertTrue(crafting.getItem(index).isEmpty());
                }
                ItemStack ordinary = new ItemStack(Items.FIRE_CHARGE);
                input.setByPlayer(ordinary);
                assertSame(ordinary, crafting.getItem(index));
                input.set(ItemStack.EMPTY);
            }
        }
    }

    @Test
    void productionCleanupCoversDistinctActiveAndInventoryMenuCursorsAndCraftingGrids()
            throws Exception {
        ClassNode access = BytecodeTestSupport.classNode(
                Controls.class.getName() + "$ServerPlayerInventoryAccess");
        MethodNode remove = access.methods.stream()
                .filter(candidate -> candidate.name.equals("removeTransient")
                        && candidate.desc.startsWith("(Lnet/minecraft/server/level/ServerPlayer;"))
                .findFirst().orElseThrow();
        List<FieldInsnNode> fields = Stream.iterate(
                        remove.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast).toList();
        List<MethodInsnNode> removeCalls = Stream.iterate(
                        remove.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();

        assertTrue(fields.stream().anyMatch(field -> field.name.equals("containerMenu")));
        assertTrue(fields.stream().anyMatch(field -> field.name.equals("inventoryMenu")));
        assertEquals(2, removeCalls.stream()
                .filter(call -> call.name.equals("removeTransientFromMenu")).count());

        MethodNode menuCleanup = method(access, "removeTransientFromMenu");
        List<MethodInsnNode> menuCalls = Stream.iterate(
                        menuCleanup.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
        for (String requiredCall : List.of(
                "getCarried", "setCarried", "getInputGridSlots", "getItem", "set")) {
            assertTrue(menuCalls.stream().anyMatch(call -> call.name.equals(requiredCall)), requiredCall);
        }
    }

    @Test
    void activeUseItemIsOneDirectDefensiveProductionBoundary() throws Exception {
        ClassNode controls = BytecodeTestSupport.classNode(Controls.class.getName());
        assertFalse(controls.innerClasses.stream().anyMatch(inner ->
                inner.name.endsWith("$ObservedUse") || inner.name.endsWith("$UseObservation")));

        ClassNode access = BytecodeTestSupport.classNode(
                Controls.class.getName() + "$ServerPlayerControlAccess");
        MethodNode active = access.methods.stream()
                .filter(method -> method.name.equals("activeUseItem"))
                .findFirst().orElseThrow();
        List<MethodInsnNode> calls = Stream.iterate(active.instructions.getFirst(),
                        java.util.Objects::nonNull, org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
        assertEquals(1, calls.stream().filter(call -> call.name.equals("isUsingItem")).count());
        assertEquals(1, calls.stream().filter(call -> call.name.equals("getUseItem")).count());
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "net/minecraft/world/item/ItemStack") && call.name.equals("copy")).count());
        assertEquals(1, Stream.iterate(active.instructions.getFirst(),
                        java.util.Objects::nonNull, org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .filter(field -> field.owner.equals("net/minecraft/world/item/ItemStack")
                        && field.name.equals("EMPTY"))
                .count());
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
        assertEquals(List.of("drop(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)"
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
        MethodNode externalHandler = external.methods.stream().filter(candidate -> annotation(
                candidate.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;") != null)
                .findFirst().orElseThrow();
        AnnotationNode externalTransform = annotation(externalHandler.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        assertEquals(List.of("set(Lnet/minecraft/world/item/ItemStack;)V"),
                annotationValue(externalTransform, "method"));
        assertEquals(1, annotationValue(externalTransform, "require"));
        assertEquals(true, annotationValue(externalTransform, "argsOnly"));
        Object at = annotationValue(externalTransform, "at");
        AnnotationNode externalAt = at instanceof AnnotationNode annotation
                ? annotation
                : (AnnotationNode) ((List<?>) at).getFirst();
        assertEquals("HEAD", annotationValue(externalAt, "value"));
        assertSingleControlsCall(externalHandler, "transformExternalControlWrite",
                "(Lnet/minecraft/world/inventory/Slot;Lnet/minecraft/world/item/ItemStack;)"
                        + "Lnet/minecraft/world/item/ItemStack;");
        assertEquals(0, Stream.iterate(externalHandler.instructions.getFirst(),
                        java.util.Objects::nonNull, org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals("net/minecraft/world/inventory/Slot")
                        && call.name.equals("set")).count());

        assertProjectileSelectionMixin(
                "xyz.pyrehaven.happyartillery.mixin.HeldProjectileMixin",
                Type.getObjectType("net/minecraft/world/item/ProjectileWeaponItem"),
                "getHeldProjectile", 2);
        assertProjectileSelectionMixin(
                "xyz.pyrehaven.happyartillery.mixin.PlayerProjectileMixin",
                Type.getType(Player.class), "getProjectile", 1);
    }

    private static void assertProjectileSelectionMixin(
            String className, Type target, String methodName, int requiredRedirects) throws Exception {
        ClassNode mixin = BytecodeTestSupport.classNode(className);
        assertEquals(List.of(target), annotationValue(
                annotation(mixin.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;"), "value"));
        MethodNode handler = mixin.methods.stream().filter(candidate -> annotation(
                candidate.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Redirect;") != null)
                .findFirst().orElseThrow();
        AnnotationNode redirect = annotation(handler.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        assertEquals(List.of(methodName), annotationValue(redirect, "method"));
        assertEquals(requiredRedirects, annotationValue(redirect, "require"));
        AnnotationNode at = (AnnotationNode)
                ((List<?>) annotationValue(redirect, "at")).getFirst();
        assertEquals("INVOKE", annotationValue(at, "value"));
        assertEquals("Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z",
                annotationValue(at, "target"));
        assertSingleControlsCall(handler, "allowsProjectileSelection",
                "(Lnet/minecraft/world/item/ItemStack;Ljava/util/function/Predicate;)Z");
    }

    @Test
    void allFiveMixinsApplyToMinecraftClassesWithTheCurrentDropSignature() throws Exception {
        var drop = ServerPlayer.class.getDeclaredMethod("drop", ItemStack.class,
                boolean.class, net.minecraft.util.Prediction.class);
        assertEquals(net.minecraft.world.entity.item.ItemEntity.class, drop.getReturnType());
        for (var target : java.util.Map.<Class<?>, String>of(
                ServerPlayer.class, "happyArtillery$consumeMarkedDrop",
                Slot.class, "happyArtillery$transformExternalControlWrite",
                BundleContents.class, "happyArtillery$blockControlInsertion",
                net.minecraft.world.item.ProjectileWeaponItem.class,
                        "happyArtillery$excludeControlFromHeldProjectiles",
                Player.class, "happyArtillery$excludeControlFromInventoryProjectiles").entrySet()) {
            assertTrue(Arrays.stream(target.getKey().getDeclaredMethods())
                            .anyMatch(method -> method.getName().contains(target.getValue())),
                    "missing applied mixin on " + target.getKey().getName());
        }
    }

    @Test
    void mixinMetadataDeclaresExactlyTheFiveNarrowMixins() throws Exception {
        try (InputStream input = ControlsTest.class.getResourceAsStream("/happy-artillery.mixins.json")) {
            assertNotNull(input);
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(metadata.get("required").getAsBoolean());
            assertEquals("JAVA_25", metadata.get("compatibilityLevel").getAsString());
            assertEquals(1, metadata.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            assertEquals(List.of("PlayerDropMixin", "ExternalContainerMixin", "BundleContentsMixin",
                            "HeldProjectileMixin", "PlayerProjectileMixin"),
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

    private static ItemStack fireControl(UUID ownerId, UUID rideId) {
        return markedControl(Items.FIRE_CHARGE, Components.Control.FIRE, ownerId, rideId);
    }

    private static ItemStack cryControl(UUID ownerId, UUID rideId) {
        return markedControl(Items.GHAST_TEAR, Components.Control.CRY, ownerId, rideId);
    }

    private static ItemStack markedControl(
            net.minecraft.world.item.Item item, Components.Control type,
            UUID ownerId, UUID rideId) {
        ItemStack stack = new ItemStack(item);
        Components.mark(stack, new Components.Marker(type, ownerId, rideId));
        return stack;
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

    private static final class RecordingDrop {
        private boolean discarded;
        private void discard() { discarded = true; }
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
        private final List<ItemStack> transientStacks = new ArrayList<>();

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
        @Override public void removeTransient(
                RecordingInventory inventory, java.util.function.Predicate<ItemStack> shouldRemove) {
            for (int index = 0; index < transientStacks.size(); index++) {
                if (shouldRemove.test(transientStacks.get(index).copy())) {
                    transientStacks.set(index, ItemStack.EMPTY);
                }
            }
        }
    }

    private static final class TestPilot implements Controls.ControlAccess<TestPilot, UUID> {
        private ItemStack main = ItemStack.EMPTY;
        private ItemStack offhand = ItemStack.EMPTY;
        private boolean riding = true;
        private boolean controllingFirstPassenger = true;
        private ItemStack activeUse = ItemStack.EMPTY;
        private InteractionHand startedHand;
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
        @Override public ItemStack activeUseItem(TestPilot player) {
            return activeUse.copy();
        }
        @Override public void startUsingItem(TestPilot player, InteractionHand hand) {
            startedHand = hand;
            activeUse = itemInHand(player, hand);
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
