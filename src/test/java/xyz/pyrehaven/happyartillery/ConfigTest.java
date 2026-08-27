package xyz.pyrehaven.happyartillery;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void configSchemaIsImmutable() {
        assertTrue(Config.class.isRecord(), "Config must be an immutable record");
    }

    @Test
    void defaultsContainTheCompleteSchema() throws ReflectiveOperationException {
        Object defaults = Config.class.getMethod("defaults").invoke(null);

        assertEquals(Map.of(
                "preset", "pvp",
                "controls", Map.of(
                        "fireSlot", 4, "crySlot", 5,
                        "fireItem", "minecraft:fire_charge", "cryItem", "minecraft:ghast_tear",
                        "holdToFire", true, "allowPlainItems", false, "lockControlSlots", true),
                "fire", Map.of(
                        "shotCooldownSeconds", 0.25, "explosionPower", 2.0, "speed", 0.35,
                        "spawnDistance", 2.0, "breaksBlocks", true, "respectProtection", true),
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
                        Map.entry("fireballPower", 2.0), Map.entry("fireAttempts", 24),
                        Map.entry("fireRadius", 8.0), Map.entry("killsGhast", true),
                        Map.entry("breaksBlocks", true), Map.entry("respectProtection", true)),
                "cry", Map.of("enabled", true, "volume", 10.0, "cooldownSeconds", 10.0),
                "hud", Map.of("bossBar", true, "actionBar", true,
                        "refreshTicks", 4, "warningFromPercent", 85)),
                recordValues(defaults));
    }

    @Test
    void startupLoadPublishesTheValidatedObjectForCallTimeReads(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"fireSlot\":2}}");

        Config loaded = Config.load(file);
        Object active = Config.class.getMethod("current").invoke(null);

        assertSame(loaded, active);
        assertEquals(2, ((Config) active).controls().fireSlot());
    }

    @Test
    void failedReloadPreservesExactActiveObjectAndInvalidBytes(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"fireSlot\":2}}");
        Config previous = Config.load(file);
        byte[] invalid = "{\"controls\":{\"fireSlot\":9}}".getBytes();
        Files.write(file, invalid);

        var reload = Config.class.getMethod("reload", Path.class);
        assertThrows(InvocationTargetException.class, () -> reload.invoke(null, file));

        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(file));
        assertEquals(2, Config.current().controls().fireSlot());
    }

    @Test
    void successfulReloadRewritesCompleteSchemaAndAtomicallySwaps(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"fireSlot\":2}}");
        Config previous = Config.load(file);
        Files.writeString(file, """
                {
                  "preset": "survival",
                  "overheat": {"explosionPower": 9.0},
                  "obsolete": true
                }
                """);

        Config reloaded = Config.reload(file);

        assertNotSame(previous, reloaded);
        assertSame(reloaded, Config.current());
        assertEquals("survival", reloaded.preset());
        assertEquals(9.0, reloaded.overheat().explosionPower());
        assertEquals(12, reloaded.overheat().fireballCount());
        assertEquals(new Gson().toJsonTree(reloaded),
                JsonParser.parseString(Files.readString(file)));
    }

    @Test
    void presetAppliesBeforeExplicitOverrides(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, """
                {
                  "preset": "survival",
                  "overheat": {"explosionPower": 9.0}
                }
                """);

        Config loaded = (Config) Config.class.getMethod("load", Path.class).invoke(null, file);

        assertEquals("survival", loaded.preset());
        assertEquals(4.0, loaded.overheat().fireRadius());
        assertEquals(12, loaded.overheat().fireballCount());
        assertEquals(9.0, loaded.overheat().explosionPower());
        assertTrue(loaded.fire().respectProtection());
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
    void successfulLoadRewritesMissingKeysAndRemovesUnknownKeys(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, """
                {
                  "controls": {"fireSlot": 2, "obsolete": true},
                  "unknownGroup": {"ignored": true}
                }
                """);

        Config loaded = Config.load(file);

        assertEquals(2, loaded.controls().fireSlot());
        assertEquals("minecraft:ghast_tear", loaded.controls().cryItem());
        assertEquals(new Gson().toJsonTree(loaded),
                JsonParser.parseString(Files.readString(file)));
    }

    @Test
    void completeSerializationRoundTripsAllDeclaredAndNestedKeys(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Config first = Config.load(file);
        JsonObject serialized = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        Config second = Config.reload(file);

        assertEquals(first, second);
        assertEquals(8, serialized.size());
        assertEquals(44, declaredKeyCount(serialized));
        assertEquals(49, nestedLeafCount(serialized));
        assertEquals(serialized, JsonParser.parseString(Files.readString(file)));
    }

    @Test
    void everyPresetSuppliesItsCompleteBehaviorBeforeOverrides(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("happy-artillery.json");

        Files.writeString(file, "{\"preset\":\"pvp\"}");
        assertEquals(Config.defaults(), Config.load(file));

        Files.writeString(file, "{\"preset\":\"survival\"}");
        Config survival = Config.reload(file);
        assertEquals(4.0, survival.overheat().explosionPower());
        assertEquals(12, survival.overheat().fireballCount());
        assertEquals(4.0, survival.overheat().fireRadius());
        assertTrue(survival.fire().respectProtection());
        assertTrue(survival.overheat().respectProtection());

        Files.writeString(file, "{\"preset\":\"off\"}");
        Config off = Config.reload(file);
        assertEquals(0, off.overheat().fireAttempts());
        assertEquals(false, off.fire().breaksBlocks());
        assertEquals(false, off.overheat().breaksBlocks());
        assertEquals(2.0, off.fire().explosionPower());
    }

    @Test
    void inclusiveValidationBoundariesAreAccepted(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, """
                {
                  "controls": {"fireSlot": 0, "crySlot": 8},
                  "fire": {"explosionPower": 0, "spawnDistance": 0},
                  "heat": {"firingWindowSeconds": 0},
                  "water": {"coolPerSecond": 0, "floor": 100},
                  "overheat": {
                    "fuseTicks": 0, "explosionPower": 0, "fireballCount": 0,
                    "fireballSpeed": 0, "fireballPower": 0, "fireAttempts": 0,
                    "fireRadius": 0
                  },
                  "cry": {"volume": 0, "cooldownSeconds": 0},
                  "hud": {"refreshTicks": 1, "warningFromPercent": 0}
                }
                """);

        Config lower = Config.load(file);
        assertEquals(0, lower.controls().fireSlot());
        assertEquals(8, lower.controls().crySlot());
        assertEquals(100.0, lower.water().floor());
        assertEquals(0, lower.hud().warningFromPercent());

        Files.writeString(file, "{\"hud\":{\"warningFromPercent\":100}}");
        assertEquals(100, Config.reload(file).hud().warningFromPercent());
    }

    @ParameterizedTest(name = "startup rejects unregistered controls.{0}")
    @MethodSource("unregisteredControlItems")
    void startupRejectsUnregisteredControlItemWithoutMutation(
            String key, String itemId, @TempDir Path directory) throws Exception {
        Path baselineFile = directory.resolve("baseline.json");
        Files.writeString(baselineFile, "{\"controls\":{\"fireSlot\":2}}");
        Config previous = Config.load(baselineFile);
        Path invalidFile = directory.resolve("invalid.json");
        byte[] invalid = controlItemDocument(key, itemId).getBytes(StandardCharsets.UTF_8);
        Files.write(invalidFile, invalid);

        assertThrows(IllegalArgumentException.class, () -> Config.load(invalidFile));

        assertSame(previous, Config.current());
        assertArrayEquals(invalid, Files.readAllBytes(invalidFile));
    }

    @ParameterizedTest(name = "reload rejects unregistered controls.{0}")
    @MethodSource("unregisteredControlItems")
    void reloadRejectsUnregisteredControlItemWithoutMutation(
            String key, String itemId, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("happy-artillery.json");
        Files.writeString(file, "{\"controls\":{\"fireSlot\":2}}");
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

    private static Stream<Arguments> invalidExistingFiles() {
        return Stream.of(
                Arguments.of("malformed JSON", "{"),
                Arguments.of("unknown preset", "{\"preset\":\"creative\"}"),
                Arguments.of("invalid identifier", "{\"controls\":{\"fireItem\":\"Bad Item\"}}"),
                Arguments.of("explicit null", "{\"hud\":{\"bossBar\":null}}"),
                Arguments.of("wrong scalar type", "{\"hud\":{\"bossBar\":\"false\"}}"),
                Arguments.of("non-finite number", "{\"heat\":{\"limit\":NaN}}"),
                Arguments.of("impossible range", "{\"controls\":{\"fireSlot\":9}}"),
                Arguments.of("zero positive value", "{\"fire\":{\"speed\":0}}"),
                Arguments.of("negative duration", "{\"fire\":{\"shotCooldownSeconds\":-1}}"),
                Arguments.of("negative count", "{\"overheat\":{\"fireballCount\":-1}}"),
                Arguments.of("duplicate slots", "{\"controls\":{\"fireSlot\":5,\"crySlot\":5}}"),
                Arguments.of("crossed temperatures", "{\"heat\":{\"coldMaxTemperature\":1.0,\"hotMinTemperature\":1.0}}"),
                Arguments.of("water floor above heat limit", "{\"water\":{\"floor\":101}}"));
    }

    private static Stream<Arguments> nonStrictJsonDocuments() {
        return Stream.of(
                Arguments.of("comments", "{\"preset\":\"pvp\" /* comment */}"),
                Arguments.of("unquoted keys", "{controls:{\"fireSlot\":2}}"),
                Arguments.of("trailing comment after the document", "{} /* trailing */"),
                Arguments.of("trailing token after the document", "{} true"));
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
                        "{\"preset\":\"pvp\",\"preset\":\"survival\"}"),
                Arguments.of("duplicate group-level known key",
                        "{\"controls\":{\"fireSlot\":2,\"fireSlot\":3}}"),
                Arguments.of("duplicate nested heat-profile known key",
                        "{\"heat\":{\"cold\":{\"heatPerShot\":0.7,\"heatPerShot\":0.8}}}"),
                Arguments.of("duplicate unknown key",
                        "{\"unknownGroup\":{\"discarded\":true,\"discarded\":false}}"));
    }

    private static Stream<Arguments> invalidIntegerLeaves() {
        String[] paths = {
                "controls.fireSlot",
                "controls.crySlot",
                "overheat.fuseTicks",
                "overheat.fireballCount",
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
        Files.writeString(file, "{\"controls\":{\"fireSlot\":2}}");
        Config previous = Config.load(file);
        byte[] invalid = invalidJson.getBytes();
        Files.write(file, invalid);

        assertThrows(Exception.class, () -> Config.reload(file), message);
        assertSame(previous, Config.current(), message);
        assertArrayEquals(invalid, Files.readAllBytes(file), message);
    }

    private static int declaredKeyCount(JsonObject root) {
        int count = 1;
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
