package xyz.pyrehaven.happyartillery;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigTest {
    @TempDir
    Path resetDirectory;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void restoreValidatedDefaults() throws Exception {
        Path file = resetDirectory.resolve("reset-defaults.json");
        Files.deleteIfExists(file);
        Config restored = Config.load(file);
        assertEquals(Config.defaults(), restored);
        assertSame(restored, Config.current());
    }

    @Test
    void configSchemaIsImmutable() {
        assertTrue(Config.class.isRecord(), "Config must be an immutable record");
        assertEquals(List.of("controls", "fire", "heat", "water", "overheat", "cry", "hud"),
                Stream.of(Config.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        assertThrows(NoSuchMethodException.class, () -> Config.class.getDeclaredMethod("preset"));
    }

    @Test
    void controlsSchemaContainsOnlyMovableControlSettings() {
        assertEquals(List.of("fireItem", "cryItem", "holdToFire", "allowPlainItems"),
                Stream.of(Config.Controls.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        assertEquals(1, Config.Controls.class.getDeclaredConstructors().length);
        assertThrows(NoSuchMethodException.class,
                () -> Config.Controls.class.getDeclaredMethod("fireSlot"));
        assertThrows(NoSuchMethodException.class,
                () -> Config.Controls.class.getDeclaredMethod("crySlot"));
        assertThrows(NoSuchMethodException.class,
                () -> Config.Controls.class.getDeclaredMethod("lockControlSlots"));
    }

    @Test
    void waterSchemaContainsOnlyTheFiringGate() {
        assertEquals(List.of("blocksFiring"),
                Stream.of(Config.Water.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        assertEquals(1, Config.Water.class.getDeclaredConstructors().length);
        assertThrows(NoSuchMethodException.class,
                () -> Config.Water.class.getDeclaredMethod("coolPerSecond"));
        assertThrows(NoSuchMethodException.class,
                () -> Config.Water.class.getDeclaredMethod("floor"));
    }

    @Test
    void hudCoolingSchemaIsTypedAndHasNoCompatibilityPath() {
        assertEquals(List.of("bossBar", "actionBar", "refreshTicks", "warningFromPercent", "cooling"),
                Stream.of(Config.Hud.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        assertEquals(List.of(
                        "noCoolingText", "noCoolingColor", "slowMaxPerSecond", "slowColor",
                        "normalMaxPerSecond", "normalColor", "fastColor"),
                Stream.of(Config.Cooling.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        assertEquals(List.of("RED", "GOLD", "GREEN", "BLUE"),
                Stream.of(Config.Color.values()).map(Enum::name).toList());
        assertEquals(1, Config.Hud.class.getDeclaredConstructors().length);
        assertEquals(1, Config.Cooling.class.getDeclaredConstructors().length);
    }

    @Test
    void directValidationRejectsNullCoolingWithExactPath() {
        Config defaults = Config.defaults();
        Config invalid = new Config(
                defaults.controls(), defaults.fire(), defaults.heat(), defaults.water(),
                defaults.overheat(), defaults.cry(),
                new Config.Hud(
                        defaults.hud().bossBar(), defaults.hud().actionBar(),
                        defaults.hud().refreshTicks(), defaults.hud().warningFromPercent(), null));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.validate(invalid));

        assertEquals("hud.cooling must not be null", failure.getMessage());
    }

    @Test
    void defaultsContainTheCompleteSchema() throws ReflectiveOperationException {
        Object defaults = Config.class.getMethod("defaults").invoke(null);

        assertEquals(Map.of(
                "controls", Map.of(
                        "fireItem", "minecraft:fire_charge", "cryItem", "minecraft:ghast_tear",
                        "holdToFire", true, "allowPlainItems", false),
                "fire", Map.of(
                        "shotCooldownSeconds", 0.25, "explosionPower", 1),
                "heat", Map.ofEntries(
                        Map.entry("limit", 100.0), Map.entry("coolingDelayAfterShotSeconds", 1.0),
                        Map.entry("cold", Map.of("heatPerShot", 0.70, "coolPerSecond", 1.0)),
                        Map.entry("base", Map.of("heatPerShot", 1.25, "coolPerSecond", 0.6)),
                        Map.entry("hot", Map.of("heatPerShot", 2.00, "coolPerSecond", 0.5)),
                        Map.entry("nether", Map.of("heatPerShot", 3.00, "coolPerSecond", 0.0)),
                        Map.entry("end", Map.of("heatPerShot", 0.70, "coolPerSecond", 1.0)),
                        Map.entry("coldBiomeMaxTemperature", 0.3),
                        Map.entry("hotBiomeMinTemperature", 1.0),
                        Map.entry("otherDimensionsUseBiomeTemperature", true)),
                "water", Map.of("blocksFiring", true),
                "overheat", Map.ofEntries(
                        Map.entry("fuseTicks", 0), Map.entry("explosionPower", 6.0),
                        Map.entry("fireballCount", 24), Map.entry("fireballSpeed", 0.4),
                        Map.entry("fireballPower", 2), Map.entry("firePlacementAttempts", 24),
                        Map.entry("firePlacementRadius", 8.0), Map.entry("killsGhast", true),
                        Map.entry("breaksBlocks", true)),
                "cry", Map.of("enabled", true, "volume", 10.0, "cooldownSeconds", 10.0),
                "hud", Map.of("bossBar", true, "actionBar", true,
                        "refreshTicks", 4, "warningFromPercent", 85,
                        "cooling", Map.of(
                                "noCoolingText", "NO COOLING", "noCoolingColor", Config.Color.RED,
                                "slowMaxPerSecond", 0.5, "slowColor", Config.Color.GOLD,
                                "normalMaxPerSecond", 1.0, "normalColor", Config.Color.GREEN,
                                "fastColor", Config.Color.BLUE))),
                recordValues(defaults));
    }

    @Test
    void startupLoadPublishesTheValidatedObjectForCallTimeReads(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"holdToFire\":false}}");

        Config loaded = Config.load(file);
        Object active = Config.class.getMethod("current").invoke(null);

        assertSame(loaded, active);
        assertEquals(false, ((Config) active).controls().holdToFire());
    }

    @Test
    void failedReloadPreservesExactActiveObjectAndInvalidBytes(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(file);
        byte[] invalid = "{\"hud\":{\"warningFromPercent\":101}}".getBytes();
        Files.write(file, invalid);

        var reload = Config.class.getMethod("reload", Path.class);
        assertThrows(InvocationTargetException.class, () -> reload.invoke(null, file));

        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
        assertEquals(false, Config.current().controls().holdToFire());
    }

    @Test
    void successfulReloadRewritesCompleteSchemaAndAtomicallySwaps(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(file);
        Files.writeString(file, "{\"overheat\":{\"explosionPower\":9.0}}");

        Config reloaded = Config.reload(file);

        assertNotSame(previous, reloaded);
        assertSame(reloaded, Config.current());
        assertEquals(9.0, reloaded.overheat().explosionPower());
        assertEquals(24, reloaded.overheat().fireballCount());
        assertEquals(new Gson().toJsonTree(reloaded),
                JsonParser.parseString(Files.readString(file)));
    }

    @Test
    void partialKnownKeysOverlayDefaultsAndRewriteOnlyPresetFreeSchema(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"overheat\":{\"explosionPower\":9.0}}");

        Config loaded = Config.load(file);
        JsonObject rewritten = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        assertEquals(9.0, loaded.overheat().explosionPower());
        assertEquals(Config.defaults().overheat().fireballCount(),
                loaded.overheat().fireballCount());
        assertEquals(Set.of("controls", "fire", "heat", "water", "overheat", "cry", "hud"),
                rewritten.keySet());
        assertEquals(new Gson().toJsonTree(loaded), rewritten);
    }

    @Test
    void everyCoolingLeafOverridesIndividuallyAndRewritesUppercaseEnums(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, """
                {"hud":{"cooling":{
                  "noCoolingText":"STILL",
                  "noCoolingColor":"BLUE",
                  "slowMaxPerSecond":0.25,
                  "slowColor":"GREEN",
                  "normalMaxPerSecond":2.5,
                  "normalColor":"GOLD",
                  "fastColor":"RED"
                }}}
                """);

        Config loaded = Config.load(file);
        JsonObject rewritten = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject cooling = rewritten.getAsJsonObject("hud").getAsJsonObject("cooling");

        assertEquals(new Config.Cooling(
                        "STILL", Config.Color.BLUE, 0.25, Config.Color.GREEN,
                        2.5, Config.Color.GOLD, Config.Color.RED),
                loaded.hud().cooling());
        assertEquals(Config.defaults().hud().bossBar(), loaded.hud().bossBar());
        assertEquals(Config.defaults().controls(), loaded.controls());
        assertEquals(Set.of(
                        "noCoolingText", "noCoolingColor", "slowMaxPerSecond", "slowColor",
                        "normalMaxPerSecond", "normalColor", "fastColor"),
                cooling.keySet());
        assertEquals("BLUE", cooling.get("noCoolingColor").getAsString());
        assertEquals("GREEN", cooling.get("slowColor").getAsString());
        assertEquals("GOLD", cooling.get("normalColor").getAsString());
        assertEquals("RED", cooling.get("fastColor").getAsString());
        assertEquals(new Gson().toJsonTree(loaded), rewritten);
    }

    @ParameterizedTest(name = "hud.cooling.{0} overrides independently")
    @MethodSource("individualCoolingOverrides")
    void eachCoolingLeafOverridesWithoutChangingSiblingDefaults(
            String key, String rawValue, Config.Cooling expected, @TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file,
                "{\"hud\":{\"cooling\":{\"" + key + "\":" + rawValue + "}}}");

        Config loaded = Config.load(file);

        assertEquals(expected, loaded.hud().cooling());
        assertEquals(Config.defaults().controls(), loaded.controls());
        assertEquals(new Gson().toJsonTree(loaded),
                JsonParser.parseString(Files.readString(file)));
    }

    @Test
    void missingFileIsCreatedFromValidatedDefaults(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");

        Config loaded = Config.load(file);

        assertEquals(Config.defaults(), loaded);
        assertTrue(Files.isRegularFile(file));
        assertEquals(new Gson().toJsonTree(Config.defaults()),
                JsonParser.parseString(Files.readString(file)));
    }

    @Test
    void unknownRootKeyFailsWithFullPathWithoutChangingStateOrBytes(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = "{\"unknownGroup\":true}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file));

        assertEquals("Unknown config key: unknownGroup", failure.getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @Test
    void unknownNestedKeyFailsWithFullPathWithoutChangingStateOrBytes(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = "{\"heat\":{\"cold\":{\"typo\":1}}}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file));

        assertEquals("Unknown config key: heat.cold.typo", failure.getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @Test
    void unknownCoolingKeyFailsWithFullPathWithoutChangingStateOrBytes(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = "{\"hud\":{\"cooling\":{\"preset\":\"cold\"}}}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file));

        assertEquals("Unknown config key: hud.cooling.preset", failure.getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @ParameterizedTest(name = "removed controls.{0} is rejected")
    @MethodSource("removedControlSettings")
    void removedControlSettingFailsClearlyWithoutChangingStateOrBytes(
            String key, String value, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = ("{\"controls\":{\"" + key + "\":" + value + "}}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file));

        assertEquals("Removed config setting: controls." + key, failure.getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @ParameterizedTest(name = "removed water.{0} is rejected")
    @MethodSource("removedWaterCoolingSettings")
    void removedWaterCoolingSettingFailsClearlyWithoutChangingStateOrBytes(
            String key, String value, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = ("{\"water\":{\"" + key + "\":" + value + "}}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file));

        assertEquals("Removed config setting: water." + key, failure.getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @ParameterizedTest(name = "renamed {0} points to {1}")
    @MethodSource("renamedSettings")
    void renamedSettingFailsClearlyWithoutChangingStateOrBytes(
            String oldPath, String newPath, String value, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        String[] parts = oldPath.split("\\.", 2);
        byte[] invalid = ("{\"" + parts[0] + "\":{\"" + parts[1] + "\":" + value + "}}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file));

        assertEquals("Renamed config setting: " + oldPath + "; use " + newPath,
                failure.getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @ParameterizedTest(name = "wrong-type {0} remains a type error")
    @MethodSource("renamedSettingGroups")
    void renamedSettingDetectionDoesNotReplaceGroupTypeErrors(String group, @TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = ("{\"" + group + "\":false}").getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file));

        assertEquals("Invalid value type for " + group, failure.getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @Test
    void completeSerializationRoundTripsAllDeclaredAndNestedKeys(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config first = Config.load(file);
        JsonObject serialized = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        Config second = Config.reload(file);

        assertEquals(first, second);
        assertEquals(7, serialized.size());
        assertEquals(34, declaredKeyCount(serialized));
        assertEquals(45, nestedLeafCount(serialized));
        assertEquals(Set.of("blocksFiring"), serialized.getAsJsonObject("water").keySet());
        assertEquals(Set.of("bossBar", "actionBar", "refreshTicks", "warningFromPercent", "cooling"),
                serialized.getAsJsonObject("hud").keySet());
        assertEquals(Set.of(
                        "noCoolingText", "noCoolingColor", "slowMaxPerSecond", "slowColor",
                        "normalMaxPerSecond", "normalColor", "fastColor"),
                serialized.getAsJsonObject("hud").getAsJsonObject("cooling").keySet());
        assertEquals(JsonParser.parseString("""
                {
                  "noCoolingText":"NO COOLING",
                  "noCoolingColor":"RED",
                  "slowMaxPerSecond":0.5,
                  "slowColor":"GOLD",
                  "normalMaxPerSecond":1.0,
                  "normalColor":"GREEN",
                  "fastColor":"BLUE"
                }
                """), serialized.getAsJsonObject("hud").get("cooling"));
        assertEquals(serialized, JsonParser.parseString(Files.readString(file)));
    }


    @Test
    void inclusiveValidationBoundariesAreAccepted(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, """
                {
                  "fire": {"explosionPower": 0},
                  "heat": {"coolingDelayAfterShotSeconds": 0},
                  "water": {"blocksFiring": false},
                  "overheat": {
                    "fuseTicks": 0, "explosionPower": 0, "fireballCount": 0,
                    "fireballSpeed": 0, "fireballPower": 0, "firePlacementAttempts": 0,
                    "firePlacementRadius": 0
                  },
                  "cry": {"volume": 0, "cooldownSeconds": 0},
                  "hud": {
                    "refreshTicks": 4,
                    "warningFromPercent": 0,
                    "cooling": {"slowMaxPerSecond": 0, "normalMaxPerSecond": 0.1}
                  }
                }
                """);

        Config lower = Config.load(file);
        assertEquals(false, lower.water().blocksFiring());
        assertEquals(0, lower.hud().warningFromPercent());

        Files.writeString(file, "{\"hud\":{\"warningFromPercent\":100}}");
        assertEquals(100, Config.reload(file).hud().warningFromPercent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCoolingThresholds")
    void coolingThresholdsRejectInvalidOrderingAndNonFiniteOrNegativeValuesTransactionally(
            String description, String invalidJson, String expectedMessage,
            @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = invalidJson.getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file), description);

        assertEquals(expectedMessage, failure.getMessage(), description);
        assertSame(previous, Config.current(), description);
        assertArrayEquals(invalid, Files.readAllBytes(file), description);
    }

    @ParameterizedTest(name = "hud.cooling.{0} rejects {1}")
    @MethodSource("invalidCoolingColors")
    void everyCoolingColorRejectsInvalidNamesTypesAndNullTransactionally(
            String key, String description, String rawValue, @TempDir Path directory)
            throws Exception {
        assertRejectedWithoutMutation(directory,
                "{\"hud\":{\"cooling\":{\"" + key + "\":" + rawValue + "}}}",
                key + " accepted " + description);
    }

    @Test
    void noCoolingTextAcceptsBlankButRejectsNullTransactionally(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("blank.json");
        Files.writeString(file, "{\"hud\":{\"cooling\":{\"noCoolingText\":\"\"}}}");
        assertEquals("", Config.load(file).hud().cooling().noCoolingText());

        assertRejectedWithoutMutation(directory,
                "{\"hud\":{\"cooling\":{\"noCoolingText\":null}}}",
                "null noCoolingText");
    }

    @Test
    void parsingAcceptsSyntacticallyValidItemIdBeforeRegistryLifecycle(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file,
                "{\"controls\":{\"fireItem\":\"later-mod:registered_after_initialize\"}}");

        Config loaded = Config.load(file);

        assertEquals("later-mod:registered_after_initialize", loaded.controls().fireItem());
        assertSame(loaded, Config.current());
    }

    @ParameterizedTest(name = "lifecycle rejects missing controls.{0}")
    @MethodSource("unregisteredControlItems")
    void lifecycleResolutionFailsWithExactConfigPathAndId(
            String key, String itemId) throws Exception {
        Config candidate = Config.defaults();
        Config.Controls controls = new Config.Controls(
                key.equals("fireItem") ? itemId : candidate.controls().fireItem(),
                key.equals("cryItem") ? itemId : candidate.controls().cryItem(),
                candidate.controls().holdToFire(), candidate.controls().allowPlainItems());
        candidate = new Config(
                controls, candidate.fire(), candidate.heat(), candidate.water(),
                candidate.overheat(), candidate.cry(), candidate.hud());
        Config resolvedCandidate = candidate;
        Predicate<String> registry = id -> !id.equals(itemId);

        var method = Config.class.getDeclaredMethod(
                "resolveConfiguredItems", Config.class, Predicate.class);
        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> method.invoke(null, resolvedCandidate, registry));

        assertEquals("Missing configured item controls." + key + ": " + itemId,
                failure.getCause().getMessage());
    }

    @ParameterizedTest(name = "reload registry failure preserves controls.{0} transaction")
    @MethodSource("unregisteredControlItems")
    void reloadRegistryFailurePreservesExactLiveObjectAndFileBytes(
            String key, String itemId, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = controlItemDocument(key, itemId).getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);
        Predicate<String> registry = id -> !id.equals(itemId);
        var reload = Config.class.getDeclaredMethod("reload", Path.class, Predicate.class);

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class, () -> reload.invoke(null, file, registry));

        assertEquals("Missing configured item controls." + key + ": " + itemId,
                failure.getCause().getMessage());
        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @Test
    void replacementFailurePreservesExactLiveObjectTargetBytesAndCleansTemporaryFile(
            @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] candidateBytes = "{\"controls\":{\"holdToFire\":false}}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, candidateBytes);
        IOException replacementFailure = new IOException("replacement failed");

        AtomicMove failingMove = (temporary, target) -> {
            assertEquals(file.getParent(), temporary.getParent());
            assertEquals(file, target);
            JsonObject serialized = JsonParser.parseString(Files.readString(temporary))
                    .getAsJsonObject();
            assertEquals(34, declaredKeyCount(serialized));
            assertEquals(45, nestedLeafCount(serialized));
            assertEquals(new Gson().toJsonTree(Config.defaults().hud().cooling()),
                    serialized.getAsJsonObject("hud").get("cooling"));
            assertEquals(false, serialized.getAsJsonObject("controls")
                    .get("holdToFire").getAsBoolean());
            throw replacementFailure;
        };

        IOException failure = assertThrows(IOException.class,
                () -> Config.reload(file, item -> true, failingMove));

        assertSame(replacementFailure, failure);
        assertSame(previous, Config.current());
        assertArrayEquals(candidateBytes, Files.readAllBytes(file));
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @ParameterizedTest(name = "reload rejects unregistered controls.{0}")
    @MethodSource("unregisteredControlItems")
    void reloadRejectsUnregisteredControlItemWithoutMutation(
            String key, String itemId, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(file);
        byte[] invalid = controlItemDocument(key, itemId).getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        assertThrows(IllegalArgumentException.class, () -> Config.reload(file));

        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidExistingFiles")
    void invalidExistingFileFailsLoudlyWithoutRewrite(
            String description, String invalidJson, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, invalidJson);
        byte[] original = Files.readAllBytes(file);

        assertThrows(RuntimeException.class, () -> Config.load(file), description);
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void removedPresetFailsAtStartupWithPathWithoutRewritingBytes(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        byte[] invalid = "{\"preset\":\"survival\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.load(file));

        assertEquals("Removed config setting: preset", failure.getMessage());
        assertArrayEquals(invalid, Files.readAllBytes(file));
    }

    @ParameterizedTest(name = "removed preset form {0} is rejected")
    @MethodSource("removedPresetValues")
    void removedPresetFailsWithPathWithoutChangingStateOrBytes(
            String description, String invalidJson, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config previous = Config.load(file);
        byte[] invalid = invalidJson.getBytes(StandardCharsets.UTF_8);
        Files.write(file, invalid);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Config.reload(file), description);

        assertEquals("Removed config setting: preset", failure.getMessage());
        assertSame(previous, Config.current(), description);
        assertArrayEquals(invalid, Files.readAllBytes(file), description);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStrictJsonDocuments")
    void nonStrictJsonFailsWithoutChangingActiveConfigOrFile(
            String description, String invalidJson, @TempDir Path directory) throws Exception {
        assertRejectedWithoutMutation(directory, invalidJson, description);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("duplicateKeyDocuments")
    void duplicateKeysFailWithoutChangingActiveConfigOrFile(
            String description, String invalidJson, @TempDir Path directory) throws Exception {
        assertRejectedWithoutMutation(directory, invalidJson, description);
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidIntegerLeaves")
    void everyIntegerLeafRequiresAnExactSigned32BitInteger(
            String path, String rawValue, String invalidJson, @TempDir Path directory)
            throws Exception {
        assertRejectedWithoutMutation(directory, invalidJson, path + " accepted " + rawValue);
    }

    private static Stream<Arguments> removedControlSettings() {
        return Stream.of(
                Arguments.of("fireSlot", "2"),
                Arguments.of("crySlot", "6"),
                Arguments.of("lockControlSlots", "false"));
    }

    private static Stream<Arguments> removedWaterCoolingSettings() {
        return Stream.of(
                Arguments.of("coolPerSecond", "5.0"),
                Arguments.of("floor", "0.0"));
    }

    private static Stream<Arguments> renamedSettings() {
        return Stream.of(
                Arguments.of("heat.firingWindowSeconds", "heat.coolingDelayAfterShotSeconds", "1.0"),
                Arguments.of("heat.coldMaxTemperature", "heat.coldBiomeMaxTemperature", "0.3"),
                Arguments.of("heat.hotMinTemperature", "heat.hotBiomeMinTemperature", "1.0"),
                Arguments.of("heat.unknownDimensionUsesTemperature",
                        "heat.otherDimensionsUseBiomeTemperature", "true"),
                Arguments.of("overheat.fireAttempts", "overheat.firePlacementAttempts", "24"),
                Arguments.of("overheat.fireRadius", "overheat.firePlacementRadius", "8.0"));
    }

    private static Stream<Arguments> renamedSettingGroups() {
        return Stream.of(Arguments.of("heat"), Arguments.of("overheat"));
    }

    private static Stream<Arguments> individualCoolingOverrides() {
        Config.Cooling defaults = Config.defaults().hud().cooling();
        return Stream.of(
                Arguments.of("noCoolingText", "\"STILL\"", new Config.Cooling(
                        "STILL", defaults.noCoolingColor(), defaults.slowMaxPerSecond(),
                        defaults.slowColor(), defaults.normalMaxPerSecond(),
                        defaults.normalColor(), defaults.fastColor())),
                Arguments.of("noCoolingColor", "\"BLUE\"", new Config.Cooling(
                        defaults.noCoolingText(), Config.Color.BLUE, defaults.slowMaxPerSecond(),
                        defaults.slowColor(), defaults.normalMaxPerSecond(),
                        defaults.normalColor(), defaults.fastColor())),
                Arguments.of("slowMaxPerSecond", "0.25", new Config.Cooling(
                        defaults.noCoolingText(), defaults.noCoolingColor(), 0.25,
                        defaults.slowColor(), defaults.normalMaxPerSecond(),
                        defaults.normalColor(), defaults.fastColor())),
                Arguments.of("slowColor", "\"GREEN\"", new Config.Cooling(
                        defaults.noCoolingText(), defaults.noCoolingColor(),
                        defaults.slowMaxPerSecond(), Config.Color.GREEN,
                        defaults.normalMaxPerSecond(), defaults.normalColor(), defaults.fastColor())),
                Arguments.of("normalMaxPerSecond", "2.5", new Config.Cooling(
                        defaults.noCoolingText(), defaults.noCoolingColor(),
                        defaults.slowMaxPerSecond(), defaults.slowColor(), 2.5,
                        defaults.normalColor(), defaults.fastColor())),
                Arguments.of("normalColor", "\"GOLD\"", new Config.Cooling(
                        defaults.noCoolingText(), defaults.noCoolingColor(),
                        defaults.slowMaxPerSecond(), defaults.slowColor(),
                        defaults.normalMaxPerSecond(), Config.Color.GOLD, defaults.fastColor())),
                Arguments.of("fastColor", "\"RED\"", new Config.Cooling(
                        defaults.noCoolingText(), defaults.noCoolingColor(),
                        defaults.slowMaxPerSecond(), defaults.slowColor(),
                        defaults.normalMaxPerSecond(), defaults.normalColor(), Config.Color.RED)));
    }

    private static Stream<Arguments> invalidCoolingThresholds() {
        String orderMessage =
                "hud.cooling.slowMaxPerSecond must be less than hud.cooling.normalMaxPerSecond";
        return Stream.of(
                Arguments.of("negative slow threshold",
                        "{\"hud\":{\"cooling\":{\"slowMaxPerSecond\":-0.1}}}",
                        "hud.cooling.slowMaxPerSecond must not be negative"),
                Arguments.of("negative normal threshold",
                        "{\"hud\":{\"cooling\":{\"normalMaxPerSecond\":-0.1}}}",
                        "hud.cooling.normalMaxPerSecond must not be negative"),
                Arguments.of("non-finite slow threshold",
                        "{\"hud\":{\"cooling\":{\"slowMaxPerSecond\":1e309}}}",
                        "hud.cooling.slowMaxPerSecond must be finite"),
                Arguments.of("non-finite normal threshold",
                        "{\"hud\":{\"cooling\":{\"normalMaxPerSecond\":1e309}}}",
                        "hud.cooling.normalMaxPerSecond must be finite"),
                Arguments.of("equal thresholds",
                        "{\"hud\":{\"cooling\":{\"slowMaxPerSecond\":1,\"normalMaxPerSecond\":1}}}",
                        orderMessage),
                Arguments.of("crossed thresholds",
                        "{\"hud\":{\"cooling\":{\"slowMaxPerSecond\":2,\"normalMaxPerSecond\":1}}}",
                        orderMessage));
    }

    private static Stream<Arguments> invalidCoolingColors() {
        String[] keys = {"noCoolingColor", "slowColor", "normalColor", "fastColor"};
        Arguments[] invalidValues = {
                Arguments.of("lowercase name", "\"red\""),
                Arguments.of("vanilla alias", "\"YELLOW\""),
                Arguments.of("unknown name", "\"PURPLE\""),
                Arguments.of("wrong scalar type", "1"),
                Arguments.of("object", "{}"),
                Arguments.of("array", "[]"),
                Arguments.of("boolean", "true"),
                Arguments.of("null", "null")
        };
        return Stream.of(keys).flatMap(key -> Stream.of(invalidValues)
                .map(value -> Arguments.of(
                        key, value.get()[0], value.get()[1])));
    }

    private static Stream<Arguments> invalidExistingFiles() {
        return Stream.of(
                Arguments.of("malformed JSON", "{"),
                Arguments.of("removed preset", "{\"preset\":\"creative\"}"),
                Arguments.of("invalid identifier", "{\"controls\":{\"fireItem\":\"Bad Item\"}}"),
                Arguments.of("explicit null", "{\"hud\":{\"bossBar\":null}}"),
                Arguments.of("wrong scalar type", "{\"hud\":{\"bossBar\":\"false\"}}"),
                Arguments.of("non-finite number", "{\"heat\":{\"limit\":NaN}}"),
                Arguments.of("impossible range", "{\"hud\":{\"warningFromPercent\":101}}"),
                Arguments.of("zero positive value", "{\"fire\":{\"shotCooldownSeconds\":0}}"),
                Arguments.of("negative duration", "{\"fire\":{\"shotCooldownSeconds\":-1}}"),
                Arguments.of("negative count", "{\"overheat\":{\"fireballCount\":-1}}"),
                Arguments.of("HUD refresh below the four-tick packet floor",
                        "{\"hud\":{\"refreshTicks\":1}}"),
                Arguments.of("HUD refresh just below the four-tick packet floor",
                        "{\"hud\":{\"refreshTicks\":3}}"),
                Arguments.of("crossed temperatures", "{\"heat\":{\"coldBiomeMaxTemperature\":1.0,\"hotBiomeMinTemperature\":1.0}}"),
                Arguments.of("water floor above heat limit", "{\"water\":{\"floor\":101}}"));
    }

    private static Stream<Arguments> nonStrictJsonDocuments() {
        return Stream.of(
                Arguments.of("comments", "{\"controls\":{\"holdToFire\":true /* comment */}}"),
                Arguments.of("unquoted keys", "{controls:{\"holdToFire\":false}}"),
                Arguments.of("trailing comment after the document", "{} /* trailing */"),
                Arguments.of("trailing token after the document", "{} true"));
    }

    private static Stream<Arguments> removedPresetValues() {
        return Stream.of(
                Arguments.of("string", "{\"preset\":\"survival\"}"),
                Arguments.of("null", "{\"preset\":null}"),
                Arguments.of("array", "{\"preset\":[]}"),
                Arguments.of("object", "{\"preset\":{}}"));
    }

    private static Stream<Arguments> unregisteredControlItems() {
        return Stream.of(
                Arguments.of("fireItem", "happy-artillery:unregistered_fire_item"),
                Arguments.of("cryItem", "happy-artillery:unregistered_cry_item"));
    }

    private static String controlItemDocument(String key, String itemId) {
        return "{\"controls\":{\"" + key + "\":\"" + itemId + "\"}}";
    }

    private static Stream<Arguments> duplicateKeyDocuments() {
        return Stream.of(
                Arguments.of("duplicate top-level known key",
                        "{\"hud\":{},\"hud\":{}}"),
                Arguments.of("duplicate group-level known key",
                        "{\"controls\":{\"holdToFire\":true,\"holdToFire\":false}}"),
                Arguments.of("duplicate nested heat-profile known key",
                        "{\"heat\":{\"cold\":{\"heatPerShot\":0.7,\"heatPerShot\":0.8}}}"),
                Arguments.of("duplicate nested cooling known key",
                        "{\"hud\":{\"cooling\":{\"slowColor\":\"RED\",\"slowColor\":\"BLUE\"}}}"),
                Arguments.of("duplicate unknown key",
                        "{\"unknownGroup\":{\"discarded\":true,\"discarded\":false}}"));
    }

    private static Stream<Arguments> invalidIntegerLeaves() {
        String[] paths = {
                "fire.explosionPower",
                "overheat.fuseTicks",
                "overheat.fireballCount",
                "overheat.fireballPower",
                "overheat.firePlacementAttempts",
                "hud.refreshTicks",
                "hud.warningFromPercent"
        };
        String[] invalidValues = {"1.5", "4294967297", "-4294967295"};
        return Stream.of(paths).flatMap(path -> Stream.of(invalidValues)
                .map(rawValue -> Arguments.of(path, rawValue, integerDocument(path, rawValue))));
    }

    private static String integerDocument(String path, String rawValue) {
        String[] parts = path.split("\\.", 2);
        return "{\"" + parts[0] + "\":{\"" + parts[1] + "\":" + rawValue + "}}";
    }

    private static void assertRejectedWithoutMutation(
            Path directory, String invalidJson, String message) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"holdToFire\":false}}");
        Config previous = Config.load(file);
        byte[] invalid = invalidJson.getBytes();
        Files.write(file, invalid);

        assertThrows(Exception.class, () -> Config.reload(file), message);
        assertSame(previous, Config.current(), message);
        assertArrayEquals(invalid, Files.readAllBytes(file), message);
    }

    private static int declaredKeyCount(JsonObject root) {
        int count = 0;
        for (String group : new String[]{"controls", "fire", "heat", "water", "overheat", "cry", "hud"}) {
            count += root.getAsJsonObject(group).size();
        }
        return count;
    }

    private static int nestedLeafCount(JsonElement element) {
        if (!element.isJsonObject()) {
            return 1;
        }
        int count = 0;
        for (JsonElement child : element.getAsJsonObject().asMap().values()) {
            count += nestedLeafCount(child);
        }
        return count;
    }

    private static Map<String, Object> recordValues(Object value)
            throws InvocationTargetException, IllegalAccessException {
        Map<String, Object> values = new LinkedHashMap<>();
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            Object componentValue = component.getAccessor().invoke(value);
            values.put(component.getName(), componentValue != null && componentValue.getClass().isRecord()
                    ? recordValues(componentValue)
                    : componentValue);
        }
        return values;
    }
}
