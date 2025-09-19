package gavinx.temperatureapi.api;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * BodyTemperatureAPI
 *
 * Stateless helpers to compute how a player's core body temperature tends to change
 * based on ambient temperature and humidity.
 *
 * Design (initial pass):
 * - Neutral comfort band: 13°C .. 30°C -> no passive change to body temperature.
 * - Colder than 13°C: the body cools down; the colder it is, the faster it cools.
 * - Hotter than 30°C: the body heats up; the hotter it is, the faster it heats.
 *   If humidity > 50%, heating accelerates further (linear boost with humidity above 50%).
 *
 * Returned values are rates (dT/dt) in °C per second so callers can integrate per tick or per second.
 * Other factors (e.g., wetness, wind, activity) can be layered later via modifiers/context.
 */
public final class BodyTemperatureAPI {

    private BodyTemperatureAPI() {}

    // Constants and tunables (initial values; can be made data-driven later)
    public static final double NORMAL_BODY_TEMP_C = 36.6;
    public static final double COMFORT_MIN_C = 13.0;
    public static final double COMFORT_MAX_C = 30.0;

    // Base passive exchange rates per degree delta beyond comfort band (°C change per second per °C ambient beyond band)
    private static final double COOL_RATE_PER_DEGREE_PER_SEC = 0.00035; // colder than 13°C
    private static final double HEAT_RATE_PER_DEGREE_PER_SEC = 0.00025; // hotter than 30°C (before humidity boost)

    // Humidity boost: for humidity above 50%, multiply heating rate by (1 + HUMIDITY_ACCEL * (hum-50)/50)
    private static final double HUMIDITY_ACCEL = 1.0; // at 100% humidity -> 2.0x heating rate

    /** Compute the passive body temperature rate-of-change (°C per second) at a world position. */
    public static double computeRateCPerSecond(World world, BlockPos pos) {
        if (world == null || pos == null) return 0.0;
        double ambientC = TemperatureAPI.getTemperatureCelsius(world, pos);
        if (Double.isNaN(ambientC)) return 0.0;
        int humidity = HumidityAPI.getHumidityValue(world, pos);
        TemperatureResistanceAPI.Resistance res = null;
        // If a player context is needed for providers, callers should use the player overload.
        return computeRateCPerSecond(ambientC, humidity, res);
    }

    /** Compute the passive body temperature rate-of-change (°C per second) at the player's current position. */
    public static double computeRateCPerSecond(PlayerEntity player) {
        if (player == null) return 0.0;
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        double ambientC = TemperatureAPI.getTemperatureCelsius(world, pos);
        if (Double.isNaN(ambientC)) return 0.0;
        int humidity = HumidityAPI.getHumidityValue(world, pos);
        TemperatureResistanceAPI.Resistance res = TemperatureResistanceAPI.computeTotal(player);
        return computeRateCPerSecond(ambientC, humidity, res);
    }

    /** Compute the passive body temperature rate-of-change (°C per second) from explicit ambient inputs. */
    public static double computeRateCPerSecond(double ambientC, int humidityPercent) {
        return computeRateCPerSecond(ambientC, humidityPercent, null);
    }

    /** Core rate calculation with optional resistances, which shift the comfort band. */
    public static double computeRateCPerSecond(double ambientC, int humidityPercent, TemperatureResistanceAPI.Resistance resistance) {
        double comfortMin = COMFORT_MIN_C;
        double comfortMax = COMFORT_MAX_C;
        if (resistance != null) {
            comfortMin -= Math.max(0.0, resistance.coldC); // expand colder comfort
            comfortMax += Math.max(0.0, resistance.heatC); // expand hotter comfort
        }

        if (ambientC < comfortMin) {
            double degreesBelow = comfortMin - ambientC; // positive
            return -COOL_RATE_PER_DEGREE_PER_SEC * degreesBelow;
        }
        if (ambientC > comfortMax) {
            double degreesAbove = ambientC - comfortMax; // positive
            double rate = HEAT_RATE_PER_DEGREE_PER_SEC * degreesAbove;
            if (humidityPercent > 50) {
                double over = (humidityPercent - 50) / 50.0; // 0..1 for 50..100
                double boost = 1.0 + HUMIDITY_ACCEL * Math.max(0.0, Math.min(1.0, over));
                rate *= boost;
            }
            return rate;
        }
        // Within comfort band: no passive change.
        return 0.0;
    }

    /**
     * Integrate a body temperature value forward in time using the passive rate from world conditions.
     *
     * @param world World for ambient calculation.
     * @param pos   Position for ambient calculation.
     * @param currentBodyTempC Current core body temperature in °C.
     * @param dtSeconds Time step in seconds (negative values are treated as zero).
     * @return new body temperature after applying passive change for dtSeconds.
     */
    public static double advanceBodyTemp(World world, BlockPos pos, double currentBodyTempC, double dtSeconds) {
        double rate = computeRateCPerSecond(world, pos);
        double dt = Math.max(0.0, dtSeconds);
        return currentBodyTempC + rate * dt;
    }

    /** Player convenience overload for advanceBodyTemp. */
    public static double advanceBodyTemp(PlayerEntity player, double currentBodyTempC, double dtSeconds) {
        if (player == null) return currentBodyTempC;
        return advanceBodyTemp(player.getWorld(), player.getBlockPos(), currentBodyTempC, dtSeconds);
    }
}
