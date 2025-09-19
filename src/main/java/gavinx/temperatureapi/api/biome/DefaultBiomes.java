package gavinx.temperatureapi.api.biome;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import gavinx.temperatureapi.TemperatureApi;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * DefaultBiomes: Loads built-in default biome values from a bundled JSON.
 *
 * The JSON format matches BiomeAPI's external config format:
 * [
 *   { "biome": "biome.minecraft.plains", "temperature": 22.0, "humidity": 40 },
 *   { "biome": "biome.minecraft.desert", "temperature": 38.0, "humidity": 5 }
 * ]
 *
 * File location (resource path): /temperatureapi/default_biomes.json
 */
public final class DefaultBiomes {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String RESOURCE_PATH = "/temperatureapi/default_biomes.json";

    private static final Map<String, BiomeAPI.CustomBiome> DEFAULTS = loadDefaults();

    private DefaultBiomes() { }

    private static Map<String, BiomeAPI.CustomBiome> loadDefaults() {
        Map<String, BiomeAPI.CustomBiome> map = new HashMap<>();
        try (InputStream in = DefaultBiomes.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                TemperatureApi.LOGGER.warn("Default biome resource not found at {}. No built-in defaults loaded.", RESOURCE_PATH);
                return Collections.unmodifiableMap(map);
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = GSON.fromJson(reader, JsonElement.class);
                if (root == null || !root.isJsonArray()) {
                    throw new JsonParseException("Default biomes JSON must be an array of entries");
                }
                JsonArray arr = root.getAsJsonArray();
                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    String biome = getAsString(obj, "biome", null);
                    Double temp = getAsDouble(obj, "temperature", null);
                    Integer hum = getAsInt(obj, "humidity", null);
                    if (biome == null || temp == null || hum == null) continue;
                    map.put(biome, new BiomeAPI.CustomBiome(biome, temp, hum));
                }
                TemperatureApi.LOGGER.info("Loaded {} built-in default biome entries from {}", map.size(), RESOURCE_PATH);
                return Collections.unmodifiableMap(map);
            }
        } catch (Exception e) {
            TemperatureApi.LOGGER.error("Failed to load built-in default biomes from {}: {}", RESOURCE_PATH, e.getMessage());
            return Collections.unmodifiableMap(map);
        }
    }

    public static Map<String, BiomeAPI.CustomBiome> defaults() {
        return DEFAULTS;
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
