package gavinx.temperatureapi;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.Identifier;
import gavinx.temperatureapi.client.DiurnalClientState;

public class TemperatureApiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(new Identifier("temperatureapi", "diurnal_sync"), (client, handler, buf, responseSender) -> {
            String dim = buf.readString();
            long dayIndex = buf.readLong();
            double M = buf.readDouble();
            double m = buf.readDouble();
            client.execute(() -> DiurnalClientState.put(dim, dayIndex, M, m));
        });
    }
}