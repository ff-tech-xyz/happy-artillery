package xyz.pyrehaven.happyartillery;

import com.mojang.serialization.DynamicOps;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.impl.attachment.AttachmentRegistryImpl;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @Test
    void riderStateContainsOnlyRideTickAndHudCache() {
        assertEquals(List.of("riddenGhastId", "lastHandledTick", "hudCache"),
                Arrays.stream(RiderState.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test
    void riderStateRoundTripsOnlyRideTickAndHudCache() {
        UUID ride = UUID.fromString("8f3f0de4-d4a8-40f5-9a8d-9fcf04e99e22");
        RiderState state = new RiderState(Optional.of(ride), 48_121L,
                Optional.of(new RiderState.HudCache(0.875, "red", "NETHER · NO COOLING", 48_120L)));
        Tag encoded = RiderState.CODEC.encodeStart(registryOps, state).getOrThrow();
        RiderState decoded = RiderState.CODEC.parse(registryOps, encoded).getOrThrow();
        assertEquals(state, decoded);
        assertEquals(ride, decoded.riddenGhastId().orElseThrow());
        assertEquals(48_121L, decoded.lastHandledTick());
        assertEquals(state.hudCache(), decoded.hudCache());
        assertEquals(RiderState.fresh(), new RiderState(
                Optional.empty(), Long.MIN_VALUE, Optional.empty()));
    }

    @Test
    void riderStateRejectsNullValueFields() {
        assertThrows(NullPointerException.class, () -> new RiderState(null, 0L, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new RiderState(Optional.empty(), 0L, null));
        assertThrows(NullPointerException.class,
                () -> new RiderState.HudCache(0.0, null, "", 0L));
        assertThrows(NullPointerException.class,
                () -> new RiderState.HudCache(0.0, "white", null, 0L));
    }

    @Test
    void ghastStatePersistsEveryOverworldGameTimeValue() {
        UUID riderId = UUID.fromString("377b6687-dcea-41a8-b213-724860ce2d25");
        GhastState scheduled = new GhastState(37.5, 12_000L, 12_020L, 12_100L, 12_200L,
                OptionalLong.of(12_040L), Optional.of(riderId));
        Tag encoded = GhastState.CODEC.encodeStart(NbtOps.INSTANCE, scheduled).getOrThrow();
        assertEquals(scheduled, GhastState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void pendingDeadlineAndDetonatingRiderIdentityAreOnePersistedState() {
        UUID riderId = UUID.fromString("e514734c-ad6e-4538-b005-c6fc67b0547e");
        assertThrows(IllegalArgumentException.class,
                () -> new GhastState(1.0, 1L, 1L, 1L, 1L,
                        OptionalLong.of(2L), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new GhastState(1.0, 1L, 1L, 1L, 1L,
                        OptionalLong.empty(), Optional.of(riderId)));
    }

    @Test
    void attachmentReplacementUsesRegisteredOwnerAndImmutableValues() {
        FaithfulAttachmentTarget target = new FaithfulAttachmentTarget();
        RiderState initial = RiderState.fresh();
        RiderState updated = new RiderState(Optional.of(UUID.randomUUID()), 92L,
                Optional.of(new RiderState.HudCache(0.5, "yellow", "READY", 91L)));
        assertNull(RiderState.replace(target, initial));
        assertSame(initial, target.getAttached(RiderState.register()));
        assertSame(initial, RiderState.replace(target, updated));
        assertSame(updated, target.getAttached(RiderState.register()));
    }

    private static final class FaithfulAttachmentTarget implements AttachmentTarget {
        private final IdentityHashMap<AttachmentType<?>, Object> attachments = new IdentityHashMap<>();
        @SuppressWarnings("unchecked")
        @Override public <A> A getAttached(AttachmentType<A> type) { return (A) attachments.get(type); }
        @SuppressWarnings("unchecked")
        @Override public <A> A setAttached(AttachmentType<A> type, A value) {
            return value == null ? (A) attachments.remove(type) : (A) attachments.put(type, value);
        }
    }
}
