package gavinx.temperatureapi;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-player "soaked" state with a countdown timer (in seconds).
 *
 * Semantics:
 * - If the player is in/touching water OR standing under open sky while it is precipitating (rain/snow),
 *   their soaked timer is refreshed to at least SOAK_DURATION_S.
 * - The timer counts down each tick; when it reaches 0 the player is no longer considered soaked.
 *
 * This state is server-side only and can be persisted via attached mixins.
 */
public final class SoakedState {
    private static final Map<UUID, Double> SOAKED_SECONDS = new ConcurrentHashMap<>();

    // Default duration to apply when newly getting wet (seconds)
    public static final double SOAK_DURATION_S = 10.0;

    private SoakedState() {}

    /** Return remaining soaked seconds (0 if not soaked). */
    public static double getSeconds(ServerPlayerEntity player) {
        return SOAKED_SECONDS.getOrDefault(player.getUuid(), 0.0);
    }

    /** Set remaining soaked seconds (clamped to >= 0). */
    public static void setSeconds(ServerPlayerEntity player, double seconds) {
        SOAKED_SECONDS.put(player.getUuid(), Math.max(0.0, seconds));
    }

    /** Increase soaked timer by additional seconds. */
    public static void addSeconds(ServerPlayerEntity player, double addSeconds) {
        double cur = getSeconds(player);
        setSeconds(player, cur + Math.max(0.0, addSeconds));
    }

    /** True if the player is currently considered soaked. */
    public static boolean isSoaked(ServerPlayerEntity player) {
        return getSeconds(player) > 0.0;
    }

    /** Clear all state for a player. */
    public static void clear(ServerPlayerEntity player) {
        SOAKED_SECONDS.remove(player.getUuid());
    }

    /** Per-tick update: refresh on triggers and decrement timer by dtSeconds. */
    public static void tick(ServerPlayerEntity player, double dtSeconds) {
        boolean gotWet = false;

        // Trigger A: water contact/submersion
        if (player.isSubmergedInWater() || player.isTouchingWater()) {
            gotWet = true;
        }

        // Trigger B: standing under open sky while it's precipitating (rain or snow)
        var world = player.getWorld();
        if (world.isRaining()) {
            BlockPos pos = player.getBlockPos().up();
            if (world.isSkyVisible(pos)) {
                // Some dimensions/boss rooms may technically rain but not on the player; sky visibility guards this
                gotWet = true;
            }
        }

        if (gotWet) {
            // Refresh to at least SOAK_DURATION_S
            double cur = getSeconds(player);
            if (cur < SOAK_DURATION_S) setSeconds(player, SOAK_DURATION_S);
        }

        // Countdown
        double cur = getSeconds(player);
        if (cur > 0.0) {
            double next = cur - Math.max(0.0, dtSeconds);
            setSeconds(player, Math.max(0.0, next));
        }
    }
}
