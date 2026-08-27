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

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Sole immutable configuration owner. */
public record Config(
        String preset,
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
        if (Files.notExists(path)) {
            Config defaults = defaults();
            validate(defaults);
            Files.createDirectories(path.toAbsolutePath().getParent());
            Files.writeString(path, JSON.toJson(defaults) + System.lineSeparator());
            ACTIVE.set(defaults);
            return defaults;
        }
        JsonObject explicit = parseStrictObject(Files.readString(path));
        validateIntegerLeaves(explicit);
        String preset = explicit.has("preset") ? explicit.get("preset").getAsString() : "pvp";
        JsonObject complete = JSON.toJsonTree(preset(preset)).getAsJsonObject();
        mergeKnown(complete, explicit);
        Config loaded = JSON.fromJson(complete, Config.class);
        validate(loaded);
        Files.writeString(path, JSON.toJson(loaded) + System.lineSeparator());
        ACTIVE.set(loaded);
        return loaded;
    }

    public static Config reload(Path path) throws IOException {
        return load(path);
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

    private static void validateIntegerLeaves(JsonObject explicit) {
        requireExactInteger(explicit, "controls", "fireSlot");
        requireExactInteger(explicit, "controls", "crySlot");
        requireExactInteger(explicit, "overheat", "fuseTicks");
        requireExactInteger(explicit, "overheat", "fireballCount");
        requireExactInteger(explicit, "overheat", "fireAttempts");
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

    private static Config preset(String name) {
        Config defaults = defaults();
        return switch (name) {
            case "pvp" -> defaults;
            case "survival" -> new Config(
                    name, defaults.controls(), defaults.fire(), defaults.heat(), defaults.water(),
                    new Overheat(0, 4.0, 12, 0.4, 2.0, 24, 4.0, true, true, true),
                    defaults.cry(), defaults.hud());
            case "off" -> new Config(
                    name, defaults.controls(),
                    new Fire(0.25, 2.0, 0.35, 2.0, false, true),
                    defaults.heat(), defaults.water(),
                    new Overheat(0, 6.0, 24, 0.4, 2.0, 0, 8.0, true, false, true),
                    defaults.cry(), defaults.hud());
            default -> throw new IllegalArgumentException("Unknown preset: " + name);
        };
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

    private static void validate(Config config) {
        Objects.requireNonNull(config, "config");
        Controls controls = Objects.requireNonNull(config.controls(), "controls");
        Fire fire = Objects.requireNonNull(config.fire(), "fire");
        Heat heat = Objects.requireNonNull(config.heat(), "heat");
        Water water = Objects.requireNonNull(config.water(), "water");
        Overheat overheat = Objects.requireNonNull(config.overheat(), "overheat");
        Cry cry = Objects.requireNonNull(config.cry(), "cry");
        Hud hud = Objects.requireNonNull(config.hud(), "hud");

        requireRange("controls.fireSlot", controls.fireSlot(), 0, 8);
        requireRange("controls.crySlot", controls.crySlot(), 0, 8);
        if (controls.fireSlot() == controls.crySlot()) {
            throw new IllegalArgumentException("Control slots must be distinct");
        }
        requireIdentifier("controls.fireItem", controls.fireItem());
        requireIdentifier("controls.cryItem", controls.cryItem());

        requirePositive("fire.shotCooldownSeconds", fire.shotCooldownSeconds());
        requireNonNegative("fire.explosionPower", fire.explosionPower());
        requirePositive("fire.speed", fire.speed());
        requireNonNegative("fire.spawnDistance", fire.spawnDistance());

        requirePositive("heat.limit", heat.limit());
        requireNonNegative("heat.firingWindowSeconds", heat.firingWindowSeconds());
        validateProfile("heat.cold", heat.cold());
        validateProfile("heat.base", heat.base());
        validateProfile("heat.hot", heat.hot());
        validateProfile("heat.nether", heat.nether());
        validateProfile("heat.end", heat.end());
        requireFinite("heat.coldMaxTemperature", heat.coldMaxTemperature());
        requireFinite("heat.hotMinTemperature", heat.hotMinTemperature());
        if (heat.coldMaxTemperature() >= heat.hotMinTemperature()) {
            throw new IllegalArgumentException("Cold temperature must be below hot temperature");
        }

        requireNonNegative("water.coolPerSecond", water.coolPerSecond());
        requireNonNegative("water.floor", water.floor());
        if (water.floor() > heat.limit()) {
            throw new IllegalArgumentException("Water floor cannot exceed heat limit");
        }

        requireRange("overheat.fuseTicks", overheat.fuseTicks(), 0, Integer.MAX_VALUE);
        requireNonNegative("overheat.explosionPower", overheat.explosionPower());
        requireRange("overheat.fireballCount", overheat.fireballCount(), 0, Integer.MAX_VALUE);
        requireNonNegative("overheat.fireballSpeed", overheat.fireballSpeed());
        requireNonNegative("overheat.fireballPower", overheat.fireballPower());
        requireRange("overheat.fireAttempts", overheat.fireAttempts(), 0, Integer.MAX_VALUE);
        requireNonNegative("overheat.fireRadius", overheat.fireRadius());

        requireNonNegative("cry.volume", cry.volume());
        requireNonNegative("cry.cooldownSeconds", cry.cooldownSeconds());
        requireRange("hud.refreshTicks", hud.refreshTicks(), 1, Integer.MAX_VALUE);
        requireRange("hud.warningFromPercent", hud.warningFromPercent(), 0, 100);
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
                "pvp",
                new Controls(4, 5, "minecraft:fire_charge", "minecraft:ghast_tear",
                        true, false, true),
                new Fire(0.25, 2.0, 0.35, 2.0, true, true),
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
                new Water(5.0, 0.0, true),
                new Overheat(0, 6.0, 24, 0.4, 2.0, 24, 8.0, true, true, true),
                new Cry(true, 10.0, 10.0),
                new Hud(true, true, 4, 85));
    }

    public record Controls(
            int fireSlot,
            int crySlot,
            String fireItem,
            String cryItem,
            boolean holdToFire,
            boolean allowPlainItems,
            boolean lockControlSlots) {
    }

    public record Fire(
            double shotCooldownSeconds,
            double explosionPower,
            double speed,
            double spawnDistance,
            boolean breaksBlocks,
            boolean respectProtection) {
    }

    public record Heat(
            double limit,
            double firingWindowSeconds,
            HeatProfile cold,
            HeatProfile base,
            HeatProfile hot,
            HeatProfile nether,
            HeatProfile end,
            double coldMaxTemperature,
            double hotMinTemperature,
            boolean unknownDimensionUsesTemperature) {
    }

    public record HeatProfile(double heatPerShot, double coolPerSecond) {
    }

    public record Water(double coolPerSecond, double floor, boolean blocksFiring) {
    }

    public record Overheat(
            int fuseTicks,
            double explosionPower,
            int fireballCount,
            double fireballSpeed,
            double fireballPower,
            int fireAttempts,
            double fireRadius,
            boolean killsGhast,
            boolean breaksBlocks,
            boolean respectProtection) {
    }

    public record Cry(boolean enabled, double volume, double cooldownSeconds) {
    }

    public record Hud(
            boolean bossBar,
            boolean actionBar,
            int refreshTicks,
            int warningFromPercent) {
    }
}
