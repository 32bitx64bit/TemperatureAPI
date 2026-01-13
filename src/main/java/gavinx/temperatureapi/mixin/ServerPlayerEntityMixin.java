package gavinx.temperatureapi.mixin;

import gavinx.temperatureapi.BodyTemperatureState;
import gavinx.temperatureapi.api.BodyTemperatureAPI;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Persist and restore body temperature on the player via custom NBT.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {
    private static final String NBT_BODY_TEMP = "temperatureapi:body_temp_c";
    private static final String NBT_SOAKED_SEC = "temperatureapi:soaked_seconds";

    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void temperatureapi$writeBodyTemp(WriteView nbt, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        double value = BodyTemperatureState.getC(self);
        nbt.putDouble(NBT_BODY_TEMP, value);
        nbt.putDouble(NBT_SOAKED_SEC, gavinx.temperatureapi.SoakedState.getSeconds(self));
    }

    @Inject(method = "readCustomData", at = @At("TAIL"))
    private void temperatureapi$readBodyTemp(ReadView nbt, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        double value = nbt.getDouble(NBT_BODY_TEMP, BodyTemperatureAPI.NORMAL_BODY_TEMP_C);
        BodyTemperatureState.setC(self, value);

        gavinx.temperatureapi.SoakedState.setSeconds(self, nbt.getDouble(NBT_SOAKED_SEC, 0.0));
    }

    @Inject(method = "copyFrom", at = @At("TAIL"))
    private void temperatureapi$copyBodyTemp(ServerPlayerEntity oldPlayer, boolean alive, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (alive) {
            // Keep body temperature across dimension changes or similar
            double value = BodyTemperatureState.getC(oldPlayer);
            BodyTemperatureState.setC(self, value);
        } else {
            // Reset body temperature on death
            BodyTemperatureState.setC(self, BodyTemperatureAPI.NORMAL_BODY_TEMP_C);
        }
        // Carry over soaked state on respawn/cloning
        gavinx.temperatureapi.SoakedState.setSeconds(self, gavinx.temperatureapi.SoakedState.getSeconds(oldPlayer));
    }
}
