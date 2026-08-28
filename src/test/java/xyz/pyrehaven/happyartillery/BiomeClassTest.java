package xyz.pyrehaven.happyartillery;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class BiomeClassTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void vanillaDimensionsAreClassifiedByTheirResourceKeyIdentity() {
        Config config = Config.defaults();
        assertEquals(BiomeClass.NETHER, BiomeClass.classify(Level.NETHER, 0.0, config));
        assertEquals(BiomeClass.END, BiomeClass.classify(Level.END, 2.0, config));
    }

    @Test
    void customDimensionNamesDoNotOverrideInclusiveTemperatureEdges(@TempDir Path directory)
            throws Exception {
        Config.load(directory.resolve("happy-artillery.json"));
        ResourceKey<Level> netherNamed = dimension("example:nether_expanded");
        ResourceKey<Level> endNamed = dimension("example:the_endless");

        Config config = Config.defaults();
        assertEquals(BiomeClass.COLD, BiomeClass.classify(netherNamed, 0.3, config));
        assertEquals(BiomeClass.BASE, BiomeClass.classify(netherNamed, 0.3000001, config));
        assertEquals(BiomeClass.BASE, BiomeClass.classify(endNamed, 0.9999999, config));
        assertEquals(BiomeClass.HOT, BiomeClass.classify(endNamed, 1.0, config));
    }

    @Test
    void unknownDimensionPolicyAndThresholdsAreReadAtEachCallAfterReload(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"heat\":{\"unknownDimensionUsesTemperature\":false}}");
        Config.load(file);
        ResourceKey<Level> custom = dimension("example:moon");

        assertEquals(BiomeClass.BASE, BiomeClass.classify(custom, -100.0, Config.current()));

        Files.writeString(file, """
                {"heat":{
                  "unknownDimensionUsesTemperature":true,
                  "coldMaxTemperature":-2.0,
                  "hotMinTemperature":2.0
                }}
                """);
        Config.reload(file);

        assertEquals(BiomeClass.COLD, BiomeClass.classify(custom, -2.0, Config.current()));
        assertEquals(BiomeClass.BASE, BiomeClass.classify(custom, 0.0, Config.current()));
        assertEquals(BiomeClass.HOT, BiomeClass.classify(custom, 2.0, Config.current()));
    }

    @Test
    void everyClassSelectsItsLiveConfiguredProfile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config initial = Config.load(file);

        assertEquals(initial.heat().cold(), BiomeClass.COLD.profile(initial));
        assertEquals(initial.heat().base(), BiomeClass.BASE.profile(initial));
        assertEquals(initial.heat().hot(), BiomeClass.HOT.profile(initial));
        assertEquals(initial.heat().nether(), BiomeClass.NETHER.profile(initial));
        assertEquals(initial.heat().end(), BiomeClass.END.profile(initial));

        Files.writeString(file, "{\"heat\":{\"cold\":{\"heatPerShot\":9.0,\"coolPerSecond\":8.0}}}");
        Config reloaded = Config.reload(file);

        assertEquals(reloaded.heat().cold(), BiomeClass.COLD.profile(reloaded));
        assertEquals(new Config.HeatProfile(9.0, 8.0), BiomeClass.COLD.profile(reloaded));
    }

    private static ResourceKey<Level> dimension(String identifier) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(identifier));
    }
}
