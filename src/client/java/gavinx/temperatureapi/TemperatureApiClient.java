package gavinx.temperatureapi;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import gavinx.temperatureapi.client.DiurnalClientState;
import gavinx.temperatureapi.client.TooltipHandler;
import gavinx.temperatureapi.net.DiurnalSyncPayload;

public class TemperatureApiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(DiurnalSyncPayload.ID, (payload, context) -> {
            String dim = payload.dimensionId().toString();
            long dayIndex = payload.dayIndex();
            double M = payload.M();
            double m = payload.m();
            context.client().execute(() -> DiurnalClientState.put(dim, dayIndex, M, m));
        });

        // Register standardized tooltip for temperature resistance
        TooltipHandler.register();
    }
}