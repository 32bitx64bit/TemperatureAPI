package gavinx.temperatureapi.api;

import gavinx.temperatureapi.api.RoomAPI.ActiveSetpoint;
import gavinx.temperatureapi.api.RoomAPI.Room;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * IndoorClimateAPI
 *
 * Lags the outdoor ambient temperature inside enclosed structures, and lets thermostatic
 * emitters pull a room toward a setpoint. Only the ambient (biome + seasonal + diurnal) term
 * is affected -- block thermal sources and other modifiers are applied by the caller on top
 * and remain unchanged.
 *
 * Model (per tick):
 * <pre>
 *   dT_in/dt = k*(T_out - T_in) + sum_i a_i*p_i*(S_i - T_in)
 * </pre>
 * Integrated in closed form over any step:
 * <pre>
 *   T* = (k*T_out + sum a_i*p_i*S_i) / (k + sum a_i*p_i)
 *   T_in <- T* + (T_in - T*) * e^(-(k + sum a_i*p_i) * dt)
 * </pre>
 *
 * The lag rate {@code k} comes from the structure's effective insulation
 * {@code alpha = avgInsulation * enclosure} via a half-life:
 * {@code halfLife = H_SCALE * alpha/(1-alpha)} -- so alpha=0 tracks the outdoors instantly and
 * alpha=1 never changes.
 *
 * Returning players / unloaded chunks: because the outdoor curve is fully deterministic
 * ({@link DayNightAPI} is seeded per day), a cold start reconstructs the indoor temperature by
 * replaying the real past outdoor history over a bounded trailing window -- no persistence
 * required, accurate to the actual day/time. Non-persistent emitters are excluded during the
 * unloaded window (the chunk was not ticking); persistent ones are included.
 */
public final class IndoorClimateAPI {

    private IndoorClimateAPI() {}

    // --- Tunables ---
    /** Half-life scale (ticks): a typical house lags by ~1 in-game hour. */
    public static final double H_SCALE = 3000.0;
    /** alpha at or above this is treated as perfectly frozen (k = 0). */
    public static final double ALPHA_FROZEN = 0.9995;
    /** Cap on the lag rate for near-zero alpha, so the interior effectively snaps to outdoors. */
    public static final double K_MAX = 10.0;
    /** Amplification of outdoor swings at full conductivity (alpha = -1). */
    public static final double AMP_GAIN = 1.5;

    /** Reconstruction integration step (1 in-game minute), matching the diurnal quantization. */
    public static final long TICKS_PER_STEP = 1200L;
    /** Trailing reconstruction window measured in passive half-lives. */
    public static final double RECON_HALFLIVES = 5.0;
    /** Hard cap on the reconstruction window (2 in-game days). */
    public static final long RECON_MAX_TICKS = 48000L;

    /**
     * Adjust the outdoor ambient temperature for indoor lag and any thermostatic emitters.
     *
     * @param outdoorEnvC the raw outdoor environment temperature (biome + seasonal + diurnal), in C
     * @return the effective ambient the occupants experience; equals {@code outdoorEnvC} outdoors
     */
    public static double adjustAmbient(World world, BlockPos pos, double outdoorEnvC) {
        if (world == null || pos == null || Double.isNaN(outdoorEnvC)) return outdoorEnvC;
        // No skylight (Nether/End): the whole indoor/outdoor notion does not apply.
        if (!world.getDimension().hasSkyLight()) return outdoorEnvC;

        Room room = RoomAPI.get(world, pos);
        if (room == null || room.outdoor) return outdoorEnvC;

        double alpha = room.effectiveAlpha();
        if (Math.abs(alpha) <= 1e-6 && room.setpoints.isEmpty()) return outdoorEnvC;

        double k = passiveRate(alpha);
        long now = world.getTime();

        synchronized (room) {
            if (!room.indoorInit) {
                reconstruct(world, room, outdoorEnvC, k, alpha, now);
            } else {
                advance(world, room, outdoorEnvC, k, alpha, now);
            }
            return room.indoorC;
        }
    }

    /**
     * Passive relaxation rate (per tick) toward the (possibly amplified) outdoor target.
     * Positive alpha lags with a half-life; alpha &le; 0 (no insulation or conductive) snaps quickly,
     * with conductivity expressed through {@link #amplificationGain} rather than the rate.
     */
    public static double passiveRate(double alpha) {
        if (alpha >= ALPHA_FROZEN) return 0.0;
        if (alpha <= 0.0) return K_MAX;
        double halfLife = H_SCALE * alpha / (1.0 - alpha);
        if (halfLife < 1e-6) return K_MAX;
        return Math.log(2.0) / halfLife;
    }

    /**
     * Gain applied to the outdoor deviation from the daily mean. 1.0 for insulators (alpha &ge; 0);
     * for conductive structures (alpha &lt; 0) it grows to {@code 1 + AMP_GAIN} at alpha = -1, so the
     * interior overshoots the outdoor swing (hotter by day, colder by night).
     */
    public static double amplificationGain(double alpha) {
        if (alpha >= 0.0) return 1.0;
        return 1.0 + AMP_GAIN * Math.min(1.0, -alpha);
    }

    /** The outdoor target the interior is driven toward, amplifying the deviation from the daily mean. */
    private static double amplifiedOutdoor(World world, double outdoorEnvC, double gain) {
        if (gain == 1.0) return outdoorEnvC;
        double staticPart = outdoorEnvC - DayNightAPI.temperatureOffsetC(world, world.getTimeOfDay());
        return staticPart + gain * (outdoorEnvC - staticPart);
    }

    // --- Integration ---

    private static void advance(World world, Room room, double outdoorEnvC, double k, double alpha, long now) {
        long dt = now - room.lastTick;
        if (dt <= 0) { room.lastTick = now; return; }
        double target = amplifiedOutdoor(world, outdoorEnvC, amplificationGain(alpha));
        room.indoorC = step(room.indoorC, target, k, room.setpoints, dt);
        room.lastTick = now;
    }

    private static void reconstruct(World world, Room room, double outdoorNow, double k, double alpha, long now) {
        long nowTOD = world.getTimeOfDay();
        double curDiurnal = DayNightAPI.temperatureOffsetC(world, nowTOD);
        // Static (biome + seasonal) component, derived from the supplied current outdoor value.
        double staticPart = outdoorNow - curDiurnal;
        double gain = amplificationGain(alpha);

        // While unloaded, only persistent emitters were running.
        List<ActiveSetpoint> persistent = new ArrayList<>();
        for (ActiveSetpoint s : room.setpoints) if (s.persistent()) persistent.add(s);

        long window;
        if (k > 0.0) {
            double halfLife = Math.log(2.0) / k;
            window = (long) Math.min(RECON_MAX_TICKS, Math.ceil(RECON_HALFLIVES * halfLife));
        } else {
            // Frozen shell: seed at the long-run mean unless a persistent emitter drives it.
            window = persistent.isEmpty() ? 0L : RECON_MAX_TICKS;
        }

        // Seed at the long-run mean (biome + seasonal); the replay washes this out within the window.
        double tin = staticPart;
        long t = now - window;
        while (t < now) {
            long stepEnd = Math.min(now, t + TICKS_PER_STEP);
            long dt = stepEnd - t;
            long mid = t + dt / 2;
            long pastTOD = nowTOD - (now - mid);
            // Amplify the past diurnal deviation for conductive structures (gain > 1).
            double outdoorPast = staticPart + gain * DayNightAPI.temperatureOffsetC(world, pastTOD);
            tin = step(tin, outdoorPast, k, persistent, dt);
            t = stepEnd;
        }

        room.indoorC = tin;
        room.indoorInit = true;
        room.lastTick = now;
    }

    /** One closed-form integration step toward the combined outdoor + emitter equilibrium. */
    private static double step(double tin, double outdoor, double k, List<ActiveSetpoint> setpoints, long dt) {
        double p = 0.0, ws = 0.0;
        for (ActiveSetpoint s : setpoints) {
            if (!active(s, tin)) continue;
            p += s.power();
            ws += s.power() * s.targetC();
        }
        double kp = k + p;
        if (kp <= 0.0) return tin; // perfectly insulated, no emitter driving: no change
        double target = (k * outdoor + ws) / kp;
        double decay = 1.0 - Math.exp(-kp * (double) dt);
        return tin + (target - tin) * decay;
    }

    /** Whether an emitter is actively driving at the current interior temperature, per its mode. */
    private static boolean active(ActiveSetpoint s, double tin) {
        return switch (s.mode()) {
            case HEAT_ONLY -> tin < s.targetC();
            case COOL_ONLY -> tin > s.targetC();
            case BOTH -> true;
        };
    }
}
