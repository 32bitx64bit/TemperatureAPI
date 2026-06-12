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
 * BlockInsulationAPI
 *
 * Lightweight registry for per-block thermal insulation, used by the indoor-climate model
 * to decide how strongly a structure resists the outside temperature.
 *
 * Insulation is a value in [0.0, 1.0]:
 * - 0.0 -> no resistance (interior tracks the outdoors immediately)
 * - 1.0 -> maximal resistance (interior never changes with the outdoors)
 *
 * Modder usage is intentionally minimal: one call per block.
 * <pre>
 *   BlockInsulationAPI.register(MyBlocks.INSULATED_PANEL, 0.85);
 * </pre>
 *
 * Default behavior:
 * - Any block without a registered value resolves to {@link #DEFAULT_INSULATION} (0.3),
 *   so vanilla and unconfigured modded blocks still participate sensibly.
 *
 * Effective vs base insulation:
 * - {@link #getBaseInsulation} returns the block's own value.
 * - A boundary block's *effective* insulation is boosted by same-surface neighbors via
 *   {@link #applyConnectivity}; a block embedded in a contiguous surface (e.g. the middle
 *   of a floor) trends toward 1.0, while an edge/corner block stays closer to its base.
 *   The neighbor counting is performed by the room scanner, which owns the interior geometry.
 */
public final class BlockInsulationAPI {

    private BlockInsulationAPI() {}

    /** Insulation applied to any block that has no registered value. */
    public static final double DEFAULT_INSULATION = 0.3;

    /** Dynamic provider for state-dependent insulation. Return null to abstain (falls through). */
    @FunctionalInterface
    public interface Provider {
        Double get(World world, BlockPos pos, BlockState state);
    }

    private static final Map<Block, Double> SIMPLE = new ConcurrentHashMap<>();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Register a constant insulation value for a block. Clamped to [0,1]. */
    public static void register(Block block, double insulation) {
        Objects.requireNonNull(block, "block");
        SIMPLE.put(block, clamp01(insulation));
    }

    /** Register a dynamic provider, evaluated (in registration order) after the simple registry. */
    public static void register(Provider provider) {
        if (provider != null) PROVIDERS.add(provider);
    }

    /** True if any block-specific insulation has been registered (simple or provider). */
    public static boolean hasAny() {
        return !SIMPLE.isEmpty() || !PROVIDERS.isEmpty();
    }

    /**
     * Resolve a block's base insulation: registered constant, else first non-null provider,
     * else {@link #DEFAULT_INSULATION}.
     */
    public static double getBaseInsulation(World world, BlockPos pos, BlockState state) {
        if (state == null) return DEFAULT_INSULATION;
        Double v = SIMPLE.get(state.getBlock());
        if (v != null) return v;
        if (!PROVIDERS.isEmpty()) {
            for (Provider p : PROVIDERS) {
                try {
                    Double dyn = p.get(world, pos, state);
                    if (dyn != null) return clamp01(dyn);
                } catch (Throwable ignored) {}
            }
        }
        return DEFAULT_INSULATION;
    }

    /**
     * Boost a boundary block's base insulation toward 1.0 by how embedded it is in its surface.
     *
     * Uses the isotropic 9-point Laplacian weighting (the correct cheap discretization of
     * steady-state conduction): each in-plane edge neighbor is worth 4 units, each diagonal 1,
     * so a fully-surrounded cell (4 edges + 4 diagonals) reaches connectivity 1.0.
     *
     * @param base     the block's own insulation in [0,1]
     * @param edges    qualifying same-surface edge neighbors present (0..4)
     * @param diagonals qualifying same-surface diagonal neighbors present (0..4)
     * @return effective insulation = base + (1 - base) * (4*edges + diagonals)/20, clamped to [0,1]
     */
    public static double applyConnectivity(double base, int edges, int diagonals) {
        int e = Math.max(0, Math.min(4, edges));
        int d = Math.max(0, Math.min(4, diagonals));
        double connectivity = (4.0 * e + d) / 20.0; // 0..1
        double b = clamp01(base);
        return clamp01(b + (1.0 - b) * connectivity);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
