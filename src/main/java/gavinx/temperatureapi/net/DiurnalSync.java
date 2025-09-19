package gavinx.temperatureapi.net;

import gavinx.temperatureapi.TemperatureApi;
import gavinx.temperatureapi.api.DayNightAPI;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/** Handles server->client sync of diurnal parameters (M/m) per world per day. */
public final class DiurnalSync {
    public static final Identifier CHANNEL = new Identifier("temperatureapi", "diurnal_sync");

    private static final Map<Identifier, Long> lastSentDayByDim = new HashMap<>();

    private DiurnalSync() {}

    public static void registerServer() {
        // Send on player join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            ServerWorld world = (ServerWorld) player.getWorld();
            sendForWorldToPlayer(world, player);
        });

        // Send on day change per world
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (!(world instanceof ServerWorld)) return;
            ServerWorld sw = (ServerWorld) world;
            long time = sw.getTimeOfDay();
            long dayIndex = Math.floorDiv(time, 24000L);
            Identifier dim = sw.getRegistryKey().getValue();
            Long last = lastSentDayByDim.get(dim);
            if (last == null || last != dayIndex) {
                lastSentDayByDim.put(dim, dayIndex);
                // Broadcast to all players in this world
                for (ServerPlayerEntity p : sw.getPlayers()) {
                    sendForWorldToPlayer(sw, p);
                }
                TemperatureApi.LOGGER.debug("Synced diurnal params for {} day {} to {} players", dim, dayIndex, sw.getPlayers().size());
            }
        });
    }

    private static void sendForWorldToPlayer(ServerWorld world, ServerPlayerEntity player) {
        long time = world.getTimeOfDay();
        long dayIndex = Math.floorDiv(time, 24000L);
        // Compute deterministic daily parameters on server (uses world seed)
        DayNightParams params = computeParams(world, dayIndex);

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeString(world.getRegistryKey().getValue().toString());
        buf.writeLong(dayIndex);
        buf.writeDouble(params.M);
        buf.writeDouble(params.m);
        ServerPlayNetworking.send(player, CHANNEL, buf);
    }

    private static DayNightParams computeParams(ServerWorld world, long dayIndex) {
        // Use DayNightAPI's internal deterministic logic by sampling once (any time) and inverting to M/m
        // Easier: re-create with same seed path used in DayNightAPI
        long seed = seedFor(world, dayIndex);
        java.util.SplittableRandom rng = new java.util.SplittableRandom(seed);
        double M = rng.nextDouble(0.0, 6.0);
        double m = rng.nextDouble(-7.0, -2.0);
        return new DayNightParams(M, m);
    }

    private static long seedFor(ServerWorld world, long dayIndex) {
        long seed = 0x9E3779B97F4A7C15L;
        try {
            seed ^= mix64(world.getSeed());
        } catch (Throwable ignored) {}
        String dim = world.getRegistryKey().getValue().toString();
        seed ^= mix64(dim.hashCode());
        seed ^= mix64(dayIndex);
        return seed;
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private record DayNightParams(double M, double m) {}
}
