package gavinx.temperatureapi.api;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * TemperatureResistanceAPI
 *
 * Provides utilities for computing a player's effective heat/cold resistance and
 * a simple registration point for other mods to contribute custom sources.
 *
 * Resistance model:
 * - Heat resistance tiers: 1..5
 * - Cold resistance tiers: -1..-5
 * - Tier magnitudes (both directions):
 *   1 -> 2°C, 2 -> 4°C, 3 -> 8°C, 4 -> 12°C, 5 -> 16°C
 *
 * The total heat resistance increases the comfort MAX; the total cold resistance increases
 * comfort in the cold direction (lowers the comfort MIN by that number of degrees).
 *
 * Mods can:
 * - Add NBT keys on ItemStacks: "tempapi_heat_tier" (1..5) and/or "tempapi_cold_tier" (-1..-5)
 *   on armor, held items, or other carried items.
 * - Register a provider via registerProvider to supply dynamic resistances (e.g., trinkets/baubles).
 */
public final class TemperatureResistanceAPI {

    private TemperatureResistanceAPI() {}

    // NBT keys that items can use to declare tiers
    public static final String NBT_HEAT_TIER = "tempapi_heat_tier"; // 1..5
    public static final String NBT_COLD_TIER = "tempapi_cold_tier"; // -1..-5

    // Optional global caps (per direction). Mods can ignore these by calling tierToDegrees directly.
    public static final double DEFAULT_MAX_HEAT_RESIST_C = 16.0; // up to tier 5
    public static final double DEFAULT_MAX_COLD_RESIST_C = 16.0; // up to tier -5

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
        heat += stackHeatC(player.getEquippedStack(EquipmentSlot.HEAD));
        heat += stackHeatC(player.getEquippedStack(EquipmentSlot.CHEST));
        heat += stackHeatC(player.getEquippedStack(EquipmentSlot.LEGS));
        heat += stackHeatC(player.getEquippedStack(EquipmentSlot.FEET));
        heat += stackHeatC(player.getEquippedStack(EquipmentSlot.MAINHAND));
        heat += stackHeatC(player.getEquippedStack(EquipmentSlot.OFFHAND));

        cold += stackColdC(player.getEquippedStack(EquipmentSlot.HEAD));
        cold += stackColdC(player.getEquippedStack(EquipmentSlot.CHEST));
        cold += stackColdC(player.getEquippedStack(EquipmentSlot.LEGS));
        cold += stackColdC(player.getEquippedStack(EquipmentSlot.FEET));
        cold += stackColdC(player.getEquippedStack(EquipmentSlot.MAINHAND));
        cold += stackColdC(player.getEquippedStack(EquipmentSlot.OFFHAND));

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

    /** Convert a tier value to degrees C (absolute magnitude). Accepts 1..5 or -1..-5. */
    public static double tierToDegrees(int tier) {
        int abs = Math.abs(tier);
        return switch (abs) {
            case 1 -> 2.0;
            case 2 -> 4.0;
            case 3 -> 8.0;
            case 4 -> 12.0;
            case 5 -> 16.0;
            default -> 0.0;
        };
    }

    /** Read heat resistance from an ItemStack via NBT tier. */
    public static double stackHeatC(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
        NbtCompound tag = stack.getNbt();
        if (tag == null) return 0.0;
        int tier = 0;
        try {
            if (tag.contains(NBT_HEAT_TIER)) tier = tag.getInt(NBT_HEAT_TIER);
        } catch (Throwable ignored) {}
        if (tier < 0) tier = 0; // heat uses positive tiers only
        if (tier > 5) tier = 5;
        return tierToDegrees(tier);
    }

    /** Read cold resistance from an ItemStack via NBT tier. */
    public static double stackColdC(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0.0;
        NbtCompound tag = stack.getNbt();
        if (tag == null) return 0.0;
        int tier = 0;
        try {
            if (tag.contains(NBT_COLD_TIER)) tier = tag.getInt(NBT_COLD_TIER);
        } catch (Throwable ignored) {}
        if (tier > 0) tier = -tier; // cold expects negative; ensure sign
        if (tier < -5) tier = -5;
        return tierToDegrees(tier);
    }
}
