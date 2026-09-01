package xyz.pyrehaven.happyartillery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Sole immutable configuration owner. */
public record Config(
        Controls controls,
        Fire fire,
        Heat heat,
        Water water,
        Overheat overheat,
        Cry cry,
        Hud hud) {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final AtomicReference<Config> ACTIVE = new AtomicReference<>(defaults());

    public static Config current() {
        return ACTIVE.get();
    }

    public static Config load(Path path) throws IOException {
        Config loaded = readCandidate(path);
        publish(path, loaded, Config::replaceAtomically);
        return loaded;
    }

    public static Config reload(Path path) throws IOException {
        return reload(path, Config::isRegisteredItem);
    }

    static Config reload(Path path, Predicate<String> registeredItem) throws IOException {
        return reload(path, registeredItem, Config::replaceAtomically);
    }

    static Config reload(
            Path path, Predicate<String> registeredItem, AtomicMove move) throws IOException {
        Config candidate = readCandidate(path);
        resolveConfiguredItems(candidate, registeredItem);
        publish(path, candidate, move);
        return candidate;
    }

    private static Config readCandidate(Path path) throws IOException {
        if (Files.notExists(path)) {
            Config defaults = defaults();
            validate(defaults);
            return defaults;
        }
        JsonObject explicit = parseStrictObject(Files.readString(path));
        rejectRenamedSettings(explicit);
        rejectRemovedSettings(explicit);
        rejectUnknownKeys(JSON.toJsonTree(defaults()).getAsJsonObject(), explicit, "");
        validateIntegerLeaves(explicit);
        JsonObject complete = JSON.toJsonTree(defaults()).getAsJsonObject();
        mergeKnown(complete, explicit);
        Config loaded = JSON.fromJson(complete, Config.class);
        validate(loaded);
        return loaded;
    }

    private static void publish(Path path, Config config, AtomicMove move) throws IOException {
        String serialized = JSON.toJson(config) + System.lineSeparator();
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
            Files.writeString(temporary, serialized);
            move.replace(temporary, target);
            ACTIVE.set(config);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Preserve the publication failure; cleanup is best-effort.
                }
            }
        }
    }

    private static void replaceAtomically(Path temporary, Path target) throws IOException {
        Files.move(temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean isRegisteredItem(String itemId) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(itemId)).isPresent();
    }

    private static JsonObject parseStrictObject(String contents) {
        JsonReader reader = new JsonReader(new StringReader(contents));
        reader.setStrictness(Strictness.STRICT);
        try {
            JsonElement parsed = readStrictValue(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing content after config document");
            }
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("Config document must be an object");
            }
            return parsed.getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid config JSON", exception);
        }
    }

    private static JsonElement readStrictValue(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readStrictObject(reader);
            case BEGIN_ARRAY -> readStrictArray(reader);
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IllegalArgumentException("Expected a JSON value");
        };
    }

    private static JsonObject readStrictObject(JsonReader reader) throws IOException {
        JsonObject object = new JsonObject();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate config key: " + name);
            }
            object.add(name, readStrictValue(reader));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray readStrictArray(JsonReader reader) throws IOException {
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) {
            array.add(readStrictValue(reader));
        }
        reader.endArray();
        return array;
    }

    private static void rejectRenamedSettings(JsonObject explicit) {
        Map<String, Map<String, String>> renames = Map.of(
                "heat", Map.of(
                        "firingWindowSeconds", "coolingDelayAfterShotSeconds",
                        "coldMaxTemperature", "coldBiomeMaxTemperature",
                        "hotMinTemperature", "hotBiomeMinTemperature",
                        "unknownDimensionUsesTemperature", "otherDimensionsUseBiomeTemperature"),
                "overheat", Map.of(
                        "fireAttempts", "firePlacementAttempts",
                        "fireRadius", "firePlacementRadius"));
        for (var group : renames.entrySet()) {
            if (!explicit.has(group.getKey()) || !explicit.get(group.getKey()).isJsonObject()) {
                continue;
            }
            JsonObject values = explicit.getAsJsonObject(group.getKey());
            for (var rename : group.getValue().entrySet()) {
                if (values.has(rename.getKey())) {
                    throw new IllegalArgumentException("Renamed config setting: "
                            + group.getKey() + "." + rename.getKey() + "; use "
                            + group.getKey() + "." + rename.getValue());
                }
            }
        }
    }

    private static void rejectRemovedSettings(JsonObject explicit) {
        if (explicit.has("preset")) {
            throw new IllegalArgumentException("Removed config setting: preset");
        }
        if (explicit.has("water") && explicit.get("water").isJsonObject()) {
            JsonObject water = explicit.getAsJsonObject("water");
            for (String key : Set.of("coolPerSecond", "floor")) {
                if (water.has(key)) {
                    throw new IllegalArgumentException("Removed config setting: water." + key);
                }
            }
        }
        if (!explicit.has("controls") || !explicit.get("controls").isJsonObject()) {
            return;
        }
        JsonObject controls = explicit.getAsJsonObject("controls");
        for (String key : Set.of("fireSlot", "crySlot", "lockControlSlots")) {
            if (controls.has(key)) {
                throw new IllegalArgumentException("Removed config setting: controls." + key);
            }
        }
    }

    private static void rejectUnknownKeys(JsonObject known, JsonObject explicit, String parentPath) {
        for (String key : explicit.keySet()) {
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;
            if (!known.has(key)) {
                throw new IllegalArgumentException("Unknown config key: " + path);
            }
            JsonElement knownValue = known.get(key);
            JsonElement explicitValue = explicit.get(key);
            if (knownValue.isJsonObject() && explicitValue.isJsonObject()) {
                rejectUnknownKeys(knownValue.getAsJsonObject(), explicitValue.getAsJsonObject(), path);
            }
        }
    }

    private static void validateIntegerLeaves(JsonObject explicit) {
        requireExactInteger(explicit, "fire", "explosionPower");
        requireExactInteger(explicit, "overheat", "fuseTicks");
        requireExactInteger(explicit, "overheat", "fireballCount");
        requireExactInteger(explicit, "overheat", "fireballPower");
        requireExactInteger(explicit, "overheat", "firePlacementAttempts");
        requireExactInteger(explicit, "hud", "refreshTicks");
        requireExactInteger(explicit, "hud", "warningFromPercent");
    }

    private static void requireExactInteger(JsonObject root, String group, String key) {
        if (!root.has(group) || !root.get(group).isJsonObject()) {
            return;
        }
        JsonObject values = root.getAsJsonObject(group);
        if (!values.has(key) || !values.get(key).isJsonPrimitive()
                || !values.get(key).getAsJsonPrimitive().isNumber()) {
            return;
        }
        try {
            values.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(group + "." + key
                    + " must be an exact 32-bit integer", exception);
        }
    }


    private static void mergeKnown(JsonObject target, JsonObject explicit) {
        for (String key : target.keySet()) {
            if (!explicit.has(key)) {
                continue;
            }
            JsonElement targetValue = target.get(key);
            JsonElement explicitValue = explicit.get(key);
            if (targetValue.isJsonObject() && explicitValue.isJsonObject()) {
                mergeKnown(targetValue.getAsJsonObject(), explicitValue.getAsJsonObject());
            } else if (sameScalarType(targetValue, explicitValue)) {
                target.add(key, explicitValue);
            } else {
                throw new IllegalArgumentException("Invalid value type for " + key);
            }
        }
    }

    private static boolean sameScalarType(JsonElement expected, JsonElement actual) {
        if (!expected.isJsonPrimitive() || !actual.isJsonPrimitive()) {
            return false;
        }
        var expectedPrimitive = expected.getAsJsonPrimitive();
        var actualPrimitive = actual.getAsJsonPrimitive();
        return expectedPrimitive.isBoolean() && actualPrimitive.isBoolean()
                || expectedPrimitive.isNumber() && actualPrimitive.isNumber()
                || expectedPrimitive.isString() && actualPrimitive.isString();
    }

    static void validate(Config config) {
        Objects.requireNonNull(config, "config");
        Controls controls = Objects.requireNonNull(config.controls(), "controls");
        Fire fire = Objects.requireNonNull(config.fire(), "fire");
        Heat heat = Objects.requireNonNull(config.heat(), "heat");
        Water water = Objects.requireNonNull(config.water(), "water");
        Overheat overheat = Objects.requireNonNull(config.overheat(), "overheat");
        Cry cry = Objects.requireNonNull(config.cry(), "cry");
        Hud hud = Objects.requireNonNull(config.hud(), "hud");

        requireIdentifier("controls.fireItem", controls.fireItem());
        requireIdentifier("controls.cryItem", controls.cryItem());

        requireNonNegative("fire.shotCooldownSeconds", fire.shotCooldownSeconds());
        requireRange("fire.explosionPower", fire.explosionPower(), 0, Integer.MAX_VALUE);

        requirePositive("heat.limit", heat.limit());
        requireNonNegative("heat.coolingDelayAfterShotSeconds", heat.coolingDelayAfterShotSeconds());
        validateProfile("heat.cold", heat.cold());
        validateProfile("heat.base", heat.base());
        validateProfile("heat.hot", heat.hot());
        validateProfile("heat.nether", heat.nether());
        validateProfile("heat.end", heat.end());
        requireFinite("heat.coldBiomeMaxTemperature", heat.coldBiomeMaxTemperature());
        requireFinite("heat.hotBiomeMinTemperature", heat.hotBiomeMinTemperature());
        if (heat.coldBiomeMaxTemperature() >= heat.hotBiomeMinTemperature()) {
            throw new IllegalArgumentException("Cold temperature must be below hot temperature");
        }

        requireRange("overheat.fuseTicks", overheat.fuseTicks(), 0, Integer.MAX_VALUE);
        requireNonNegative("overheat.explosionPower", overheat.explosionPower());
        requireRange("overheat.fireballCount", overheat.fireballCount(), 0, Integer.MAX_VALUE);
        requireNonNegative("overheat.fireballSpeed", overheat.fireballSpeed());
        requireRange("overheat.fireballPower", overheat.fireballPower(), 0, Integer.MAX_VALUE);
        requireRange("overheat.firePlacementAttempts", overheat.firePlacementAttempts(), 0, Integer.MAX_VALUE);
        requireNonNegative("overheat.firePlacementRadius", overheat.firePlacementRadius());

        requireNonNegative("cry.volume", cry.volume());
        requireNonNegative("cry.cooldownSeconds", cry.cooldownSeconds());
        requireRange("hud.refreshTicks", hud.refreshTicks(), 4, Integer.MAX_VALUE);
        requireRange("hud.warningFromPercent", hud.warningFromPercent(), 0, 100);
        validateCooling(hud.cooling());
    }

    private static void validateCooling(Cooling cooling) {
        requirePresent("hud.cooling", cooling);
        requirePresent("hud.cooling.noCoolingText", cooling.noCoolingText());
        requirePresent("hud.cooling.noCoolingColor", cooling.noCoolingColor());
        requireNonNegative("hud.cooling.slowMaxPerSecond", cooling.slowMaxPerSecond());
        requirePresent("hud.cooling.slowColor", cooling.slowColor());
        requireNonNegative("hud.cooling.normalMaxPerSecond", cooling.normalMaxPerSecond());
        requirePresent("hud.cooling.normalColor", cooling.normalColor());
        requirePresent("hud.cooling.fastColor", cooling.fastColor());
        if (cooling.slowMaxPerSecond() >= cooling.normalMaxPerSecond()) {
            throw new IllegalArgumentException(
                    "hud.cooling.slowMaxPerSecond must be less than hud.cooling.normalMaxPerSecond");
        }
    }

    private static void requirePresent(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    private static void validateProfile(String name, HeatProfile profile) {
        Objects.requireNonNull(profile, name);
        requirePositive(name + ".heatPerShot", profile.heatPerShot());
        requireNonNegative(name + ".coolPerSecond", profile.coolPerSecond());
    }

    private static void requireIdentifier(String name, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not a valid identifier");
        }
    }

    static void resolveConfiguredItems(Config config, Predicate<String> registeredItem) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(registeredItem, "registeredItem");
        requireResolvedItem("controls.fireItem", config.controls().fireItem(), registeredItem);
        requireResolvedItem("controls.cryItem", config.controls().cryItem(), registeredItem);
    }

    private static void requireResolvedItem(
            String path, String itemId, Predicate<String> registeredItem) {
        if (!registeredItem.test(itemId)) {
            throw new IllegalArgumentException("Missing configured item " + path + ": " + itemId);
        }
    }

    private static void requirePositive(String name, double value) {
        requireFinite(name, value);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(String name, double value) {
        requireFinite(name, value);
        if (value < 0.0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }

    public static Config defaults() {
        return new Config(
                new Controls("minecraft:fire_charge", "minecraft:ghast_tear", true, false),
                new Fire(0.25, 1),
                new Heat(
                        100.0,
                        1.0,
                        new HeatProfile(0.70, 1.0),
                        new HeatProfile(1.25, 0.6),
                        new HeatProfile(2.00, 0.5),
                        new HeatProfile(3.00, 0.0),
                        new HeatProfile(0.70, 1.0),
                        0.3,
                        1.0,
                        true),
                new Water(true),
                new Overheat(0, 6.0, 24, 0.4, 2, 24, 8.0, true, true),
                new Cry(true, 10.0, 10.0),
                new Hud(true, true, 4, 85,
                        new Cooling(
                                "NO COOLING", Color.RED,
                                0.5, Color.GOLD,
                                1.0, Color.GREEN,
                                Color.BLUE)));
    }

    public record Controls(
            String fireItem,
            String cryItem,
            boolean holdToFire,
            boolean allowPlainItems) {
    }

    public record Fire(
            double shotCooldownSeconds,
            int explosionPower) {
    }

    public record Heat(
            double limit,
            double coolingDelayAfterShotSeconds,
            HeatProfile cold,
            HeatProfile base,
            HeatProfile hot,
            HeatProfile nether,
            HeatProfile end,
            double coldBiomeMaxTemperature,
            double hotBiomeMinTemperature,
            boolean otherDimensionsUseBiomeTemperature) {
    }

    public record HeatProfile(double heatPerShot, double coolPerSecond) {
    }

    public record Water(boolean blocksFiring) {
    }

    public record Overheat(
            int fuseTicks,
            double explosionPower,
            int fireballCount,
            double fireballSpeed,
            int fireballPower,
            int firePlacementAttempts,
            double firePlacementRadius,
            boolean killsGhast,
            boolean breaksBlocks) {
    }

    public record Cry(boolean enabled, double volume, double cooldownSeconds) {
    }

    public record Hud(
            boolean bossBar,
            boolean actionBar,
            int refreshTicks,
            int warningFromPercent,
            Cooling cooling) {
    }

    public record Cooling(
            String noCoolingText,
            Color noCoolingColor,
            double slowMaxPerSecond,
            Color slowColor,
            double normalMaxPerSecond,
            Color normalColor,
            Color fastColor) {
    }

    public enum Color {
        RED,
        GOLD,
        GREEN,
        BLUE
    }
}

@FunctionalInterface
interface AtomicMove {
    void replace(Path temporary, Path target) throws IOException;
}
