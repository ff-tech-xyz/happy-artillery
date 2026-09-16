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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
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
    private static final Set<String> LEGACY_KEYS = Set.of(
            "fireballAmmoMax", "fireballAmmoCost", "ammoDeliveryIntervalMin",
            "shootCooldownSeconds", "fireRestartDelaySeconds", "cryCooldownSeconds",
            "baseOverheatLimit", "baseHeatPerShot", "baseCoolIntervalSeconds",
            "hotBiomeOverheatLimit", "hotBiomeHeatPerShot", "hotBiomeCoolIntervalSeconds",
            "coldBiomeOverheatLimit", "coldBiomeHeatPerShot", "coldBiomeCoolIntervalSeconds",
            "netherOverheatLimit", "netherHeatPerShot", "netherNoCooldown",
            "waterCooldownRate", "waterCooldownLimit", "fireballExplosionPower",
            "overheatExplosionPower", "overheatExplosionCreatesFire", "cryVolume");

    public static Config current() {
        return ACTIVE.get();
    }

    public static Config load(Path path) throws IOException {
        Candidate candidate = readCandidate(path);
        publish(path, candidate, Config::replaceAtomically);
        return candidate.config();
    }

    public static Config reload(Path path) throws IOException {
        return reload(path, Config::isRegisteredItem);
    }

    static Config reload(Path path, Predicate<String> registeredItem) throws IOException {
        return reload(path, registeredItem, Config::replaceAtomically);
    }

    static Config reload(
            Path path, Predicate<String> registeredItem, AtomicMove move) throws IOException {
        Candidate candidate = readCandidate(path);
        resolveConfiguredItems(candidate.config(), registeredItem);
        publish(path, candidate, move);
        return candidate.config();
    }

    private static Candidate readCandidate(Path path) throws IOException {
        if (Files.notExists(path)) {
            Config defaults = defaults();
            validate(defaults);
            return new Candidate(defaults, true, null);
        }
        byte[] original = Files.readAllBytes(path);
        JsonObject explicit = parseStrictObject(new String(original, StandardCharsets.UTF_8));
        if (explicit.keySet().stream().anyMatch(LEGACY_KEYS::contains)) {
            return new Candidate(migrateLegacy(explicit), true, original);
        }
        rejectRenamedSettings(explicit);
        rejectRemovedSettings(explicit);
        rejectUnknownKeys(JSON.toJsonTree(defaults()).getAsJsonObject(), explicit, "");
        validateIntegerLeaves(explicit);
        JsonObject complete = JSON.toJsonTree(defaults()).getAsJsonObject();
        mergeKnown(complete, explicit, "");
        Config loaded = JSON.fromJson(complete, Config.class);
        validate(loaded);
        return new Candidate(loaded, false, null);
    }

    private static void publish(Path path, Candidate candidate, AtomicMove move) throws IOException {
        if (!candidate.write()) {
            ACTIVE.set(candidate.config());
            return;
        }
        String serialized = JSON.toJson(candidate.config()) + System.lineSeparator();
        Path target = path.toAbsolutePath();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        if (candidate.legacyBytes() != null) {
            preserveLegacyBackup(target, candidate.legacyBytes());
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
            Files.writeString(temporary, serialized);
            move.replace(temporary, target);
            ACTIVE.set(candidate.config());
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

    private static void preserveLegacyBackup(Path target, byte[] original) throws IOException {
        Path backup = target.resolveSibling(target.getFileName() + ".v1.1.2.bak");
        Path temporary = Files.createTempFile(target.getParent(), backup.getFileName() + ".", ".tmp");
        try {
            Files.write(temporary, original);
            try {
                Files.createLink(backup, temporary);
            } catch (FileAlreadyExistsException raced) {
                requireMatchingBackup(backup, original);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireMatchingBackup(Path backup, byte[] original) throws IOException {
        if (!Arrays.equals(Files.readAllBytes(backup), original)) {
            throw new IOException("Legacy config backup already exists with different contents: " + backup);
        }
    }

    private static Config migrateLegacy(JsonObject explicit) {
        JsonObject complete = legacyDefaults();
        rejectUnknownKeys(complete, explicit, "");
        validateLegacyIntegerLeaves(explicit);
        mergeKnown(complete, explicit, "");
        LegacyConfig legacy = JSON.fromJson(complete, LegacyConfig.class);
        rejectCustomizedRemovedLegacySettings(legacy);
        if (legacy.baseOverheatLimit() != legacy.hotBiomeOverheatLimit()
                || legacy.baseOverheatLimit() != legacy.coldBiomeOverheatLimit()
                || legacy.baseOverheatLimit() != legacy.netherOverheatLimit()) {
            throw new IllegalArgumentException("Cannot migrate differing legacy overheat limits");
        }

        Config defaults = defaults();
        double baseCooling = coolingRate("baseCoolIntervalSeconds", legacy.baseCoolIntervalSeconds());
        double coldCooling = coolingRate(
                "coldBiomeCoolIntervalSeconds", legacy.coldBiomeCoolIntervalSeconds());
        Config migrated = new Config(
                defaults.controls(),
                new Fire(defaults.fire().enabled(), legacy.shootCooldownSeconds(),
                        legacy.fireballExplosionPower()),
                new Heat(
                        legacy.baseOverheatLimit(),
                        legacy.fireRestartDelaySeconds(),
                        new HeatProfile(legacy.coldBiomeHeatPerShot(), coldCooling),
                        new HeatProfile(legacy.baseHeatPerShot(), baseCooling),
                        new HeatProfile(legacy.hotBiomeHeatPerShot(),
                                coolingRate("hotBiomeCoolIntervalSeconds",
                                        legacy.hotBiomeCoolIntervalSeconds())),
                        new HeatProfile(legacy.netherHeatPerShot(),
                                legacy.netherNoCooldown() ? 0.0 : baseCooling),
                        new HeatProfile(legacy.coldBiomeHeatPerShot(), coldCooling),
                        0.0,
                        defaults.heat().hotBiomeMinTemperature(),
                        defaults.heat().otherDimensionsUseBiomeTemperature()),
                defaults.water(),
                new Overheat(
                        defaults.overheat().fuseTicks(), legacy.overheatExplosionPower(),
                        defaults.overheat().fireballCount(), defaults.overheat().fireballSpeed(),
                        defaults.overheat().fireballPower(), defaults.overheat().firePlacementAttempts(),
                        defaults.overheat().firePlacementRadius(), defaults.overheat().killsGhast(),
                        defaults.overheat().breaksBlocks()),
                new Cry(defaults.cry().enabled(), legacy.cryVolume(), legacy.cryCooldownSeconds()),
                defaults.hud());
        validate(migrated);
        return migrated;
    }

    private static double coolingRate(String path, double intervalSeconds) {
        requirePositive(path, intervalSeconds);
        return 1.0 / intervalSeconds;
    }

    private static void rejectCustomizedRemovedLegacySettings(LegacyConfig legacy) {
        requireLegacyDefault("fireballAmmoMax", legacy.fireballAmmoMax(), 200);
        requireLegacyDefault("fireballAmmoCost", legacy.fireballAmmoCost(), 1);
        requireLegacyDefault("ammoDeliveryIntervalMin", legacy.ammoDeliveryIntervalMin(), 5);
        requireLegacyDefault("waterCooldownRate", legacy.waterCooldownRate(), 8);
        requireLegacyDefault("waterCooldownLimit", legacy.waterCooldownLimit(), 5);
        if (!legacy.overheatExplosionCreatesFire()) {
            throw new IllegalArgumentException(
                    "Cannot migrate customized removed setting: overheatExplosionCreatesFire");
        }
    }

    private static void requireLegacyDefault(String path, int actual, int expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Cannot migrate customized removed setting: " + path);
        }
    }

    private static void validateLegacyIntegerLeaves(JsonObject explicit) {
        for (String key : Set.of(
                "fireballAmmoMax", "fireballAmmoCost", "ammoDeliveryIntervalMin",
                "baseOverheatLimit", "hotBiomeOverheatLimit", "coldBiomeOverheatLimit",
                "netherOverheatLimit", "waterCooldownRate", "waterCooldownLimit",
                "fireballExplosionPower")) {
            requireExactInteger(explicit, key);
        }
    }

    private static void requireExactInteger(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonPrimitive()
                || !root.get(key).getAsJsonPrimitive().isNumber()) {
            return;
        }
        try {
            root.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an exact 32-bit integer", exception);
        }
    }

    private static JsonObject legacyDefaults() {
        return JSON.toJsonTree(new LegacyConfig(
                200, 1, 5, 0.25, 0.5, 10.0,
                60, 1.0, 3.0, 60, 2.0, 6.0,
                60, 0.5, 1.5, 60, 3.0, true,
                8, 5, 2, 4.0, true, 3.0)).getAsJsonObject();
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


    private static void mergeKnown(JsonObject target, JsonObject explicit, String parentPath) {
        for (String key : target.keySet()) {
            if (!explicit.has(key)) {
                continue;
            }
            String path = parentPath.isEmpty() ? key : parentPath + "." + key;
            JsonElement targetValue = target.get(key);
            JsonElement explicitValue = explicit.get(key);
            if (targetValue.isJsonObject() && explicitValue.isJsonObject()) {
                mergeKnown(targetValue.getAsJsonObject(), explicitValue.getAsJsonObject(), path);
            } else if (sameScalarType(targetValue, explicitValue)) {
                target.add(key, explicitValue);
            } else {
                throw new IllegalArgumentException("Invalid value type for " + path);
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
        if (controls.allowPlainItems() && controls.fireItem().equals(controls.cryItem())) {
            throw new IllegalArgumentException(
                    "controls.fireItem and controls.cryItem must differ when controls.allowPlainItems is true");
        }

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
        requirePresent("hud.firingColor", hud.firingColor());
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
        if (itemId.equals("minecraft:air")) {
            throw new IllegalArgumentException("Configured item " + path + " must not be minecraft:air");
        }
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
                new Fire(true, 0.25, 1),
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
                new Hud(true, true, 4, 85, Color.GOLD,
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
            boolean enabled,
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
            Color firingColor,
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

    private record Candidate(Config config, boolean write, byte[] legacyBytes) {
    }

    private record LegacyConfig(
            int fireballAmmoMax,
            int fireballAmmoCost,
            int ammoDeliveryIntervalMin,
            double shootCooldownSeconds,
            double fireRestartDelaySeconds,
            double cryCooldownSeconds,
            int baseOverheatLimit,
            double baseHeatPerShot,
            double baseCoolIntervalSeconds,
            int hotBiomeOverheatLimit,
            double hotBiomeHeatPerShot,
            double hotBiomeCoolIntervalSeconds,
            int coldBiomeOverheatLimit,
            double coldBiomeHeatPerShot,
            double coldBiomeCoolIntervalSeconds,
            int netherOverheatLimit,
            double netherHeatPerShot,
            boolean netherNoCooldown,
            int waterCooldownRate,
            int waterCooldownLimit,
            int fireballExplosionPower,
            double overheatExplosionPower,
            boolean overheatExplosionCreatesFire,
            double cryVolume) {
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
