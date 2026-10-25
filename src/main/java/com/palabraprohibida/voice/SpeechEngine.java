package com.palabraprohibida.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.palabraprohibida.Pp;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconocimiento de voz fuera del hilo principal.
 *
 * - Una cola acotada (200) que descarta lo mas viejo.
 * - Un Recognizer por hablante (maximo 5 ajenos, mas el microfono propio).
 * - Los reconocedores se liberan por inactividad, al salir del mundo y al cerrar.
 */
public final class SpeechEngine {
	private static final int MAX_QUEUE = 200;
	private static final int MAX_OTHERS = 5;
	private static final long IDLE_MILLIS = 60_000L;
	private static final SpeechEngine INSTANCE = new SpeechEngine();

	public interface Listener {
		void onPartial(UUID speaker, boolean own, String text);

		/** words y confidences van en paralelo (confianza 0..1). */
		void onFinal(UUID speaker, boolean own, String text, List<String> words, List<Float> confidences);

		void onStatus(String key, String detail);
	}

	private final ArrayDeque<Frame> queue = new ArrayDeque<>();
	private final Map<UUID, Session> sessions = new LinkedHashMap<>(16, 0.75f, true);
	private final Object lock = new Object();

	private Thread worker;
	private volatile boolean running;
	private Model model;
	private Listener listener;
	private String modelName = ModelManager.SMALL;
	private boolean reportedReady;

	private static final class Frame {
		private final UUID speaker;
		private final boolean own;
		private final short[] pcm;

		private Frame(UUID speaker, boolean own, short[] pcm) {
			this.speaker = speaker;
			this.own = own;
			this.pcm = pcm;
		}
	}

	private static final class Session {
		private final UUID id;
		private final boolean own;
		private final Resampler resampler = new Resampler();
		private Recognizer recognizer;
		private long lastUsed = System.currentTimeMillis();

		private Session(UUID id, boolean own) {
			this.id = id;
			this.own = own;
		}

		private void close() {
			if (recognizer != null) {
				try {
					recognizer.close();
				} catch (Exception ignored) {
				}
				recognizer = null;
			}
		}
	}

	public static SpeechEngine get() {
		return INSTANCE;
	}

	public boolean isRunning() {
		return running && model != null;
	}

	public String modelName() {
		return modelName;
	}

	/** Arranca el hilo y carga el modelo dentro de el (nunca en el hilo del juego). */
	public void start(String requestedModel, Listener newListener) {
		if (newListener != null) {
			this.listener = newListener;
		}
		String normalized = ModelManager.normalizeChoice(requestedModel);
		if (running && normalized.equals(modelName)) {
			return;
		}
		stop();
		this.modelName = normalized;
		this.reportedReady = false;
		this.running = true;
		worker = new Thread(this::loop, "palabraprohibida-vosk");
		worker.setDaemon(true);
		worker.start();
	}

	public void stop() {
		running = false;
		synchronized (lock) {
			lock.notifyAll();
		}
		Thread thread = worker;
		worker = null;
		if (thread != null) {
			try {
				thread.join(1500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		synchronized (sessions) {
			for (Session session : sessions.values()) {
				session.close();
			}
			sessions.clear();
		}
		closeModel();
	}

	private void closeModel() {
		if (model != null) {
			try {
				model.close();
			} catch (Exception ignored) {
			}
			model = null;
		}
	}

	/** Encola un cuadro de audio de 48 kHz. Nunca bloquea. */
	public void offer(UUID speaker, boolean own, short[] pcm) {
		if (!running || speaker == null) {
			return;
		}
		synchronized (lock) {
			if (queue.size() >= MAX_QUEUE) {
				queue.pollFirst(); // descarta lo mas viejo
			}
			queue.addLast(new Frame(speaker, own, pcm));
			lock.notifyAll();
		}
	}

	/** Cierra el reconocedor de un hablante (por ejemplo al desconectarse). */
	public void drop(UUID speaker) {
		synchronized (sessions) {
			Session session = sessions.remove(speaker);
			if (session != null) {
				session.close();
			}
		}
	}

	public void resetSessions() {
		synchronized (sessions) {
			for (Session session : sessions.values()) {
				session.close();
			}
			sessions.clear();
		}
		synchronized (lock) {
			queue.clear();
		}
	}

	// ---------- Hilo de trabajo ----------

	private void loop() {
		if (!ModelManager.isReady(modelName)) {
			report("palabraprohibida.voice.model_missing", modelName);
			running = false;
			return;
		}
		try {
			model = new Model(ModelManager.dir(modelName).toString());
		} catch (Exception | LinkageError e) {
			Pp.LOG.warn("Modelo {} no cargable: {}", modelName, e.getMessage());
			running = false;
			closeModel();
			report("palabraprohibida.voice.model_failed", e.getMessage());
			return;
		}
		report("palabraprohibida.voice.ready", modelName);

		while (running) {
			Frame frame;
			synchronized (lock) {
				while (running && queue.isEmpty()) {
					try {
						lock.wait(250L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						running = false;
					}
					evictIdle();
				}
				frame = queue.pollFirst();
			}
			if (!running) {
				break;
			}
			if (frame != null) {
				try {
					process(frame);
				} catch (Exception e) {
					Pp.LOG.debug("Cuadro de audio descartado: {}", e.getMessage());
				}
			}
			evictIdle();
		}
		closeModel();
	}

	private void evictIdle() {
		long now = System.currentTimeMillis();
		synchronized (sessions) {
			Iterator<Map.Entry<UUID, Session>> iterator = sessions.entrySet().iterator();
			while (iterator.hasNext()) {
				Session session = iterator.next().getValue();
				if (now - session.lastUsed > IDLE_MILLIS) {
					session.close();
					iterator.remove();
				}
			}
		}
	}

	private void process(Frame frame) {
		Session session;
		synchronized (sessions) {
			session = sessions.get(frame.speaker);
			if (session == null) {
				if (!frame.own && countOthers() >= MAX_OTHERS) {
					return; // demasiados hablantes ajenos a la vez
				}
				session = new Session(frame.speaker, frame.own);
				sessions.put(frame.speaker, session);
			}
			session.lastUsed = System.currentTimeMillis();
		}
		if (model == null) {
			return;
		}
		if (session.recognizer == null) {
			session.recognizer = new Recognizer(model, Resampler.OUT_RATE);
			session.recognizer.setWords(true);
		}
		Recognizer recognizer = session.recognizer;
		if (frame.pcm == null || frame.pcm.length == 0) {
			// Fin de transmision: volcamos el resultado final y reiniciamos.
			String json = recognizer.getFinalResult();
			emit(session, json, false);
			session.close();
			session.resampler.reset();
			return;
		}
		short[] pcm16 = session.resampler.process(frame.pcm);
		if (pcm16.length == 0) {
			return;
		}
		byte[] bytes = Resampler.toBytes(pcm16);
		boolean end = recognizer.acceptWaveForm(bytes, bytes.length);
		if (end) {
			emit(session, recognizer.getResult(), false);
		} else {
			emit(session, recognizer.getPartialResult(), true);
		}
	}

	private int countOthers() {
		int count = 0;
		for (Session session : sessions.values()) {
			if (!session.own) {
				count++;
			}
		}
		return count;
	}

	/** Parsea el JSON de Vosk y avisa al listener. */
	private void emit(Session session, String json, boolean partial) {
		if (json == null || json.isEmpty() || listener == null) {
			return;
		}
		try {
			JsonObject object = JsonParser.parseString(json).getAsJsonObject();
			if (partial) {
				String text = object.has("partial") ? object.get("partial").getAsString() : "";
				if (!text.isEmpty()) {
					listener.onPartial(session.id, session.own, text);
				}
				return;
			}
			String text = object.has("text") ? object.get("text").getAsString() : "";
			List<String> words = new ArrayList<>();
			List<Float> confidences = new ArrayList<>();
			if (object.has("result") && object.get("result").isJsonArray()) {
				JsonArray result = object.getAsJsonArray("result");
				for (JsonElement element : result) {
					if (!element.isJsonObject()) {
						continue;
					}
					JsonObject word = element.getAsJsonObject();
					if (!word.has("word")) {
						continue;
					}
					words.add(word.get("word").getAsString());
					confidences.add(word.has("conf") ? word.get("conf").getAsFloat() : 1.0f);
				}
			}
			if (!text.isEmpty() || !words.isEmpty()) {
				listener.onFinal(session.id, session.own, text, words, confidences);
			}
		} catch (Exception e) {
			Pp.LOG.debug("JSON de Vosk invalido: {}", e.getMessage());
		}
	}

	private void report(String key, String detail) {
		Listener current = listener;
		if (current != null) {
			current.onStatus(key, detail);
		}
	}

	/** Utilidad para depuracion: nombre por hablante que conoce el cliente. */
	public static Map<UUID, String> emptyNames() {
		return new HashMap<>();
	}
}
