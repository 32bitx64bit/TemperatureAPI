package gavinx.temperatureapi.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Server->client payload for syncing deterministic daily diurnal parameters.
 */
public record DiurnalSyncPayload(Identifier dimensionId, long dayIndex, double M, double m) implements CustomPayload {
	public static final CustomPayload.Id<DiurnalSyncPayload> ID = new CustomPayload.Id<>(Identifier.of("temperatureapi", "diurnal_sync"));

	public static final PacketCodec<PacketByteBuf, DiurnalSyncPayload> CODEC = CustomPayload.codecOf(
			(payload, buf) -> {
				buf.writeIdentifier(payload.dimensionId);
				buf.writeLong(payload.dayIndex);
				buf.writeDouble(payload.M);
				buf.writeDouble(payload.m);
			},
			buf -> new DiurnalSyncPayload(
					buf.readIdentifier(),
					buf.readLong(),
					buf.readDouble(),
					buf.readDouble()
			)
	);

	public static void register() {
		PayloadTypeRegistry.playS2C().register(ID, CODEC);
	}

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}