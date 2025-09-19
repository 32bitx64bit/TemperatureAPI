package gavinx.temperatureapi.mixin;

import gavinx.temperatureapi.BodyTemperatureState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
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

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void temperatureapi$writeBodyTemp(NbtCompound nbt, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        double value = BodyTemperatureState.getC(self);
        nbt.putDouble(NBT_BODY_TEMP, value);
    }

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void temperatureapi$readBodyTemp(NbtCompound nbt, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (nbt.contains(NBT_BODY_TEMP)) {
            double value = nbt.getDouble(NBT_BODY_TEMP);
            BodyTemperatureState.setC(self, value);
        }
    }

    @Inject(method = "copyFrom", at = @At("TAIL"))
    private void temperatureapi$copyBodyTemp(ServerPlayerEntity oldPlayer, boolean alive, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        double value = BodyTemperatureState.getC(oldPlayer);
        BodyTemperatureState.setC(self, value);
    }
}
