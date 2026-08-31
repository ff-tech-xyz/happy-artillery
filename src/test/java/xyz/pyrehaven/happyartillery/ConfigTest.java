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
        Files.writeString(file, new Gson().toJson(Config.defaults()));
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
    void defaultsContainTheCompleteSchema() throws ReflectiveOperationException {
        Object defaults = Config.class.getMethod("defaults").invoke(null);

        assertEquals(Map.of(
                "controls", Map.of(
                        "fireItem", "minecraft:fire_charge", "cryItem", "minecraft:ghast_tear",
                        "holdToFire", true, "allowPlainItems", false),
                "fire", Map.of(
                        "shotCooldownSeconds", 0.25, "explosionPower", 1),
                "heat", Map.ofEntries(
                        Map.entry("limit", 100.0), Map.entry("firingWindowSeconds", 1.0),
                        Map.entry("cold", Map.of("heatPerShot", 0.70, "coolPerSecond", 1.0)),
                        Map.entry("base", Map.of("heatPerShot", 1.25, "coolPerSecond", 0.6)),
                        Map.entry("hot", Map.of("heatPerShot", 2.00, "coolPerSecond", 0.5)),
                        Map.entry("nether", Map.of("heatPerShot", 3.00, "coolPerSecond", 0.0)),
                        Map.entry("end", Map.of("heatPerShot", 0.70, "coolPerSecond", 1.0)),
                        Map.entry("coldMaxTemperature", 0.3), Map.entry("hotMinTemperature", 1.0),
                        Map.entry("unknownDimensionUsesTemperature", true)),
                "water", Map.of("coolPerSecond", 5.0, "floor", 0.0, "blocksFiring", true),
                "overheat", Map.ofEntries(
                        Map.entry("fuseTicks", 0), Map.entry("explosionPower", 6.0),
                        Map.entry("fireballCount", 24), Map.entry("fireballSpeed", 0.4),
                        Map.entry("fireballPower", 2), Map.entry("fireAttempts", 24),
                        Map.entry("fireRadius", 8.0), Map.entry("killsGhast", true),
                        Map.entry("breaksBlocks", true)),
                "cry", Map.of("enabled", true, "volume", 10.0, "cooldownSeconds", 10.0),
                "hud", Map.of("bossBar", true, "actionBar", true,
                        "refreshTicks", 4, "warningFromPercent", 85)),
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

    @Test
    void completeSerializationRoundTripsAllDeclaredAndNestedKeys(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config first = Config.load(file);
        JsonObject serialized = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        Config second = Config.reload(file);

        assertEquals(first, second);
        assertEquals(7, serialized.size());
        assertEquals(35, declaredKeyCount(serialized));
        assertEquals(40, nestedLeafCount(serialized));
        assertEquals(serialized, JsonParser.parseString(Files.readString(file)));
    }


    @Test
    void inclusiveValidationBoundariesAreAccepted(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, """
                {
                  "fire": {"explosionPower": 0},
                  "heat": {"firingWindowSeconds": 0},
                  "water": {"coolPerSecond": 0, "floor": 100},
                  "overheat": {
                    "fuseTicks": 0, "explosionPower": 0, "fireballCount": 0,
                    "fireballSpeed": 0, "fireballPower": 0, "fireAttempts": 0,
                    "fireRadius": 0
                  },
                  "cry": {"volume": 0, "cooldownSeconds": 0},
                  "hud": {"refreshTicks": 4, "warningFromPercent": 0}
                }
                """);

        Config lower = Config.load(file);
        assertEquals(100.0, lower.water().floor());
        assertEquals(0, lower.hud().warningFromPercent());

        Files.writeString(file, "{\"hud\":{\"warningFromPercent\":100}}");
        assertEquals(100, Config.reload(file).hud().warningFromPercent());
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
            assertEquals(35, declaredKeyCount(serialized));
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
                Arguments.of("crossed temperatures", "{\"heat\":{\"coldMaxTemperature\":1.0,\"hotMinTemperature\":1.0}}"),
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
                Arguments.of("duplicate unknown key",
                        "{\"unknownGroup\":{\"discarded\":true,\"discarded\":false}}"));
    }

    private static Stream<Arguments> invalidIntegerLeaves() {
        String[] paths = {
                "fire.explosionPower",
                "overheat.fuseTicks",
                "overheat.fireballCount",
                "overheat.fireballPower",
                "overheat.fireAttempts",
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
