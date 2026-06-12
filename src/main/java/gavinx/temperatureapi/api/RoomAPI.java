package gavinx.temperatureapi.api;

import gavinx.temperatureapi.api.internal.Occlusion;
import net.minecraft.block.BlockState;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RoomAPI
 *
 * Detects the enclosed space around a position and summarizes it for the indoor-climate model:
 * how enclosed it is, how well-insulated its surfaces are, and which thermostatic emitters
 * are present. Work is done per-structure (one bounded scan shared by everyone inside), not
 * per air-block, and cached so a query is O(1) on the common path.
 *
 * Enclosure: {@code wallFaces / (wallFaces + LEAK_WEIGHT * leakFaces)} in [0,1], where a leak
 * face is an interior face that vents to open sky. A sealed room is 1.0; a hole in the roof or
 * an open doorway pulls it down; standing outdoors is 0.0; a deep cave (no sky within budget)
 * reads as fully enclosed.
 *
 * Average insulation: the face-area-weighted mean of each wall block's *effective* insulation,
 * where a block embedded in a contiguous surface is boosted toward 1.0 (see
 * {@link BlockInsulationAPI#applyConnectivity}).
 */
public final class RoomAPI {

    private RoomAPI() {}

    // --- Tunables ---
    /** Maximum BFS radius (in passable steps) from the query position. */
    public static final int MAX_STEPS = 32;
    /** Maximum number of interior cells visited before the scan stops. */
    public static final int MAX_CELLS = 4096;
    /** How long (ticks) a scanned room stays valid before a re-scan. */
    public static final long ROOM_TTL = 100L;
    /** Maximum cached rooms per world (FIFO eviction). */
    public static final int MAX_ROOMS = 64;
    /** How much one open (sky-venting) face leaks relative to a wall face. */
    public static final double LEAK_WEIGHT = 6.0;
    /** Enclosure at or below this is treated as effectively outdoors. */
    public static final double OUTDOOR_EPSILON = 0.02;

    /** A resolved, volume-scaled thermostatic emitter acting on a room. */
    public record ActiveSetpoint(double power, double targetC, BlockSetpointAPI.Mode mode, boolean persistent) {}

    /** Immutable summary of an enclosed space, plus mutable indoor-climate state managed by IndoorClimateAPI. */
    public static final class Room {
        public final double enclosure;      // 0..1
        public final double avgInsulation;  // 0..1 (effective, connectivity-boosted)
        public final int volume;            // interior cell count
        public final boolean outdoor;       // true if effectively open to the sky
        public final List<ActiveSetpoint> setpoints;
        public final boolean hasPersistentSetpoint;

        final Set<Long> interior;
        final long computedTick;

        // Indoor-climate state (written by IndoorClimateAPI; carried across rescans).
        double indoorC = Double.NaN;
        boolean indoorInit = false;
        long lastTick = 0L;

        Room(double enclosure, double avgInsulation, int volume, boolean outdoor,
             List<ActiveSetpoint> setpoints, Set<Long> interior, long computedTick) {
            this.enclosure = enclosure;
            this.avgInsulation = avgInsulation;
            this.volume = volume;
            this.outdoor = outdoor;
            this.setpoints = setpoints;
            boolean persist = false;
            for (ActiveSetpoint s : setpoints) if (s.persistent()) { persist = true; break; }
            this.hasPersistentSetpoint = persist;
            this.interior = interior;
            this.computedTick = computedTick;
        }

        /** Effective insulation strength of the structure: avgInsulation * enclosure, in [0,1]. */
        public double effectiveAlpha() {
            return outdoor ? 0.0 : avgInsulation * enclosure;
        }
    }

    /**
     * Get the room containing {@code pos}, scanning on a cache miss. Never returns null;
     * an open-air position yields a room with {@code outdoor == true}.
     */
    public static Room get(World world, BlockPos pos) {
        if (world == null || pos == null) return null;
        WorldCache wc = CACHES.computeIfAbsent(keyFor(world), k -> new WorldCache());
        long now = world.getTime();
        long cell = pos.asLong();

        Room existing = wc.index.get(cell);
        if (existing != null && (now - existing.computedTick) <= ROOM_TTL) {
            return existing;
        }

        Room scanned = scan(world, pos, now);
        // Carry indoor-climate state forward from a predecessor room covering this position.
        if (existing != null && existing.indoorInit) {
            scanned.indoorC = existing.indoorC;
            scanned.indoorInit = true;
            scanned.lastTick = existing.lastTick;
        }
        wc.install(scanned);
        return scanned;
    }

    /** Drop all cached rooms for a world (e.g., on unload). Safe to call anytime. */
    public static void invalidate(World world) {
        if (world == null) return;
        CACHES.remove(keyFor(world));
    }

    // --- Scanning ---

    private static Room scan(World world, BlockPos start, long now) {
        BlockState startState = world.getBlockState(start);
        boolean startPassable = Occlusion.isPassable(world, start, startState);

        // Fast path: the query cell is itself open to the sky -> outdoors.
        if (startPassable && Occlusion.hasPassableSkyColumn(world, start)) {
            return outdoorRoom(start, now);
        }

        // --- Pass 1: flood the interior, count sky-venting leak faces ---
        Set<Long> interior = new HashSet<>();
        ArrayDeque<long[]> queue = new ArrayDeque<>(); // {cellLong, depth}

        // Seed: the start cell if passable, otherwise its passable (non-sky) neighbors.
        if (startPassable) {
            interior.add(start.asLong());
            queue.add(new long[]{start.asLong(), 0});
        } else {
            for (Direction d : Direction.values()) {
                BlockPos np = start.offset(d);
                if (!Occlusion.isPassable(world, np, world.getBlockState(np))) continue;
                if (Occlusion.hasPassableSkyColumn(world, np)) continue; // opens to sky; not interior
                long npl = np.asLong();
                if (interior.add(npl)) queue.add(new long[]{npl, 0});
            }
        }
        if (interior.isEmpty()) {
            // Embedded in solid blocks or fully open to sky: treat as outdoors (no lag).
            return outdoorRoom(start, now);
        }

        int leakFaces = 0;
        while (!queue.isEmpty()) {
            long[] node = queue.poll();
            BlockPos c = BlockPos.fromLong(node[0]);
            int depth = (int) node[1];
            for (Direction d : Direction.values()) {
                BlockPos np = c.offset(d);
                BlockState st = world.getBlockState(np);
                if (!Occlusion.isPassable(world, np, st)) continue; // solid -> handled as wall in pass 2
                if (Occlusion.hasPassableSkyColumn(world, np)) {
                    leakFaces++; // this interior face vents to open sky
                    continue;    // do not flood outdoors
                }
                long npl = np.asLong();
                if (interior.contains(npl)) continue;
                if (depth + 1 > MAX_STEPS || interior.size() >= MAX_CELLS) continue;
                interior.add(npl);
                queue.add(new long[]{npl, depth + 1});
            }
        }

        int volume = interior.size();

        // --- Pass 2: walls (effective insulation) + setpoint emitters, using the complete interior set ---
        int wallFaces = 0;
        double sumEffective = 0.0;

        boolean collectSetpoints = BlockSetpointAPI.hasAny();
        Set<Long> emitterSeen = collectSetpoints ? new HashSet<>() : null;
        List<ActiveSetpoint> setpoints = new ArrayList<>();

        for (long cl : interior) {
            BlockPos c = BlockPos.fromLong(cl);

            if (collectSetpoints) {
                // An emitter can be a non-solid block occupying an interior cell.
                addSetpoint(world, c, world.getBlockState(c), volume, emitterSeen, setpoints);
            }

            for (Direction d : Direction.values()) {
                BlockPos np = c.offset(d);
                BlockState st = world.getBlockState(np);
                if (Occlusion.isPassable(world, np, st)) continue; // open or interior -> not a wall
                wallFaces++;
                sumEffective += effectiveWallInsulation(world, c, d, interior);
                if (collectSetpoints) {
                    addSetpoint(world, np, st, volume, emitterSeen, setpoints);
                }
            }
        }

        double enclosure = (wallFaces + LEAK_WEIGHT * leakFaces) > 0.0
                ? wallFaces / (wallFaces + LEAK_WEIGHT * leakFaces)
                : 0.0;
        double avgInsulation = wallFaces > 0 ? sumEffective / wallFaces : 0.0;
        boolean outdoor = enclosure <= OUTDOOR_EPSILON;

        return new Room(enclosure, avgInsulation, volume, outdoor, setpoints, interior, now);
    }

    private static void addSetpoint(World world, BlockPos pos, BlockState state, int volume,
                                    Set<Long> seen, List<ActiveSetpoint> out) {
        if (!seen.add(pos.asLong())) return;
        BlockSetpointAPI.Setpoint s = BlockSetpointAPI.resolve(world, pos, state);
        if (s == null) return;
        double power = BlockSetpointAPI.powerPerTick(s, volume);
        if (power <= 0.0) return;
        out.add(new ActiveSetpoint(power, s.targetC, s.mode, s.persistsWhileUnloaded));
    }

    /**
     * Effective insulation of the wall block at {@code C.offset(d)}, boosted by same-surface
     * neighbors. A lateral slot reinforces the wall only if its matching interior cell is part
     * of this room AND a wall block exists there in the same orientation -- which automatically
     * excludes perpendicular walls (the connective-structure exclusion).
     */
    private static double effectiveWallInsulation(World world, BlockPos c, Direction d, Set<Long> interior) {
        BlockPos b = c.offset(d);
        double base = BlockInsulationAPI.getBaseInsulation(world, b, world.getBlockState(b));

        int[][] offs = planeOffsets(d);
        int edges = 0, diags = 0;
        for (int i = 0; i < 8; i++) {
            BlockPos cp = c.add(offs[i][0], offs[i][1], offs[i][2]);
            if (!interior.contains(cp.asLong())) continue;            // not same room/surface
            BlockPos bp = cp.offset(d);
            if (Occlusion.isPassable(world, bp, world.getBlockState(bp))) continue; // hole, not a wall
            if (i < 4) edges++; else diags++;
        }
        return BlockInsulationAPI.applyConnectivity(base, edges, diags);
    }

    /** The 8 in-plane neighbor offsets (4 edges then 4 diagonals) for the plane perpendicular to {@code d}. */
    private static int[][] planeOffsets(Direction d) {
        int ux, uy, uz, vx, vy, vz;
        switch (d.getAxis()) {
            case Y -> { ux = 1; uy = 0; uz = 0; vx = 0; vy = 0; vz = 1; }
            case X -> { ux = 0; uy = 1; uz = 0; vx = 0; vy = 0; vz = 1; }
            default -> { ux = 1; uy = 0; uz = 0; vx = 0; vy = 1; vz = 0; } // Z
        }
        return new int[][]{
                {ux, uy, uz}, {-ux, -uy, -uz}, {vx, vy, vz}, {-vx, -vy, -vz},
                {ux + vx, uy + vy, uz + vz}, {ux - vx, uy - vy, uz - vz},
                {-ux + vx, -uy + vy, -uz + vz}, {-ux - vx, -uy - vy, -uz - vz}
        };
    }

    private static Room outdoorRoom(BlockPos start, long now) {
        Set<Long> one = new HashSet<>(1);
        one.add(start.asLong());
        return new Room(0.0, 0.0, 1, true, List.of(), one, now);
    }

    // --- Per-world cache (cell -> room index + FIFO eviction) ---

    private static final Map<String, WorldCache> CACHES = new ConcurrentHashMap<>();

    private static String keyFor(World world) {
        Identifier id = world.getRegistryKey().getValue();
        return (world.isClient() ? "c:" : "s:") + id;
    }

    private static final class WorldCache {
        final ConcurrentHashMap<Long, Room> index = new ConcurrentHashMap<>();
        final ArrayDeque<Room> lru = new ArrayDeque<>();

        void install(Room r) {
            for (long c : r.interior) index.put(c, r);
            synchronized (lru) {
                lru.addLast(r);
                while (lru.size() > MAX_ROOMS) {
                    Room old = lru.pollFirst();
                    if (old == null) break;
                    // Conditional remove: only drop cells still mapped to the evicted room,
                    // so a rescanned room sharing the same cells is not clobbered.
                    for (long c : old.interior) index.remove(c, old);
                }
            }
        }
    }
}
