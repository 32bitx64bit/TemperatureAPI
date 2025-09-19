package gavinx.temperatureapi.api;

import gavinx.temperatureapi.SoakedState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Public API for querying and manipulating a player's temporary "soaked" status.
 *
 * Semantics:
 * - Players become soaked for a brief duration (default 10s) when touching water or
 *   standing under open sky while it is precipitating (rain/snow).
 * - While soaked, body temperature adjusts faster toward cooling: you freeze faster in cold
 *   and heat up more slowly in heat (see BodyTemperatureAPI integration).
 */
public final class SoakedAPI {
    private SoakedAPI() {}

    /** Returns true if the player is currently soaked. Client-side queries return false. */
    public static boolean isSoaked(PlayerEntity player) {
        if (player == null || player.getWorld().isClient()) return false;
        if (player instanceof ServerPlayerEntity sp) {
            return SoakedState.isSoaked(sp);
        }
        return false;
    }

    /** Remaining soaked seconds (0 if not soaked). Client-side returns 0. */
    public static double getSoakedSeconds(PlayerEntity player) {
        if (player == null || player.getWorld().isClient()) return 0.0;
        if (player instanceof ServerPlayerEntity sp) {
            return SoakedState.getSeconds(sp);
        }
        return 0.0;
    }

    /** Set remaining soaked duration in seconds (clamped to >= 0). No-op on client. */
    public static void setSoakedSeconds(PlayerEntity player, double seconds) {
        if (player instanceof ServerPlayerEntity sp) {
            SoakedState.setSeconds(sp, seconds);
        }
    }

    /** Add seconds to remaining soaked timer. No-op on client. */
    public static void addSoakedSeconds(PlayerEntity player, double addSeconds) {
        if (player instanceof ServerPlayerEntity sp) {
            SoakedState.addSeconds(sp, addSeconds);
        }
    }
}
