package gavinx.temperatureapi;

import gavinx.temperatureapi.api.BodyTemperatureAPI;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple per-player body temperature storage and ticking.
 *
 * This is a lightweight, non-persistent store intended for debugging and basic gameplay.
 * Other mods can manage persistence if needed or hook into this state.
 */
public final class BodyTemperatureState {
    private static final Map<UUID, Double> BODY_TEMP = new ConcurrentHashMap<>();

    private BodyTemperatureState() {}

    /** Get current body temperature for a player, defaulting to NORMAL_BODY_TEMP_C if unset. */
    public static double getC(ServerPlayerEntity player) {
        return BODY_TEMP.getOrDefault(player.getUuid(), BodyTemperatureAPI.NORMAL_BODY_TEMP_C);
    }

    /** Set current body temperature for a player. */
    public static void setC(ServerPlayerEntity player, double valueC) {
        BODY_TEMP.put(player.getUuid(), valueC);
    }

    /** Advance the player's body temperature by dtSeconds using the passive rate. */
    public static void tick(ServerPlayerEntity player, double dtSeconds) {
        double current = getC(player);
        // Using resistance-aware, homeostasis-aware integration
        double next = BodyTemperatureAPI.advanceBodyTemp(player, current, dtSeconds);
        setC(player, next);
    }

    /** Optional: clear state for a player, e.g., on quit. */
    public static void clear(ServerPlayerEntity player) {
        BODY_TEMP.remove(player.getUuid());
    }
}
