package gavinx.temperatureapi.api.biome;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import gavinx.temperatureapi.TemperatureApi;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * BiomeAPI: Load custom biome temperature/humidity data from a JSON file.
 *
 * Format (JSON array):
 * [
 *   { "biome": "biome.minecraft.plains", "temperature": 22.0, "humidity": 40 },
 *   { "biome": "biome.minecraft.desert", "temperature": 38.0, "humidity": 5 }
 * ]
 *
 * Units:
 * - temperature: Celsius (TemperatureAPI handles Fahrenheit conversion)
 * - humidity: 0..100 (percent)
 */
public final class BiomeAPI {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_DIR = "temperatureapi";
    private static final String DEFAULT_FILE = "biomes.json";

    private static Path configPath;
    private static Map<String, CustomBiome> registry = Collections.emptyMap();
    private static Map<String, CustomBiome> defaults = Collections.emptyMap();
    private static Map<String, CustomBiome> bopDefaults = Collections.emptyMap();

    private BiomeAPI() { }

    /** Simple data holder for a custom biome entry. */
    public static final class CustomBiome {
        public final String key;         // "biome.namespace.path"
        public final double temperature; // Celsius
        public final int humidity;       // 0..100

        public CustomBiome(String key, double temperature, int humidity) {
            this.key = key;
            this.temperature = temperature;
            this.humidity = Math.max(0, Math.min(100, humidity));
        }
    }

    /** Set a custom path for the biome config JSON file. */
    public static void setConfigPath(Path path) {
        configPath = path;
    }

    /** Returns the currently configured path (default if unset). */
    public static Path getConfigPath() {
        if (configPath != null) return configPath;
        Path cfgDir = FabricLoader.getInstance().getConfigDir().resolve(DEFAULT_DIR);
        return cfgDir.resolve(DEFAULT_FILE);
    }

    /** Load JSON contributions from classpath and external config. External config overrides. Creates a sample if missing. */
    public static void load() {
        Path path = getConfigPath();
        ensureParentDir(path);

        Map<String, CustomBiome> merged = new HashMap<>();

        // 1) Classpath contributions: all resources named "temperatureapi/biomes.json" from any loaded mod jar
        int resourceFiles = 0;
        int resourceEntries = 0;
        try {
            ClassLoader cl = BiomeAPI.class.getClassLoader();
            Enumeration<URL> urls = cl.getResources("temperatureapi/biomes.json");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                resourceFiles++;
                try (InputStream in = url.openStream(); InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonElement root = GSON.fromJson(reader, JsonElement.class);
                    if (root != null && root.isJsonArray()) {
                        JsonArray arr = root.getAsJsonArray();
                        for (JsonElement el : arr) {
                            if (!el.isJsonObject()) continue;
                            JsonObject obj = el.getAsJsonObject();
                            String biome = getAsString(obj, "biome", null);
                            Double temp = getAsDouble(obj, "temperature", null);
                            Integer hum = getAsInt(obj, "humidity", null);
                            if (biome == null || temp == null || hum == null) continue;
                            merged.put(biome, new CustomBiome(biome, temp, hum));
                            resourceEntries++;
                        }
                    } else {
                        TemperatureApi.LOGGER.warn("Ignoring non-array TemperatureAPI resource at {}", url);
                    }
                } catch (Exception e) {
                    TemperatureApi.LOGGER.warn("Failed to read TemperatureAPI resource {}: {}", url, e.getMessage());
                }
            }
        } catch (IOException e) {
            TemperatureApi.LOGGER.debug("No TemperatureAPI classpath contributions found: {}", e.getMessage());
        }

        // 2) External config (overrides classpath values). Create a sample if missing
        if (!Files.exists(path)) {
            writeSample(path);
        }
        int externalEntries = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                throw new JsonParseException("Root must be a JSON array of biome entries");
            }
            JsonArray arr = root.getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();
                String biome = getAsString(obj, "biome", null);
                Double temp = getAsDouble(obj, "temperature", null);
                Integer hum = getAsInt(obj, "humidity", null);
                if (biome == null || temp == null || hum == null) continue;
                merged.put(biome, new CustomBiome(biome, temp, hum));
                externalEntries++;
            }
        } catch (Exception e) {
            TemperatureApi.LOGGER.error("Failed to load biome config from {}: {}", path, e.getMessage());
        }

        registry = Collections.unmodifiableMap(merged);

        // 3) Defaults: built-in vanilla + optional BOP defaults
        Map<String, CustomBiome> combined = new HashMap<>(DefaultBiomes.defaults());
        bopDefaults = BopDefaults.defaults();
        combined.putAll(bopDefaults);
        defaults = Collections.unmodifiableMap(combined);

        TemperatureApi.LOGGER.info("BiomeAPI loaded: {} classpath files ({} entries), {} external entries, {} defaults ({} BOP)",
                resourceFiles, resourceEntries, externalEntries, defaults.size(), bopDefaults.size());
    }

    /** Get a custom biome entry by key: e.g. "biome.minecraft.plains". */
    public static Optional<CustomBiome> get(String key) {
        CustomBiome v = registry.get(key);
        if (v != null) return Optional.of(v);
        // Fallback to vanilla defaults
        return Optional.ofNullable(defaults.get(key));
    }

    /** Resolve the canonical key ("biome.namespace.path") for a biome registry entry. */
    public static String keyFor(RegistryEntry<Biome> biomeEntry) {
        Identifier id = biomeEntry.getKey().map(k -> k.getValue()).orElse(null);
        if (id == null) return null;
        return "biome." + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    private static void ensureParentDir(Path path) {
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            TemperatureApi.LOGGER.warn("Could not create config directory for {}: {}", path, e.getMessage());
        }
    }

    private static void writeSample(Path path) {
        JsonArray arr = new JsonArray();
        arr.add(sampleEntry("biome.minecraft.plains", 22.0, 40));
        arr.add(sampleEntry("biome.minecraft.desert", 38.0, 5));
        arr.add(sampleEntry("biome.minecraft.snowy_plains", -5.0, 60));
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(arr, w);
            TemperatureApi.LOGGER.info("Wrote sample biome config to {}", path);
        } catch (IOException e) {
            TemperatureApi.LOGGER.warn("Failed to write sample biome config to {}: {}", path, e.getMessage());
        }
    }

    private static JsonObject sampleEntry(String key, double temp, int humidity) {
        JsonObject obj = new JsonObject();
        obj.addProperty("biome", key);
        obj.addProperty("temperature", temp);
        obj.addProperty("humidity", humidity);
        return obj;
    }

    private static String getAsString(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }
    private static Double getAsDouble(JsonObject o, String k, Double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }
    private static Integer getAsInt(JsonObject o, String k, Integer def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
    }
}
