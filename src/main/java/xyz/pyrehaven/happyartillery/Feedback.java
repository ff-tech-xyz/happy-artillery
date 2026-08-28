package xyz.pyrehaven.happyartillery;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.Objects;

/** Sole visible rejection feedback mapping. */
public final class Feedback {
    private Feedback() {
    }

    static <P> void present(Abilities.CryRejection rejection, P player, Access<P> access) {
        Objects.requireNonNull(rejection, "rejection");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(access, "access");
        if (rejection == Abilities.CryRejection.IN_WATER) {
            access.actionBar(player, "Can't use artillery in water");
            access.blockedSound(player);
        }
    }

    static void present(Abilities.CryRejection rejection, ServerPlayer player) {
        present(rejection, player, ServerPlayerFeedbackAccess.INSTANCE);
    }

    interface Access<P> {
        void actionBar(P player, String message);

        void blockedSound(P player);
    }

    enum ServerPlayerFeedbackAccess implements Access<ServerPlayer> {
        INSTANCE;

        @Override
        public void actionBar(ServerPlayer player, String message) {
            player.sendOverlayMessage(Component.literal(message));
        }

        @Override
        public void blockedSound(ServerPlayer player) {
            player.playSound(SoundEvents.FIRE_EXTINGUISH, 1.0F, 1.0F);
        }
    }
}
