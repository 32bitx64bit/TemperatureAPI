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
    private static volatile boolean loaded = false;

    // Canonical key form is "biome.<namespace>.<path>" (lowercase). Accept loose inputs and normalize.
    private static String normalizeKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        s = s.toLowerCase();
        // Common forms to accept:
        // 1) "biome.namespace.path" -> already canonical
        if (s.startsWith("biome.")) {
            return s;
        }
        // 2) "namespace:path" -> convert to "biome.namespace.path" (also replace '/' with '.')
        int colon = s.indexOf(':');
        if (colon > 0) {
            String ns = s.substring(0, colon);
            String path = s.substring(colon + 1).replace('/', '.');
            if (!ns.isEmpty() && !path.isEmpty()) {
                return "biome." + ns + "." + path;
            }
        }
        // 3) "namespace.path" -> convert to "biome.namespace.path"
        int firstDot = s.indexOf('.');
        if (firstDot > 0) {
            String ns = s.substring(0, firstDot);
            String path = s.substring(firstDot + 1);
            if (!ns.isEmpty() && !path.isEmpty()) {
                return "biome." + ns + "." + path;
            }
        }
        // 4) Single token -> assume minecraft namespace
        if (!s.contains(".") && !s.contains(":")) {
            return "biome.minecraft." + s;
        }
        return s;
    }

    // Collapse a canonical key by removing intermediate path components: biome.ns.a.b.c -> biome.ns.c
    private static String collapseKey(String canonical) {
        if (canonical == null) return null;
        String s = canonical.toLowerCase();
        if (!s.startsWith("biome.")) return s;
        String[] parts = s.split("\\.");
        if (parts.length < 3) return s;
        String ns = parts[1];
        String last = parts[parts.length - 1];
        return "biome." + ns + "." + last;
    }

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
    public static synchronized void load() {
        Path path = getConfigPath();
        ensureParentDir(path);

        Map<String, CustomBiome> merged = new HashMap<>();

        // 1) Classpath contributions: all resources named "temperatureapi/biomes.json" from any loaded mod jar
        int resourceFiles = 0;
        int resourceEntries = 0;
        int resourceSkipped = 0;
        try {
            ClassLoader cl = BiomeAPI.class.getClassLoader();
            Enumeration<URL> urls = cl.getResources("temperatureapi/biomes.json");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                resourceFiles++;
                try (InputStream in = url.openStream(); InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    JsonElement root = GSON.fromJson(reader, JsonElement.class);
                    if (root != null && root.isJsonArray()) {
                        int[] r = mergeJsonArray(root, merged);
                        resourceEntries += r[0];
                        resourceSkipped += r[1];
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

        // 1a) Mod resource contributions: look under common locations in each loaded mod
        try {
            for (net.fabricmc.loader.api.ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String modid = mod.getMetadata().getId();
                String[] candidates = new String[] {
                    "temperatureapi/biomes.json",
                    "assets/" + modid + "/temperatureapi/biomes.json",
                    "data/" + modid + "/temperatureapi/biomes.json"
                };
                for (String rel : candidates) {
                    java.util.Optional<java.nio.file.Path> pOpt = mod.findPath(rel);
                    if (pOpt.isEmpty()) continue;
                    java.nio.file.Path p = pOpt.get();
                    if (!java.nio.file.Files.exists(p)) continue;
                    resourceFiles++;
                    try (InputStream in = java.nio.file.Files.newInputStream(p); InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                        JsonElement root = GSON.fromJson(reader, JsonElement.class);
                        if (root != null && root.isJsonArray()) {
                            int[] r = mergeJsonArray(root, merged);
                            resourceEntries += r[0];
                            resourceSkipped += r[1];
                            JsonArray arr = root.getAsJsonArray();
                            TemperatureApi.LOGGER.info("Loaded TemperatureAPI biome resource from mod '{}' path '{}' ({} entries)", modid, rel, arr.size());
                        } else {
                            TemperatureApi.LOGGER.warn("Ignoring non-array TemperatureAPI resource from mod '{}' at '{}'", modid, rel);
                        }
                    } catch (Exception e) {
                        TemperatureApi.LOGGER.warn("Failed to read TemperatureAPI resource from mod '{}' at '{}': {}", modid, rel, e.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            TemperatureApi.LOGGER.debug("TemperatureAPI mod resource scan skipped: {}", t.getMessage());
        }

        // 2) External config (overrides classpath values). Create a sample if missing
        boolean createdSample = false;
        if (!Files.exists(path)) {
            writeSample(path);
            createdSample = true;
        }
        int externalEntries = 0;
        int externalSkipped = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = GSON.fromJson(reader, JsonElement.class);
            if (root == null || !root.isJsonArray()) {
                throw new JsonParseException("Root must be a JSON array of biome entries");
            }
            int[] r = mergeJsonArray(root, merged);
            externalEntries += r[0];
            externalSkipped += r[1];
        } catch (Exception e) {
            TemperatureApi.LOGGER.error("Failed to load biome config from {}: {}", path, e.getMessage());
        }

        registry = Collections.unmodifiableMap(merged);

        if (merged.isEmpty() && !createdSample) {
            TemperatureApi.LOGGER.warn("BiomeAPI: No entries loaded from external config {}. Ensure JSON is an array of objects with biome/temperature/humidity.", path);
        }

        // 3) Defaults: built-in vanilla + optional BOP defaults
        Map<String, CustomBiome> combined = new HashMap<>(DefaultBiomes.defaults());
        bopDefaults = BopDefaults.defaults();
        combined.putAll(bopDefaults);
        defaults = Collections.unmodifiableMap(combined);

        TemperatureApi.LOGGER.info("BiomeAPI loaded: {} classpath files ({} entries, {} skipped), {} external entries ({} skipped), {} defaults ({} BOP)",
                resourceFiles, resourceEntries, resourceSkipped, externalEntries, externalSkipped, defaults.size(), bopDefaults.size());
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) {
            try {
                load();
            } catch (Exception e) {
                TemperatureApi.LOGGER.error("BiomeAPI failed lazy-load: {}", e.getMessage());
            }
        }
    }

    /** Return a snapshot of all configured biomes (external/classpath), without defaults. */
    public static Map<String, CustomBiome> snapshotConfigured() {
        ensureLoaded();
        return new java.util.HashMap<>(registry);
    }

    /** Return a snapshot of defaults (vanilla + BOP) used as fallback. */
    public static Map<String, CustomBiome> snapshotDefaults() {
        ensureLoaded();
        return new java.util.HashMap<>(defaults);
    }

    /** Get a custom biome entry by key: e.g. "biome.minecraft.plains". */
    public static Optional<CustomBiome> get(String key) {
        ensureLoaded();
        String k = normalizeKey(key);
        CustomBiome v = registry.get(k);
        if (v != null) return Optional.of(v);
        v = registry.get(collapseKey(k));
        if (v != null) return Optional.of(v);
        // Fallback to defaults
        CustomBiome d = defaults.get(k);
        if (d != null) return Optional.of(d);
        d = defaults.get(collapseKey(k));
        return Optional.ofNullable(d);
    }

    /** Resolve the canonical key ("biome.namespace.path") for a biome registry entry. */
    public static String keyFor(RegistryEntry<Biome> biomeEntry) {
        Identifier id = biomeEntry.getKey().map(k -> k.getValue()).orElse(null);
        if (id == null) return null;
        String key = "biome." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        return key.toLowerCase();
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
        // Allow flexible input in external files, but write canonical example
        obj.addProperty("biome", normalizeKey(key));
        obj.addProperty("temperature", temp);
        obj.addProperty("humidity", humidity);
        return obj;
    }

    // Parse a JSON array root and merge valid entries into the map. Returns [added, skipped]
    private static int[] mergeJsonArray(JsonElement root, Map<String, CustomBiome> out) {
        int added = 0;
        int skipped = 0;
        JsonArray arr = root.getAsJsonArray();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            String biomeRaw = getAsString(obj, "biome", null);
            Double temp = getFirstDouble(obj, "temperature", "temp");
            Integer hum = getFirstInt(obj, "humidity", "hum");
            String biome = normalizeKey(biomeRaw);
            if (biome == null || temp == null || hum == null) { skipped++; continue; }
            out.put(biome, new CustomBiome(biome, temp, hum));
            added++;
        }
        return new int[] { added, skipped };
    }

    private static Double getFirstDouble(JsonObject o, String... keys) {
        for (String k : keys) {
            Double v = getAsDouble(o, k, null);
            if (v != null) return v;
        }
        return null;
    }

    private static Integer getFirstInt(JsonObject o, String... keys) {
        for (String k : keys) {
            Integer v = getAsInt(o, k, null);
            if (v != null) return v;
        }
        return null;
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
