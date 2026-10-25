package com.palabraprohibida.client;

import com.palabraprohibida.config.ServerConfig;
import com.palabraprohibida.net.Sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estado que el cliente conoce del servidor, mas las cosas puramente visuales.
 * Solo se toca en el hilo del cliente.
 */
public final class ClientState {
	public static ServerConfig config = new ServerConfig();
	public static boolean admin;
	public static long salt;
	public static final Set<Long> hashes = new HashSet<>();

	public static String mode = "fija";
	public static boolean accumulative;
	public static final Map<String, String> words = new LinkedHashMap<>();
	public static final Map<String, String> wordOwners = new LinkedHashMap<>();
	public static final Map<String, Integer> counts = new LinkedHashMap<>();
	public static final List<Sync.RankEntry> ranking = new ArrayList<>();
	public static int activeWords;
	public static int maxWords = 10;
	public static long nextAddAt;

	public static final List<Subtitle> subtitles = new ArrayList<>();
	public static final List<NoticeMsg> notices = new ArrayList<>();

	public static long silenceUntil;
	public static long invertedLookUntil;
	public static long clumsyUntil;

	public static float micLevel;
	public static String engineStatus = "";
	public static double downloadProgress = -1.0;
	public static boolean voiceConnected = true;
	public static boolean micMuted = false;

	private ClientState() {
	}

	public static class Subtitle {
		public final String speaker;
		public final String text;
		public final boolean partial;
		public final boolean own;
		public long updatedAt;
		public long expiresAt;

		public Subtitle(String speaker, String text, boolean partial, boolean own, long duration) {
			this.speaker = speaker;
			this.text = text;
			this.partial = partial;
			this.own = own;
			this.updatedAt = System.currentTimeMillis();
			this.expiresAt = this.updatedAt + duration;
		}
	}

	public static class NoticeMsg {
		public final String text;
		public final long expiresAt;

		public NoticeMsg(String text, long millis) {
			this.text = text;
			this.expiresAt = System.currentTimeMillis() + millis;
		}
	}

	public static void reset() {
		config = new ServerConfig();
		admin = false;
		salt = 0L;
		hashes.clear();
		mode = "fija";
		accumulative = false;
		words.clear();
		wordOwners.clear();
		counts.clear();
		ranking.clear();
		activeWords = 0;
		notesClear();
		silenceUntil = 0L;
		invertedLookUntil = 0L;
		clumsyUntil = 0L;
		micLevel = 0.0f;
		engineStatus = "";
		downloadProgress = -1.0;
	}

	private static void notesClear() {
		synchronized (subtitles) {
			subtitles.clear();
		}
		synchronized (notices) {
			notices.clear();
		}
	}

	public static void applyConfig(Sync.Config payload) {
		if (payload == null) {
			return;
		}
		config = payload.config == null ? new ServerConfig() : payload.config;
		admin = payload.admin;
		salt = payload.salt;
		hashes.clear();
		if (payload.hashes != null) {
			hashes.addAll(payload.hashes);
		}
		mode = config.mode;
		accumulative = config.isAccumulative();
	}

	public static void applyState(Sync.Panel payload) {
		if (payload == null) {
			return;
		}
		mode = payload.mode == null ? mode : payload.mode;
		accumulative = payload.accumulative;
		words.clear();
		if (payload.words != null) {
			words.putAll(payload.words);
		}
		if (payload.wordOwners != null) {
			wordOwners.putAll(payload.wordOwners);
		}
		counts.clear();
		if (payload.counts != null) {
			counts.putAll(payload.counts);
		}
		ranking.clear();
		if (payload.ranking != null) {
			ranking.addAll(payload.ranking);
		}
		activeWords = payload.activeWords;
		maxWords = Math.max(1, payload.maxWords);
		nextAddAt = payload.nextAddAt;
	}

	public static void pushSubtitle(String speaker, String text, boolean partial, boolean own, int durationMs) {
		if (text == null || text.isBlank()) {
			return;
		}
		String pretty = capitalize(text.trim());
		synchronized (subtitles) {
			// El parcial del mismo hablante se reemplaza; el final cierra el parcial.
			for (int i = subtitles.size() - 1; i >= 0; i--) {
				Subtitle existing = subtitles.get(i);
				if (existing.speaker.equals(speaker) && existing.partial) {
					subtitles.remove(i);
				}
			}
			subtitles.add(new Subtitle(speaker, pretty, partial, own, durationMs));
			while (subtitles.size() > 6) {
				subtitles.remove(0);
			}
		}
	}

	public static void pushNotice(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		synchronized (notices) {
			notices.add(new NoticeMsg(text, 4500L));
			while (notices.size() > 5) {
				notices.remove(0);
			}
		}
	}

	public static void tick() {
		long now = System.currentTimeMillis();
		synchronized (subtitles) {
			subtitles.removeIf(subtitle -> subtitle.expiresAt <= now);
		}
		synchronized (notices) {
			notices.removeIf(notice -> notice.expiresAt <= now);
		}
	}

	public static boolean silenced() {
		return System.currentTimeMillis() < silenceUntil;
	}

	public static boolean invertedLook() {
		return System.currentTimeMillis() < invertedLookUntil;
	}

	public static boolean clumsy() {
		return System.currentTimeMillis() < clumsyUntil;
	}

	public static String capitalize(String value) {
		if (value.isEmpty()) {
			return value;
		}
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
