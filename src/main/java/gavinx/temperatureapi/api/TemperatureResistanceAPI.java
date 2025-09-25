package gavinx.temperatureapi.api;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.List;

/**
 * TemperatureResistanceAPI
 *
 * Provides utilities for computing a player's effective heat/cold resistance and
 * a simple registration point for other mods to contribute custom sources.
 *
 * New resistance model (tiers-based):
 * - Items may declare resistance via a single NBT string key: "tempapi_resistance"
 * - Format supports one or both directions, e.g.: "heat:3" or "heat:3,cold:2"
 * - Accepted directions (case-insensitive): "heat" and "cold"
 * - Accepted tiers: 1..6 (each tier = 2°C)
 *   - heat: expands the hot side of the comfort band by +2°C per tier
 *   - cold: expands the cold side of the comfort band by 2°C per tier (effectively lowers comfort min by 2°C per tier)
 *
 * Resistance totals feed into BodyTemperatureAPI by widening the comfort band
 *   comfortMin = COMFORT_MIN_C - totalColdC
 *   comfortMax = COMFORT_MAX_C + totalHeatC
 *
 * Mods can also register a provider via registerProvider to supply dynamic resistances (e.g., trinkets/baubles).
 */
public final class TemperatureResistanceAPI {

    private TemperatureResistanceAPI() {}

    // Control whether the automatic tooltip is shown for items declaring temp resistance (default: true)
    public static volatile boolean AUTO_TOOLTIPS_ENABLED = true;
    public static void setAutoTooltipsEnabled(boolean enabled) { AUTO_TOOLTIPS_ENABLED = enabled; }

    // New single NBT key for declaring resistances on an ItemStack
    public static final String NBT_RESISTANCE = "tempapi_resistance"; // e.g., "heat:3,cold:2"

    // Optional global caps (per direction). Mods can ignore these by calling tierToDegrees directly.
    public static final double DEFAULT_MAX_HEAT_RESIST_C = 48.0; // tiers up to 6 -> 12°C per source
    public static final double DEFAULT_MAX_COLD_RESIST_C = 48.0; // tiers up to 6 -> 12°C per source

    /** Encapsulates resistance in degrees Celsius for both directions. */
    public static final class Resistance {
        public final double heatC; // increases comfort MAX by this amount
        public final double coldC; // increases comfort in cold by this amount (lowers comfort MIN by this amount)
        public Resistance(double heatC, double coldC) {
            this.heatC = Math.max(0.0, heatC);
            this.coldC = Math.max(0.0, coldC);
        }
        public Resistance add(Resistance other) {
            if (other == null) return this;
            return new Resistance(this.heatC + other.heatC, this.coldC + other.coldC);
        }
        @Override public String toString() { return "Resistance{" + heatC + "C heat, " + coldC + "C cold}"; }
    }

    @FunctionalInterface
    public interface ResistanceProvider {
        Resistance get(PlayerEntity player);
    }

    private static final List<ResistanceProvider> PROVIDERS = new ArrayList<>();

    /** Register a provider that can contribute additional resistance for a player (e.g., trinket slots). */
    public static void registerProvider(ResistanceProvider provider) {
        if (provider != null) PROVIDERS.add(provider);
    }

    /** Compute total resistance from equips + registered providers. */
    public static Resistance computeTotal(PlayerEntity player) {
        if (player == null) return new Resistance(0, 0);
        double heat = 0.0;
        double cold = 0.0;

        // Built-in: standard equipment slots
        heat += stackResistance(player.getEquippedStack(EquipmentSlot.HEAD)).heatC;
        cold += stackResistance(player.getEquippedStack(EquipmentSlot.HEAD)).coldC;

        heat += stackResistance(player.getEquippedStack(EquipmentSlot.CHEST)).heatC;
        cold += stackResistance(player.getEquippedStack(EquipmentSlot.CHEST)).coldC;

        heat += stackResistance(player.getEquippedStack(EquipmentSlot.LEGS)).heatC;
        cold += stackResistance(player.getEquippedStack(EquipmentSlot.LEGS)).coldC;

        heat += stackResistance(player.getEquippedStack(EquipmentSlot.FEET)).heatC;
        cold += stackResistance(player.getEquippedStack(EquipmentSlot.FEET)).coldC;

        heat += stackResistance(player.getEquippedStack(EquipmentSlot.MAINHAND)).heatC;
        cold += stackResistance(player.getEquippedStack(EquipmentSlot.MAINHAND)).coldC;

        heat += stackResistance(player.getEquippedStack(EquipmentSlot.OFFHAND)).heatC;
        cold += stackResistance(player.getEquippedStack(EquipmentSlot.OFFHAND)).coldC;

        // External providers (e.g. trinkets)
        for (ResistanceProvider rp : PROVIDERS) {
            try {
                Resistance extra = rp.get(player);
                if (extra != null) {
                    heat += Math.max(0.0, extra.heatC);
                    cold += Math.max(0.0, extra.coldC);
                }
            } catch (Throwable ignored) {}
        }

        // Clamp to defaults (can be revisited later if stacking should exceed caps)
        heat = Math.min(DEFAULT_MAX_HEAT_RESIST_C, heat);
        cold = Math.min(DEFAULT_MAX_COLD_RESIST_C, cold);
        return new Resistance(heat, cold);
    }

    /** Convert a tier value to degrees C (absolute magnitude). Accepts 1..6. */
    public static double tierToDegrees(int tier) {
        int abs = Math.abs(tier);
        return (abs >= 1 && abs <= 6) ? abs * 2.0 : 0.0;
    }

    /** Parse both heat and cold resistance from an ItemStack's NBT. */
    public static Resistance stackResistance(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new Resistance(0.0, 0.0);
        NbtCompound tag = stack.getNbt();
        if (tag == null || !tag.contains(NBT_RESISTANCE)) return new Resistance(0.0, 0.0);
        String spec;
        try {
            spec = tag.getString(NBT_RESISTANCE);
        } catch (Throwable t) {
            return new Resistance(0.0, 0.0);
        }
        if (spec == null) return new Resistance(0.0, 0.0);
        spec = spec.trim();
        if (spec.isEmpty()) return new Resistance(0.0, 0.0);

        double heat = 0.0;
        double cold = 0.0;

        // Allow comma, semicolon, or whitespace separated entries
        String[] parts = spec.split("[\\s,;]+");
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (s.isEmpty()) continue;
            int colon = s.indexOf(':');
            if (colon <= 0 || colon >= s.length() - 1) continue;
            String key = s.substring(0, colon).trim().toLowerCase();
            String val = s.substring(colon + 1).trim();
            int tier;
            try {
                tier = Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                continue;
            }
            if (tier < 1) tier = 1;
            if (tier > 6) tier = 6;
            double deg = tierToDegrees(tier);
            if ("heat".equals(key)) {
                heat += deg;
            } else if ("cold".equals(key)) {
                cold += deg; // expands cold comfort by this many degrees (BodyTemperatureAPI subtracts this)
            }
        }
        return new Resistance(heat, cold);
    }

    /** Legacy convenience for callers expecting directional reads (now parses the unified key). */
    public static double stackHeatC(ItemStack stack) {
        return stackResistance(stack).heatC;
    }

    /** Legacy convenience for callers expecting directional reads (now parses the unified key). */
    public static double stackColdC(ItemStack stack) {
        return stackResistance(stack).coldC;
    }
}
