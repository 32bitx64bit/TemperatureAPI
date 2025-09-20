package gavinx.temperatureapi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import gavinx.temperatureapi.api.HumidityAPI;
import gavinx.temperatureapi.api.TemperatureAPI;
import gavinx.temperatureapi.api.TemperatureAPI.Unit;
import gavinx.temperatureapi.api.SeasonsAPI;
import gavinx.temperatureapi.api.SoakedAPI;
import gavinx.temperatureapi.api.biome.BiomeAPI;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class DebugCommands {
    private DebugCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("temperatureapi")
                .requires(src -> src.hasPermissionLevel(0))
                .executes(ctx -> executeInfo(ctx, null, null))
                .then(unitArg().executes(ctx -> executeInfo(ctx, getUnitArg(ctx), null))
                    .then(argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> executeInfo(ctx, getUnitArg(ctx), BlockPosArgumentType.getLoadedBlockPos(ctx, "pos")))))
                .then(literal("temp")
                    .executes(ctx -> executeTemp(ctx, null, null))
                    .then(unitArg().executes(ctx -> executeTemp(ctx, getUnitArg(ctx), null))
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                            .executes(ctx -> executeTemp(ctx, getUnitArg(ctx), BlockPosArgumentType.getLoadedBlockPos(ctx, "pos"))))))
                .then(literal("resistance")
                    .executes(ctx -> executeResistance(ctx)))
                .then(literal("humidity")
                    .executes(ctx -> executeHumidity(ctx, null))
                    .then(argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> executeHumidity(ctx, BlockPosArgumentType.getLoadedBlockPos(ctx, "pos")))))
                .then(literal("season")
                    .executes(ctx -> executeSeason(ctx)))
                .then(literal("body")
                    .executes(ctx -> executeBodyTemp(ctx))
                    .then(literal("set").then(argument("value", StringArgumentType.word())
                        .executes(ctx -> executeBodySet(ctx, StringArgumentType.getString(ctx, "value")))))
                )
                .then(literal("soaked")
                    .executes(ctx -> executeSoaked(ctx)))
                .then(literal("biome")
                    .executes(ctx -> executeBiome(ctx)))
        );
    }

    private static ArgumentBuilder<ServerCommandSource, ?> unitArg() {
        return argument("unit", StringArgumentType.word());
    }

    private static Unit getUnitArg(CommandContext<ServerCommandSource> ctx) {
        if (!ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("unit"))) return null;
        String u = StringArgumentType.getString(ctx, "unit");
        String up = u == null ? "" : u.trim().toUpperCase();
        if (up.equals("F") || up.equals("FAHRENHEIT")) return Unit.FAHRENHEIT;
        if (up.equals("C") || up.equals("CELSIUS")) return Unit.CELSIUS;
        return null; // unknown -> show both
    }

    private static int executeInfo(CommandContext<ServerCommandSource> ctx, Unit unit, BlockPos posArg) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        World world = player.getWorld();
        BlockPos pos = posArg != null ? posArg : player.getBlockPos();

        String tempOut = (unit == null)
            ? TemperatureAPI.getTemperature(world, pos, Unit.CELSIUS) + " / " + TemperatureAPI.getTemperature(world, pos, Unit.FAHRENHEIT)
            : TemperatureAPI.getTemperature(world, pos, unit);
        String humidityOut = HumidityAPI.getHumidity(world, pos);
        String season = SeasonsAPI.getCurrentSeason(world);

        src.sendFeedback(() -> Text.literal("Temperature: " + tempOut + ", Humidity: " + humidityOut + ", Season: " + season), false);
        return 1;
    }

    private static int executeTemp(CommandContext<ServerCommandSource> ctx, Unit unit, BlockPos posArg) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        World world = player.getWorld();
        BlockPos pos = posArg != null ? posArg : player.getBlockPos();

        String out = (unit == null)
            ? TemperatureAPI.getTemperature(world, pos, Unit.CELSIUS) + " / " + TemperatureAPI.getTemperature(world, pos, Unit.FAHRENHEIT)
            : TemperatureAPI.getTemperature(world, pos, unit);
        double currentBody = gavinx.temperatureapi.BodyTemperatureState.getC(player);
        double rate = gavinx.temperatureapi.api.BodyTemperatureAPI.computeRateCPerSecond(player, currentBody);
        src.sendFeedback(() -> Text.literal("Temperature: " + out + ", Passive dT/dt (homeostasis): " + String.format("%.5f", rate) + " °C/s"), false);
        return 1;
    }

    private static int executeHumidity(CommandContext<ServerCommandSource> ctx, BlockPos posArg) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        World world = player.getWorld();
        BlockPos pos = posArg != null ? posArg : player.getBlockPos();

        String out = HumidityAPI.getHumidity(world, pos);
        src.sendFeedback(() -> Text.literal("Humidity: " + out), false);
        return 1;
    }

    private static int executeSeason(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        World world = player.getWorld();
        String season = SeasonsAPI.getCurrentSeason(world);
        double offset = gavinx.temperatureapi.api.SeasonsAPI.temperatureOffsetC(world, player.getBlockPos());
        src.sendFeedback(() -> Text.literal("Season: " + season + " (offset: " + String.format("%.1f", offset) + "°C)"), false);
        return 1;
    }

    private static int executeResistance(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        gavinx.temperatureapi.api.TemperatureResistanceAPI.Resistance r = gavinx.temperatureapi.api.TemperatureResistanceAPI.computeTotal(player);
        src.sendFeedback(() -> Text.literal("Resistance: +" + r.heatC + "°C heat, +" + r.coldC + "°C cold"), false);
        return 1;
    }

    private static int executeBodyTemp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        double val = gavinx.temperatureapi.BodyTemperatureState.getC(player);
        double rate = gavinx.temperatureapi.api.BodyTemperatureAPI.computeRateCPerSecond(player, val);
        src.sendFeedback(() -> Text.literal("Body temperature: " + String.format("%.2f", val) + "°C (dT/dt: " + String.format("%.5f", rate) + " °C/s)"), false);
        return 1;
    }

    private static int executeBodySet(CommandContext<ServerCommandSource> ctx, String value) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        try {
            double v = Double.parseDouble(value);
            gavinx.temperatureapi.BodyTemperatureState.setC(player, v);
            src.sendFeedback(() -> Text.literal("Set body temperature to " + String.format("%.2f", v) + "°C"), false);
            return 1;
        } catch (NumberFormatException e) {
            src.sendError(Text.literal("Invalid number: " + value));
            return 0;
        }
    }

    private static int executeSoaked(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        boolean soaked = SoakedAPI.isSoaked(player);
        double secs = soaked ? SoakedAPI.getSoakedSeconds(player) : 0.0;
        String msg = soaked ? ("Soaked (" + String.format("%.1f", secs) + "s remaining)") : "Dry";
        src.sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    private static int executeBiome(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        ServerPlayerEntity player = getPlayerOrFeedback(src);
        if (player == null) return 0;
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        var biomeEntry = world.getBiome(pos);
        String regId = biomeEntry.getKey().map(k -> k.getValue().toString()).orElse("unknown");
        String key = BiomeAPI.keyFor(biomeEntry);
        var resolved = BiomeAPI.get(key);
        String configured = resolved
            .map(cb -> "Configured: temp=" + String.format("%.1f", cb.temperature) + "°C, humidity=" + cb.humidity + "%")
            .orElse("Configured: none");
        // Extra diagnostics: show collapseKey resolution and whether it's present in registry/defaults
        String collapsed = (key == null ? null : "biome." + regId.split(":")[0] + "." + regId.split(":")[1].replace('/', '.').replace(".", "."));
        int regCount = BiomeAPI.snapshotConfigured().size();
        int defCount = BiomeAPI.snapshotDefaults().size();
        String msg = "Biome id: " + regId +
                     ", key: " + (key == null ? "unknown" : key) +
                     ", " + configured +
                     ", configuredEntries=" + regCount + ", defaultEntries=" + defCount;
        src.sendFeedback(() -> Text.literal(msg), false);
        return 1;
    }

    private static ServerPlayerEntity getPlayerOrFeedback(ServerCommandSource src) {
        try {
            return src.getPlayer();
        } catch (Exception e) {
            src.sendError(Text.literal("This command requires a player context."));
            return null;
        }
    }
}
