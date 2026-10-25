package com.palabraprohibida.client.mixin;

import com.palabraprohibida.client.ClientState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Castigo "mirada invertida": invierte el movimiento del raton mientras dura.
 * Se hace en el cliente porque la direccion de la camara es cosa suya.
 */
@Mixin(Entity.class)
public class EntityMixin {
	@Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
	private void palabraprohibida$invertedLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo info) {
		if (!((Object) this instanceof ClientPlayerEntity)) {
			return;
		}
		if (!ClientState.invertedLook()) {
			return;
		}
		Entity self = (Entity) (Object) this;
		float pitch = (float) cursorDeltaY * 0.15F;
		float yaw = (float) cursorDeltaX * 0.15F;
		self.setPitch(MathHelper.clamp(self.getPitch() - pitch, -90.0F, 90.0F));
		self.setYaw(self.getYaw() - yaw);
		info.cancel();
	}
}
