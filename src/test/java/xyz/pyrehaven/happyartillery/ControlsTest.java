package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    @Order(1)
    void initializationRequiresNoHappyArtilleryDataComponentRegistryEntriesOrRegistrationSeam()
            throws Exception {
        new HappyArtillery().onInitialize();

        List<Identifier> customEntries = BuiltInRegistries.DATA_COMPONENT_TYPE.keySet().stream()
                .filter(id -> id.getNamespace().equals("happy-artillery"))
                .toList();
        assertEquals(List.of(), customEntries);
        assertThrows(NoSuchMethodException.class,
                () -> HappyArtillery.Registrar.class.getDeclaredMethod("registerComponents"));
        assertFalse(methodReferences(HappyArtillery.class).stream()
                .anyMatch(call -> call.name().equals("registerComponents")));
    }

    @Test
    void packagedMetadataAndRuntimeBytecodeRemainServerOnlyCompatible() throws Exception {
        String metadata = Files.readString(Path.of("build/resources/main/fabric.mod.json"));
        com.google.gson.JsonObject projectMetadata = com.google.gson.JsonParser.parseString(metadata)
                .getAsJsonObject();
        assertEquals("happy-artillery", projectMetadata.get("id").getAsString());
        assertEquals("*", projectMetadata.get("environment").getAsString());

        for (String className : List.of(
                Components.class.getName(),
                HappyArtillery.class.getName(),
                HappyArtillery.class.getName() + "$FabricRegistrar")) {
            ClassNode owner = BytecodeTestSupport.classNode(className);
            assertFalse(owner.fields.stream().map(field -> field.desc)
                    .anyMatch(desc -> desc.contains("DataComponentType")), className);
            assertFalse(owner.methods.stream().flatMap(method -> Stream.iterate(
                            method.instructions.getFirst(), java.util.Objects::nonNull,
                            org.objectweb.asm.tree.AbstractInsnNode::getNext))
                    .anyMatch(instruction -> instruction instanceof FieldInsnNode field
                            && field.name.equals("DATA_COMPONENT_TYPE")
                            || instruction instanceof MethodInsnNode call
                            && (call.name.equals("registerComponents")
                                    || call.owner.equals("net/minecraft/core/Registry")
                                            && call.name.equals("register"))), className);
        }
    }

    @ParameterizedTest(name = "{0} survives persistence and network round trips")
    @MethodSource("controlStacks")
    void markerIdentitySurvivesItemPersistenceAndNetworkRoundTrips(
            String name,
            ItemStack original,
            Components.Control marker,
            Components.Control opposite) {
        ItemStack persisted = ItemStack.CODEC.parse(
                        JsonOps.INSTANCE,
                        ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow())
                .getOrThrow();

        assertTrue(Components.is(persisted, marker));
        assertFalse(Components.is(persisted, opposite));
        assertTrue(ItemStack.matches(original, persisted));

        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            ItemStack.STREAM_CODEC.encode(buffer, original);
            ItemStack synchronizedStack = ItemStack.STREAM_CODEC.decode(buffer);

            assertTrue(Components.is(synchronizedStack, marker));
            assertFalse(Components.is(synchronizedStack, opposite));
            assertTrue(ItemStack.matches(original, synchronizedStack));
        } finally {
            buffer.release();
        }
    }

    @Test
    void markerHelperPreservesUnrelatedCustomDataOnANonFreshStack() {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag existing = new CompoundTag();
        existing.putString("another-mod:owner", "kept");
        existing.putInt("sequence", 42);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(existing));

        Components.mark(stack, Components.Control.FIRE);

        CompoundTag marked = stack.get(DataComponents.CUSTOM_DATA).copyTag();
        assertEquals("kept", marked.getString("another-mod:owner").orElseThrow());
        assertEquals(42, marked.getInt("sequence").orElseThrow());
        assertTrue(Components.is(stack, Components.Control.FIRE));
        assertFalse(Components.is(stack, Components.Control.CRY));
    }

    @Test
    void factoriesCreateFreshNamedGlintingFireAndCryControlsAndRejectNameTypeFakes() {
        ItemStack firstFire = Controls.fireControl();
        ItemStack secondFire = Controls.fireControl();
        ItemStack cry = Controls.cryControl();
        ItemStack fakeFire = new ItemStack(Items.FIRE_CHARGE);
        fakeFire.set(DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Fire Control"));
        fakeFire.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);

        assertNotSame(firstFire, secondFire);
        assertTrue(firstFire.is(Items.FIRE_CHARGE));
        assertTrue(cry.is(Items.GHAST_TEAR));
        assertTrue(Components.is(firstFire, Components.Control.FIRE));
        assertFalse(Components.is(firstFire, Components.Control.CRY));
        assertTrue(Components.is(cry, Components.Control.CRY));
        assertFalse(Components.is(cry, Components.Control.FIRE));
        assertFalse(Components.is(fakeFire, Components.Control.FIRE));
        assertFalse(Components.is(fakeFire, Components.Control.CRY));
        assertEquals("Fire Control", firstFire.getHoverName().getString());
        assertEquals("Cry Control", cry.getHoverName().getString());
        assertTrue(firstFire.hasFoil());
        assertTrue(cry.hasFoil());
        assertEquals(firstFire.get(DataComponents.CONSUMABLE), cry.get(DataComponents.CONSUMABLE));
        assertNotNull(firstFire.get(DataComponents.CONSUMABLE));
    }

    @Test
    void factoriesReadEveryValidConfigChangeAtCallTime(@TempDir Path directory) throws Exception {
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
    void observedUseRejectsUsingStateWithoutAnObservedHand() {
        ItemStack control = Controls.fireControl();

        assertThrows(NullPointerException.class,
                () -> new Controls.ObservedUse(true, null, control));
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
    void productionPilotAdapterBindsExactVehiclePassengerAndUuidApis() throws Exception {
        List<MethodReference> calls = methodReferences(Controls.ServerPlayerControlAccess.class);

        assertEquals(Controls.Admission.class, Controls.class.getDeclaredMethod(
                "handleUseEntity", ServerPlayer.class, Entity.class, InteractionHand.class,
                RiderState.class, long.class).getReturnType());
        assertTrue(calls.contains(new MethodReference(
                "net/minecraft/server/level/ServerPlayer", "getVehicle",
                "()Lnet/minecraft/world/entity/Entity;")));
        assertTrue(calls.contains(new MethodReference(
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getFirstPassenger",
                "()Lnet/minecraft/world/entity/Entity;")));
        assertTrue(calls.contains(new MethodReference(
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getControllingPassenger",
                "()Lnet/minecraft/world/entity/LivingEntity;")));
        assertTrue(calls.contains(new MethodReference(
                "net/minecraft/world/entity/animal/happyghast/HappyGhast", "getUUID",
                "()Ljava/util/UUID;")));
    }

    @Test
    void productionSlotAdapterBindsExactStatePilotInventorySlotAndSelectionApis() throws Exception {
        List<MethodReference> calls = methodReferences(Controls.ServerPlayerContainerDecisionAccess.class);
        String controlAccess = "xyz/pyrehaven/happyartillery/Controls$ServerPlayerControlAccess";

        assertEquals(boolean.class, Controls.class.getDeclaredMethod(
                "shouldCancelSelectedSlotDrop", ServerPlayer.class).getReturnType());
        assertEquals(boolean.class, Controls.class.getDeclaredMethod(
                "shouldCancelContainerMutation",
                net.minecraft.world.inventory.AbstractContainerMenu.class,
                int.class, int.class, ContainerInput.class, ServerPlayer.class,
                Controls.QuickCraftSnapshot.class).getReturnType());
        assertTrue(calls.contains(new MethodReference(
                "net/fabricmc/fabric/api/attachment/v1/AttachmentTarget", "getAttached",
                "(Lnet/fabricmc/fabric/api/attachment/v1/AttachmentType;)Ljava/lang/Object;")));
        assertTrue(calls.contains(new MethodReference(controlAccess, "riddenHappyGhast",
                "(Lnet/minecraft/server/level/ServerPlayer;)Ljava/util/Optional;")));
        assertTrue(calls.contains(new MethodReference(controlAccess, "isControllingFirstPassenger",
                "(Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)Z")));
        assertTrue(calls.contains(new MethodReference(controlAccess, "ghastId",
                "(Lnet/minecraft/world/entity/animal/happyghast/HappyGhast;)Ljava/util/UUID;")));
        assertFalse(calls.stream().anyMatch(call -> call.owner().equals(
                "net/minecraft/server/level/ServerPlayer") && call.name().equals("getVehicle")));
        assertFalse(calls.stream().anyMatch(call -> call.owner().equals(
                "net/minecraft/world/entity/animal/happyghast/HappyGhast")
                && List.of("getFirstPassenger", "getControllingPassenger", "getUUID").contains(call.name())));
        assertTrue(calls.contains(new MethodReference(
                "net/minecraft/server/level/ServerPlayer", "getInventory",
                "()Lnet/minecraft/world/entity/player/Inventory;")));
        assertTrue(calls.contains(new MethodReference(
                "net/minecraft/world/entity/player/Inventory", "getSelectedSlot", "()I")));
        assertTrue(calls.contains(new MethodReference(
                "net/minecraft/world/inventory/Slot", "getContainerSlot", "()I")));
        ClassNode adapter = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Controls$ServerPlayerContainerDecisionAccess");
        assertTrue(adapter.methods.stream().flatMap(method -> Stream.iterate(
                        method.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext))
                .filter(FieldInsnNode.class::isInstance).map(FieldInsnNode.class::cast)
                .anyMatch(field -> field.owner.equals("net/minecraft/world/inventory/Slot")
                        && field.name.equals("container")
                        && field.desc.equals("Lnet/minecraft/world/Container;")));
    }

    @Test
    void configuredPlainItemLookupCannotUseTheDefaultingRegistryApi() throws IOException {
        List<MethodReference> calls = methodReferences(Controls.class);

        assertTrue(calls.stream().anyMatch(call -> call.name().equals("getOptional")));
        assertFalse(calls.stream().anyMatch(call -> call.name().equals("getValue")));
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
        assertTrue(Components.is(inventory.peek(4), Components.Control.FIRE));
        assertTrue(Components.is(inventory.peek(5), Components.Control.CRY));
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
        ItemStack invalidMarker = Controls.fireControl();
        CompoundTag invalidTag = new CompoundTag();
        invalidTag.putString("happy-artillery:control", "invalid");
        invalidMarker.set(DataComponents.CUSTOM_DATA, CustomData.of(invalidTag));
        inventory.seed(0, duplicatedFire);
        inventory.seed(1, new ItemStack(Items.FIRE_CHARGE, 4));
        inventory.seed(2, new ItemStack(Items.GHAST_TEAR, 3));
        inventory.seed(3, new ItemStack(Items.DIAMOND));
        inventory.seed(4, Controls.fireControl());
        inventory.seed(5, Controls.cryControl());
        inventory.seed(8, duplicatedCry);
        inventory.seed(7, invalidMarker);
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
                "read:6", "read:7", "read:8", "write:8"), inventory.events());
        assertTrue(ItemStack.matches(fireOriginal, inventory.peek(4)));
        assertTrue(ItemStack.matches(cryOriginal, inventory.peek(5)));
        assertTrue(inventory.peek(0).isEmpty());
        assertFalse(inventory.peek(7).isEmpty());
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
    void deathBoundaryRestoresAndReplacesStateBeforeDropSnapshot() {
        RecordingInventory inventory = new RecordingInventory(9);
        ItemStack fireOriginal = new ItemStack(Items.DIAMOND_SWORD);
        fireOriginal.setDamageValue(19);
        ItemStack cryOriginal = new ItemStack(Items.WRITABLE_BOOK, 6);
        cryOriginal.set(DataComponents.REPAIR_COST, 12);
        inventory.seed(4, Controls.fireControl());
        inventory.seed(5, Controls.cryControl());
        RiderState active = new RiderState(
                Optional.of(new RiderState.StashedStack(4, fireOriginal)),
                Optional.of(new RiderState.StashedStack(5, cryOriginal)),
                Optional.of(UUID.fromString("aa1ea980-aa19-43f0-9476-933373fb19f5")),
                101L,
                Optional.of(new RiderState.HudCache(0.4, "yellow", "HOT", 99L)));
        inventory.attach(active);

        Controls.beforeDeathDrops(inventory, inventory, inventory::snapshotDrops);

        assertTrue(ItemStack.matches(fireOriginal, inventory.drops().get(4)));
        assertTrue(ItemStack.matches(cryOriginal, inventory.drops().get(5)));
        assertEquals(Optional.empty(), inventory.attachedState().orElseThrow().fireStash());
        assertEquals(Optional.empty(), inventory.attachedState().orElseThrow().cryStash());
        assertEquals(Optional.empty(), inventory.attachedState().orElseThrow().riddenGhastId());
        assertEquals(101L, inventory.attachedState().orElseThrow().lastHandledTick());
        assertEquals(active.hudCache(), inventory.attachedState().orElseThrow().hudCache());
        assertTrue(inventory.events().indexOf("replace-state")
                < inventory.events().indexOf("drop-snapshot"));
        assertEquals(2, inventory.events().stream().filter(event -> event.startsWith("write:")).count());
        assertEquals(1, inventory.events().stream().filter("replace-state"::equals).count());
        assertEquals(1, inventory.events().stream().filter("drop-snapshot"::equals).count());
    }

    @Test
    void deathBoundaryRejectsEveryMalformedPartialStateBeforeDropCreation() {
        ItemStack original = new ItemStack(Items.DIAMOND);
        UUID ghastId = UUID.fromString("35200c02-d841-48ad-af95-22cf27aa9469");
        List<RiderState> malformed = List.of(
                new RiderState(
                        Optional.of(new RiderState.StashedStack(4, original)),
                        Optional.empty(), Optional.of(ghastId), 1L, Optional.empty()),
                new RiderState(
                        Optional.empty(),
                        Optional.of(new RiderState.StashedStack(5, original)),
                        Optional.of(ghastId), 1L, Optional.empty()),
                new RiderState(
                        Optional.empty(), Optional.empty(), Optional.of(ghastId),
                        1L, Optional.empty()),
                new RiderState(
                        Optional.of(new RiderState.StashedStack(4, original)),
                        Optional.of(new RiderState.StashedStack(5, original)),
                        Optional.empty(), 1L, Optional.empty()));

        for (RiderState state : malformed) {
            RecordingInventory inventory = new RecordingInventory(9);
            inventory.attach(state);

            assertThrows(IllegalStateException.class,
                    () -> Controls.beforeDeathDrops(inventory, inventory, inventory::snapshotDrops));
            assertTrue(inventory.events().isEmpty());
        }
    }

    @Test
    void deathBoundaryLeavesAbsentAndClearedStateUnchanged() {
        RecordingInventory absent = new RecordingInventory(9);
        absent.seed(2, new ItemStack(Items.EMERALD, 3));

        Controls.beforeDeathDrops(absent, absent, absent::snapshotDrops);

        assertEquals(Optional.empty(), absent.attachedState());
        assertEquals(List.of("drop-snapshot"), absent.events());
        assertEquals(3, absent.drops().get(2).getCount());

        RecordingInventory cleared = new RecordingInventory(9);
        RiderState state = RiderState.fresh();
        cleared.attach(state);

        Controls.beforeDeathDrops(cleared, cleared, cleared::snapshotDrops);

        assertSame(state, cleared.attachedState().orElseThrow());
        assertEquals(List.of("drop-snapshot"), cleared.events());
    }

    @Test
    void deathBoundaryPropagatesRestoreReplaceAndDropFailures() {
        RiderState active = activeState(
                UUID.fromString("eb6cedc6-7ca3-482d-9838-a33d650038af"), 12L);

        RecordingInventory restoreFailure = new RecordingInventory(9);
        restoreFailure.attach(active);
        restoreFailure.failOnWrite(1);
        IllegalStateException restore = assertThrows(IllegalStateException.class,
                () -> Controls.beforeDeathDrops(
                        restoreFailure, restoreFailure, restoreFailure::snapshotDrops));
        assertEquals("fixture write failure 1", restore.getMessage());
        assertFalse(restoreFailure.events().contains("replace-state"));
        assertFalse(restoreFailure.events().contains("drop-snapshot"));

        RecordingInventory replaceFailure = new RecordingInventory(9);
        replaceFailure.attach(active);
        replaceFailure.failOnReplace();
        IllegalStateException replace = assertThrows(IllegalStateException.class,
                () -> Controls.beforeDeathDrops(
                        replaceFailure, replaceFailure, replaceFailure::snapshotDrops));
        assertEquals("fixture replace failure", replace.getMessage());
        assertFalse(replaceFailure.events().contains("drop-snapshot"));

        RecordingInventory dropFailure = new RecordingInventory(9);
        dropFailure.attach(active);
        IllegalStateException drop = assertThrows(IllegalStateException.class,
                () -> Controls.beforeDeathDrops(dropFailure, dropFailure, () -> {
                    throw new IllegalStateException("fixture drop failure");
                }));
        assertEquals("fixture drop failure", drop.getMessage());
        assertEquals(Optional.empty(), dropFailure.attachedState().orElseThrow().fireStash());
        assertEquals(1, dropFailure.events().stream().filter("replace-state"::equals).count());
    }

    @Test
    void productionDeathBoundaryBindsExactAttachmentOwnerAndExistingInventoryAdapter() throws Exception {
        List<MethodReference> calls = methodReferences(Controls.ServerPlayerDeathDropAccess.class);

        assertEquals(void.class, Controls.class.getDeclaredMethod(
                "beforeDeathDrops", ServerPlayer.class, Runnable.class).getReturnType());
        assertTrue(calls.contains(new MethodReference(
                "net/fabricmc/fabric/api/attachment/v1/AttachmentTarget", "getAttached",
                "(Lnet/fabricmc/fabric/api/attachment/v1/AttachmentType;)Ljava/lang/Object;")));
        assertTrue(calls.contains(new MethodReference(
                "xyz/pyrehaven/happyartillery/RiderState", "replace",
                "(Lnet/fabricmc/fabric/api/attachment/v1/AttachmentTarget;"
                        + "Lxyz/pyrehaven/happyartillery/RiderState;)"
                        + "Lxyz/pyrehaven/happyartillery/RiderState;")));
        assertTrue(calls.stream().filter(call -> call.owner().equals(
                "xyz/pyrehaven/happyartillery/Controls$ServerPlayerInventoryAccess")).count() >= 3);
    }

    @Test
    void deathDropMixinWrapsTheExactCommittedDropInvocationAndDelegatesOnce() throws Exception {
        ClassNode mixin = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.mixin.DeathDropMixin");
        AnnotationNode target = annotation(mixin.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of(Type.getType(ServerPlayer.class)), annotationValue(target, "value"));

        MethodNode wrapper = mixin.methods.stream()
                .filter(method -> annotation(method.visibleAnnotations,
                        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;") != null)
                .findFirst().orElseThrow();
        AnnotationNode wrap = annotation(wrapper.visibleAnnotations,
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;");
        assertEquals(List.of("die(Lnet/minecraft/world/damagesource/DamageSource;)V"),
                annotationValue(wrap, "method"));
        assertEquals(1, annotationValue(wrap, "require"));
        AnnotationNode at = (AnnotationNode) ((List<?>) annotationValue(wrap, "at")).getFirst();
        assertEquals("INVOKE", annotationValue(at, "value"));
        assertEquals("Lnet/minecraft/server/level/ServerPlayer;dropAllDeathLoot("
                        + "Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/damagesource/DamageSource;)V",
                annotationValue(at, "target"));

        List<MethodInsnNode> calls = Stream.iterate(
                        wrapper.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).toList();
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls")
                && call.name.equals("beforeDeathDrops")
                && call.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/Runnable;)V"))
                .count());
        assertEquals(0, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/RiderState")).count());
        MethodNode originalCall = mixin.methods.stream()
                .filter(method -> method.name.startsWith(
                        "lambda$happyArtillery$restoreBeforeDeathDrops"))
                .findFirst().orElseThrow();
        List<MethodInsnNode> originalCalls = Stream.iterate(
                        originalCall.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).toList();
        assertEquals(1, originalCalls.stream().filter(call -> call.owner.equals(
                "com/llamalad7/mixinextras/injector/wrapoperation/Operation")
                && call.name.equals("call")
                && call.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")).count());
    }

    @Test
    void playerDropMixinCancelsTheExactDirectDropBoundaryThroughControlsOnly() throws Exception {
        ClassNode mixin = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.mixin.PlayerDropMixin");
        AnnotationNode target = annotation(mixin.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of(Type.getType(ServerPlayer.class)), annotationValue(target, "value"));

        MethodNode handler = injectedHandler(mixin);
        AnnotationNode inject = annotation(handler.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("drop(Z)V"), annotationValue(inject, "method"));
        assertEquals(true, annotationValue(inject, "cancellable"));
        assertEquals(1, annotationValue(inject, "require"));
        assertEquals("HEAD", annotationValue(
                (AnnotationNode) ((List<?>) annotationValue(inject, "at")).getFirst(), "value"));
        assertEquals("(ZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", handler.desc);
        assertMixinDelegatesOnlyToControls(handler, "shouldCancelSelectedSlotDrop",
                "(Lnet/minecraft/server/level/ServerPlayer;)Z");
    }

    @Test
    void slotGuardMixinCancelsTheExactContainerBoundaryThroughControlsOnly() throws Exception {
        ClassNode mixin = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.mixin.SlotGuardMixin");
        AnnotationNode target = annotation(mixin.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of(Type.getType(net.minecraft.world.inventory.AbstractContainerMenu.class)),
                annotationValue(target, "value"));
        for (String name : List.of("quickcraftStatus", "quickcraftType", "quickcraftSlots")) {
            FieldNode shadow = mixin.fields.stream().filter(field -> field.name.equals(name))
                    .findFirst().orElseThrow();
            assertNotNull(annotation(shadow.visibleAnnotations,
                    "Lorg/spongepowered/asm/mixin/Shadow;"));
            assertEquals(name.equals("quickcraftSlots") ? "Ljava/util/Set;" : "I", shadow.desc);
            if (name.equals("quickcraftSlots")) {
                assertNotNull(annotation(shadow.visibleAnnotations,
                        "Lorg/spongepowered/asm/mixin/Final;"));
            }
        }

        MethodNode handler = injectedHandler(mixin);
        AnnotationNode inject = annotation(handler.visibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("clicked(IILnet/minecraft/world/inventory/ContainerInput;"
                        + "Lnet/minecraft/world/entity/player/Player;)V"), annotationValue(inject, "method"));
        assertEquals(true, annotationValue(inject, "cancellable"));
        assertEquals(1, annotationValue(inject, "require"));
        assertEquals("HEAD", annotationValue(
                (AnnotationNode) ((List<?>) annotationValue(inject, "at")).getFirst(), "value"));
        assertEquals("(IILnet/minecraft/world/inventory/ContainerInput;"
                        + "Lnet/minecraft/world/entity/player/Player;"
                        + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", handler.desc);
        assertMixinDelegatesOnlyToControls(handler, "shouldCancelContainerMutation",
                "(Lnet/minecraft/world/inventory/AbstractContainerMenu;II"
                        + "Lnet/minecraft/world/inventory/ContainerInput;"
                        + "Lnet/minecraft/server/level/ServerPlayer;"
                        + "Lxyz/pyrehaven/happyartillery/Controls$QuickCraftSnapshot;)Z");
        assertEquals(1, Stream.iterate(handler.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(TypeInsnNode.class::isInstance).map(TypeInsnNode.class::cast)
                .filter(instruction -> instruction.getOpcode() == org.objectweb.asm.Opcodes.INSTANCEOF
                        && instruction.desc.equals("net/minecraft/server/level/ServerPlayer"))
                .count());
    }

    @Test
    void allMixinClassesContainNoStateConfigInventoryOrPilotPolicyCalls() throws Exception {
        for (String name : List.of("DeathDropMixin", "PlayerDropMixin", "SlotGuardMixin")) {
            ClassNode mixin = BytecodeTestSupport.classNode(
                    "xyz.pyrehaven.happyartillery.mixin." + name);
            List<MethodInsnNode> calls = mixin.methods.stream().flatMap(method -> Stream.iterate(
                            method.instructions.getFirst(), java.util.Objects::nonNull,
                            org.objectweb.asm.tree.AbstractInsnNode::getNext))
                    .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
            assertEquals(0, calls.stream().filter(call -> List.of(
                            "xyz/pyrehaven/happyartillery/RiderState",
                            "xyz/pyrehaven/happyartillery/Config",
                            "net/fabricmc/fabric/api/attachment/v1/AttachmentTarget",
                            "net/minecraft/world/entity/player/Inventory",
                            "net/minecraft/world/inventory/Slot",
                            "net/minecraft/world/entity/animal/happyghast/HappyGhast")
                    .contains(call.owner)).count(), name);
        }
    }

    @Test
    void mixinMetadataRequiresAllDeclaredPlumbingClasses() throws Exception {
        try (InputStream input = ControlsTest.class.getResourceAsStream(
                "/happy-artillery.mixins.json")) {
            assertNotNull(input);
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertTrue(metadata.get("required").getAsBoolean());
            assertEquals(1, metadata.getAsJsonObject("injectors").get("defaultRequire").getAsInt());
            assertEquals(List.of("DeathDropMixin", "PlayerDropMixin", "SlotGuardMixin"),
                    metadata.getAsJsonArray("mixins").asList().stream()
                            .map(com.google.gson.JsonElement::getAsString).toList());
        }
    }

    @Test
    void pickupCancelsOnlyWhenTheClickedMenuSlotIsAPersistedLockedPlayerSlot() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addChestSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(5, Controls.cryControl());

        assertFalse(fixture.decide(0, 0, ContainerInput.PICKUP));
        assertTrue(fixture.decide(1, 0, ContainerInput.PICKUP));
        assertTrue(fixture.decide(2, 1, ContainerInput.PICKUP));
        assertFalse(fixture.decide(1, 2, ContainerInput.PICKUP));
        assertFalse(fixture.decide(-999, 0, ContainerInput.PICKUP));
        assertFalse(fixture.decide(99, 0, ContainerInput.PICKUP));
    }

    @Test
    void quickMoveCancelsLockedClickedSlotButNotChestOrInvalidRoutes() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addChestSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(4, Controls.fireControl());

        assertFalse(fixture.decide(0, 0, ContainerInput.QUICK_MOVE));
        assertTrue(fixture.decide(1, 0, ContainerInput.QUICK_MOVE));
        assertTrue(fixture.decide(1, 1, ContainerInput.QUICK_MOVE));
        assertFalse(fixture.decide(1, 2, ContainerInput.QUICK_MOVE));
        assertFalse(fixture.decide(-999, 0, ContainerInput.QUICK_MOVE));
    }

    @Test
    void swapCancelsWhenEitherTheClickedPlayerSlotOrHotbarButtonIndexIsLocked() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addChestSlot(27, new ItemStack(Items.DIAMOND));
        fixture.addPlayerSlot(5, Controls.cryControl());
        fixture.addPlayerSlot(4, Controls.fireControl());

        assertTrue(fixture.decide(0, 4, ContainerInput.SWAP));
        assertTrue(fixture.decide(1, 0, ContainerInput.SWAP));
        assertTrue(fixture.decide(2, 8, ContainerInput.SWAP));
        assertFalse(fixture.decide(0, 0, ContainerInput.SWAP));
        assertFalse(fixture.decide(0, 40, ContainerInput.SWAP));
        assertFalse(fixture.decide(0, 9, ContainerInput.SWAP));
        assertFalse(fixture.decide(-999, 4, ContainerInput.SWAP));
    }

    @Test
    void quickCraftCancelsOnlyValidAddStageForLockedPlayerSlots() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addChestSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(4, Controls.fireControl());
        fixture.carried = new ItemStack(Items.DIAMOND, 3);
        fixture.quickcraftStatus = 1;
        fixture.quickcraftType = 0;

        assertFalse(fixture.decide(1, 0, ContainerInput.QUICK_CRAFT));
        assertTrue(fixture.decide(1, 1, ContainerInput.QUICK_CRAFT));
        assertTrue(fixture.decide(1, 9, ContainerInput.QUICK_CRAFT));
        fixture.quickcraftType = 2;
        assertFalse(fixture.decide(1, 9, ContainerInput.QUICK_CRAFT));
        fixture.infiniteMaterials = true;
        assertTrue(fixture.decide(1, 9, ContainerInput.QUICK_CRAFT));
        assertFalse(fixture.decide(1, 2, ContainerInput.QUICK_CRAFT));
        assertFalse(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
        assertTrue(fixture.decide(1, 13, ContainerInput.QUICK_CRAFT));
        assertFalse(fixture.decide(-999, 1, ContainerInput.QUICK_CRAFT));
    }

    @Test
    void quickCraftContinuationPreservesEveryVanillaResetOrAdmissionFailure() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addPlayerSlot(4, ItemStack.EMPTY, true, true, 64);
        fixture.carried = new ItemStack(Items.DIAMOND, 2);

        fixture.quickcraftStatus = 0;
        assertFalse(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
        fixture.quickcraftStatus = 1;
        fixture.carried = ItemStack.EMPTY;
        assertFalse(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
        fixture.carried = new ItemStack(Items.DIAMOND, 2);
        fixture.canQuickReplace = false;
        assertFalse(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
        fixture.canQuickReplace = true;
        fixture.slots.get(0).mayPlace = false;
        assertFalse(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
        fixture.slots.get(0).mayPlace = true;
        fixture.canDragTo = false;
        assertFalse(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
        fixture.canDragTo = true;
        fixture.addChestSlot(0, ItemStack.EMPTY);
        fixture.quickcraftSelectedCount = 2;
        assertFalse(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
        fixture.quickcraftSelectedCount = 1;
        assertTrue(fixture.decide(0, 1, ContainerInput.QUICK_CRAFT));
    }

    @Test
    void cloneCancelsOnlyCreativeDuplicationFromAnOccupiedLockedSlot() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addChestSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(5, ItemStack.EMPTY);

        assertFalse(fixture.decide(1, 7, ContainerInput.CLONE));
        fixture.infiniteMaterials = true;
        assertTrue(fixture.decide(1, 7, ContainerInput.CLONE));
        assertFalse(fixture.decide(0, 7, ContainerInput.CLONE));
        assertFalse(fixture.decide(2, 7, ContainerInput.CLONE));
        fixture.carried = new ItemStack(Items.STICK);
        assertFalse(fixture.decide(1, 7, ContainerInput.CLONE));
        assertFalse(fixture.decide(-999, 7, ContainerInput.CLONE));
    }

    @Test
    void throwCancelsOnlyAnActualDropFromAnOccupiedLockedSlot() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addChestSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(5, ItemStack.EMPTY);

        assertTrue(fixture.decide(1, 0, ContainerInput.THROW));
        assertTrue(fixture.decide(1, 1, ContainerInput.THROW));
        assertTrue(fixture.decide(1, 99, ContainerInput.THROW));
        assertFalse(fixture.decide(0, 0, ContainerInput.THROW));
        assertFalse(fixture.decide(2, 0, ContainerInput.THROW));
        fixture.carried = new ItemStack(Items.STICK);
        assertFalse(fixture.decide(1, 0, ContainerInput.THROW));
        fixture.carried = ItemStack.EMPTY;
        fixture.canDropItems = false;
        assertFalse(fixture.decide(1, 0, ContainerInput.THROW));
    }

    @Test
    void pickupAllCancelsWhenVanillaCanScanEitherLockedSlotFromElsewhere() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.carried = Controls.fireControl();
        fixture.carried.setCount(1);
        fixture.addChestSlot(0, ItemStack.EMPTY);
        fixture.addPlayerSlot(4, Controls.fireControl());
        fixture.addPlayerSlot(5, Controls.cryControl());

        assertTrue(fixture.decide(0, 0, ContainerInput.PICKUP_ALL));
        assertTrue(fixture.decide(0, 1, ContainerInput.PICKUP_ALL));
        assertTrue(fixture.decide(0, 2, ContainerInput.PICKUP_ALL));
    }

    @Test
    void pickupAllUsesVanillasTwoPassDirectionCapacityAndPartialRemoval() {
        SlotDecisionFixture reverse = SlotDecisionFixture.activePilot();
        reverse.carried = Controls.fireControl();
        reverse.carried.setCount(60);
        reverse.addPlayerSlot(4, Controls.fireControl());
        reverse.slots.get(0).stack.setCount(5);
        reverse.addChestSlot(0, Controls.fireControl());
        reverse.slots.get(1).stack.setCount(4);
        reverse.addChestSlot(1, ItemStack.EMPTY);
        assertFalse(reverse.decide(2, 2, ContainerInput.PICKUP_ALL));

        SlotDecisionFixture twoPass = SlotDecisionFixture.activePilot();
        twoPass.carried = Controls.fireControl();
        twoPass.carried.setCount(1);
        twoPass.addChestSlot(0, ItemStack.EMPTY);
        twoPass.addPlayerSlot(4, Controls.fireControl());
        twoPass.slots.get(1).stack.setCount(64);
        assertTrue(twoPass.decide(0, 0, ContainerInput.PICKUP_ALL));

        SlotDecisionFixture partialVeto = SlotDecisionFixture.activePilot();
        partialVeto.carried = Controls.fireControl();
        partialVeto.carried.setCount(63);
        partialVeto.addChestSlot(0, ItemStack.EMPTY);
        partialVeto.addPlayerSlot(4, Controls.fireControl(), true, true, 64);
        partialVeto.slots.get(1).stack.setCount(5);
        partialVeto.slots.get(1).allowModification = false;
        assertFalse(partialVeto.decide(0, 0, ContainerInput.PICKUP_ALL));
        partialVeto.slots.get(1).allowModification = true;
        partialVeto.slots.get(1).removableAmount = 1;
        assertTrue(partialVeto.decide(0, 0, ContainerInput.PICKUP_ALL));
    }

    @Test
    void pickupSwapThrowAndQuickMoveSkipLockedSlotsVanillaCannotMutate() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addPlayerSlot(4, Controls.fireControl(), false, false, 0);
        assertFalse(fixture.decide(0, 0, ContainerInput.PICKUP));
        assertFalse(fixture.decide(0, 0, ContainerInput.QUICK_MOVE));
        assertFalse(fixture.decide(0, 0, ContainerInput.THROW));

        fixture.slots.get(0).mayPickup = true;
        fixture.quickMoveDestination = false;
        assertFalse(fixture.decide(0, 0, ContainerInput.QUICK_MOVE));
        fixture.quickMoveDestination = true;
        assertTrue(fixture.decide(0, 0, ContainerInput.QUICK_MOVE));

        SlotDecisionFixture emptyLocked = SlotDecisionFixture.activePilot();
        emptyLocked.addPlayerSlot(4, ItemStack.EMPTY, true, false, 0);
        emptyLocked.carried = new ItemStack(Items.DIAMOND);
        assertFalse(emptyLocked.decide(0, 0, ContainerInput.PICKUP));
        emptyLocked.slots.get(0).mayPlace = true;
        emptyLocked.slots.get(0).maxStackSize = 1;
        assertTrue(emptyLocked.decide(0, 0, ContainerInput.PICKUP));

        SlotDecisionFixture offhand = SlotDecisionFixture.activePilot();
        offhand.addPlayerSlot(4, Controls.fireControl());
        offhand.inventoryStacks[40] = new ItemStack(Items.STICK);
        assertTrue(offhand.decide(0, 40, ContainerInput.SWAP));
    }

    @Test
    void directDropDecisionProtectsOnlyThePersistedSelectedSlotForTheActivePilot() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();

        fixture.selectedSlot = 4;
        assertTrue(fixture.decideSelectedDrop());
        fixture.selectedSlot = 5;
        assertTrue(fixture.decideSelectedDrop());
        fixture.selectedSlot = 3;
        assertFalse(fixture.decideSelectedDrop());
    }

    @Test
    void slotDecisionsRejectInactivePilotsUnrelatedSlotsAndMalformedRoutes() {
        SlotDecisionFixture absent = SlotDecisionFixture.activePilot();
        absent.state = Optional.empty();
        absent.addPlayerSlot(4, Controls.fireControl());
        assertFalse(absent.decide(0, 0, ContainerInput.PICKUP));
        assertFalse(absent.decideSelectedDrop());

        SlotDecisionFixture cleared = SlotDecisionFixture.activePilot();
        cleared.state = Optional.of(RiderState.fresh());
        cleared.addPlayerSlot(4, Controls.fireControl());
        assertFalse(cleared.decide(0, 0, ContainerInput.PICKUP));

        SlotDecisionFixture unridden = SlotDecisionFixture.activePilot();
        unridden.rider.vehicle = null;
        unridden.addPlayerSlot(4, Controls.fireControl());
        assertFalse(unridden.decide(0, 0, ContainerInput.PICKUP));

        SlotDecisionFixture passenger = SlotDecisionFixture.activePilot();
        passenger.ghast.firstPassenger = new TestRider();
        passenger.addPlayerSlot(4, Controls.fireControl());
        assertFalse(passenger.decide(0, 0, ContainerInput.PICKUP));

        SlotDecisionFixture nonPilot = SlotDecisionFixture.activePilot();
        nonPilot.ghast.controller = new TestRider();
        nonPilot.addPlayerSlot(4, Controls.fireControl());
        assertFalse(nonPilot.decide(0, 0, ContainerInput.PICKUP));

        SlotDecisionFixture wrongGhast = SlotDecisionFixture.activePilot();
        wrongGhast.state = Optional.of(activeState(UUID.randomUUID(), 1L));
        wrongGhast.addPlayerSlot(4, Controls.fireControl());
        assertFalse(wrongGhast.decide(0, 0, ContainerInput.PICKUP));

        SlotDecisionFixture routes = SlotDecisionFixture.activePilot();
        routes.addChestSlot(4, Controls.fireControl());
        routes.addPlayerSlot(4, Controls.fireControl());
        assertFalse(routes.decide(0, 0, ContainerInput.PICKUP));
        assertFalse(routes.decide(-999, 0, ContainerInput.PICKUP));
        assertFalse(routes.decide(99, 0, ContainerInput.PICKUP));
        assertFalse(routes.decide(1, 2, ContainerInput.PICKUP));
        assertFalse(routes.decide(1, 2, ContainerInput.QUICK_MOVE));
        assertFalse(routes.decide(0, 9, ContainerInput.SWAP));
        assertFalse(routes.decide(0, 40, ContainerInput.SWAP));
        assertTrue(routes.decide(1, 40, ContainerInput.SWAP));
        assertFalse(routes.decide(1, 0, ContainerInput.QUICK_CRAFT));
        assertFalse(routes.decide(1, 2, ContainerInput.QUICK_CRAFT));
        assertFalse(routes.decide(1, 13, ContainerInput.QUICK_CRAFT));
    }

    @Test
    void malformedPersistedSlotStateFailsBeforeAnyMenuSlotIsRead() {
        ItemStack original = new ItemStack(Items.DIAMOND);
        UUID id = UUID.fromString("f56660f6-d255-475b-b43f-f2dcb3d2a640");
        List<RiderState> malformed = List.of(
                new RiderState(Optional.of(new RiderState.StashedStack(4, original)),
                        Optional.empty(), Optional.of(id), 1L, Optional.empty()),
                new RiderState(Optional.empty(),
                        Optional.of(new RiderState.StashedStack(5, original)),
                        Optional.of(id), 1L, Optional.empty()),
                new RiderState(Optional.empty(), Optional.empty(), Optional.of(id), 1L, Optional.empty()),
                new RiderState(Optional.of(new RiderState.StashedStack(4, original)),
                        Optional.of(new RiderState.StashedStack(5, original)),
                        Optional.empty(), 1L, Optional.empty()),
                new RiderState(Optional.of(new RiderState.StashedStack(4, original)),
                        Optional.of(new RiderState.StashedStack(4, original)),
                        Optional.of(id), 1L, Optional.empty()));

        for (RiderState state : malformed) {
            SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
            fixture.state = Optional.of(state);
            fixture.addPlayerSlot(4, Controls.fireControl());

            assertThrows(IllegalStateException.class,
                    () -> fixture.decide(0, 0, ContainerInput.PICKUP));
            assertEquals(0, fixture.slotReads);
            assertThrows(IllegalStateException.class, fixture::decideSelectedDrop);
            assertEquals(0, fixture.slotReads);
        }
    }

    @Test
    void lockConfigDisablesContainerAndDirectDropDecisions(@TempDir Path directory) throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.addPlayerSlot(4, Controls.fireControl());
        try {
            Files.writeString(file, "{\"controls\":{\"lockControlSlots\":false}}");
            Config.reload(file);

            assertFalse(fixture.decide(0, 0, ContainerInput.PICKUP));
            assertFalse(fixture.decideSelectedDrop());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void pickupAllRejectsEveryVanillaNoScanOrNoCandidateCase() {
        SlotDecisionFixture fixture = SlotDecisionFixture.activePilot();
        fixture.carried = Controls.fireControl();
        fixture.addChestSlot(0, ItemStack.EMPTY);
        fixture.addPlayerSlot(4, Controls.cryControl());
        assertFalse(fixture.decide(0, 0, ContainerInput.PICKUP_ALL));
        assertFalse(fixture.decide(0, 2, ContainerInput.PICKUP_ALL));

        SlotDecisionFixture clickedOccupied = SlotDecisionFixture.activePilot();
        clickedOccupied.carried = Controls.fireControl();
        clickedOccupied.addChestSlot(0, new ItemStack(Items.STICK));
        clickedOccupied.addPlayerSlot(4, Controls.fireControl());
        assertFalse(clickedOccupied.decide(0, 0, ContainerInput.PICKUP_ALL));

        SlotDecisionFixture full = SlotDecisionFixture.activePilot();
        full.carried = Controls.fireControl();
        full.carried.setCount(full.carried.getMaxStackSize());
        full.addChestSlot(0, ItemStack.EMPTY);
        full.addPlayerSlot(4, Controls.fireControl());
        assertFalse(full.decide(0, 0, ContainerInput.PICKUP_ALL));

        SlotDecisionFixture vetoed = SlotDecisionFixture.activePilot();
        vetoed.carried = Controls.fireControl();
        vetoed.canTakeForPickupAll = false;
        vetoed.addChestSlot(0, ItemStack.EMPTY);
        vetoed.addPlayerSlot(4, Controls.fireControl());
        assertFalse(vetoed.decide(0, 0, ContainerInput.PICKUP_ALL));

        SlotDecisionFixture immovable = SlotDecisionFixture.activePilot();
        immovable.carried = Controls.fireControl();
        immovable.addChestSlot(0, ItemStack.EMPTY);
        immovable.addPlayerSlot(4, Controls.fireControl(), false);
        assertFalse(immovable.decide(0, 0, ContainerInput.PICKUP_ALL));
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
            assertTrue(Components.is(
                    Controls.fireControl(inventory, active, inventory).orElseThrow(),
                    Components.Control.FIRE));
            assertTrue(Components.is(
                    Controls.cryControl(inventory, active, inventory).orElseThrow(),
                    Components.Control.CRY));
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
        assertTrue(Components.is(inventory.peek(4), Components.Control.FIRE));
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

    @Test
    void onlyTheControllingFirstPassengerOfThePersistedHappyGhastIsAdmitted() {
        UUID ghastId = UUID.fromString("fd4a9cc6-c4d9-4a5b-9c69-bab1bd621d0a");
        TestGhast ghast = new TestGhast(ghastId);
        TestRider pilot = new TestRider();
        TestRider passenger = new TestRider();
        ghast.firstPassenger = pilot;
        ghast.controller = pilot;
        pilot.vehicle = ghast;
        passenger.vehicle = ghast;
        RiderState active = activeState(ghastId, 40L);
        pilot.mainHand = Controls.cryControl();
        passenger.mainHand = Controls.cryControl();
        pilot.slots[4] = Controls.fireControl();
        pilot.slots[5] = Controls.cryControl();
        passenger.slots[4] = Controls.fireControl();
        passenger.slots[5] = Controls.cryControl();

        Controls.Admission accepted = Controls.handleUseItem(
                pilot, InteractionHand.MAIN_HAND,
                active, 41L, TestControlAccess.INSTANCE);
        Controls.Admission rejected = Controls.handleUseItem(
                passenger, InteractionHand.MAIN_HAND,
                active, 41L, TestControlAccess.INSTANCE);

        assertEquals(Controls.ControlIntent.CRY, accepted.intent());
        assertEquals(41L, accepted.state().lastHandledTick());
        assertEquals(Controls.ControlIntent.NONE, rejected.intent());
        assertSame(active, rejected.state());
    }

    @Test
    void useEntityRejectsAnArbitraryTargetWithoutSpendingTheTickThenAcceptsTheRiddenGhast() {
        UUID ghastId = UUID.fromString("d68bfd39-9457-4940-b4f4-dddc81d19886");
        TestGhast riddenGhast = new TestGhast(ghastId);
        TestGhast otherTarget = new TestGhast(UUID.randomUUID());
        TestRider pilot = new TestRider();
        riddenGhast.firstPassenger = pilot;
        riddenGhast.controller = pilot;
        pilot.vehicle = riddenGhast;
        pilot.mainHand = Controls.cryControl();
        pilot.slots[4] = Controls.fireControl();
        pilot.slots[5] = Controls.cryControl();
        RiderState active = activeState(ghastId, 50L);

        Controls.Admission ignored = Controls.handleUseEntity(
                pilot, otherTarget, InteractionHand.MAIN_HAND,
                active, 51L, TestControlAccess.INSTANCE);
        Controls.Admission accepted = Controls.handleUseEntity(
                pilot, riddenGhast, InteractionHand.MAIN_HAND,
                ignored.state(), 51L, TestControlAccess.INSTANCE);

        assertEquals(Controls.ControlIntent.NONE, ignored.intent());
        assertSame(active, ignored.state());
        assertEquals(Controls.ControlIntent.CRY, accepted.intent());
        assertEquals(51L, accepted.state().lastHandledTick());
    }

    @Test
    void explicitAdmissionSettingsOverrideDifferentGlobalForHoldClickAndCry(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        UUID ghastId = UUID.fromString("4ee63053-0f5f-41c2-b65f-b70a29535530");
        TestGhast ghast = new TestGhast(ghastId);
        TestRider pilot = new TestRider();
        ghast.firstPassenger = pilot;
        ghast.controller = pilot;
        pilot.vehicle = ghast;
        pilot.slots[4] = Controls.fireControl();
        pilot.slots[5] = Controls.cryControl();
        RiderState active = activeState(ghastId, 100L);
        Config.Controls supplied = new Config.Controls(
                4, 5, "minecraft:fire_charge", "minecraft:ghast_tear", false, true, true);
        try {
            Files.writeString(file, """
                    {"controls":{"holdToFire":true,"allowPlainItems":false}}
                    """);
            Config.reload(file);
            assertTrue(Config.current().controls().holdToFire());
            assertFalse(Config.current().controls().allowPlainItems());

            java.lang.reflect.Method sampleHeld = explicitAdmissionMethod("sampleHeld",
                    Object.class, RiderState.class, long.class, Config.Controls.class,
                    Controls.ControlAccess.class);
            java.lang.reflect.Method handleUseItem = explicitAdmissionMethod("handleUseItem",
                    Object.class, InteractionHand.class, RiderState.class, long.class,
                    Config.Controls.class, Controls.ControlAccess.class);
            java.lang.reflect.Method handleUseEntity = explicitAdmissionMethod("handleUseEntity",
                    Object.class, Object.class, InteractionHand.class, RiderState.class, long.class,
                    Config.Controls.class, Controls.ControlAccess.class);

            pilot.observedUse = observe(true, InteractionHand.MAIN_HAND, Controls.fireControl());
            Controls.Admission held = (Controls.Admission) sampleHeld.invoke(
                    null, pilot, active, 101L, supplied, TestControlAccess.INSTANCE);
            pilot.mainHand = Controls.fireControl();
            Controls.Admission click = (Controls.Admission) handleUseItem.invoke(
                    null, pilot, InteractionHand.MAIN_HAND, active, 101L, supplied,
                    TestControlAccess.INSTANCE);
            pilot.mainHand = new ItemStack(Items.GHAST_TEAR);
            Controls.Admission cry = (Controls.Admission) handleUseEntity.invoke(
                    null, pilot, ghast, InteractionHand.MAIN_HAND, active, 102L, supplied,
                    TestControlAccess.INSTANCE);

            assertEquals(Controls.ControlIntent.NONE, held.intent());
            assertSame(active, held.state());
            assertEquals(Controls.ControlIntent.FIRE, click.intent());
            assertEquals(Controls.ControlIntent.CRY, cry.intent());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void explicitAdmissionPathContainsNoGlobalConfigReadWhileConveniencePathsReadOnce()
            throws IOException {
        ClassNode controls = BytecodeTestSupport.classNode(
                "xyz.pyrehaven.happyartillery.Controls");
        String prefix = "xyz/pyrehaven/happyartillery/";
        String itemExplicitDescriptor =
                "(Ljava/lang/Object;Lnet/minecraft/world/InteractionHand;L" + prefix
                        + "RiderState;JL" + prefix + "Config$Controls;L" + prefix
                        + "Controls$ControlAccess;)L" + prefix + "Controls$Admission;";
        String entityExplicitDescriptor =
                "(Ljava/lang/Object;Ljava/lang/Object;Lnet/minecraft/world/InteractionHand;L"
                        + prefix + "RiderState;JL" + prefix + "Config$Controls;L" + prefix
                        + "Controls$ControlAccess;)L" + prefix + "Controls$Admission;";
        String heldExplicitDescriptor =
                "(Ljava/lang/Object;L" + prefix + "RiderState;JL" + prefix
                        + "Config$Controls;L" + prefix + "Controls$ControlAccess;)L"
                        + prefix + "Controls$Admission;";
        List<MethodNode> explicit = List.of(
                exactControlMethod(controls, "handleUseItem",
                        itemExplicitDescriptor),
                exactControlMethod(controls, "handleUseEntity",
                        entityExplicitDescriptor),
                exactControlMethod(controls, "sampleHeld",
                        heldExplicitDescriptor),
                exactControlMethod(controls, "admit",
                        "(Ljava/lang/Object;L" + prefix
                                + "Controls$CallbackSource;Lnet/minecraft/world/InteractionHand;"
                                + "Lnet/minecraft/world/item/ItemStack;Ljava/util/Optional;L" + prefix
                                + "RiderState;JL" + prefix + "Config$Controls;L" + prefix
                                + "Controls$ControlAccess;)L" + prefix + "Controls$Admission;"),
                exactControlMethod(controls, "classify",
                        "(Ljava/lang/Object;L" + prefix
                                + "Controls$CallbackSource;Lnet/minecraft/world/item/ItemStack;L"
                                + prefix + "RiderState;L" + prefix + "Config$Controls;L" + prefix
                                + "Controls$ControlAccess;)L" + prefix + "Controls$ControlIntent;"));
        for (MethodNode method : explicit) {
            assertEquals(0, configCurrentCalls(method), method.name + method.desc);
        }

        List<MethodNode> concreteExplicit = List.of(
                exactControlMethod(controls, "handleUseItem",
                        "(Lnet/minecraft/server/level/ServerPlayer;"
                                + "Lnet/minecraft/world/InteractionHand;L" + prefix
                                + "RiderState;JL" + prefix + "Config$Controls;)L" + prefix
                                + "Controls$Admission;"),
                exactControlMethod(controls, "handleUseEntity",
                        "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity;"
                                + "Lnet/minecraft/world/InteractionHand;L" + prefix
                                + "RiderState;JL" + prefix + "Config$Controls;)L" + prefix
                                + "Controls$Admission;"),
                exactControlMethod(controls, "sampleHeld",
                        "(Lnet/minecraft/server/level/ServerPlayer;L" + prefix
                                + "RiderState;JL" + prefix + "Config$Controls;)L" + prefix
                                + "Controls$Admission;"));
        assertExactConfigWrapper(concreteExplicit.get(0), false, 5,
                List.of("ALOAD 0", "ALOAD 1", "ALOAD 2", "LLOAD 3", "ALOAD 5"),
                "handleUseItem", itemExplicitDescriptor, true);
        assertExactConfigWrapper(concreteExplicit.get(1), false, 6,
                List.of("ALOAD 0", "ALOAD 1", "ALOAD 2", "ALOAD 3", "LLOAD 4", "ALOAD 6"),
                "handleUseEntity", entityExplicitDescriptor, true);
        assertExactConfigWrapper(concreteExplicit.get(2), false, 4,
                List.of("ALOAD 0", "ALOAD 1", "LLOAD 2", "ALOAD 4"),
                "sampleHeld", heldExplicitDescriptor, true);

        List<MethodNode> convenience = List.of(
                exactControlMethod(controls, "handleUseItem",
                        "(Ljava/lang/Object;Lnet/minecraft/world/InteractionHand;L" + prefix
                                + "RiderState;JL" + prefix + "Controls$ControlAccess;)L" + prefix
                                + "Controls$Admission;"),
                exactControlMethod(controls, "handleUseEntity",
                        "(Ljava/lang/Object;Ljava/lang/Object;Lnet/minecraft/world/InteractionHand;L"
                                + prefix + "RiderState;JL" + prefix + "Controls$ControlAccess;)L"
                                + prefix + "Controls$Admission;"),
                exactControlMethod(controls, "sampleHeld",
                        "(Ljava/lang/Object;L" + prefix + "RiderState;JL" + prefix
                                + "Controls$ControlAccess;)L" + prefix + "Controls$Admission;"));
        assertExactConfigWrapper(convenience.get(0), true, -1,
                List.of("ALOAD 0", "ALOAD 1", "ALOAD 2", "LLOAD 3", "ALOAD 5"),
                "handleUseItem", itemExplicitDescriptor, false);
        assertExactConfigWrapper(convenience.get(1), true, -1,
                List.of("ALOAD 0", "ALOAD 1", "ALOAD 2", "ALOAD 3", "LLOAD 4", "ALOAD 6"),
                "handleUseEntity", entityExplicitDescriptor, false);
        assertExactConfigWrapper(convenience.get(2), true, -1,
                List.of("ALOAD 0", "ALOAD 1", "LLOAD 2", "ALOAD 4"),
                "sampleHeld", heldExplicitDescriptor, false);

        List<MethodNode> concreteConvenience = List.of(
                exactControlMethod(controls, "handleUseItem",
                        "(Lnet/minecraft/server/level/ServerPlayer;"
                                + "Lnet/minecraft/world/InteractionHand;L" + prefix
                                + "RiderState;J)L" + prefix + "Controls$Admission;"),
                exactControlMethod(controls, "handleUseEntity",
                        "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity;"
                                + "Lnet/minecraft/world/InteractionHand;L" + prefix
                                + "RiderState;J)L" + prefix + "Controls$Admission;"),
                exactControlMethod(controls, "sampleHeld",
                        "(Lnet/minecraft/server/level/ServerPlayer;L" + prefix
                                + "RiderState;J)L" + prefix + "Controls$Admission;"));
        assertExactConfigWrapper(concreteConvenience.get(0), true, 5,
                List.of("ALOAD 0", "ALOAD 1", "ALOAD 2", "LLOAD 3", "ALOAD 5"),
                "handleUseItem", itemExplicitDescriptor, true);
        assertExactConfigWrapper(concreteConvenience.get(1), true, 6,
                List.of("ALOAD 0", "ALOAD 1", "ALOAD 2", "ALOAD 3", "LLOAD 4", "ALOAD 6"),
                "handleUseEntity", entityExplicitDescriptor, true);
        assertExactConfigWrapper(concreteConvenience.get(2), true, 4,
                List.of("ALOAD 0", "ALOAD 1", "LLOAD 2", "ALOAD 4"),
                "sampleHeld", heldExplicitDescriptor, true);
    }

    @Test
    void callbacksHandsHoldSamplingAndTickDedupShareOneAdmissionPath(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        UUID ghastId = UUID.fromString("616c2e80-dde7-4c21-bdad-a10913b31e5d");
        TestGhast ghast = new TestGhast(ghastId);
        TestRider pilot = new TestRider();
        ghast.firstPassenger = pilot;
        ghast.controller = pilot;
        pilot.vehicle = ghast;
        pilot.slots[4] = Controls.fireControl();
        pilot.slots[5] = Controls.cryControl();
        RiderState active = activeState(ghastId, 70L);
        try {
            pilot.offHand = Controls.cryControl();
            Controls.Admission item = Controls.handleUseItem(
                    pilot, InteractionHand.OFF_HAND,
                    active, 71L, TestControlAccess.INSTANCE);
            pilot.mainHand = Controls.cryControl();
            Controls.Admission duplicate = Controls.handleUseEntity(
                    pilot, ghast, InteractionHand.MAIN_HAND,
                    item.state(), 71L, TestControlAccess.INSTANCE);
            Controls.Admission nextTick = Controls.handleUseEntity(
                    pilot, ghast, InteractionHand.MAIN_HAND,
                    item.state(), 72L, TestControlAccess.INSTANCE);

            assertEquals(Controls.ControlIntent.CRY, item.intent());
            assertEquals(Controls.ControlIntent.NONE, duplicate.intent());
            assertSame(item.state(), duplicate.state());
            assertEquals(Controls.ControlIntent.CRY, nextTick.intent());

            pilot.observedUse = observe(true, InteractionHand.OFF_HAND, Controls.fireControl());
            Controls.Admission held = Controls.sampleHeld(
                    pilot, nextTick.state(), 73L, TestControlAccess.INSTANCE);
            pilot.mainHand = Controls.cryControl();
            Controls.Admission callbackAfterHold = Controls.handleUseEntity(
                    pilot, ghast, InteractionHand.MAIN_HAND,
                    held.state(), 73L, TestControlAccess.INSTANCE);
            Controls.Admission heldWithoutAnotherPacket = Controls.sampleHeld(
                    pilot, held.state(), 74L, TestControlAccess.INSTANCE);
            assertEquals(Controls.ControlIntent.FIRE, held.intent());
            assertEquals(Controls.ControlIntent.NONE, callbackAfterHold.intent());
            assertSame(held.state(), callbackAfterHold.state());
            assertEquals(Controls.ControlIntent.FIRE, heldWithoutAnotherPacket.intent());

            Files.writeString(file, """
                    {"controls":{"holdToFire":false}}
                    """);
            Config.reload(file);
            pilot.mainHand = Controls.fireControl();
            pilot.observedUse = observe(false, InteractionHand.OFF_HAND, ItemStack.EMPTY);
            Controls.Admission released = Controls.sampleHeld(
                    pilot, heldWithoutAnotherPacket.state(), 75L, TestControlAccess.INSTANCE);
            Controls.Admission click = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND,
                    heldWithoutAnotherPacket.state(), 75L, TestControlAccess.INSTANCE);
            Controls.Admission noHoldInClickMode = Controls.sampleHeld(
                    pilot, click.state(), 76L, TestControlAccess.INSTANCE);
            assertEquals(Controls.ControlIntent.NONE, released.intent());
            assertSame(heldWithoutAnotherPacket.state(), released.state());
            assertEquals(Controls.ControlIntent.FIRE, click.intent());
            assertEquals(Controls.ControlIntent.NONE, noHoldInClickMode.intent());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    @Test
    void rejectedInputsSpendNoTickAndPlainItemsRequireLiveExplicitOptIn(@TempDir Path directory)
            throws Exception {
        Config original = Config.current();
        Path file = directory.resolve("happy-artillery.json");
        UUID ghastId = UUID.fromString("815abdfa-9ea1-48e5-863f-a863f1be7905");
        TestGhast ghast = new TestGhast(ghastId);
        TestRider pilot = new TestRider();
        ghast.firstPassenger = pilot;
        ghast.controller = pilot;
        pilot.vehicle = ghast;
        pilot.slots[4] = Controls.fireControl();
        pilot.slots[5] = Controls.cryControl();
        RiderState active = activeState(ghastId, 90L);
        try {
            pilot.mainHand = new ItemStack(Items.GHAST_TEAR);
            Controls.Admission plainDisabled = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND,
                    active, 91L, TestControlAccess.INSTANCE);
            ItemStack invalidMarker = Controls.fireControl();
            CompoundTag invalidTag = new CompoundTag();
            invalidTag.putString("happy-artillery:control", "invalid");
            invalidMarker.set(DataComponents.CUSTOM_DATA, CustomData.of(invalidTag));
            pilot.mainHand = invalidMarker;
            Controls.Admission ambiguousMarked = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND,
                    active, 91L, TestControlAccess.INSTANCE);
            pilot.mainHand = Controls.cryControl();
            pilot.slots[5] = new ItemStack(Items.GHAST_TEAR);
            Controls.Admission missingActiveMarker = Controls.handleUseEntity(
                    pilot, ghast, InteractionHand.MAIN_HAND,
                    active, 91L, TestControlAccess.INSTANCE);
            pilot.slots[5] = Controls.cryControl();
            pilot.vehicle = null;
            Controls.Admission unridden = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND,
                    active, 91L, TestControlAccess.INSTANCE);
            pilot.vehicle = ghast;
            ghast.controller = new TestRider();
            Controls.Admission nonPilot = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND,
                    active, 91L, TestControlAccess.INSTANCE);
            ghast.controller = pilot;
            RiderState wrongGhast = activeState(UUID.randomUUID(), 90L);
            Controls.Admission wrongPersistedGhast = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND,
                    wrongGhast, 91L, TestControlAccess.INSTANCE);

            for (Controls.Admission rejection : List.of(
                    plainDisabled, ambiguousMarked, missingActiveMarker, unridden, nonPilot,
                    wrongPersistedGhast)) {
                assertEquals(Controls.ControlIntent.NONE, rejection.intent());
                assertEquals(90L, rejection.state().lastHandledTick());
            }

            Controls.Admission acceptedAfterRejections = Controls.handleUseEntity(
                    pilot, ghast, InteractionHand.MAIN_HAND,
                    active, 91L, TestControlAccess.INSTANCE);
            assertEquals(Controls.ControlIntent.CRY, acceptedAfterRejections.intent());
            assertNotSame(active, acceptedAfterRejections.state());
            assertEquals(active.fireStash(), acceptedAfterRejections.state().fireStash());
            assertEquals(active.cryStash(), acceptedAfterRejections.state().cryStash());
            assertEquals(active.riddenGhastId(), acceptedAfterRejections.state().riddenGhastId());
            assertEquals(active.hudCache(), acceptedAfterRejections.state().hudCache());

            Files.writeString(file, """
                    {"controls":{"fireSlot":1,"crySlot":2,"allowPlainItems":true}}
                    """);
            Config.reload(file);
            pilot.mainHand = new ItemStack(Items.GHAST_TEAR);
            Controls.Admission plainEnabledWithPersistedSlots = Controls.handleUseItem(
                    pilot, InteractionHand.MAIN_HAND,
                    active, 92L, TestControlAccess.INSTANCE);
            assertEquals(Controls.ControlIntent.CRY, plainEnabledWithPersistedSlots.intent());
        } finally {
            Files.writeString(file, new com.google.gson.Gson().toJson(original));
            Config.reload(file);
        }
    }

    private static MethodNode exactControlMethod(
            ClassNode owner, String name, String descriptor) {
        List<MethodNode> matches = owner.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .toList();
        assertEquals(1, matches.size(), owner.name + "." + name + descriptor);
        return matches.getFirst();
    }

    private static long configCurrentCalls(MethodNode method) {
        return Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.equals("xyz/pyrehaven/happyartillery/Config")
                        && call.name.equals("current") && call.desc.equals(
                                "()Lxyz/pyrehaven/happyartillery/Config;"))
                .count();
    }

    private static void assertExactConfigWrapper(
            MethodNode method,
            boolean capturesCurrent,
            int settingsLocal,
            List<String> argumentLoads,
            String targetName,
            String targetDescriptor,
            boolean productionAccess) {
        List<String> expected = new ArrayList<>();
        if (capturesCurrent && settingsLocal >= 0) {
            expected.add("INVOKESTATIC xyz/pyrehaven/happyartillery/Config.current "
                    + "()Lxyz/pyrehaven/happyartillery/Config;");
            expected.add("INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Config.controls "
                    + "()Lxyz/pyrehaven/happyartillery/Config$Controls;");
            expected.add("ASTORE " + settingsLocal);
        }
        expected.addAll(argumentLoads.subList(0, argumentLoads.size() - (capturesCurrent && settingsLocal < 0 ? 1 : 0)));
        if (capturesCurrent && settingsLocal < 0) {
            expected.add("INVOKESTATIC xyz/pyrehaven/happyartillery/Config.current "
                    + "()Lxyz/pyrehaven/happyartillery/Config;");
            expected.add("INVOKEVIRTUAL xyz/pyrehaven/happyartillery/Config.controls "
                    + "()Lxyz/pyrehaven/happyartillery/Config$Controls;");
            expected.add(argumentLoads.get(argumentLoads.size() - 1));
        }
        if (productionAccess) {
            expected.add("GETSTATIC xyz/pyrehaven/happyartillery/"
                    + "Controls$ServerPlayerControlAccess.INSTANCE "
                    + "Lxyz/pyrehaven/happyartillery/Controls$ServerPlayerControlAccess;");
        }
        expected.add("INVOKESTATIC xyz/pyrehaven/happyartillery/Controls."
                + targetName + " " + targetDescriptor);
        expected.add("ARETURN");
        assertEquals(expected, controlInstructionShape(method), method.name + method.desc);
    }

    private static List<String> controlInstructionShape(MethodNode method) {
        return Stream.iterate(method.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(instruction -> instruction.getOpcode() >= 0)
                .map(ControlsTest::controlInstruction)
                .toList();
    }

    private static String controlInstruction(org.objectweb.asm.tree.AbstractInsnNode instruction) {
        String opcode = switch (instruction.getOpcode()) {
            case org.objectweb.asm.Opcodes.ALOAD -> "ALOAD";
            case org.objectweb.asm.Opcodes.LLOAD -> "LLOAD";
            case org.objectweb.asm.Opcodes.ASTORE -> "ASTORE";
            case org.objectweb.asm.Opcodes.GETSTATIC -> "GETSTATIC";
            case org.objectweb.asm.Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case org.objectweb.asm.Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case org.objectweb.asm.Opcodes.ARETURN -> "ARETURN";
            default -> "OPCODE_" + instruction.getOpcode();
        };
        if (instruction instanceof org.objectweb.asm.tree.VarInsnNode variable) {
            return opcode + " " + variable.var;
        }
        if (instruction instanceof FieldInsnNode field) {
            return opcode + " " + field.owner + "." + field.name + " " + field.desc;
        }
        if (instruction instanceof MethodInsnNode call) {
            return opcode + " " + call.owner + "." + call.name + " " + call.desc;
        }
        return opcode;
    }

    private static java.lang.reflect.Method explicitAdmissionMethod(
            String name, Class<?>... parameterTypes) {
        try {
            return Controls.class.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("explicit config admission seam is missing: " + name, missing);
        }
    }

    private static RiderState activeState(UUID ghastId, long lastHandledTick) {
        return new RiderState(
                Optional.of(new RiderState.StashedStack(4, ItemStack.EMPTY)),
                Optional.of(new RiderState.StashedStack(5, ItemStack.EMPTY)),
                Optional.of(ghastId),
                lastHandledTick,
                Optional.of(new RiderState.HudCache(0.25, "white", "READY", 39L)));
    }

    private static Controls.ObservedUse observe(
            boolean using, InteractionHand hand, ItemStack stack) {
        RecordingObservation source = new RecordingObservation(using, hand, stack);
        return Controls.observeUse(source, source);
    }

    private static Stream<Arguments> controlStacks() {
        return Stream.of(
                Arguments.of("fire", Controls.fireControl(),
                        Components.Control.FIRE, Components.Control.CRY),
                Arguments.of("cry", Controls.cryControl(),
                        Components.Control.CRY, Components.Control.FIRE));
    }


    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream().filter(value -> value.desc.equals(descriptor)).findFirst().orElse(null);
    }

    private static MethodNode injectedHandler(ClassNode mixin) {
        return mixin.methods.stream()
                .filter(method -> annotation(method.visibleAnnotations,
                        "Lorg/spongepowered/asm/mixin/injection/Inject;") != null)
                .findFirst().orElseThrow();
    }

    private static void assertMixinDelegatesOnlyToControls(
            MethodNode handler, String controlsMethod, String controlsDescriptor) {
        List<MethodInsnNode> calls = Stream.iterate(
                        handler.instructions.getFirst(), java.util.Objects::nonNull,
                        org.objectweb.asm.tree.AbstractInsnNode::getNext)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast).toList();
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "xyz/pyrehaven/happyartillery/Controls")
                && call.name.equals(controlsMethod)
                && call.desc.equals(controlsDescriptor)).count());
        assertEquals(0, calls.stream().filter(call -> call.owner.startsWith(
                "xyz/pyrehaven/happyartillery/")
                && !call.owner.equals("xyz/pyrehaven/happyartillery/Controls")
                && !call.owner.equals(
                        "xyz/pyrehaven/happyartillery/Controls$QuickCraftSnapshot")).count());
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(
                "org/spongepowered/asm/mixin/injection/callback/CallbackInfo")
                && call.name.equals("cancel") && call.desc.equals("()V")).count());
    }

    private static Object annotationValue(AnnotationNode annotation, String name) {
        assertNotNull(annotation);
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(name)) {
                return annotation.values.get(index + 1);
            }
        }
        throw new AssertionError("Missing annotation value " + name);
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
            implements Controls.DeathDropAccess<RecordingInventory> {
        private final ItemStack[] stacks;
        private final List<String> events = new ArrayList<>();
        private List<ItemStack> drops = List.of();
        private Optional<RiderState> attachedState = Optional.empty();
        private int failOnWrite;
        private int writes;
        private boolean failOnReplace;

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

        private void attach(RiderState state) {
            attachedState = Optional.of(state);
        }

        private Optional<RiderState> attachedState() {
            return attachedState;
        }

        private void snapshotDrops() {
            events.add("drop-snapshot");
            drops = Arrays.stream(stacks).map(ItemStack::copy).toList();
        }

        private List<ItemStack> drops() {
            return drops;
        }

        private void failOnWrite(int writeNumber) {
            failOnWrite = writeNumber;
        }

        private void failOnReplace() {
            failOnReplace = true;
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

        @Override
        public Optional<RiderState> state(RecordingInventory inventory) {
            return attachedState;
        }

        @Override
        public void replaceState(RecordingInventory inventory, RiderState state) {
            if (failOnReplace) {
                throw new IllegalStateException("fixture replace failure");
            }
            events.add("replace-state");
            attachedState = Optional.of(state);
        }
    }

    private static final class SlotDecisionFixture
            implements Controls.ContainerDecisionAccess<
                    SlotDecisionFixture, TestGhast, SlotDecisionFixture, TestMenuSlot, Object> {
        private final Object inventory = new Object();
        private final Object chest = new Object();
        private final List<TestMenuSlot> slots = new ArrayList<>();
        private final TestRider rider = new TestRider();
        private final TestGhast ghast;
        private Optional<RiderState> state;
        private ItemStack carried = ItemStack.EMPTY;
        private boolean infiniteMaterials;
        private boolean canDropItems = true;
        private int selectedSlot = 4;
        private boolean canTakeForPickupAll = true;
        private boolean canQuickReplace = true;
        private boolean canDragTo = true;
        private boolean quickMoveDestination = true;
        private int quickcraftStatus;
        private int quickcraftType;
        private int quickcraftSelectedCount;
        private final ItemStack[] inventoryStacks = new ItemStack[41];
        private int slotReads;

        private SlotDecisionFixture(UUID ghastId, Optional<RiderState> state) {
            ghast = new TestGhast(ghastId);
            this.state = state;
            Arrays.fill(inventoryStacks, ItemStack.EMPTY);
        }

        static SlotDecisionFixture activePilot() {
            UUID id = UUID.fromString("fc50d08b-e6a0-441b-8e5f-15c5cdbec56d");
            SlotDecisionFixture fixture = new SlotDecisionFixture(id, Optional.of(activeState(id, 1L)));
            fixture.rider.vehicle = fixture.ghast;
            fixture.ghast.firstPassenger = fixture.rider;
            fixture.ghast.controller = fixture.rider;
            return fixture;
        }

        private void addPlayerSlot(int inventoryIndex, ItemStack stack) {
            addPlayerSlot(inventoryIndex, stack, true);
        }

        private void addPlayerSlot(int inventoryIndex, ItemStack stack, boolean mayPickup) {
            addPlayerSlot(inventoryIndex, stack, mayPickup, true, stack.getMaxStackSize());
        }

        private void addPlayerSlot(
                int inventoryIndex, ItemStack stack, boolean mayPickup, boolean mayPlace, int maxStackSize) {
            slots.add(new TestMenuSlot(
                    inventory, inventoryIndex, stack.copy(), mayPickup, mayPlace, maxStackSize));
            inventoryStacks[inventoryIndex] = stack.copy();
        }

        private void addChestSlot(int containerIndex, ItemStack stack) {
            slots.add(new TestMenuSlot(
                    chest, containerIndex, stack.copy(), true, true, stack.getMaxStackSize()));
        }

        private boolean decide(int slotId, int button, ContainerInput input) {
            Set<TestMenuSlot> selected = quickcraftSelectedCount == 0
                    ? Set.of()
                    : Set.copyOf(slots.subList(0, Math.min(quickcraftSelectedCount, slots.size())));
            return Controls.shouldCancelContainerMutation(
                    this, this, slotId, button, input,
                    new Controls.QuickCraftSnapshot(quickcraftStatus, quickcraftType, selected), this);
        }

        private boolean decideSelectedDrop() {
            return Controls.shouldCancelSelectedSlotDrop(this, this);
        }

        @Override public Optional<RiderState> state(SlotDecisionFixture player) { return state; }
        @Override public Optional<TestGhast> riddenHappyGhast(SlotDecisionFixture player) {
            return rider.vehicle == ghast ? Optional.of(ghast) : Optional.empty();
        }
        @Override public boolean isControllingFirstPassenger(SlotDecisionFixture player, TestGhast target) {
            return target.firstPassenger == rider && target.controller == rider;
        }
        @Override public UUID ghastId(TestGhast target) { return target.id; }
        @Override public Object playerInventory(SlotDecisionFixture player) { return inventory; }
        @Override public int selectedSlot(SlotDecisionFixture player) { return selectedSlot; }
        @Override public int slotCount(SlotDecisionFixture menu) { return slots.size(); }
        @Override public TestMenuSlot slot(SlotDecisionFixture menu, int slotId) {
            slotReads++;
            return slots.get(slotId);
        }
        @Override public Object slotContainer(TestMenuSlot slot) { return slot.container; }
        @Override public int containerSlot(TestMenuSlot slot) { return slot.containerSlot; }
        @Override public ItemStack carried(SlotDecisionFixture menu) { return carried.copy(); }
        @Override public boolean hasItem(TestMenuSlot slot) { return !slot.stack.isEmpty(); }
        @Override public ItemStack item(TestMenuSlot slot) { return slot.stack.copy(); }
        @Override public boolean mayPickup(TestMenuSlot slot, SlotDecisionFixture player) { return slot.mayPickup; }
        @Override public boolean mayPlace(TestMenuSlot slot, ItemStack stack) { return slot.mayPlace; }
        @Override public boolean allowModification(TestMenuSlot slot, SlotDecisionFixture player) {
            return slot.allowModification;
        }
        @Override public int maxStackSize(TestMenuSlot slot, ItemStack stack) { return slot.maxStackSize; }
        @Override public int removableAmount(TestMenuSlot slot, int requested) {
            return Math.min(requested, slot.removableAmount);
        }
        @Override public boolean canItemQuickReplace(
                TestMenuSlot slot, ItemStack stack, boolean allowOverflow) { return canQuickReplace; }
        @Override public boolean canDragTo(SlotDecisionFixture menu, TestMenuSlot slot) { return canDragTo; }
        @Override public boolean canQuickMove(
                SlotDecisionFixture menu, SlotDecisionFixture player, int slotId, TestMenuSlot slot) {
            return quickMoveDestination;
        }
        @Override public ItemStack inventoryItem(SlotDecisionFixture player, int slot) {
            return inventoryStacks[slot].copy();
        }
        @Override public boolean canTakeForPickupAll(
                SlotDecisionFixture menu, ItemStack carriedStack, TestMenuSlot slot) {
            return canTakeForPickupAll;
        }
        @Override public boolean hasInfiniteMaterials(SlotDecisionFixture player) { return infiniteMaterials; }
        @Override public boolean canDropItems(SlotDecisionFixture player) { return canDropItems; }
    }

    private static final class TestMenuSlot {
        private final Object container;
        private final int containerSlot;
        private final ItemStack stack;
        private boolean mayPickup;
        private boolean mayPlace;
        private int maxStackSize;
        private boolean allowModification = true;
        private int removableAmount = Integer.MAX_VALUE;

        private TestMenuSlot(
                Object container, int containerSlot, ItemStack stack,
                boolean mayPickup, boolean mayPlace, int maxStackSize) {
            this.container = container;
            this.containerSlot = containerSlot;
            this.stack = stack;
            this.mayPickup = mayPickup;
            this.mayPlace = mayPlace;
            this.maxStackSize = maxStackSize;
        }
    }

    private static final class TestRider {
        private Object vehicle;
        private ItemStack mainHand = ItemStack.EMPTY;
        private ItemStack offHand = ItemStack.EMPTY;
        private final ItemStack[] slots = new ItemStack[9];
        private Controls.ObservedUse observedUse = observe(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        private TestRider() {
            Arrays.fill(slots, ItemStack.EMPTY);
        }
    }

    private static final class TestGhast {
        private final UUID id;
        private TestRider firstPassenger;
        private TestRider controller;

        private TestGhast(UUID id) {
            this.id = id;
        }
    }

    private enum TestControlAccess implements Controls.ControlAccess<TestRider, TestGhast> {
        INSTANCE;

        @Override
        public Optional<TestGhast> riddenHappyGhast(TestRider rider) {
            return rider.vehicle instanceof TestGhast ghast ? Optional.of(ghast) : Optional.empty();
        }

        @Override
        public boolean isControllingFirstPassenger(TestRider rider, TestGhast ghast) {
            return ghast.firstPassenger == rider && ghast.controller == rider;
        }

        @Override
        public UUID ghastId(TestGhast ghast) {
            return ghast.id;
        }

        @Override
        public ItemStack itemInHand(TestRider rider, InteractionHand hand) {
            return (hand == InteractionHand.MAIN_HAND ? rider.mainHand : rider.offHand).copy();
        }

        @Override
        public ItemStack itemAt(TestRider rider, int slot) {
            return rider.slots[slot].copy();
        }

        @Override
        public Controls.ObservedUse observedUse(TestRider rider) {
            return rider.observedUse;
        }
    }
}

final class BytecodeTestSupport {
    private BytecodeTestSupport() {
    }

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
