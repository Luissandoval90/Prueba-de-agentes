package com.palabraprohibida.client;

import com.palabraprohibida.Pp;
import com.palabraprohibida.client.config.ClientConfig;
import com.palabraprohibida.client.hud.HudOverlay;
import com.palabraprohibida.client.ui.MenuScreen;
import com.palabraprohibida.net.Payloads;
import com.palabraprohibida.net.Sync;
import com.palabraprohibida.util.Json;
import com.palabraprohibida.voice.ModelManager;
import com.palabraprohibida.voice.SpeechEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Entrada del cliente: tecla K, boton en el menu de pausa, HUD y recepcion de
 * paquetes del servidor.
 */
public class PalabraProhibidaClient implements ClientModInitializer {
	private static KeyBinding menuKey;

	public static KeyBinding menuKey() {
		return menuKey;
	}

	@Override
	public void onInitializeClient() {
		KeyBinding.Category category = KeyBinding.Category.create(Pp.id("main"));
		menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.palabraprohibida.menu", GLFW.GLFW_KEY_K, category));

		registerReceivers();
		registerTick();
		registerConnection();
		registerHud();
		registerPauseButton();
		prepareModel();
	}

	private void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(Payloads.ConfigSync.ID, (payload, context) ->
				context.client().execute(() -> {
					try {
						Sync.Config sync = Json.COMPACT.fromJson(payload.json(), Sync.Config.class);
						ClientState.applyConfig(sync);
						if (sync != null && sync.config != null) {
							String model = ModelManager.normalizeChoice(ClientConfig.get().model);
							if (!SpeechEngine.get().isRunning()) {
								SpeechEngine.get().start(model, null);
							}
						}
					} catch (Exception e) {
						Pp.LOG.warn("ConfigSync invalido: {}", e.getMessage());
					}
				}));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.State.ID, (payload, context) ->
				context.client().execute(() -> {
					try {
						ClientState.applyState(Json.COMPACT.fromJson(payload.json(), Sync.Panel.class));
					} catch (Exception e) {
						Pp.LOG.warn("State invalido: {}", e.getMessage());
					}
				}));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Punish.ID, (payload, context) ->
				context.client().execute(() -> applyClientPunishment(payload.type(), payload.message())));

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Notice.ID, (payload, context) ->
				context.client().execute(() -> {
					try {
						Sync.Notice notice = Json.COMPACT.fromJson(payload.json(), Sync.Notice.class);
						if (notice == null || notice.key == null) {
							return;
						}
						Object[] args = new Object[notice.args == null ? 0 : notice.args.size()];
						for (int i = 0; i < args.length; i++) {
							String arg = notice.args.get(i);
							args[i] = arg != null && arg.startsWith("palabraprohibida.") ? Text.translatable(arg) : arg;
						}
						ClientState.pushNotice(Text.translatable(notice.key, args).getString());
					} catch (Exception e) {
						Pp.LOG.warn("Notice invalido: {}", e.getMessage());
					}
				}));
	}

	/** Castigos que aplica el cliente. El servidor manda los segundos en el mensaje. */
	private void applyClientPunishment(String type, String data) {
		int seconds = 8;
		try {
			var object = Json.COMPACT.fromJson(data, com.google.gson.JsonObject.class);
			if (object != null && object.has("seconds")) {
				seconds = object.get("seconds").getAsInt();
			}
		} catch (Exception ignored) {
		}
		long until = System.currentTimeMillis() + Math.max(1, seconds) * 1000L;
		switch (type) {
			case "silence" -> ClientState.silenceUntil = until;
			case "inverted_look" -> ClientState.invertedLookUntil = until;
			case "clumsy_hand" -> ClientState.clumsyUntil = until;
			default -> {
			}
		}
	}

	private void registerTick() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ClientState.tick();
			ClientState.micLevel *= 0.88f;
			while (menuKey != null && menuKey.wasPressed()) {
				if (client.currentScreen == null && client.player != null) {
					client.setScreen(new MenuScreen());
				}
			}
			if (ClientState.clumsy() && client.player != null && client.currentScreen == null) {
				// Mano torpe: la hotbar se mueve sola de vez en cuando.
				if (client.player.age % 18 == 0) {
					int slot = client.player.getInventory().getSelectedSlot();
					client.player.getInventory().setSelectedSlot((slot + 1) % 9);
				}
			}
		});
	}

	private void registerConnection() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ClientState.reset();
			SpeechEngine.get().resetSessions();
			String model = ModelManager.normalizeChoice(ClientConfig.get().model);
			if (ModelManager.isReady(model)) {
				SpeechEngine.get().start(model, null);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			// Limpieza total: efectos, subtitulos y reconocedores.
			ClientState.reset();
			SpeechEngine.get().resetSessions();
		});
	}

	private void registerHud() {
		HudElementRegistry.addLast(Pp.id("overlay"), new HudOverlay());
	}

	private void registerPauseButton() {
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof GameMenuScreen)) {
				return;
			}
			String label = Text.translatable("palabraprohibida.menu.title").getString();
			int bottom = 0;
			for (ClickableWidget widget : Screens.getButtons(screen)) {
				if (widget.getMessage().getString().equals(label)) {
					return; // ya existe un boton con ese texto
				}
				bottom = Math.max(bottom, widget.getBottom());
			}
			int y = Math.min(scaledHeight - 32, (bottom == 0 ? scaledHeight / 4 : bottom) + 4);
			var button = net.minecraft.client.gui.widget.ButtonWidget
					.builder(Text.translatable("palabraprohibida.menu.title"),
							b -> client.setScreen(new MenuScreen()))
					.dimensions(scaledWidth / 2 - 100, y, 200, 20)
					.build();
			// addDrawableChild es protected; la lista de Fabric admite altas.
			Screens.getButtons(screen).add(button);
		});
	}

	/** Descarga el modelo rapido al primer inicio, fuera del hilo del juego. */
	private void prepareModel() {
		Thread thread = new Thread(() -> {
			String model = ModelManager.normalizeChoice(ClientConfig.get().model);
			if (ModelManager.isReady(model)) {
				return;
			}
			ModelManager.ensure(model,
					progress -> ClientState.downloadProgress = progress,
					ok -> {
						ClientState.downloadProgress = -1.0;
						if (Boolean.TRUE.equals(ok)) {
							ClientState.pushNotice(Text.translatable("palabraprohibida.voice.model_ready").getString());
							SpeechEngine.get().start(model, null);
						} else {
							ClientState.pushNotice(Text.translatable("palabraprohibida.voice.model_failed").getString());
						}
					});
		}, "palabraprohibida-model");
		thread.setDaemon(true);
		thread.start();
	}
}
