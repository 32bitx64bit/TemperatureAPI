package gavinx.temperatureapi.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import gavinx.temperatureapi.api.HumidityAPI;
import gavinx.temperatureapi.api.TemperatureAPI;
import gavinx.temperatureapi.api.TemperatureAPI.Unit;
import gavinx.temperatureapi.api.SeasonsAPI;
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
                .then(literal("humidity")
                    .executes(ctx -> executeHumidity(ctx, null))
                    .then(argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> executeHumidity(ctx, BlockPosArgumentType.getLoadedBlockPos(ctx, "pos")))))
                .then(literal("season")
                    .executes(ctx -> executeSeason(ctx)))
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
        src.sendFeedback(() -> Text.literal("Temperature: " + out), false);
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

    private static ServerPlayerEntity getPlayerOrFeedback(ServerCommandSource src) {
        try {
            return src.getPlayer();
        } catch (Exception e) {
            src.sendError(Text.literal("This command requires a player context."));
            return null;
        }
    }
}
