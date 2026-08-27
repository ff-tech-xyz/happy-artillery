package xyz.pyrehaven.happyartillery;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.component.Consumable;

import java.util.Objects;

/** Owns the guarded hold consumable and server-observed fire intent seam. */
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

    static FireIntent fireIntent(
            boolean holdToFire,
            ObservedClick click,
            ObservedUse observedUse) {
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(observedUse, "observedUse");
        if (holdToFire) {
            return observedUse.using() && isHoldControl(observedUse.stack())
                    ? FireIntent.HELD
                    : FireIntent.NONE;
        }
        return click.observed() && isHoldControl(click.stack())
                ? FireIntent.CLICK
                : FireIntent.NONE;
    }

    private static boolean isHoldControl(ItemStack stack) {
        return stack.has(Components.FIRE_CONTROL) && !stack.has(Components.CRY_CONTROL);
    }

    enum FireIntent {
        NONE,
        CLICK,
        HELD
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

    record ObservedClick(boolean observed, ItemStack stack) {
        ObservedClick {
            stack = Objects.requireNonNull(stack, "stack").copy();
        }

        static ObservedClick of(ItemStack stack) {
            return new ObservedClick(true, stack);
        }

        static ObservedClick none() {
            return new ObservedClick(false, ItemStack.EMPTY);
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
