package com.palabraprohibida.client;

import com.palabraprohibida.Pp;
import com.palabraprohibida.net.Payloads;
import com.palabraprohibida.util.Json;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/**
 * Salida de paquetes del cliente. Siempre se comprueba canSend: en un servidor
 * sin el mod no se envia nada.
 */
public final class ClientNet {
	private ClientNet() {
	}

	public static boolean canSend() {
		try {
			return ClientPlayNetworking.canSend(Payloads.Action.ID);
		} catch (Exception e) {
			return false;
		}
	}

	public static void action(String action, String data) {
		if (!canSend()) {
			return;
		}
		try {
			ClientPlayNetworking.send(new Payloads.Action(action, data == null ? "" : data));
		} catch (Exception e) {
			Pp.LOG.debug("Accion no enviada: {}", e.getMessage());
		}
	}

	public static void sendConfig() {
		if (!canSend()) {
			return;
		}
		String json = Json.COMPACT.toJson(ClientState.config);
		if (json.length() > Payloads.MAX_JSON) {
			Pp.LOG.warn("Configuracion demasiado grande para el paquete");
			return;
		}
		try {
			ClientPlayNetworking.send(new Payloads.ConfigUpdate(json));
		} catch (Exception e) {
			Pp.LOG.debug("Configuracion no enviada: {}", e.getMessage());
		}
	}

	public static void talk(String text) {
		if (text == null || text.isBlank() || !canSend()) {
			return;
		}
		try {
			ClientPlayNetworking.send(new Payloads.Talk(text.length() > 500 ? text.substring(0, 500) : text));
		} catch (Exception e) {
			Pp.LOG.debug("Talk no enviado: {}", e.getMessage());
		}
	}

	public static void spokenWord(UUID speaker, String word) {
		if (speaker == null || word == null || word.isBlank() || !canSend()) {
			return;
		}
		try {
			ClientPlayNetworking.send(new Payloads.SpokenWord(speaker, word));
		} catch (Exception e) {
			Pp.LOG.debug("SpokenWord no enviado: {}", e.getMessage());
		}
	}

	public static boolean inGame() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null && client.world != null;
	}
}
