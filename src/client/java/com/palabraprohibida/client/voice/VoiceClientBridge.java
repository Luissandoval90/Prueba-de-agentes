package com.palabraprohibida.client.voice;

import com.palabraprohibida.client.ClientNet;
import com.palabraprohibida.client.ClientState;
import com.palabraprohibida.client.config.ClientConfig;
import com.palabraprohibida.voice.SpeechEngine;
import com.palabraprohibida.word.WordHash;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientVoicechatConnectionEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophoneMuteEvent;
import de.maxhenkel.voicechat.api.events.VoicechatDisableEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puente entre Simple Voice Chat y el reconocimiento.
 *
 * Los eventos de audio llegan en el hilo de voz: aqui solo se encola audio.
 * Las decisiones (subtitulos, deteccion, envio) se hacen en el hilo del cliente.
 */
public final class VoiceClientBridge {
	private static final Map<UUID, Set<String>> PREVIOUS_PARTIALS = new HashMap<>();

	private VoiceClientBridge() {
	}

	public static void register(EventRegistration registration) {
		registration.registerEvent(ClientSoundEvent.class, VoiceClientBridge::onOwnAudio);
		registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, VoiceClientBridge::onEntityAudio);
		registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, VoiceClientBridge::onLocationalAudio);
		registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, VoiceClientBridge::onStaticAudio);

		registration.registerEvent(ClientVoicechatConnectionEvent.class,
				event -> ClientState.voiceConnected = event.isConnected());
		registration.registerEvent(MicrophoneMuteEvent.class,
				event -> ClientState.micMuted = event.isDisabled());
		registration.registerEvent(VoicechatDisableEvent.class,
				event -> ClientState.voiceConnected = !event.isDisabled());

		SpeechEngine.get().start(ClientConfig.get().model, new Listener());
	}

	// ---------- Audio entrante ----------

	private static void onOwnAudio(ClientSoundEvent event) {
		short[] raw = event.getRawAudio();
		if (ClientState.silenced()) {
			// Castigo de silencio: se envia silencio para que nadie lo oiga.
			event.setRawAudio(new short[raw == null ? 0 : raw.length]);
			return;
		}
		UUID self = localUuid();
		if (self == null) {
			return;
		}
		ClientState.micLevel = level(raw);
		SpeechEngine.get().offer(self, true, raw == null ? new short[0] : raw);
	}

	private static void onEntityAudio(ClientReceiveSoundEvent.EntitySound event) {
		SpeechEngine.get().offer(event.getEntityId(), false, event.getRawAudio());
	}

	private static void onLocationalAudio(ClientReceiveSoundEvent.LocationalSound event) {
		SpeechEngine.get().offer(event.getId(), false, event.getRawAudio());
	}

	private static void onStaticAudio(ClientReceiveSoundEvent.StaticSound event) {
		SpeechEngine.get().offer(event.getId(), false, event.getRawAudio());
	}

	private static UUID localUuid() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		return player == null ? null : player.getUuid();
	}

	private static float level(short[] raw) {
		if (raw == null || raw.length == 0) {
			return 0.0f;
		}
		double sum = 0.0;
		for (short sample : raw) {
			sum += (double) sample * sample;
		}
		return (float) Math.min(1.0, Math.sqrt(sum / raw.length) / 4000.0);
	}

	// ---------- Resultados ----------

	private static final class Listener implements SpeechEngine.Listener {
		@Override
		public void onPartial(UUID speaker, boolean own, String text) {
			MinecraftClient client = MinecraftClient.getInstance();
			client.execute(() -> {
				if (own && !ClientConfig.get().showOwnSubtitles) {
					return;
				}
				String name = nameOf(speaker, own);
				ClientState.pushSubtitle(own ? "" : name, text, true, own,
						ClientConfig.get().subtitleDurationMs);
				if (ClientConfig.get().quickDetection) {
					quickDetect(speaker, text);
				}
			});
		}

		@Override
		public void onFinal(UUID speaker, boolean own, String text, List<String> words, List<Float> confidences) {
			MinecraftClient client = MinecraftClient.getInstance();
			client.execute(() -> {
				if (text != null && !text.isBlank()) {
					String name = nameOf(speaker, own);
					ClientState.pushSubtitle(own ? "" : name, text, false, own,
							ClientConfig.get().subtitleDurationMs);
				}
				if (own && text != null && !text.isBlank()) {
					ClientNet.talk(text);
				}
				detect(speaker, words, confidences);
				if (own) {
					ClientState.micLevel = 0.0f;
				}
			});
		}

		@Override
		public void onStatus(String key, String detail) {
			MinecraftClient client = MinecraftClient.getInstance();
			client.execute(() -> {
				ClientState.engineStatus = key;
				ClientState.pushNotice(net.minecraft.text.Text.translatable(key).getString());
			});
		}
	}

	/** Deteccion sobre resultados finales, con confianza minima configurable. */
	private static void detect(UUID speaker, List<String> words, List<Float> confidences) {
		if (words == null || words.isEmpty() || ClientState.hashes.isEmpty()) {
			return;
		}
		double minimum = ClientConfig.get().minConfidence;
		for (int i = 0; i < words.size(); i++) {
			float confidence = i < confidences.size() ? confidences.get(i) : 1.0f;
			if (confidence < minimum) {
				continue;
			}
			String normalized = WordHash.normalize(words.get(i));
			if (normalized.isEmpty() || !matches(normalized)) {
				continue;
			}
			ClientNet.spokenWord(speaker, normalized);
		}
	}

	/**
	 * Deteccion rapida (opcional): un parcial cuenta solo si la misma palabra
	 * aparecio en dos parciales seguidos.
	 */
	private static void quickDetect(UUID speaker, String partial) {
		if (ClientState.hashes.isEmpty()) {
			return;
		}
		Set<String> current = new HashSet<>();
		for (String word : WordHash.words(partial)) {
			if (matches(word)) {
				current.add(word);
			}
		}
		Set<String> previous = PREVIOUS_PARTIALS.getOrDefault(speaker, Set.of());
		for (String word : current) {
			if (previous.contains(word)) {
				ClientNet.spokenWord(speaker, word);
			}
		}
		PREVIOUS_PARTIALS.put(speaker, current);
	}

	private static boolean matches(String normalized) {
		long hash = WordHash.hash(ClientState.salt, normalized);
		return ClientState.hashes.contains(hash);
	}

	private static String nameOf(UUID speaker, boolean own) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (own) {
			return "";
		}
		if (client.world != null) {
			PlayerEntity player = client.world.getPlayerByUuid(speaker);
			if (player != null) {
				return player.getName().getString();
			}
		}
		return net.minecraft.text.Text.translatable("palabraprohibida.voice.unknown").getString();
	}
}
