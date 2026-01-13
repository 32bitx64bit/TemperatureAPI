package gavinx.temperatureapi.mixin;

import gavinx.temperatureapi.BodyTemperatureState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inject into PlayerEntity to persist/restore body temperature via NBT.
 * We target PlayerEntity for write/read because those methods are defined on the superclass,
 * and inject into ServerPlayerEntity separately for copyFrom (respawns/clones).
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
    private static final String NBT_BODY_TEMP = "temperatureapi:body_temp_c";
    private static final String NBT_SOAKED_SEC = "temperatureapi:soaked_seconds";

    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void temperatureapi$writeBodyTemp(WriteView nbt, CallbackInfo ci) {
        // Only meaningful on the server
        if ((Object) this instanceof ServerPlayerEntity self) {
            double value = BodyTemperatureState.getC(self);
            nbt.putDouble(NBT_BODY_TEMP, value);
            // Save soaked state
            nbt.putDouble(NBT_SOAKED_SEC, gavinx.temperatureapi.SoakedState.getSeconds(self));
        }
    }

    @Inject(method = "readCustomData", at = @At("TAIL"))
    private void temperatureapi$readBodyTemp(ReadView nbt, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayerEntity self) {
            double value = nbt.getDouble(NBT_BODY_TEMP, gavinx.temperatureapi.api.BodyTemperatureAPI.NORMAL_BODY_TEMP_C);
            BodyTemperatureState.setC(self, value);

            gavinx.temperatureapi.SoakedState.setSeconds(self, nbt.getDouble(NBT_SOAKED_SEC, 0.0));
        }
    }
}
