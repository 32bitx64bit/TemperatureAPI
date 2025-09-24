package gavinx.temperatureapi.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Optional runtime integration with the Dehydration mod.
 *
 * We avoid any compile-time dependency by using reflection. If the mod is not present,
 * all methods are safe no-ops/defaults.
 */
public final class DehydrationCompat {
    private static final String MOD_ID = "dehydration";

    private DehydrationCompat() {}

    /** Returns true if the Dehydration mod is loaded. */
    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }

    /**
     * Returns true if the player is considered "dehydrated" by the mod.
     * Current heuristic: thirst level <= 0 (fully dehydrated) and hasThirst() is true when available.
     */
    public static boolean isPlayerDehydrated(PlayerEntity player) {
        if (player == null || !isLoaded()) return false;
        try {
            // Dehydration mixin adds getThirstManager() to PlayerEntity via ThirstManagerAccess
            Object thirstManager = player.getClass().getMethod("getThirstManager").invoke(player);
            if (thirstManager == null) return false;

            // Optional: check hasThirst() if present
            boolean hasThirst = true;
            try {
                Object has = thirstManager.getClass().getMethod("hasThirst").invoke(thirstManager);
                if (has instanceof Boolean b) hasThirst = b;
            } catch (NoSuchMethodException ignored) {
                // older/newer versions might not expose this; assume true
            }
            if (!hasThirst) return false;

            Object lvlObj = thirstManager.getClass().getMethod("getThirstLevel").invoke(thirstManager);
            if (lvlObj instanceof Integer level) {
                return level <= 0; // fully dehydrated
            }
        } catch (Throwable ignored) {
            // Any failure: treat as not dehydrated to remain safe
        }
        return false;
    }
}
