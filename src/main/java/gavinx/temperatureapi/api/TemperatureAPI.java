package gavinx.temperatureapi.api;

import gavinx.temperatureapi.api.biome.BiomeAPI;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import java.util.Optional;

/**
 * Public API to retrieve a biome temperature as a formatted String.
 *
 * Notes:
 * - This currently derives its value from the underlying Minecraft biome temperature.
 *   A future biome system can replace the resolver used here to provide more
 *   realistic values. The method signature will remain stable so other mods can depend on it.
 */
public final class TemperatureAPI {

    private TemperatureAPI() { }

    /** Temperature output units. */
    public enum Unit {
        CELSIUS, FAHRENHEIT
    }

    // --- Component accessors (biome/season/day-night only; excludes block sources) ---

    /** Base biome temperature in Celsius (without seasonal/diurnal or block sources). NaN on null inputs. */
    public static double getBiomeBaseCelsius(World world, BlockPos pos) {
        if (world == null || pos == null) return Double.NaN;
        return resolveBiomeBaseCelsius(world, pos);
    }

    /** Seasonal temperature offset in Celsius at a world position. NaN on null inputs. */
    public static double getSeasonalOffsetCelsius(World world, BlockPos pos) {
        if (world == null || pos == null) return Double.NaN;
        return SeasonsAPI.temperatureOffsetC(world, pos);
    }

    /** Diurnal/day-night temperature offset in Celsius at a world position. NaN on null inputs. */
    public static double getDiurnalOffsetCelsius(World world, BlockPos pos) {
        if (world == null || pos == null) return Double.NaN;
        return DayNightAPI.temperatureOffsetC(world, pos);
    }

    /** Environment temperature in Celsius = biome base + seasonal + diurnal. Excludes block sources. NaN on null. */
    public static double getEnvironmentCelsius(World world, BlockPos pos) {
        if (world == null || pos == null) return Double.NaN;
        double base = resolveBiomeBaseCelsius(world, pos);
        double seasonal = SeasonsAPI.temperatureOffsetC(world, pos);
        double diurnal = DayNightAPI.temperatureOffsetC(world, pos);
        return base + seasonal + diurnal;
    }

    /** Player overloads for component accessors. */
    public static double getBiomeBaseCelsius(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return Double.NaN;
        return getBiomeBaseCelsius(player.getWorld(), player.getBlockPos());
    }
    public static double getSeasonalOffsetCelsius(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return Double.NaN;
        return getSeasonalOffsetCelsius(player.getWorld(), player.getBlockPos());
    }
    public static double getDiurnalOffsetCelsius(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return Double.NaN;
        return getDiurnalOffsetCelsius(player.getWorld(), player.getBlockPos());
    }
    public static double getEnvironmentCelsius(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return Double.NaN;
        return getEnvironmentCelsius(player.getWorld(), player.getBlockPos());
    }

    /**
     * Get the temperature string for the biome at the given world position.
     *
     * @param world The world to sample from (server or client).
     * @param pos   The block position whose biome temperature to use.
     * @param unit  Desired output unit (Celsius or Fahrenheit).
     * @return A formatted temperature string, e.g. "20.0°C" or "68.0°F".
     */
    public static String getTemperature(World world, BlockPos pos, Unit unit) {
        if (world == null || pos == null || unit == null) {
            return "N/A";
        }

        double celsius = resolveBiomeTemperatureCelsius(world, pos);
        double value = unit == Unit.FAHRENHEIT ? cToF(celsius) : celsius;
        String suffix = unit == Unit.FAHRENHEIT ? "°F" : "°C";
        return String.format("%.1f%s", value, suffix);
    }

    /**
     * Convenience overload: pass true for Fahrenheit, false for Celsius.
     */
    public static String getTemperature(World world, BlockPos pos, boolean fahrenheit) {
        return getTemperature(world, pos, fahrenheit ? Unit.FAHRENHEIT : Unit.CELSIUS);
    }

    /**
     * Convenience overload: unit string can be "F", "f", "FAHRENHEIT" or "C", "c", "CELSIUS".
     * Defaults to Celsius on unknown values.
     */
    public static String getTemperature(World world, BlockPos pos, String unit) {
        Unit parsed = parseUnit(unit);
        return getTemperature(world, pos, parsed);
    }

    /**
     * Get the temperature string for the biome at the player's current position.
     */
    public static String getTemperature(net.minecraft.entity.player.PlayerEntity player, Unit unit) {
        if (player == null || unit == null) {
            return "N/A";
        }
        return getTemperature(player.getWorld(), player.getBlockPos(), unit);
    }

    /**
     * Convenience overload for player: pass true for Fahrenheit, false for Celsius.
     */
    public static String getTemperature(net.minecraft.entity.player.PlayerEntity player, boolean fahrenheit) {
        return getTemperature(player, fahrenheit ? Unit.FAHRENHEIT : Unit.CELSIUS);
    }

    /**
     * Convenience overload for player with unit string ("F"/"FAHRENHEIT" or "C"/"CELSIUS").
     */
    public static String getTemperature(net.minecraft.entity.player.PlayerEntity player, String unit) {
        return getTemperature(player, parseUnit(unit));
    }

    // --- Numeric helpers ---

    /**
     * Get the ambient temperature (including seasonal/diurnal adjustments and block sources) in Celsius at a world position.
     * Returns NaN if inputs are null.
     */
    public static double getTemperatureCelsius(World world, BlockPos pos) {
        if (world == null || pos == null) return Double.NaN;
        return resolveBiomeTemperatureCelsius(world, pos);
    }

    /**
     * Get the ambient temperature (including seasonal/diurnal adjustments) in Celsius at the player's position.
     * Returns NaN if inputs are null.
     */
    public static double getTemperatureCelsius(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return Double.NaN;
        return getTemperatureCelsius(player.getWorld(), player.getBlockPos());
    }

    // --- Internal helpers ---

    /** Base biome temperature in Celsius (no season/diurnal/block). */
    private static double resolveBiomeBaseCelsius(World world, BlockPos pos) {
        RegistryEntry<Biome> entry = world.getBiome(pos);

        String key = BiomeAPI.keyFor(entry);
        if (key != null) {
            Optional<BiomeAPI.CustomBiome> custom = BiomeAPI.get(key);
            if (custom.isPresent()) {
                return custom.get().temperature; // Celsius from JSON
            }
        }
        return entry.value().getTemperature();
    }

    /** Resolve the full ambient temperature in Celsius (biome + season + diurnal + blocks). */
    private static double resolveBiomeTemperatureCelsius(World world, BlockPos pos) {
        double baseC = resolveBiomeBaseCelsius(world, pos);
        // Seasonal adjustment (no-op if Serene Seasons is not present)
        double seasonalOffset = SeasonsAPI.temperatureOffsetC(world, pos);
        // Diurnal adjustment (quantized to 1-minute steps)
        double diurnalOffset = DayNightAPI.temperatureOffsetC(world, pos);
        double blockOffset = BlockThermalAPI.temperatureOffsetC(world, pos);
        return baseC + seasonalOffset + diurnalOffset + blockOffset;
    }

    private static double cToF(double c) {
        return (c * 9.0 / 5.0) + 32.0;
    }

    private static Unit parseUnit(String unit) {
        if (unit == null) return Unit.CELSIUS;
        String u = unit.trim().toUpperCase();
        if (u.equals("F") || u.equals("FAHRENHEIT")) return Unit.FAHRENHEIT;
        return Unit.CELSIUS;
    }
}
