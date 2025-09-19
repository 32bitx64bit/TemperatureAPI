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
 * Optional default values for Biomes O' Plenty biomes, bundled as a resource.
 * File location: /temperatureapi/default_biomes_bop.json
 *
 * These are only used when Biomes O' Plenty is installed, and serve as
 * sensible defaults that users can override via the external BiomeAPI config.
 */
public final class BopDefaults {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String RESOURCE_PATH = "/temperatureapi/default_biomes_bop.json";

    private BopDefaults() {}

    public static Map<String, BiomeAPI.CustomBiome> defaults() {
        Map<String, BiomeAPI.CustomBiome> map = new HashMap<>();
        try (InputStream in = BopDefaults.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                TemperatureApi.LOGGER.info("No BOP default biome resource found at {} (this is fine if BOP isn't present)", RESOURCE_PATH);
                return Collections.unmodifiableMap(map);
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement root = GSON.fromJson(reader, JsonElement.class);
                if (root == null || !root.isJsonArray()) {
                    throw new JsonParseException("BOP defaults JSON must be an array of entries");
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
                TemperatureApi.LOGGER.info("Loaded {} Biomes O' Plenty default biome entries from {}", map.size(), RESOURCE_PATH);
                return Collections.unmodifiableMap(map);
            }
        } catch (Exception e) {
            TemperatureApi.LOGGER.error("Failed to load BOP default biomes from {}: {}", RESOURCE_PATH, e.getMessage());
            return Collections.unmodifiableMap(map);
        }
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
