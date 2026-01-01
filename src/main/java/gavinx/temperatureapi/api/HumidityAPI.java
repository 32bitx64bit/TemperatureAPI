package gavinx.temperatureapi.api;

import gavinx.temperatureapi.api.biome.BiomeAPI;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.Optional;

/**
 * HumidityAPI: retrieve biome humidity values (0..100).
 *
 * Sources:
 * - Custom values from BiomeAPI (biomes.json) when available
 * - Fallback to vanilla biome downfall scaled to 0..100
 */
public final class HumidityAPI {

    private HumidityAPI() { }

    // ----- Public string helpers (formatted as "NN%") -----

    /** Get humidity at a world position as a formatted string (e.g., "40%"). */
    public static String getHumidity(World world, BlockPos pos) {
        if (world == null || pos == null) return "N/A";
        int value = getHumidityValue(world, pos);
        return value < 0 ? "N/A" : (value + "%");
    }

    /** Get humidity at the player's current position as a formatted string (e.g., "40%"). */
    public static String getHumidity(PlayerEntity player) {
        if (player == null) return "N/A";
        BlockPos pos = TemperatureAPI.getSamplePos(player);
        if (pos == null) pos = player.getBlockPos();
        return getHumidity(player.getWorld(), pos);
    }

    // ----- Public numeric helpers (0..100) -----

    /** Get humidity at a world position as an integer percentage (0..100). */
    public static int getHumidityValue(World world, BlockPos pos) {
        if (world == null || pos == null) return -1;
        return resolveHumidity(world, pos);
    }

    /** Get humidity at the player's current position as an integer percentage (0..100). */
    public static int getHumidityValue(PlayerEntity player) {
        if (player == null) return -1;
        BlockPos pos = TemperatureAPI.getSamplePos(player);
        if (pos == null) pos = player.getBlockPos();
        return getHumidityValue(player.getWorld(), pos);
    }

    // ----- Internal resolution -----

    private static int resolveHumidity(World world, BlockPos pos) {
        RegistryEntry<Biome> entry = world.getBiome(pos);

        // Prefer custom value from BiomeAPI if present
        String key = BiomeAPI.keyFor(entry);
        if (key != null) {
            Optional<BiomeAPI.CustomBiome> custom = BiomeAPI.get(key);
            if (custom.isPresent()) {
                return clamp0to100(custom.get().humidity);
            }
        }

        // Fallback: derive from precipitation type (coarse estimate)
        Biome biome = entry.value();
        Biome.Precipitation p = biome.getPrecipitation(pos);
        int percent;
        if (p == Biome.Precipitation.NONE) {
            percent = 10; // generally dry
        } else if (p == Biome.Precipitation.SNOW) {
            percent = 50; // snowy/cold but some moisture
        } else { // RAIN
            percent = 65; // moderate humidity
        }
        return clamp0to100(percent);
    }

    private static int clamp0to100(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
