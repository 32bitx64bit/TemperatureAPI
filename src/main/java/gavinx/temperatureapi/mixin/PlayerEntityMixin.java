package gavinx.temperatureapi.mixin;

import gavinx.temperatureapi.BodyTemperatureState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
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

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void temperatureapi$writeBodyTemp(NbtCompound nbt, CallbackInfo ci) {
        // Only meaningful on the server
        if ((Object) this instanceof ServerPlayerEntity self) {
            double value = BodyTemperatureState.getC(self);
            nbt.putDouble(NBT_BODY_TEMP, value);
            // Save soaked state
            nbt.putDouble(NBT_SOAKED_SEC, gavinx.temperatureapi.SoakedState.getSeconds(self));
        }
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void temperatureapi$readBodyTemp(NbtCompound nbt, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayerEntity self) {
            if (nbt.contains(NBT_BODY_TEMP)) {
                double value = nbt.getDouble(NBT_BODY_TEMP);
                BodyTemperatureState.setC(self, value);
            }
            if (nbt.contains(NBT_SOAKED_SEC)) {
                gavinx.temperatureapi.SoakedState.setSeconds(self, nbt.getDouble(NBT_SOAKED_SEC));
            }
        }
    }
}
