package gavinx.temperatureapi.api;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BlockSetpointAPI
 *
 * Registry for thermostatic ("setpoint") emitters: blocks that try to hold the room they are
 * in at a target temperature, rather than adding a fixed offset like {@link BlockThermalAPI}.
 *
 * Setpoint emitters act as a second attractor inside the indoor-climate model:
 * <pre>
 *   dT_in/dt = k*(T_out - T_in) + a*p*(S - T_in)
 * </pre>
 * where {@code S} is the target temperature, {@code p} is the emitter's pull strength
 * (derived from its response half-life and scaled by room volume), and {@code a} gates the
 * term according to the emitter's {@link Mode} (a heater never cools, an AC never heats).
 *
 * Because the {@code k} term blows up in poorly-insulated/enclosed rooms, an emitter only
 * holds its setpoint to the extent the structure is actually sealed and insulated -- you
 * cannot heat an open field. This falls out of the model with no extra bookkeeping.
 *
 * Modder usage:
 * <pre>
 *   // A fireplace that warms its room toward 22C, reaching it in ~30s in a small sealed room.
 *   BlockSetpointAPI.register(MyBlocks.FIREPLACE, 22.0, 600, Mode.HEAT_ONLY);
 * </pre>
 */
public final class BlockSetpointAPI {

    private BlockSetpointAPI() {}

    /** Reference room volume (3x3x3 interior). Power is scaled down for larger rooms. */
    public static final double V_REF = 27.0;

    /** Whether an emitter heats, cools, or does both toward its setpoint. */
    public enum Mode {
        /** Only adds heat while the room is below the setpoint (fireplace, furnace). */
        HEAT_ONLY,
        /** Only removes heat while the room is above the setpoint (cooler, AC). */
        COOL_ONLY,
        /** Always drives toward the setpoint, heating or cooling (full HVAC). */
        BOTH
    }

    /** An immutable description of a thermostatic emitter contributed by a block. */
    public static final class Setpoint {
        /** Target temperature in Celsius. */
        public final double targetC;
        /** Time (ticks) to close ~half the gap to the setpoint in a reference-size sealed room. */
        public final double responseHalfLifeTicks;
        public final Mode mode;
        /** If true, the emitter is treated as running even while its chunk is unloaded. */
        public final boolean persistsWhileUnloaded;

        public Setpoint(double targetC, double responseHalfLifeTicks, Mode mode, boolean persistsWhileUnloaded) {
            this.targetC = targetC;
            this.responseHalfLifeTicks = Math.max(1.0, responseHalfLifeTicks);
            this.mode = mode == null ? Mode.BOTH : mode;
            this.persistsWhileUnloaded = persistsWhileUnloaded;
        }

        @Override public String toString() {
            return "Setpoint{" + targetC + "C, halfLife=" + responseHalfLifeTicks + "t, " + mode
                    + (persistsWhileUnloaded ? ", persistent" : "") + "}";
        }
    }

    /** Provider for state-dependent setpoints (e.g., a thermostat configured via GUI). Return null to abstain. */
    @FunctionalInterface
    public interface Provider {
        Setpoint get(World world, BlockPos pos, BlockState state);
    }

    private static final Map<Block, Setpoint> SIMPLE = new ConcurrentHashMap<>();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Register a constant setpoint emitter for a block. */
    public static void register(Block block, double targetC, double responseHalfLifeTicks, Mode mode) {
        register(block, targetC, responseHalfLifeTicks, mode, false);
    }

    /** Register a constant setpoint emitter, specifying whether it keeps running while unloaded. */
    public static void register(Block block, double targetC, double responseHalfLifeTicks, Mode mode, boolean persistsWhileUnloaded) {
        Objects.requireNonNull(block, "block");
        SIMPLE.put(block, new Setpoint(targetC, responseHalfLifeTicks, mode, persistsWhileUnloaded));
    }

    /** Register a dynamic provider, evaluated (in registration order) after the simple registry. */
    public static void register(Provider provider) {
        if (provider != null) PROVIDERS.add(provider);
    }

    /** True if any setpoint emitter has been registered. Used as a global fast-skip. */
    public static boolean hasAny() {
        return !SIMPLE.isEmpty() || !PROVIDERS.isEmpty();
    }

    /** Resolve the setpoint for a block state: registered constant, else first non-null provider, else null. */
    public static Setpoint resolve(World world, BlockPos pos, BlockState state) {
        if (state == null) return null;
        Setpoint s = SIMPLE.get(state.getBlock());
        if (s != null) return s;
        if (!PROVIDERS.isEmpty()) {
            for (Provider p : PROVIDERS) {
                try {
                    Setpoint dyn = p.get(world, pos, state);
                    if (dyn != null) return dyn;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * Effective per-tick pull strength {@code p} for an emitter in a room of the given interior volume.
     * Derived from the response half-life and scaled down with room volume (more air to drive = slower),
     * never boosted above the reference-room rate for very small rooms.
     */
    public static double powerPerTick(Setpoint s, int volume) {
        if (s == null) return 0.0;
        double base = Math.log(2.0) / Math.max(1.0, s.responseHalfLifeTicks);
        double scale = Math.min(1.0, V_REF / Math.max(1.0, volume));
        return base * scale;
    }
}
