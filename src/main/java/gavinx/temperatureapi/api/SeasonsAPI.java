package gavinx.temperatureapi.api;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Method;

/**
 * SeasonsAPI facade. Integrates with Serene Seasons on Fabric when present.
 *
 * - No hard dependency: uses FabricLoader + reflection
 * - Provides: current season string, and a temperature offset in Celsius
 */
public final class SeasonsAPI {
    private static final String SS_MOD_ID = "sereneseasons";

    private SeasonsAPI() {}

    /** Returns true if Serene Seasons is loaded. */
    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded(SS_MOD_ID);
    }

    /**
     * Get a human-readable current season string, e.g. "Spring (Mid)".
     * Returns "N/A" if seasons mod is not present or not resolvable.
     */
    public static String getCurrentSeason(World world) {
        if (world == null || !isLoaded()) return "N/A";
        try {
            Object state = getSeasonState(world);
            if (state == null) return "N/A";

            String season = invokeNameIfEnum(invokeNoArg(state, "getSeason"));
            String sub = invokeNameIfEnum(invokeNoArg(state, "getSubSeason"));

            if (season != null && sub != null) return prettify(season) + " (" + prettify(sub) + ")";
            if (season != null) return prettify(season);
            if (sub != null) return prettify(sub);
            return state.toString();
        } catch (Throwable t) {
            return "N/A";
        }
    }

    /**
     * Compute a seasonal temperature offset in Celsius based on season/subseason.
     * Returns 0.0 if seasons mod is not present or cannot be resolved.
     */
    public static double temperatureOffsetC(World world, BlockPos pos) {
        if (world == null || pos == null || !isLoaded()) return 0.0;
        try {
            Object state = getSeasonState(world);
            if (state == null) return 0.0;
            Object seasonObj = invokeNoArg(state, "getSeason");
            Object subObj = invokeNoArg(state, "getSubSeason");
            String season = invokeNameIfEnum(seasonObj);
            String sub = invokeNameIfEnum(subObj);

            // Default mapping (can be made configurable later)
            // Values are chosen to be impactful but not extreme, in Celsius
            if (season == null) return 0.0;
            switch (season) {
                case "WINTER":
                    return mapSub(sub, -6.0, -9.0, -7.0); // Early, Mid, Late
                case "SPRING":
                    return mapSub(sub, -2.0, 0.0, 2.0);
                case "SUMMER":
                    return mapSub(sub, 3.0, 6.0, 4.0);
                case "AUTUMN":
                case "FALL": // Some ports may use FALL
                    return mapSub(sub, 2.0, 0.0, -2.0);
                default:
                    return 0.0;
            }
        } catch (Throwable t) {
            return 0.0;
        }
    }

    // --- Reflection helpers ---

    private static Object getSeasonState(World world) throws Exception {
        // Expected API (subject to change per port):
        // sereneseasons.api.season.SeasonHelper.getSeasonState(World)
        Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
        // Try exact World signature first
        try {
            Method m = helper.getMethod("getSeasonState", World.class);
            return m.invoke(null, world);
        } catch (NoSuchMethodException ignored) {}
        // Fallback: try any single-arg method named getSeasonState
        for (Method m : helper.getMethods()) {
            if (!m.getName().equals("getSeasonState")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0].isInstance(world)) {
                return m.invoke(null, world);
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object obj, String methodName) {
        if (obj == null) return null;
        try {
            Method m = obj.getClass().getMethod(methodName);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeNameIfEnum(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Enum<?>) return ((Enum<?>) obj).name();
        // try getName()
        try {
            Method m = obj.getClass().getMethod("name");
            Object v = m.invoke(obj);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {}
        return obj.toString();
    }

    private static double mapSub(String sub, double early, double mid, double late) {
        if (sub == null) return mid;
        switch (sub) {
            case "EARLY": return early;
            case "MID": return mid;
            case "LATE": return late;
            default: return mid;
        }
    }

    private static String prettify(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
