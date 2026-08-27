package xyz.pyrehaven.happyartillery;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void holdControlUsesTheExactLongSilentAnimationFreeConsumableApi() {
        ItemStack control = Controls.holdControl(new ItemStack(Items.FIRE_CHARGE));

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
    void holdControlAndObservedUseDefensivelyOwnTheirStacks() {
        ItemStack template = new ItemStack(Items.FIRE_CHARGE, 2);
        ItemStack control = Controls.holdControl(template);
        RecordingObservation source = new RecordingObservation(true, InteractionHand.MAIN_HAND, control);

        Controls.ObservedUse observed = Controls.observeUse(source, source);
        control.setCount(7);
        ItemStack firstRead = observed.stack();
        firstRead.setCount(9);

        assertEquals(2, template.getCount());
        assertNotSame(template, control);
        assertEquals(2, observed.stack().getCount());
        assertNotSame(firstRead, observed.stack());
    }

    @Test
    void holdModeContinuouslyUsesMainAndOffhandServerState() {
        ItemStack control = Controls.holdControl(new ItemStack(Items.FIRE_CHARGE));

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
        ItemStack control = Controls.holdControl(new ItemStack(Items.FIRE_CHARGE));

        Controls.ObservedUse released = observe(false, InteractionHand.MAIN_HAND, ItemStack.EMPTY);

        assertEquals(Controls.FireIntent.NONE,
                Controls.fireIntent(true, Controls.ObservedClick.of(control), released));
    }

    @Test
    void observedUseRejectsUsingStateWithoutAnObservedHand() {
        ItemStack control = Controls.holdControl(new ItemStack(Items.FIRE_CHARGE));

        assertThrows(NullPointerException.class,
                () -> new Controls.ObservedUse(true, null, control));
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
        ItemStack control = Controls.holdControl(new ItemStack(Items.FIRE_CHARGE));
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
    void observationReadsEveryBoundaryValueIntoOneImmutableSnapshot() {
        ItemStack control = Controls.holdControl(new ItemStack(Items.FIRE_CHARGE));
        RecordingObservation source = new RecordingObservation(true, InteractionHand.OFF_HAND, control);

        Controls.ObservedUse observed = Controls.observeUse(source, source);

        assertEquals(1, source.usingReads);
        assertEquals(1, source.handReads);
        assertEquals(1, source.stackReads);
        assertTrue(observed.using());
        assertEquals(InteractionHand.OFF_HAND, observed.hand());
        assertTrue(ItemStack.matches(control, observed.stack()));
    }

    private static Controls.ObservedUse observe(
            boolean using, InteractionHand hand, ItemStack stack) {
        RecordingObservation source = new RecordingObservation(using, hand, stack);
        return Controls.observeUse(source, source);
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
}
