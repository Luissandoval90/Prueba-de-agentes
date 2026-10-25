package com.palabraprohibida.server;

import com.palabraprohibida.Pp;
import com.palabraprohibida.net.Payloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Registro de eventos del servidor y de los paquetes C2S.
 */
public final class ServerCallbacks {
	private ServerCallbacks() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerRuntime::create);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ServerRuntime.clear());

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerRuntime runtime = ServerRuntime.get();
			if (runtime != null) {
				runtime.tick();
			}
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerRuntime runtime = ServerRuntime.get();
			if (runtime != null) {
				runtime.onJoin(handler.getPlayer());
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerRuntime runtime = ServerRuntime.get();
			if (runtime != null) {
				runtime.onDisconnect(handler.getPlayer());
			}
		});

		// ---- C2S ----
		ServerPlayNetworking.registerGlobalReceiver(Payloads.SpokenWord.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ServerRuntime runtime = ServerRuntime.get();
				if (runtime != null) {
					runtime.onSpokenWord(player, payload.speaker(), payload.word());
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Talk.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ServerRuntime runtime = ServerRuntime.get();
				if (runtime != null) {
					runtime.onTalk(player, payload.text());
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.ConfigUpdate.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				ServerRuntime runtime = ServerRuntime.get();
				if (runtime != null) {
					runtime.onConfigUpdate(player, payload.json());
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(Payloads.Action.ID, (payload, context) -> {
			ServerPlayerEntity player = context.player();
			context.server().execute(() -> {
				try {
					Actions.handle(player, payload.action(), payload.data());
				} catch (Exception e) {
					Pp.LOG.warn("Accion {} fallo: {}", payload.action(), e.getMessage());
				}
			});
		});
	}
}
