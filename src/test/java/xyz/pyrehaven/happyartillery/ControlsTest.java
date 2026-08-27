package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Unit;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class ControlsTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(initializer -> initializer.apply());
    }

    @Test
    void happyArtilleryRegistersComponentsBeforeTheDeliberateGuard() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new HappyArtillery().onInitialize());

        assertEquals("Happy Artillery structural groundwork is not a playable build",
                failure.getMessage());
        assertSame(Components.FIRE_CONTROL, BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                Identifier.fromNamespaceAndPath("happy-artillery", "fire_control")));
        assertSame(Components.CRY_CONTROL, BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                Identifier.fromNamespaceAndPath("happy-artillery", "cry_control")));
    }

    @Test
    void componentsRegisterExactFireAndCryMarkerIdentities() {
        Components.register();
        DataComponentType<?> fire = Components.FIRE_CONTROL;
        DataComponentType<?> cry = Components.CRY_CONTROL;

        assertSame(fire, BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                Identifier.fromNamespaceAndPath("happy-artillery", "fire_control")));
        assertSame(cry, BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                Identifier.fromNamespaceAndPath("happy-artillery", "cry_control")));
        assertNotSame(fire, cry);
    }

    @Test
    void componentCatalogIsExactlyTwoImmutablePersistentSynchronizedUnitMarkers() {
        Components.register();

        assertEquals(List.of(Components.FIRE_CONTROL, Components.CRY_CONTROL), Components.catalog());
        assertThrows(UnsupportedOperationException.class,
                () -> Components.catalog().add(Components.FIRE_CONTROL));
        for (DataComponentType<Unit> marker : Components.catalog()) {
            assertFalse(marker.isTransient());
            assertSame(Unit.INSTANCE, marker.codecOrThrow()
                    .parse(JsonOps.INSTANCE, marker.codecOrThrow()
                            .encodeStart(JsonOps.INSTANCE, Unit.INSTANCE)
                            .getOrThrow())
                    .getOrThrow());

            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                    Unpooled.buffer(),
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            try {
                marker.streamCodec().encode(buffer, Unit.INSTANCE);
                assertSame(Unit.INSTANCE, marker.streamCodec().decode(buffer));
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    @Order(1)
    void componentRegistrationRetriesPartialStateAndRejectsDifferentIdentity() {
        Identifier fireId = Identifier.fromNamespaceAndPath("happy-artillery", "fire_control");
        Identifier cryId = Identifier.fromNamespaceAndPath("happy-artillery", "cry_control");
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, fireId, Components.FIRE_CONTROL);

        Components.register();
        Components.register();

        assertSame(Components.FIRE_CONTROL, BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                fireId));
        assertSame(Components.CRY_CONTROL, BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(
                cryId));
        DataComponentType<Unit> different = DataComponentType.<Unit>builder()
                .persistent(Unit.CODEC)
                .networkSynchronized(Unit.STREAM_CODEC)
                .build();
        assertThrows(IllegalStateException.class, () -> Components.register(fireId, different));
    }

    @ParameterizedTest(name = "{0} survives persistence and network round trips")
    @MethodSource("controlStacks")
    void markerIdentitySurvivesItemPersistenceAndNetworkRoundTrips(
            String name,
            ItemStack original,
            DataComponentType<Unit> marker,
            DataComponentType<Unit> opposite) {
        Components.register();
        ItemStack persisted = ItemStack.CODEC.parse(
                        JsonOps.INSTANCE,
                        ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow())
                .getOrThrow();

        assertSame(Unit.INSTANCE, persisted.get(marker));
        assertFalse(persisted.has(opposite));
        assertTrue(ItemStack.matches(original, persisted));

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            ItemStack.STREAM_CODEC.encode(buffer, original);
            ItemStack synchronizedStack = ItemStack.STREAM_CODEC.decode(buffer);

            assertSame(Unit.INSTANCE, synchronizedStack.get(marker));
            assertFalse(synchronizedStack.has(opposite));
            assertTrue(ItemStack.matches(original, synchronizedStack));
        } finally {
            buffer.release();
        }
    }

    @Test
    void factoriesCreateFreshNamedGlintingFireAndCryControls() {
        Components.register();

        ItemStack firstFire = Controls.fireControl();
        ItemStack secondFire = Controls.fireControl();
        ItemStack cry = Controls.cryControl();

        assertNotSame(firstFire, secondFire);
        assertTrue(firstFire.is(Items.FIRE_CHARGE));
        assertTrue(cry.is(Items.GHAST_TEAR));
        assertSame(Unit.INSTANCE, firstFire.get(Components.FIRE_CONTROL));
        assertFalse(firstFire.has(Components.CRY_CONTROL));
        assertSame(Unit.INSTANCE, cry.get(Components.CRY_CONTROL));
        assertFalse(cry.has(Components.FIRE_CONTROL));
        assertEquals("Fire Control", firstFire.getHoverName().getString());
        assertEquals("Cry Control", cry.getHoverName().getString());
        assertTrue(firstFire.hasFoil());
        assertTrue(cry.hasFoil());
        assertEquals(firstFire.get(DataComponents.CONSUMABLE), cry.get(DataComponents.CONSUMABLE));
        assertNotNull(firstFire.get(DataComponents.CONSUMABLE));
    }

    @Test
    void factoriesReadEveryValidConfigChangeAtCallTime(@TempDir Path directory) throws Exception {
        Components.register();
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        try {
            Files.writeString(file, """
                    {"controls":{"fireItem":"minecraft:snowball","cryItem":"minecraft:feather"}}
                    """);
            Config.reload(file);

            assertTrue(Controls.fireControl().is(Items.SNOWBALL));
            assertTrue(Controls.cryControl().is(Items.FEATHER));
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void holdControlUsesTheExactLongSilentAnimationFreeConsumableApi() {
        ItemStack control = Controls.fireControl();

        Consumable consumable = control.get(DataComponents.CONSUMABLE);

        assertNotNull(consumable);
        assertEquals(Integer.MAX_VALUE, consumable.consumeTicks());
        assertEquals(ItemUseAnimation.NONE, consumable.animation());
        assertSame(SoundEvents.EMPTY, consumable.sound().value());
        assertFalse(consumable.hasConsumeParticles());
        assertTrue(consumable.onConsumeEffects().isEmpty());
        assertFalse(consumable.shouldEmitParticlesAndSounds(Integer.MAX_VALUE));
    }

    @Test
    void factoriesAndObservedUseDefensivelyOwnTheirStacks() {
        ItemStack control = Controls.fireControl();
        RecordingObservation source = new RecordingObservation(true, InteractionHand.MAIN_HAND, control);

        Controls.ObservedUse observed = Controls.observeUse(source, source);
        control.setCount(7);
        ItemStack firstRead = observed.stack();
        firstRead.setCount(9);

        assertEquals(1, observed.stack().getCount());
        assertNotSame(firstRead, observed.stack());
    }

    @Test
    void holdModeContinuouslyUsesMainAndOffhandServerState() {
        ItemStack control = Controls.fireControl();

        Controls.ObservedUse main = observe(true, InteractionHand.MAIN_HAND, control);
        Controls.ObservedUse off = observe(true, InteractionHand.OFF_HAND, control);

        assertEquals(Controls.FireIntent.HELD,
                Controls.fireIntent(true, Controls.ObservedClick.none(), main));
        assertEquals(InteractionHand.MAIN_HAND, main.hand());
        assertEquals(Controls.FireIntent.HELD,
                Controls.fireIntent(true, Controls.ObservedClick.none(), off));
        assertEquals(InteractionHand.OFF_HAND, off.hand());
    }

    @Test
    void releasingServerUseStateCancelsHoldIntent() {
        ItemStack control = Controls.fireControl();

        Controls.ObservedUse released = observe(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(true, Controls.ObservedClick.of(control), released));
    }

    @Test
    void observedUseRejectsUsingStateWithoutAnObservedHand() {
        ItemStack control = Controls.fireControl();

        assertThrows(NullPointerException.class,
                () -> new Controls.ObservedUse(true, null, control));
    }

    @Test
    void fireIntentRequiresTheFireMarkerNotPlainForgedOrCryIdentity() {
        Components.register();
        ItemStack fire = Controls.fireControl();
        ItemStack cry = Controls.cryControl();
        ItemStack plain = new ItemStack(Items.FIRE_CHARGE);
        ItemStack forgedConsumable = plain.copy();
        forgedConsumable.set(DataComponents.CONSUMABLE, fire.get(DataComponents.CONSUMABLE));
        ItemStack oppositeMarker = forgedConsumable.copy();
        oppositeMarker.set(Components.CRY_CONTROL, Unit.INSTANCE);

        assertEquals(Controls.FireIntent.HELD,
                Controls.fireIntent(true, Controls.ObservedClick.none(),
                        observe(true, InteractionHand.MAIN_HAND, fire)));
        for (ItemStack rejected : List.of(plain, forgedConsumable, oppositeMarker, cry)) {
            assertEquals(Controls.FireIntent.NONE,
                    Controls.fireIntent(true, Controls.ObservedClick.none(),
                            observe(true, InteractionHand.MAIN_HAND, rejected)));
            assertEquals(Controls.FireIntent.NONE,
                    Controls.fireIntent(false, Controls.ObservedClick.of(rejected),
                            observe(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY)));
        }
    }

    @Test
    void bothMarkersRejectHoldAndClick() {
        ItemStack both = Controls.fireControl();
        both.set(Components.CRY_CONTROL, Unit.INSTANCE);

        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(true, Controls.ObservedClick.none(),
                        observe(true, InteractionHand.MAIN_HAND, both)));
        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(false, Controls.ObservedClick.of(both),
                        observe(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY)));
    }

    @Test
    void holdModeRejectsPlainAndOtherConsumableStacks() {
        ItemStack plain = new ItemStack(Items.FIRE_CHARGE);
        ItemStack food = new ItemStack(Items.APPLE);

        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(true, Controls.ObservedClick.none(),
                        observe(true, InteractionHand.MAIN_HAND, plain)));
        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(true, Controls.ObservedClick.none(),
                        observe(true, InteractionHand.OFF_HAND, food)));
    }

    @Test
    void clickModeUsesExactlyTheObservedClickWithoutHoldOrRateInference() {
        ItemStack control = Controls.fireControl();
        Controls.ObservedUse released = observe(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        Controls.ObservedClick click = Controls.ObservedClick.of(control);
        control.setCount(7);

        assertEquals(1, click.stack().getCount());
        assertEquals(Controls.FireIntent.CLICK, Controls.fireIntent(false, click, released));
        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(false, Controls.ObservedClick.none(), released));
        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(false,
                        Controls.ObservedClick.of(new ItemStack(Items.FIRE_CHARGE)), released));
    }

    @Test
    void productionObservationCallsTheThreeExactLivingEntityApis() throws IOException {
        List<MethodReference> calls = methodReferences(Controls.LivingEntityUseObservation.class);
        String owner = "net/minecraft/world/entity/LivingEntity";

        assertTrue(calls.contains(new MethodReference(owner, "isUsingItem", "()Z")));
        assertTrue(calls.contains(new MethodReference(
                owner, "getUsedItemHand", "()Lnet/minecraft/world/InteractionHand;")));
        assertTrue(calls.contains(new MethodReference(
                owner, "getUseItem", "()Lnet/minecraft/world/item/ItemStack;")));
    }

    @Test
    void productionInventoryAdapterBindsExactServerPlayerInventoryApisAndCopies() throws IOException {
        List<MethodReference> calls = methodReferences(Controls.ServerPlayerInventoryAccess.class);
        String player = "net/minecraft/server/level/ServerPlayer";
        String inventory = "net/minecraft/world/entity/player/Inventory";
        String stack = "net/minecraft/world/item/ItemStack";

        assertTrue(calls.contains(new MethodReference(
                player, "getInventory", "()Lnet/minecraft/world/entity/player/Inventory;")));
        assertTrue(calls.contains(new MethodReference(inventory, "getContainerSize", "()I")));
        assertTrue(calls.contains(new MethodReference(
                inventory, "getItem", "(I)Lnet/minecraft/world/item/ItemStack;")));
        assertTrue(calls.contains(new MethodReference(
                inventory, "setItem", "(ILnet/minecraft/world/item/ItemStack;)V")));
        assertTrue(calls.contains(new MethodReference(
                stack, "copy", "()Lnet/minecraft/world/item/ItemStack;")));
    }

    @Test
    void observationReadsEveryBoundaryValueIntoOneImmutableSnapshot() {
        ItemStack control = Controls.fireControl();
        RecordingObservation source = new RecordingObservation(true, InteractionHand.OFF_HAND, control);

        Controls.ObservedUse observed = Controls.observeUse(source, source);

        assertEquals(1, source.usingReads);
        assertEquals(1, source.handReads);
        assertEquals(1, source.stackReads);
        assertTrue(observed.using());
        assertEquals(InteractionHand.OFF_HAND, observed.hand());
        assertTrue(ItemStack.matches(control, observed.stack()));
    }

    @Test
    void mountSnapshotsConfiguredSlotsThenInstallsExactlyTwoFreshControls() {
        RecordingInventory inventory = new RecordingInventory(9);
        ItemStack fireOriginal = new ItemStack(Items.DIAMOND_SWORD);
        fireOriginal.setDamageValue(17);
        fireOriginal.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("original fire"));
        ItemStack cryOriginal = new ItemStack(Items.WRITABLE_BOOK, 7);
        cryOriginal.set(DataComponents.REPAIR_COST, 3);
        inventory.seed(4, fireOriginal);
        inventory.seed(5, cryOriginal);
        RiderState.HudCache hud = new RiderState.HudCache(0.75, "red", "HOT", 81L);
        RiderState before = new RiderState(
                Optional.empty(), Optional.empty(), Optional.empty(), 82L, Optional.of(hud));
        UUID ghastId = UUID.fromString("33635d64-9bac-4a65-b2c7-614f1f982ebe");

        RiderState mounted = Controls.mount(inventory, before, ghastId, inventory);

        assertEquals(List.of("read:4", "read:5", "write:4", "write:5"), inventory.events());
        assertTrue(ItemStack.matches(fireOriginal, mounted.fireStash().orElseThrow().stack()));
        assertTrue(ItemStack.matches(cryOriginal, mounted.cryStash().orElseThrow().stack()));
        assertEquals(4, mounted.fireStash().orElseThrow().slotIndex());
        assertEquals(5, mounted.cryStash().orElseThrow().slotIndex());
        assertEquals(ghastId, mounted.riddenGhastId().orElseThrow());
        assertEquals(82L, mounted.lastHandledTick());
        assertEquals(Optional.of(hud), mounted.hudCache());
        assertTrue(inventory.peek(4).has(Components.FIRE_CONTROL));
        assertTrue(inventory.peek(5).has(Components.CRY_CONTROL));
        assertNotSame(mounted.fireStash().orElseThrow().stack(), fireOriginal);
    }

    @Test
    void restoreWritesBothOriginalStacksBeforeSweepingOnlyMarkedDuplicates() {
        RecordingInventory inventory = new RecordingInventory(9);
        ItemStack fireOriginal = new ItemStack(Items.DIAMOND_SWORD);
        fireOriginal.setDamageValue(21);
        fireOriginal.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("byte exact fire"));
        ItemStack cryOriginal = new ItemStack(Items.WRITABLE_BOOK, 6);
        cryOriginal.set(DataComponents.REPAIR_COST, 11);
        ItemStack duplicatedFire = Controls.fireControl();
        duplicatedFire.setCount(9);
        ItemStack duplicatedCry = Controls.cryControl();
        ItemStack bothMarkers = Controls.fireControl();
        bothMarkers.set(Components.CRY_CONTROL, Unit.INSTANCE);
        inventory.seed(0, duplicatedFire);
        inventory.seed(1, new ItemStack(Items.FIRE_CHARGE, 4));
        inventory.seed(2, new ItemStack(Items.GHAST_TEAR, 3));
        inventory.seed(3, new ItemStack(Items.DIAMOND));
        inventory.seed(4, Controls.fireControl());
        inventory.seed(5, Controls.cryControl());
        inventory.seed(8, duplicatedCry);
        inventory.seed(7, bothMarkers);
        RiderState.HudCache hud = new RiderState.HudCache(0.5, "yellow", "READY", 99L);
        RiderState active = new RiderState(
                Optional.of(new RiderState.StashedStack(4, fireOriginal)),
                Optional.of(new RiderState.StashedStack(5, cryOriginal)),
                Optional.of(UUID.fromString("45a0b4d8-08b1-4812-b5e9-d82c2f339666")),
                100L,
                Optional.of(hud));

        RiderState restored = Controls.restore(inventory, active, inventory);

        assertEquals(List.of(
                "write:4", "write:5",
                "read:0", "write:0", "read:1", "read:2", "read:3",
                "read:6", "read:7", "write:7", "read:8", "write:8"), inventory.events());
        assertTrue(ItemStack.matches(fireOriginal, inventory.peek(4)));
        assertTrue(ItemStack.matches(cryOriginal, inventory.peek(5)));
        assertTrue(inventory.peek(0).isEmpty());
        assertTrue(inventory.peek(7).isEmpty());
        assertTrue(inventory.peek(8).isEmpty());
        assertEquals(4, inventory.peek(1).getCount());
        assertTrue(inventory.peek(1).is(Items.FIRE_CHARGE));
        assertEquals(3, inventory.peek(2).getCount());
        assertTrue(inventory.peek(2).is(Items.GHAST_TEAR));
        assertTrue(inventory.peek(3).is(Items.DIAMOND));
        assertEquals(Optional.empty(), restored.fireStash());
        assertEquals(Optional.empty(), restored.cryStash());
        assertEquals(Optional.empty(), restored.riddenGhastId());
        assertEquals(100L, restored.lastHandledTick());
        assertEquals(Optional.of(hud), restored.hudCache());
    }

    @Test
    void activeReloadKeepsPersistedSlotsAndNextMountAdoptsNewLiveSlots(@TempDir Path directory)
            throws Exception {
        Config originalConfig = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        RecordingInventory inventory = new RecordingInventory(9);
        inventory.seed(4, new ItemStack(Items.DIAMOND));
        inventory.seed(5, new ItemStack(Items.EMERALD));
        UUID firstGhast = UUID.fromString("aefec777-5101-4ef3-a5bf-86dbe2ee6749");
        RiderState active = Controls.mount(inventory, RiderState.fresh(), firstGhast, inventory);
        inventory.clearEvents();
        try {
            Files.writeString(file, """
                    {"controls":{"fireSlot":1,"crySlot":2}}
                    """);
            Config.reload(file);

            assertTrue(Controls.isLockedSlot(active, 4));
            assertTrue(Controls.isLockedSlot(active, 5));
            assertFalse(Controls.isLockedSlot(active, 1));
            assertFalse(Controls.isLockedSlot(active, 2));
            assertTrue(Controls.fireControl(inventory, active, inventory).orElseThrow()
                    .has(Components.FIRE_CONTROL));
            assertTrue(Controls.cryControl(inventory, active, inventory).orElseThrow()
                    .has(Components.CRY_CONTROL));
            assertEquals(List.of("read:4", "read:5"), inventory.events());
            inventory.clearEvents();

            RiderState unchanged = Controls.reconcile(
                    inventory, active, Optional.of(firstGhast), inventory);

            assertSame(active, unchanged);
            assertTrue(inventory.events().isEmpty());

            RiderState cleared = Controls.reconcile(
                    inventory, active, Optional.empty(), inventory);
            assertEquals(List.of("write:4", "write:5"), inventory.events().subList(0, 2));
            assertTrue(ItemStack.matches(new ItemStack(Items.DIAMOND), inventory.peek(4)));
            assertTrue(ItemStack.matches(new ItemStack(Items.EMERALD), inventory.peek(5)));
            inventory.clearEvents();
            inventory.seed(1, new ItemStack(Items.APPLE, 2));
            inventory.seed(2, ItemStack.EMPTY);
            UUID nextGhast = UUID.fromString("96d3970d-ef18-4a98-9602-4b2194a74590");

            RiderState remounted = Controls.reconcile(
                    inventory, cleared, Optional.of(nextGhast), inventory);

            assertEquals(List.of("read:1", "read:2", "write:1", "write:2"), inventory.events());
            assertEquals(1, remounted.fireStash().orElseThrow().slotIndex());
            assertEquals(2, remounted.cryStash().orElseThrow().slotIndex());
            assertEquals(2, remounted.fireStash().orElseThrow().stack().getCount());
            assertTrue(remounted.fireStash().orElseThrow().stack().is(Items.APPLE));
            assertTrue(remounted.cryStash().orElseThrow().stack().isEmpty());
            assertEquals(nextGhast, remounted.riddenGhastId().orElseThrow());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(originalConfig));
            Config.reload(file);
        }
    }

    @Test
    void emptyAndComponentRichStacksRoundTripThroughDefensiveInventoryBoundaries() {
        RecordingInventory inventory = new RecordingInventory(9);
        ItemStack rich = new ItemStack(Items.WRITABLE_BOOK, 8);
        rich.set(DataComponents.REPAIR_COST, 13);
        rich.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("all data"));
        CompoundTag customTag = new CompoundTag();
        customTag.putString("owner", "PyreHaven");
        customTag.putInt("sequence", 42);
        rich.set(DataComponents.CUSTOM_DATA, CustomData.of(customTag));
        inventory.seed(4, ItemStack.EMPTY);
        inventory.seed(5, rich);
        RiderState mounted = Controls.mount(
                inventory,
                RiderState.fresh(),
                UUID.fromString("43768ac7-7398-44a7-9a6d-3733fc6377d5"),
                inventory);

        ItemStack exposedStash = mounted.cryStash().orElseThrow().stack();
        exposedStash.setCount(1);
        ItemStack exposedInventory = inventory.peek(5);
        exposedInventory.setCount(2);
        inventory.clearEvents();
        RiderState cleared = Controls.restore(inventory, mounted, inventory);

        assertTrue(inventory.peek(4).isEmpty());
        assertTrue(ItemStack.matches(rich, inventory.peek(5)));
        assertEquals(8, inventory.peek(5).getCount());
        assertEquals(13, inventory.peek(5).get(DataComponents.REPAIR_COST));
        assertEquals("all data", inventory.peek(5).getCustomName().getString());
        assertEquals(customTag, inventory.peek(5).get(DataComponents.CUSTOM_DATA).copyTag());
        assertEquals(Optional.empty(), cleared.fireStash());
        rich.setCount(3);
        assertEquals(8, inventory.peek(5).getCount());
    }

    @Test
    void inventoryFailuresPropagateWithoutRollbackOrShadowState() {
        RecordingInventory inventory = new RecordingInventory(9);
        ItemStack fireOriginal = new ItemStack(Items.DIAMOND);
        ItemStack cryOriginal = new ItemStack(Items.EMERALD);
        inventory.seed(4, fireOriginal);
        inventory.seed(5, cryOriginal);
        inventory.failOnWrite(2);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Controls.mount(
                inventory,
                RiderState.fresh(),
                UUID.fromString("d365d8a7-923a-4b09-be66-23c8f0c34f10"),
                inventory));

        assertEquals("fixture write failure 2", failure.getMessage());
        assertEquals(List.of("read:4", "read:5", "write:4", "write:5"), inventory.events());
        assertTrue(inventory.peek(4).has(Components.FIRE_CONTROL));
        assertTrue(ItemStack.matches(cryOriginal, inventory.peek(5)));

        RecordingInventory untouched = new RecordingInventory(9);
        RiderState partial = new RiderState(
                Optional.of(new RiderState.StashedStack(4, fireOriginal)),
                Optional.empty(),
                Optional.of(UUID.randomUUID()),
                7L,
                Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> Controls.reconcile(untouched, partial, Optional.empty(), untouched));
        assertTrue(untouched.events().isEmpty());
    }

    private static Controls.ObservedUse observe(
            boolean using, InteractionHand hand, ItemStack stack) {
        RecordingObservation source = new RecordingObservation(using, hand, stack);
        return Controls.observeUse(source, source);
    }

    private static Stream<Arguments> controlStacks() {
        return Stream.of(
                Arguments.of("fire", Controls.fireControl(),
                        Components.FIRE_CONTROL, Components.CRY_CONTROL),
                Arguments.of("cry", Controls.cryControl(),
                        Components.CRY_CONTROL, Components.FIRE_CONTROL));
    }

    private static List<MethodReference> methodReferences(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream raw = type.getResourceAsStream(resource)) {
            assertNotNull(raw);
            DataInputStream input = new DataInputStream(raw);
            assertEquals(0xCAFEBABE, input.readInt());
            input.readUnsignedShort();
            input.readUnsignedShort();
            Object[] pool = new Object[input.readUnsignedShort()];
            for (int index = 1; index < pool.length; index++) {
                int tag = input.readUnsignedByte();
                pool[index] = switch (tag) {
                    case 1 -> input.readUTF();
                    case 7, 8, 16, 19, 20 -> input.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 ->
                            new int[]{input.readUnsignedShort(), input.readUnsignedShort(), tag};
                    case 3, 4 -> { input.readInt(); yield null; }
                    case 5, 6 -> { input.readLong(); index++; yield null; }
                    case 15 -> { input.readUnsignedByte(); input.readUnsignedShort(); yield null; }
                    default -> throw new IOException("Unknown constant-pool tag " + tag);
                };
            }
            List<MethodReference> methods = new ArrayList<>();
            for (Object entry : pool) {
                if (entry instanceof int[] reference && (reference[2] == 10 || reference[2] == 11)) {
                    int[] nameAndType = (int[]) pool[reference[1]];
                    methods.add(new MethodReference(
                            (String) pool[(int) pool[reference[0]]],
                            (String) pool[nameAndType[0]],
                            (String) pool[nameAndType[1]]));
                }
            }
            return methods;
        }
    }

    private record MethodReference(String owner, String name, String descriptor) {
    }

    private static final class RecordingObservation
            implements Controls.UseObservation<RecordingObservation> {
        private final boolean using;
        private final InteractionHand hand;
        private final ItemStack stack;
        private int usingReads;
        private int handReads;
        private int stackReads;

        private RecordingObservation(boolean using, InteractionHand hand, ItemStack stack) {
            this.using = using;
            this.hand = hand;
            this.stack = stack;
        }

        @Override
        public boolean isUsingItem(RecordingObservation source) {
            usingReads++;
            return using;
        }

        @Override
        public InteractionHand getUsedItemHand(RecordingObservation source) {
            handReads++;
            return hand;
        }

        @Override
        public ItemStack getUseItem(RecordingObservation source) {
            stackReads++;
            return stack;
        }
    }

    private static final class RecordingInventory
            implements Controls.InventoryAccess<RecordingInventory> {
        private final ItemStack[] stacks;
        private final List<String> events = new ArrayList<>();
        private int failOnWrite;
        private int writes;

        private RecordingInventory(int size) {
            stacks = new ItemStack[size];
            Arrays.fill(stacks, ItemStack.EMPTY);
        }

        private void seed(int slot, ItemStack stack) {
            stacks[slot] = stack.copy();
        }

        private ItemStack peek(int slot) {
            return stacks[slot].copy();
        }

        private List<String> events() {
            return List.copyOf(events);
        }

        private void clearEvents() {
            events.clear();
        }

        private void failOnWrite(int writeNumber) {
            failOnWrite = writeNumber;
        }

        @Override
        public int size(RecordingInventory inventory) {
            return stacks.length;
        }

        @Override
        public ItemStack read(RecordingInventory inventory, int slot) {
            events.add("read:" + slot);
            return stacks[slot].copy();
        }

        @Override
        public void write(RecordingInventory inventory, int slot, ItemStack stack) {
            events.add("write:" + slot);
            writes++;
            if (writes == failOnWrite) {
                throw new IllegalStateException("fixture write failure " + writes);
            }
            stacks[slot] = stack.copy();
        }
    }
}
