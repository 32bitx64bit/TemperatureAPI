package gavinx.temperatureapi.client;

import gavinx.temperatureapi.api.TemperatureResistanceAPI;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.List;

/**
 * Registers a tooltip callback that adds standardized temperature resistance lines
 * to any ItemStack that declares tempapi_resistance, unless disabled via API flag.
 */
public final class TooltipHandler {
    private TooltipHandler() {}

    // Heat gradient: tier 1 -> #cd3030, tier 6 -> #b00000
    private static final int HEAT_COLOR_T1 = 0xCD3030;
    private static final int HEAT_COLOR_T6 = 0xB00000;
    // Cold gradient: tier 1 -> #4dbaf1, tier 6 -> #0091dc
    private static final int COLD_COLOR_T1 = 0x4DBAF1;
    private static final int COLD_COLOR_T6 = 0x0091DC;

    public static void register() {
        ItemTooltipCallback.EVENT.register(TooltipHandler::onTooltip);
    }

    private static void onTooltip(ItemStack stack, TooltipContext context, List<Text> lines) {
        if (stack == null || stack.isEmpty()) return;
        if (!TemperatureResistanceAPI.AUTO_TOOLTIPS_ENABLED) return;
        // Parse resistance from the item
        TemperatureResistanceAPI.Resistance r = TemperatureResistanceAPI.stackResistance(stack);
        if (r == null) return;
        boolean hasHeat = r.heatC > 0.0;
        boolean hasCold = r.coldC > 0.0;
        if (!hasHeat && !hasCold) return;

        if (hasHeat) {
            int tier = clampTier((int)Math.round(r.heatC / 2.0));
            int color = lerpColor(HEAT_COLOR_T1, HEAT_COLOR_T6, tierProgress(tier));
            MutableText line = Text.literal(String.format("Heat Resistance: +%.0f°C", r.heatC))
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
            lines.add(line);
        }
        if (hasCold) {
            int tier = clampTier((int)Math.round(r.coldC / 2.0));
            int color = lerpColor(COLD_COLOR_T1, COLD_COLOR_T6, tierProgress(tier));
            MutableText line = Text.literal(String.format("Cold Resistance: +%.0f°C", r.coldC))
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color)));
            lines.add(line);
        }
    }

    private static int clampTier(int t) {
        if (t < 1) return 1;
        if (t > 6) return 6;
        return t;
    }

    private static float tierProgress(int tier) {
        // Map tier 1..6 to 0..1
        return (tier - 1) / 5.0f;
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int rr = (int)(ar + (br - ar) * t);
        int rg = (int)(ag + (bg - ag) * t);
        int rb = (int)(ab + (bb - ab) * t);
        return (rr << 16) | (rg << 8) | rb;
    }
}
