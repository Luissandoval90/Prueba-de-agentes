package com.palabraprohibida.net;

import com.palabraprohibida.Pp;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Envio S2C con comprobaciones: nunca se manda nada a un cliente sin el mod.
 */
public final class Net {
	private Net() {
	}

	public static void toPlayer(ServerPlayerEntity player, CustomPayload payload) {
		if (player == null || payload == null) {
			return;
		}
		try {
			if (ServerPlayNetworking.canSend(player, payload.getId())) {
				ServerPlayNetworking.send(player, payload);
			}
		} catch (Exception e) {
			Pp.LOG.debug("No se pudo enviar {}: {}", payload.getId().id(), e.getMessage());
		}
	}

	public static void toAll(MinecraftServer server, CustomPayload payload) {
		if (server == null) {
			return;
		}
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			toPlayer(player, payload);
		}
	}

	public static void toAllExcept(MinecraftServer server, ServerPlayerEntity excluded, CustomPayload payload) {
		if (server == null) {
			return;
		}
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			if (player != excluded) {
				toPlayer(player, payload);
			}
		}
	}
}
