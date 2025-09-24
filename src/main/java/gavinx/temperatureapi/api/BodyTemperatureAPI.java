package gavinx.temperatureapi.api;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

// Optional: wetness integration
import gavinx.temperatureapi.api.SoakedAPI;
import gavinx.temperatureapi.compat.DehydrationCompat;

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
    private static final double COOL_RATE_PER_DEGREE_PER_SEC = 0.0045; // colder than comfort edge
    private static final double HEAT_RATE_PER_DEGREE_PER_SEC = 0.0035; // hotter than comfort edge (before humidity boost)

    // Humidity boost: for humidity above 50%, multiply heating rate by (1 + HUMIDITY_ACCEL * (hum-50)/50)
    private static final double HUMIDITY_ACCEL = 1.0; // at 100% humidity -> 2.0x heating rate

    // Homeostasis: when ambient is comfortable, drift body temperature toward normal at a small fraction per second
    private static final double RELAX_FRACTION_PER_SEC = 0.01; // 1% of (current-normal) per second

    // Conduction toward ambient: proportional term (tuned much lower to avoid rapid tanking)
    private static final double CONDUCTION_RATE_PER_SEC = 0.001; // e.g., 0.001 -> 0.1°C/s for 100°C delta

    // Wetness modifiers (when soaked)
    private static final double SOAKED_COLD_MULT = 1.8; // colder-than-comfort cooling accelerates
    private static final double SOAKED_HOT_MULT = 0.6;  // hotter-than-comfort heating is reduced

    /** Compute the passive body temperature rate-of-change (°C per second) at a world position. */
    public static double computeRateCPerSecond(World world, BlockPos pos) {
        if (world == null || pos == null) return 0.0;
        double ambientC = TemperatureAPI.getTemperatureCelsius(world, pos);
        if (Double.isNaN(ambientC)) return 0.0;
        int humidity = HumidityAPI.getHumidityValue(world, pos);
        TemperatureResistanceAPI.Resistance res = null;
        // If a player context is needed for providers, callers should use the player overload.
        return computeRateCPerSecond(ambientC, humidity, res, Double.NaN);
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
        double rate = computeRateCPerSecond(ambientC, humidity, res, Double.NaN);

        // Apply soaked modifiers (server authoritative; client calls will see false)
        if (SoakedAPI.isSoaked(player)) {
            double comfortMin = COMFORT_MIN_C - Math.max(0.0, res.coldC);
            double comfortMax = COMFORT_MAX_C + Math.max(0.0, res.heatC);
            if (ambientC < comfortMin && rate < 0) {
                rate *= SOAKED_COLD_MULT;
            } else if (ambientC > comfortMax && rate > 0) {
                rate *= SOAKED_HOT_MULT;
            }
        }
        // Optional Dehydration integration: amplify ambient-driven rate when dehydrated
        if (DehydrationCompat.isPlayerDehydrated(player)) {
            rate *= 1.4;
        }
        return rate;
    }

    /** Compute the passive body temperature rate-of-change (°C per second) at the player's current position using current body temp for homeostasis. */
    public static double computeRateCPerSecond(PlayerEntity player, double currentBodyTempC) {
        if (player == null) return 0.0;
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        double ambientC = TemperatureAPI.getTemperatureCelsius(world, pos);
        if (Double.isNaN(ambientC)) return 0.0;
        int humidity = HumidityAPI.getHumidityValue(world, pos);
        TemperatureResistanceAPI.Resistance res = TemperatureResistanceAPI.computeTotal(player);
        double rate = computeRateCPerSecond(ambientC, humidity, res, currentBodyTempC);

        // Apply soaked modifiers
        if (SoakedAPI.isSoaked(player)) {
            double comfortMin = COMFORT_MIN_C - Math.max(0.0, res.coldC);
            double comfortMax = COMFORT_MAX_C + Math.max(0.0, res.heatC);
            if (ambientC < comfortMin && rate < 0) {
                rate *= SOAKED_COLD_MULT;
            } else if (ambientC > comfortMax && rate > 0) {
                rate *= SOAKED_HOT_MULT;
            }
        }
        // Optional Dehydration integration: amplify ambient-driven rate when dehydrated
        if (DehydrationCompat.isPlayerDehydrated(player)) {
            rate *= 1.4;
        }
        return rate;
    }

    /** Compute the passive body temperature rate-of-change (°C per second) from explicit ambient inputs. */
    public static double computeRateCPerSecond(double ambientC, int humidityPercent) {
        return computeRateCPerSecond(ambientC, humidityPercent, null, Double.NaN);
    }

    /** Core rate calculation with optional resistances and optional homeostasis to NORMAL_BODY_TEMP_C. */
    public static double computeRateCPerSecond(double ambientC, int humidityPercent, TemperatureResistanceAPI.Resistance resistance, double currentBodyTempC) {
        // Clamp inputs
        if (Double.isNaN(ambientC) || Double.isInfinite(ambientC)) return 0.0;
        humidityPercent = Math.max(0, Math.min(100, humidityPercent));

        double comfortMin = COMFORT_MIN_C;
        double comfortMax = COMFORT_MAX_C;
        if (resistance != null) {
            comfortMin -= Math.max(0.0, resistance.coldC); // expand colder comfort
            comfortMax += Math.max(0.0, resistance.heatC); // expand hotter comfort
        }

        double rate = 0.0;
        if (ambientC < comfortMin) {
            double degreesBelow = comfortMin - ambientC; // positive
            rate += -COOL_RATE_PER_DEGREE_PER_SEC * degreesBelow;
        } else if (ambientC > comfortMax) {
            double degreesAbove = ambientC - comfortMax; // positive
            double heat = HEAT_RATE_PER_DEGREE_PER_SEC * degreesAbove;
            if (humidityPercent > 50) {
                double over = (humidityPercent - 50) / 50.0; // 0..1 for 50..100
                double boost = 1.0 + HUMIDITY_ACCEL * Math.max(0.0, Math.min(1.0, over));
                heat *= boost;
            }
            rate += heat;
        } else {
            // Ambient within comfort band (including resistance-expanded): no ambient-driven heating/cooling.
            // Only apply gentle homeostasis toward normal body temperature if current body temp is known.
            if (!Double.isNaN(currentBodyTempC)) {
                double delta = currentBodyTempC - NORMAL_BODY_TEMP_C; // positive if too hot
                rate += -RELAX_FRACTION_PER_SEC * delta; // cool if positive, warm if negative
            }
        }

        // Conduction: only inside the comfort band, and only to warm toward ambient (no cooling inside).
        if (!Double.isNaN(currentBodyTempC)) {
            boolean insideComfort = (ambientC >= comfortMin) && (ambientC <= comfortMax);
            if (insideComfort && currentBodyTempC < ambientC) {
                double conduction = CONDUCTION_RATE_PER_SEC * (ambientC - currentBodyTempC);
                rate += conduction;
            }
        }
        // Final safety:
        // - If body <= ambient, do not allow net cooling (prevents sinking further below ambient)
        // - If body >= ambient, do not allow net heating to overshoot upward instantly (still allowed, but rate limited by previous terms)
        if (!Double.isNaN(currentBodyTempC)) {
            if (currentBodyTempC <= ambientC && rate < 0) {
                rate = 0.0;
            }
        }
        return rate;
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
        if (world == null || pos == null) return currentBodyTempC;
        double ambientC = TemperatureAPI.getTemperatureCelsius(world, pos);
        if (Double.isNaN(ambientC)) return currentBodyTempC;
        int humidity = HumidityAPI.getHumidityValue(world, pos);
        double rate = computeRateCPerSecond(ambientC, humidity, null, currentBodyTempC);
        double dt = Math.max(0.0, dtSeconds);
        double next = currentBodyTempC + rate * dt;
        // Prevent dipping below ambient due to numerical integration or strong cooling
        if (currentBodyTempC >= ambientC && next < ambientC) {
            next = ambientC;
        }
        return next;
    }

    /** Player convenience overload for advanceBodyTemp. */
    public static double advanceBodyTemp(PlayerEntity player, double currentBodyTempC, double dtSeconds) {
        if (player == null) return currentBodyTempC;
        // Use the current body temperature to enable homeostasis within the comfort band
        double rate = computeRateCPerSecond(player, currentBodyTempC);
        double dt = Math.max(0.0, dtSeconds);
        double next = currentBodyTempC + rate * dt;
        // Clamp not to dip below ambient
        double ambientC = TemperatureAPI.getTemperatureCelsius(player.getWorld(), player.getBlockPos());
        if (!Double.isNaN(ambientC) && currentBodyTempC >= ambientC && next < ambientC) {
            next = ambientC;
        }
        return next;
    }
}
