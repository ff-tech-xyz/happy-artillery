package xyz.pyrehaven.happyartillery;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric composition-root boundary for the future owner graph; gameplay authority remains in
 * {@code FEATURES.md}. This non-deployable scaffold intentionally registers no behavior.
 */
public final class HappyArtillery implements ModInitializer {
    @Override
    public void onInitialize() {
        throw new IllegalStateException(
                "Happy Artillery structural groundwork is not a playable build");
    }
}
