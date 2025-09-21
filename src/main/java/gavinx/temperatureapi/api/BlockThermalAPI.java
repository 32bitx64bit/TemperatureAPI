package gavinx.temperatureapi.api;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BlockThermalAPI
 *
 * Lightweight registry and resolver for block-based thermal sources/sinks.
 * Other mods can register either a simple constant source for their Block, or
 * provide a dynamic provider that computes intensity/range from BlockState.
 *
 * Units:
 * - deltaC: degrees Celsius (positive warms, negative cools)
 * - range: whole blocks (Manhattan radius is not enforced; we use Euclidean distance)
 *
 * Occlusion:
 * - Line-of-sight is enforced using a raycast from the evaluation position to the
 *   center of the source block. If the first hit is not the source block, the effect
 *   is considered blocked.
 *
 * Caching:
 * - Offsets are cached per (world,pos) for 80 ticks to reduce scanning cost.
 */
public final class BlockThermalAPI {

    private BlockThermalAPI() {}

    /** Represents a thermal source or sink contributed by a block. */
    public static final class ThermalSource {
        public final double deltaC; // +C warms, -C cools
        public final int range;     // in blocks, >= 0
        public ThermalSource(double deltaC, int range) {
            this.deltaC = deltaC;
            this.range = Math.max(0, range);
        }
        @Override public String toString() { return "ThermalSource{" + deltaC + "C, r=" + range + "}"; }
    }

    /** Provider for dynamic sources based on world/blockstate. Return null for no effect. */
    @FunctionalInterface
    public interface Provider {
        ThermalSource get(World world, BlockPos pos, BlockState state);
    }

    // Simple registry: constant source per Block
    private static final Map<Block, ThermalSource> SIMPLE = new ConcurrentHashMap<>();

    // Dynamic providers (evaluated after SIMPLE)
    private static final List<Provider> PROVIDERS = new ArrayList<>();

    // Track the maximum scan radius implied by registrations
    private static volatile int MAX_SOURCE_RANGE = 0;

    /** Register a constant thermal source for a specific Block. */
    public static void register(Block block, double deltaC, int range) {
        Objects.requireNonNull(block, "block");
        ThermalSource ts = new ThermalSource(deltaC, range);
        SIMPLE.put(block, ts);
        if (ts.range > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = ts.range;
        // Cache is time-based; no immediate invalidation needed
    }

    /** Register a dynamic provider. Note: if you use this overload, also call setGlobalMaxRangeHint or use the provider+range overload so the scanner knows how far to search. */
    public static void register(Provider provider) {
        if (provider != null) {
            PROVIDERS.add(provider);
        }
    }

    /** Register a dynamic provider and give a maximum range hint so the scanner searches far enough. */
    public static void register(Provider provider, int maxRangeHint) {
        if (provider != null) {
            PROVIDERS.add(provider);
            if (maxRangeHint > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = Math.max(0, maxRangeHint);
        }
    }

    /** Set a global maximum scan radius hint (used when only dynamic providers are registered). */
    public static void setGlobalMaxRangeHint(int maxRange) {
        if (maxRange > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = Math.max(0, maxRange);
    }

    /** Return the ambient temperature offset in Celsius at a position due to registered block sources. */
    public static double temperatureOffsetC(World world, BlockPos atPos) {
        if (world == null || atPos == null) return 0.0;
        // Cached computation per 80-tick window
        double cached = Cache.get(world, atPos);
        if (!Double.isNaN(cached)) return cached;

        int maxR = Math.max(0, MAX_SOURCE_RANGE);
        if (maxR == 0 && PROVIDERS.isEmpty()) {
            Cache.put(world, atPos, 0.0);
            return 0.0;
        }

        double sum = 0.0;
        // Iterate a cube around the position. Include the origin block as sources like campfires are often underfoot.
        for (int dx = -maxR; dx <= maxR; dx++) {
            for (int dy = -maxR; dy <= maxR; dy++) {
                for (int dz = -maxR; dz <= maxR; dz++) {
                    BlockPos bp = atPos.add(dx, dy, dz);
                    BlockState state = world.getBlockState(bp);
                    if (state.isAir()) continue;

                    ThermalSource ts = resolve(world, bp, state);
                    if (ts == null || ts.range <= 0 || ts.deltaC == 0.0) continue;

                    // Distance check (Euclidean)
                    double dist = atPos.toCenterPos().distanceTo(bp.toCenterPos());
                    if (dist > ts.range + 0.5) continue;

                    // Occlusion check via raycast (treat same-block as clear)
                    if (!atPos.equals(bp) && !hasLineOfSight(world, atPos, bp)) continue;

                    // No falloff for now: full effect within range
                    sum += ts.deltaC;
                }
            }
        }

        Cache.put(world, atPos, sum);
        return sum;
    }

    private static ThermalSource resolve(World world, BlockPos pos, BlockState state) {
        ThermalSource ts = SIMPLE.get(state.getBlock());
        if (ts != null) return ts;
        if (!PROVIDERS.isEmpty()) {
            for (Provider p : PROVIDERS) {
                try {
                    ThermalSource dyn = p.get(world, pos, state);
                    if (dyn != null) {
                        // Track max radius from dynamic inputs loosely
                        if (dyn.range > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = dyn.range;
                        if (dyn.deltaC != 0.0 && dyn.range > 0) return dyn;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean hasLineOfSight(World world, BlockPos from, BlockPos to) {
        if (from.equals(to)) return true;
        Vec3d start = from.toCenterPos();
        Vec3d end = to.toCenterPos();

        // Prefer using a nearby player as the raycast context entity (avoids NPE in RaycastContext)
        net.minecraft.entity.player.PlayerEntity ctxEntity = world.getClosestPlayer(start.x, start.y, start.z, 64.0, false);
        if (ctxEntity == null) {
            // No suitable entity context available; fail open to avoid crashes in headless contexts
            try {
                RaycastContext ctx = new RaycastContext(
                        start,
                        end,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.ANY,
                        // still pass null, but guard with try/catch in case of NPE in some mappings
                        null
                );
                var hit = world.raycast(ctx);
                if (hit == null) return true;
                if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return true;
                BlockPos hitPos = hit.getBlockPos();
                return hitPos != null && hitPos.equals(to);
            } catch (Throwable t) {
                // Mapping or runtime may NPE when entity is null; treat as clear to prevent hard crash
                return true;
            }
        } else {
            RaycastContext ctx = new RaycastContext(
                    start,
                    end,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.ANY,
                    ctxEntity
            );
            var hit = world.raycast(ctx);
            if (hit == null) return true;
            if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return true;
            BlockPos hitPos = hit.getBlockPos();
            return hitPos != null && hitPos.equals(to);
        }
    }

    // --- Cache impl ---

    private static final class Cache {
        private static final long TTL_TICKS = 80L;
        private static final Map<WorldKey, Map<Long, Entry>> CACHE = new ConcurrentHashMap<>();

        static double get(World world, BlockPos pos) {
            WorldKey wk = WorldKey.of(world);
            Map<Long, Entry> map = CACHE.get(wk);
            if (map == null) return Double.NaN;
            Entry e = map.get(pos.asLong());
            if (e == null) return Double.NaN;
            long now = world.getTime();
            if (now - e.tick <= TTL_TICKS) {
                return e.value;
            } else {
                map.remove(pos.asLong());
                return Double.NaN;
            }
        }

        static void put(World world, BlockPos pos, double value) {
            WorldKey wk = WorldKey.of(world);
            CACHE.computeIfAbsent(wk, k -> new ConcurrentHashMap<>())
                    .put(pos.asLong(), new Entry(world.getTime(), value));
        }

        record Entry(long tick, double value) {}

        // Distinguish worlds by registry key namespace/path
        record WorldKey(String id) {
            static WorldKey of(World world) {
                Identifier key = world.getRegistryKey().getValue();
                return new WorldKey(key.toString());
            }
        }
    }
}
