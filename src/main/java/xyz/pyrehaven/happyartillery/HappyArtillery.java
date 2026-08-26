package xyz.pyrehaven.happyartillery;

import net.fabricmc.api.ModInitializer;

/**
 * Composition root for the future owner graph. This structural scaffold registers no gameplay.
 */
public final class HappyArtillery implements ModInitializer {
    @Override
    public void onInitialize() {
        throw new IllegalStateException(
                "Happy Artillery structural groundwork is not a playable build");
    }
}
