package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.DynamicOps;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PersistenceTest {
    private static DynamicOps<Tag> registryOps;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(initializer -> initializer.apply());
        registryOps = RegistryOps.create(NbtOps.INSTANCE, registries);
    }

    @Test
    void stateOwnersRegisterTheirExactPersistentAttachmentTypesOnce() {
        AttachmentType<GhastState> ghastType = GhastState.register();
        AttachmentType<RiderState> riderType = RiderState.register();

        assertSame(ghastType, GhastState.register());
        assertSame(riderType, RiderState.register());
        assertNotSame(ghastType, riderType);
        assertEquals("happy-artillery:ghast_state", ghastType.identifier().toString());
        assertEquals("happy-artillery:rider_state", riderType.identifier().toString());
        assertSame(ghastType, AttachmentRegistryImpl.get(ghastType.identifier()));
        assertSame(riderType, AttachmentRegistryImpl.get(riderType.identifier()));
        assertTrue(ghastType.isPersistent());
        assertTrue(riderType.isPersistent());
        assertSame(GhastState.CODEC, ghastType.persistenceCodec());
        assertSame(RiderState.CODEC, riderType.persistenceCodec());
    }

    @Test
    void ghastStatePersistsEveryOverworldGameTimeValue() {
        GhastState fresh = GhastState.fresh();
        assertEquals(new GhastState(0.0, 0L, 0L, 0L, OptionalLong.empty()), fresh);

        GhastState scheduled = new GhastState(37.5, 12_000L, 12_020L, 12_200L,
                OptionalLong.of(12_040L));
        Tag encoded = GhastState.CODEC.encodeStart(NbtOps.INSTANCE, scheduled).getOrThrow();
        GhastState decoded = GhastState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

        assertEquals(scheduled, decoded);
        assertNotSame(scheduled, decoded);
        assertArrayEquals(serialized(encoded), serialized(
                GhastState.CODEC.encodeStart(NbtOps.INSTANCE, decoded).getOrThrow()));
    }

    @Test
    void stateRecordsRejectNullFields() {
        assertThrows(NullPointerException.class,
                () -> new GhastState(0.0, 0L, 0L, 0L, null));
        assertThrows(NullPointerException.class,
                () -> new RiderState(null, Optional.empty(), Optional.empty(), 0L, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new RiderState(Optional.empty(), null, Optional.empty(), 0L, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new RiderState(Optional.empty(), Optional.empty(), null, 0L, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new RiderState(Optional.empty(), Optional.empty(), Optional.empty(), 0L, null));
        assertThrows(NullPointerException.class,
                () -> new RiderState.StashedStack(0, null));
        assertThrows(NullPointerException.class,
                () -> new RiderState.HudCache(0.0, null, "", 0L));
        assertThrows(NullPointerException.class,
                () -> new RiderState.HudCache(0.0, "white", null, 0L));
    }

    @Test
    void riderStateRoundTripPreservesIndexedStacksAndAllStackData() {
        assertEquals(RiderState.fresh(), new RiderState(
                Optional.empty(), Optional.empty(), Optional.empty(), Long.MIN_VALUE, Optional.empty()));

        ItemStack fireStack = new ItemStack(Items.DIAMOND_SWORD);
        fireStack.setDamageValue(17);
        fireStack.set(DataComponents.REPAIR_COST, 9);
        fireStack.set(DataComponents.CUSTOM_NAME, Component.literal("Fire slot original"));
        ItemStack cryStack = new ItemStack(Items.WRITABLE_BOOK, 7);
        cryStack.set(DataComponents.REPAIR_COST, 3);
        cryStack.set(DataComponents.CUSTOM_NAME, Component.literal("Cry slot original"));
        UUID ghastId = UUID.fromString("8f3f0de4-d4a8-40f5-9a8d-9fcf04e99e22");
        RiderState state = new RiderState(
                Optional.of(new RiderState.StashedStack(4, fireStack)),
                Optional.of(new RiderState.StashedStack(5, cryStack)),
                Optional.of(ghastId),
                48_121L,
                Optional.of(new RiderState.HudCache(
                        0.875, "red", "NETHER · NO COOLING", 48_120L)));

        Tag encoded = RiderState.CODEC.encodeStart(registryOps, state).getOrThrow();
        RiderState decoded = RiderState.CODEC.parse(registryOps, encoded).getOrThrow();
        Tag reencoded = RiderState.CODEC.encodeStart(registryOps, decoded).getOrThrow();
        ItemStack decodedFire = decoded.fireStash().orElseThrow().stack();
        ItemStack decodedCry = decoded.cryStash().orElseThrow().stack();

        assertArrayEquals(serialized(encoded), serialized(reencoded));
        assertEquals(4, decoded.fireStash().orElseThrow().slotIndex());
        assertEquals(5, decoded.cryStash().orElseThrow().slotIndex());
        assertTrue(ItemStack.matches(fireStack, decodedFire));
        assertTrue(ItemStack.matches(cryStack, decodedCry));
        assertEquals(17, decodedFire.getDamageValue());
        assertEquals(9, decodedFire.get(DataComponents.REPAIR_COST));
        assertEquals("Fire slot original", decodedFire.getCustomName().getString());
        assertEquals(7, decodedCry.getCount());
        assertEquals(3, decodedCry.get(DataComponents.REPAIR_COST));
        assertEquals("Cry slot original", decodedCry.getCustomName().getString());
        assertEquals(ghastId, decoded.riddenGhastId().orElseThrow());
        assertEquals(48_121L, decoded.lastHandledTick());
        assertEquals(state.hudCache(), decoded.hudCache());
    }

    @Test
    void riderStateRoundTripPreservesAnEmptyIndexedStack() {
        RiderState state = new RiderState(
                Optional.of(new RiderState.StashedStack(4, ItemStack.EMPTY)),
                Optional.of(new RiderState.StashedStack(5, new ItemStack(Items.WRITABLE_BOOK, 3))),
                Optional.of(UUID.fromString("35941e7c-7aef-47fc-a77b-0cca071790ea")),
                123L,
                Optional.empty());

        Tag encoded = RiderState.CODEC.encodeStart(registryOps, state).getOrThrow();
        RiderState decoded = RiderState.CODEC.parse(registryOps, encoded).getOrThrow();

        assertTrue(decoded.fireStash().orElseThrow().stack().isEmpty());
        assertEquals(4, decoded.fireStash().orElseThrow().slotIndex());
        assertEquals(3, decoded.cryStash().orElseThrow().stack().getCount());
        assertArrayEquals(serialized(encoded), serialized(
                RiderState.CODEC.encodeStart(registryOps, decoded).getOrThrow()));
    }

    @Test
    void stashedStacksAreImmutableValuesWithMatchingEqualsAndHashCode() {
        ItemStack source = new ItemStack(Items.WRITABLE_BOOK, 7);
        source.set(DataComponents.CUSTOM_NAME, Component.literal("Original"));
        RiderState.StashedStack stash = new RiderState.StashedStack(5, source);
        RiderState.StashedStack equalCopy = new RiderState.StashedStack(5, source.copy());

        source.setCount(1);
        ItemStack exposed = stash.stack();
        exposed.setCount(2);

        assertEquals(7, stash.stack().getCount());
        assertEquals("Original", stash.stack().getCustomName().getString());
        assertEquals(stash, equalCopy);
        assertEquals(stash.hashCode(), equalCopy.hashCode());
        assertNotEquals(stash, new RiderState.StashedStack(4, equalCopy.stack()));
        assertNotEquals(stash, new RiderState.StashedStack(5, equalCopy.stack().copyWithCount(6)));
    }

    @Test
    void stashedStackConstructorAcceptsOnlyHotbarSlotIndexes() {
        assertEquals(0, new RiderState.StashedStack(0, ItemStack.EMPTY).slotIndex());
        assertEquals(8, new RiderState.StashedStack(8, ItemStack.EMPTY).slotIndex());
        assertThrows(IllegalArgumentException.class,
                () -> new RiderState.StashedStack(-1, ItemStack.EMPTY));
        assertThrows(IllegalArgumentException.class,
                () -> new RiderState.StashedStack(9, ItemStack.EMPTY));
    }

    @Test
    void stashedStackCodecAcceptsOnlyHotbarSlotIndexes() {
        assertEquals(0, RiderState.StashedStack.CODEC.parse(registryOps, encodedStash(0))
                .getOrThrow().slotIndex());
        assertEquals(8, RiderState.StashedStack.CODEC.parse(registryOps, encodedStash(8))
                .getOrThrow().slotIndex());
        assertTrue(RiderState.StashedStack.CODEC.parse(registryOps, encodedStash(-1)).result().isEmpty());
        assertTrue(RiderState.StashedStack.CODEC.parse(registryOps, encodedStash(9)).result().isEmpty());
    }

    @Test
    void ghastStateReplacementUsesItsRegisteredTypeAndPreservesImmutableValues() {
        FaithfulAttachmentTarget target = new FaithfulAttachmentTarget();
        AttachmentType<GhastState> ghastType = GhastState.register();
        GhastState initial = GhastState.fresh();
        GhastState updated = new GhastState(8.0, 100L, 120L, 140L, OptionalLong.of(160L));

        assertNull(GhastState.replace(target, initial));
        assertSame(initial, target.getAttached(ghastType));
        assertSame(initial, GhastState.replace(target, updated));
        assertSame(updated, target.getAttached(ghastType));
        assertEquals(GhastState.fresh(), initial);
    }

    @Test
    void riderStateReplacementUsesItsRegisteredTypeAndPreservesImmutableValues() {
        FaithfulAttachmentTarget target = new FaithfulAttachmentTarget();
        AttachmentType<RiderState> riderType = RiderState.register();
        RiderState initial = RiderState.fresh();
        RiderState updated = new RiderState(
                Optional.empty(),
                Optional.empty(),
                Optional.of(UUID.fromString("cc6823b4-4b0f-4eaa-af55-f70cf1cb1f36")),
                92L,
                Optional.of(new RiderState.HudCache(0.5, "yellow", "READY", 91L)));

        assertNull(RiderState.replace(target, initial));
        assertSame(initial, target.getAttached(riderType));
        assertSame(initial, RiderState.replace(target, updated));
        assertSame(updated, target.getAttached(riderType));
        assertEquals(RiderState.fresh(), initial);
    }

    private static CompoundTag encodedStash(int slotIndex) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("slot_index", slotIndex);
        tag.put("stack", new CompoundTag());
        return tag;
    }

    private static byte[] serialized(Tag tag) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeAnyTag(tag, new DataOutputStream(bytes));
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FaithfulAttachmentTarget implements AttachmentTarget {
        private final IdentityHashMap<AttachmentType<?>, Object> attachments = new IdentityHashMap<>();

        @SuppressWarnings("unchecked")
        @Override
        public <A> A getAttached(AttachmentType<A> type) {
            return (A) attachments.get(type);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <A> A setAttached(AttachmentType<A> type, A value) {
            return value == null
                    ? (A) attachments.remove(type)
                    : (A) attachments.put(type, value);
        }
    }
}
