package xyz.pyrehaven.happyartillery;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

/** Sole dimension, temperature, and heat-profile classifier. */
public enum BiomeClass {
    COLD,
    BASE,
    HOT,
    NETHER,
    END;

    public static BiomeClass classify(
            ResourceKey<Level> dimension, double baseTemperature, Config config) {
        Objects.requireNonNull(config, "config");
        if (dimension == Level.NETHER) {
            return NETHER;
        }
        if (dimension == Level.END) {
            return END;
        }
        Config.Heat temperaturePolicy = config.heat();
        if (!temperaturePolicy.unknownDimensionUsesTemperature()) {
            return BASE;
        }
        if (baseTemperature <= temperaturePolicy.coldMaxTemperature()) {
            return COLD;
        }
        if (baseTemperature >= temperaturePolicy.hotMinTemperature()) {
            return HOT;
        }
        return BASE;
    }

    public Config.HeatProfile profile(Config config) {
        Config.Heat heat = Objects.requireNonNull(config, "config").heat();
        return switch (this) {
            case COLD -> heat.cold();
            case BASE -> heat.base();
            case HOT -> heat.hot();
            case NETHER -> heat.nether();
            case END -> heat.end();
        };
    }
}
