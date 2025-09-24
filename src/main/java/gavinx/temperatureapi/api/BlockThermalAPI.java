package gavinx.temperatureapi.api;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
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
 * - Default: flood-fill occlusion that "flows" through openings and around corners.
 * - Line-of-sight raycast is also supported and can be selected per source.
 *
 * Falloff (per source):
 * - Each source can be static (no dropoff) or use cosine dropoff beyond its full-strength range.
 *   With dropoff, full strength applies up to 'range', then attenuates with weight = cos((dOver/dropoff) * PI/2)
 *   until 0 at 'range + dropoff'. Default dropoff is 7 (clamped to max 15) if not specified.
 *
 * Caching:
 * - Offsets are cached per (world,pos) for 80 ticks to reduce scanning cost.
 */
public final class BlockThermalAPI {

    private BlockThermalAPI() {}

    /** Occlusion model used to determine if a target position can be affected by a source. */
    public enum OcclusionMode {
        LINE_OF_SIGHT, // straight raycast; fast
        FLOOD_FILL     // vents through openings and around corners; cached per source
    }

    /** Falloff curve for attenuation beyond full-strength range. */
    public enum FalloffCurve {
        COSINE // weight = cos(t * PI/2)
    }

    /** Represents a thermal source or sink contributed by a block. */
    public static final class ThermalSource {
        public final double deltaC; // +C warms, -C cools
        public final int range;     // full-strength radius in blocks, >= 0
        public final OcclusionMode occlusion;
        public final int dropoffBlocks; // 0 = static (no dropoff), else clamp [0,15]
        public final FalloffCurve curve;
        public final Direction face; // optional: directional emission face; null = omnidirectional

        /** Default: FLOOD_FILL occlusion, cosine dropoff of 7 (clamped), omnidirectional. */
        public ThermalSource(double deltaC, int range) {
            this(deltaC, range, OcclusionMode.FLOOD_FILL, 7, FalloffCurve.COSINE, null);
        }
        /** Set occlusion; default cosine dropoff of 7 (clamped), omnidirectional. */
        public ThermalSource(double deltaC, int range, OcclusionMode occlusion) {
            this(deltaC, range, occlusion, 7, FalloffCurve.COSINE, null);
        }
        /** Full control; dropoffBlocks=0 disables dropoff; omnidirectional. */
        public ThermalSource(double deltaC, int range, OcclusionMode occlusion, int dropoffBlocks, FalloffCurve curve) {
            this(deltaC, range, occlusion, dropoffBlocks, curve, null);
        }
        /** Full control + directional emission. */
        public ThermalSource(double deltaC, int range, OcclusionMode occlusion, int dropoffBlocks, FalloffCurve curve, Direction face) {
            this.deltaC = deltaC;
            this.range = Math.max(0, range);
            this.occlusion = occlusion == null ? OcclusionMode.FLOOD_FILL : occlusion;
            int d = Math.max(0, dropoffBlocks);
            if (d > 15) d = 15;
            this.dropoffBlocks = d;
            this.curve = curve == null ? FalloffCurve.COSINE : curve;
            this.face = face; // null -> all directions
        }
        /** Convenience: create a static source (no dropoff), keeping default occlusion. */
        public static ThermalSource staticSource(double deltaC, int range) {
            return new ThermalSource(deltaC, range, OcclusionMode.FLOOD_FILL, 0, FalloffCurve.COSINE, null);
        }
        /** Total influence radius including any dropoff zone. */
        public int influenceRadius() { return range + dropoffBlocks; }
        @Override public String toString() { return "ThermalSource{" + deltaC + "C, r=" + range + ", occ=" + occlusion + ", drop=" + dropoffBlocks + ", curve=" + curve + ", face=" + (face == null ? "omni" : face.asString()) + "}"; }
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

    /** Register a constant thermal source for a specific Block. Defaults: FLOOD_FILL + cosine dropoff(7). */
    public static void register(Block block, double deltaC, int range) {
        Objects.requireNonNull(block, "block");
        ThermalSource ts = new ThermalSource(deltaC, range);
        SIMPLE.put(block, ts);
        int influence = ts.influenceRadius();
        if (influence > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = influence;
        // Cache is time-based; no immediate invalidation needed
    }

    /** Register a constant thermal source with explicit occlusion (still uses cosine dropoff 7 by default). */
    public static void register(Block block, double deltaC, int range, OcclusionMode occlusion) {
        Objects.requireNonNull(block, "block");
        ThermalSource ts = new ThermalSource(deltaC, range, occlusion);
        SIMPLE.put(block, ts);
        int influence = ts.influenceRadius();
        if (influence > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = influence;
    }

    /** Register a constant thermal source with explicit dropoff and occlusion. Set dropoffBlocks=0 for static. */
    public static void register(Block block, double deltaC, int range, OcclusionMode occlusion, int dropoffBlocks) {
        Objects.requireNonNull(block, "block");
        ThermalSource ts = new ThermalSource(deltaC, range, occlusion, dropoffBlocks, FalloffCurve.COSINE, null);
        SIMPLE.put(block, ts);
        int influence = ts.influenceRadius();
        if (influence > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = influence;
    }

    /** Register a constant thermal source with directional emission (single face). */
    public static void register(Block block, double deltaC, int range, Direction face) {
        Objects.requireNonNull(block, "block");
        ThermalSource ts = new ThermalSource(deltaC, range, OcclusionMode.FLOOD_FILL, 7, FalloffCurve.COSINE, face);
        SIMPLE.put(block, ts);
        int influence = ts.influenceRadius();
        if (influence > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = influence;
    }

    /** Register a constant thermal source with occlusion and directional emission. */
    public static void register(Block block, double deltaC, int range, OcclusionMode occlusion, Direction face) {
        Objects.requireNonNull(block, "block");
        ThermalSource ts = new ThermalSource(deltaC, range, occlusion, 7, FalloffCurve.COSINE, face);
        SIMPLE.put(block, ts);
        int influence = ts.influenceRadius();
        if (influence > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = influence;
    }

    /** Register a constant thermal source with occlusion, dropoff, and directional emission. */
    public static void register(Block block, double deltaC, int range, OcclusionMode occlusion, int dropoffBlocks, Direction face) {
        Objects.requireNonNull(block, "block");
        ThermalSource ts = new ThermalSource(deltaC, range, occlusion, dropoffBlocks, FalloffCurve.COSINE, face);
        SIMPLE.put(block, ts);
        int influence = ts.influenceRadius();
        if (influence > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = influence;
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

                    int drop = ts.dropoffBlocks;
                    int budget = ts.range + drop;

                    // Distance check (Euclidean quick reject)
                    double euclid = atPos.toCenterPos().distanceTo(bp.toCenterPos());
                    if (euclid > budget + 0.5) continue;

                    // Directional emission cull: only affect targets on the chosen face's half-space
                    if (ts.face != null && !bp.equals(atPos)) {
                        Vec3d src = bp.toCenterPos();
                        Vec3d tgt = atPos.toCenterPos();
                        Vec3d diff = tgt.subtract(src);
                        Direction f = ts.face;
                        double dot = diff.x * f.getOffsetX() + diff.y * f.getOffsetY() + diff.z * f.getOffsetZ();
                        if (dot <= 0.0) continue;
                    }

                    // Quick seal test: if the source block is fully enclosed by non-passable neighbors, skip
                    if (isFullySealed(world, bp)) {
                        continue;
                    }

                    double contrib = 0.0;
                    if (ts.occlusion == OcclusionMode.FLOOD_FILL) {
                        int steps;
                        if (ts.face != null) {
                            // Require path to exit through the specified face
                            steps = FloodFill.stepsToViaFace(world, bp, atPos, budget, ts.face);
                        } else {
                            steps = FloodFill.stepsTo(world, bp, atPos, budget);
                        }
                        if (steps < 0) continue; // unreachable
                        if (steps <= ts.range) {
                            contrib = ts.deltaC;
                        } else if (drop > 0) {
                            int over = Math.max(0, steps - ts.range);
                            double t = Math.min(1.0, over / (double) drop);
                            contrib = ts.deltaC * weight(ts.curve, t);
                        } else {
                            continue;
                        }
                    } else {
                        boolean clear = atPos.equals(bp) || hasLineOfSight(world, atPos, bp);
                        if (!clear) continue;
                        if (euclid <= ts.range + 0.5) {
                            contrib = ts.deltaC;
                        } else if (drop > 0) {
                            double over = Math.max(0.0, euclid - ts.range);
                            double t = Math.min(1.0, over / drop);
                            contrib = ts.deltaC * weight(ts.curve, t);
                        } else {
                            continue;
                        }
                    }

                    sum += contrib;
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
                        // Track max influence radius from dynamic inputs
                        int influence = dyn.influenceRadius();
                        if (influence > MAX_SOURCE_RANGE) MAX_SOURCE_RANGE = influence;
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

        try {
            // Entity is optional in modern mappings; pass null safely
            RaycastContext ctx = new RaycastContext(
                    start,
                    end,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.ANY,
                    null
            );
            var hit = world.raycast(ctx);
            if (hit == null) return false;
            if (hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return true;
            BlockPos hitPos = hit.getBlockPos();
            return hitPos != null && hitPos.equals(to);
        } catch (Throwable t) {
            // On any unexpected failure, fail closed (treat as occluded)
            return false;
        }
    }

    private static boolean isFullySealed(World world, BlockPos source) {
        for (Direction d : Direction.values()) {
            BlockPos np = source.offset(d);
            BlockState st = world.getBlockState(np);
            if (isPassable(world, np, st)) return false;
        }
        return true;
    }

    // --- Flood-fill propagation (optional occlusion mode) ---

    private static final class FloodFill {
        private static final Map<Cache.WorldKey, Map<Long, FFEntry>> CACHE = new ConcurrentHashMap<>();
        private static final long TTL_TICKS = 80L;

        static int stepsTo(World world, BlockPos source, BlockPos target, int budget) {
            if (source.equals(target)) return 0;
            int manhattan = Math.abs(target.getX() - source.getX()) + Math.abs(target.getY() - source.getY()) + Math.abs(target.getZ() - source.getZ());
            if (manhattan > budget) return -1;

            Map<Long, FFEntry> bySource = CACHE.computeIfAbsent(Cache.WorldKey.of(world), k -> new ConcurrentHashMap<>());
            long key = (source.asLong() ^ ((long) budget << 32));
            long now = world.getTime();
            FFEntry e = bySource.get(key);
            if (e == null || (now - e.tick) > TTL_TICKS) {
                e = compute(world, source, budget);
                bySource.put(key, e);
            }
            Integer dist = e.distances.get(target.asLong());
            return dist == null ? -1 : dist;
        }

        static int stepsToViaFace(World world, BlockPos source, BlockPos target, int budget, Direction face) {
            if (source.equals(target)) return 0;
            int manhattan = Math.abs(target.getX() - source.getX()) + Math.abs(target.getY() - source.getY()) + Math.abs(target.getZ() - source.getZ());
            if (manhattan > budget) return -1;

            Map<Long, FFEntry> bySource = CACHE.computeIfAbsent(Cache.WorldKey.of(world), k -> new ConcurrentHashMap<>());
            long key = (source.asLong() ^ ((long) budget << 32) ^ ((long) face.getId() << 48));
            long now = world.getTime();
            FFEntry e = bySource.get(key);
            if (e == null || (now - e.tick) > TTL_TICKS) {
                e = computeViaFace(world, source, budget, face);
                bySource.put(key, e);
            }
            Integer dist = e.distances.get(target.asLong());
            return dist == null ? -1 : dist;
        }

        private static FFEntry compute(World world, BlockPos source, int budget) {
            java.util.ArrayDeque<Node> q = new java.util.ArrayDeque<>();
            java.util.HashSet<Long> visited = new java.util.HashSet<>();
            java.util.HashMap<Long, Integer> distances = new java.util.HashMap<>();

            q.add(new Node(source, 0));
            visited.add(source.asLong());
            distances.put(source.asLong(), 0);

            while (!q.isEmpty()) {
                Node n = q.poll();
                if (n.dist >= budget) continue; // no remaining budget
                for (var dir : net.minecraft.util.math.Direction.values()) {
                    BlockPos np = n.pos.offset(dir);
                    long npLong = np.asLong();
                    if (visited.contains(npLong)) continue;
                    BlockState st = world.getBlockState(np);
                    if (!isPassable(world, np, st)) continue;
                    visited.add(npLong);
                    distances.put(npLong, n.dist + 1);
                    q.add(new Node(np, n.dist + 1));
                }
            }
            return new FFEntry(world.getTime(), budget, distances);
        }

        private static FFEntry computeViaFace(World world, BlockPos source, int budget, Direction face) {
            java.util.ArrayDeque<Node> q = new java.util.ArrayDeque<>();
            java.util.HashSet<Long> visited = new java.util.HashSet<>();
            java.util.HashMap<Long, Integer> distances = new java.util.HashMap<>();

            visited.add(source.asLong());
            distances.put(source.asLong(), 0);

            // Seed only the specified face direction from the source
            BlockPos first = source.offset(face);
            if (isPassable(world, first, world.getBlockState(first))) {
                q.add(new Node(first, 1));
                visited.add(first.asLong());
                distances.put(first.asLong(), 1);
            }

            while (!q.isEmpty()) {
                Node n = q.poll();
                if (n.dist >= budget) continue;
                for (var dir : net.minecraft.util.math.Direction.values()) {
                    BlockPos np = n.pos.offset(dir);
                    long npLong = np.asLong();
                    if (visited.contains(npLong)) continue;
                    BlockState st = world.getBlockState(np);
                    if (!isPassable(world, np, st)) continue;
                    visited.add(npLong);
                    distances.put(npLong, n.dist + 1);
                    q.add(new Node(np, n.dist + 1));
                }
            }
            return new FFEntry(world.getTime(), budget, distances);
        }

        private record Node(BlockPos pos, int dist) {}
        private record FFEntry(long tick, int budget, java.util.HashMap<Long, Integer> distances) {}
    }

    private static boolean isPassable(World world, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        try {
            if (state.getCollisionShape(world, pos).isEmpty()) return true;
        } catch (Throwable ignored) {}
        try {
            if (state.getBlock() instanceof DoorBlock && state.contains(DoorBlock.OPEN) && state.get(DoorBlock.OPEN)) return true;
            if (state.getBlock() instanceof FenceGateBlock && state.contains(FenceGateBlock.OPEN) && state.get(FenceGateBlock.OPEN)) return true;
            if (state.getBlock() instanceof TrapdoorBlock && state.contains(TrapdoorBlock.OPEN) && state.get(TrapdoorBlock.OPEN)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static double weight(FalloffCurve curve, double t) {
        // t in [0,1]
        t = Math.max(0.0, Math.min(1.0, t));
        // Only COSINE currently
        return Math.cos(t * (Math.PI / 2.0));
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
