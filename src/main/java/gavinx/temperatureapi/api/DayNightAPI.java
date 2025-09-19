package gavinx.temperatureapi.api;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.SplittableRandom;

/**
 * DayNightAPI: Provides a smooth diurnal temperature offset that only updates once per in-game minute.
 *
 * Spec:
 * - Every Minecraft day (24,000 ticks), pick two deterministic values per world/day:
 *   - Daytime peak offset M in [0°C, +6°C]
 *   - Nighttime minimum offset m in [-7°C, -2°C]
 * - Over the day, the offset follows a smooth cosine curve with:
 *   - Maximum at noon (6000 ticks)
 *   - Minimum at midnight (18000 ticks)
 * - For performance: the offset is quantized to 1-minute steps (1,200 ticks).
 * - Deterministic across runs: values seeded by world seed, dimension, and day index.
 */
public final class DayNightAPI {
    private static final long TICKS_PER_DAY = 24000L;
    private static final long TICKS_PER_MINUTE = 1200L; // 60s * 20tps

    private DayNightAPI() {}

    /** Returns the quantized diurnal offset in Celsius for the world at its current time. */
    public static double temperatureOffsetC(World world, BlockPos pos) {
        if (world == null) return 0.0;
        long timeOfDay = world.getTimeOfDay();
        return temperatureOffsetC(world, timeOfDay);
    }

    /** Returns the quantized diurnal offset in Celsius for the given time-of-day (in ticks). */
    public static double temperatureOffsetC(World world, long timeOfDayTicks) {
        if (world == null) return 0.0;

        // Compute day index and minute quantization within the day
        long dayIndex = Math.floorDiv(timeOfDayTicks, TICKS_PER_DAY);
        long dayTick = Math.floorMod(timeOfDayTicks, TICKS_PER_DAY);
        long minuteIndex = dayTick / TICKS_PER_MINUTE; // 0..19

        // Evaluate the smooth curve at the center of this minute slice for a "smooth but stepped" result
        double t = ((minuteIndex + 0.5) / (double)(TICKS_PER_DAY / TICKS_PER_MINUTE)); // 0..1 over the day in 1/20 steps

        // Prefer server-synced parameters on the client if available
        Double Msyn = null, msyn = null;
        if (world.isClient()) {
            String dim = world.getRegistryKey().getValue().toString();
            try {
                Class<?> cls = Class.forName("gavinx.temperatureapi.client.DiurnalClientState");
                Method get = cls.getMethod("get", String.class);
                Object entry = get.invoke(null, dim);
                if (entry != null) {
                    Field fDay = entry.getClass().getField("dayIndex");
                    long d = (long) fDay.get(entry);
                    if (d == dayIndex) {
                        Field fM = entry.getClass().getField("M");
                        Field fm = entry.getClass().getField("m");
                        Msyn = (Double) fM.get(entry);
                        msyn = (Double) fm.get(entry);
                    }
                }
            } catch (Throwable ignored) {}
        }

        double M, m;
        if (Msyn != null && msyn != null) {
            M = Msyn;
            m = msyn;
        } else {
            // Deterministic daily peak/min using world seed + dimension + day
            long seed = seedFor(world, dayIndex);
            SplittableRandom rng = new SplittableRandom(seed);
            M = rng.nextDouble(0.0, 6.0);        // day peak in [0, +6]
            m = rng.nextDouble(-7.0, -2.0);      // night min in [-7, -2]
        }

        // Cosine profile with max at noon (t=0.25) and min at midnight (t=0.75)
        double center = (M + m) * 0.5;
        double amplitude = (M - m) * 0.5;
        double phase = t - 0.25; // shift so noon is peak
        double offset = center + amplitude * Math.cos(2.0 * Math.PI * phase);
        return offset;
    }

    private static long seedFor(World world, long dayIndex) {
        // Combine world seed (if available), dimension id, and day index for stable daily randomness
        long seed = 0x9E3779B97F4A7C15L; // golden ratio odd constant
        try {
            if (world instanceof net.minecraft.server.world.ServerWorld sw) {
                seed ^= mix64(sw.getSeed());
            }
        } catch (Throwable ignored) {
            // Some client worlds may not expose seed; fall back to dimension hash only
        }
        String dim = world.getRegistryKey().getValue().toString();
        seed ^= mix64(dim.hashCode());
        seed ^= mix64(dayIndex);
        return seed;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
