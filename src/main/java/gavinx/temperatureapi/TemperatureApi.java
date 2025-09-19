package gavinx.temperatureapi;

import gavinx.temperatureapi.api.biome.BiomeAPI;
import gavinx.temperatureapi.command.DebugCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import gavinx.temperatureapi.net.DiurnalSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TemperatureApi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("temperatureapi");

    @Override
    public void onInitialize() {
        // Load custom biome config on mod init
        BiomeAPI.load();
        LOGGER.info("Temperature API initialized.");
        LOGGER.info("Loaded biome config from {}", BiomeAPI.getConfigPath());

        // Register debug commands (/temperatureapi, /temperatureapi temp, /temperatureapi humidity)
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            DebugCommands.register(dispatcher);
        });

        // Networking: server->client diurnal sync
        DiurnalSync.registerServer();
    }
}
