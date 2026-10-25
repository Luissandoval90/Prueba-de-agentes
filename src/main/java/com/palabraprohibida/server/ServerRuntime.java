package com.palabraprohibida.server;

import com.palabraprohibida.Pp;
import com.palabraprohibida.compat.Compat;
import com.palabraprohibida.config.ServerConfig;
import com.palabraprohibida.config.Zone;
import com.palabraprohibida.net.Net;
import com.palabraprohibida.net.Payloads;
import com.palabraprohibida.net.Sync;
import com.palabraprohibida.punish.Punisher;
import com.palabraprohibida.punish.PunishmentType;
import com.palabraprohibida.state.GameState;
import com.palabraprohibida.state.StateStore;
import com.palabraprohibida.util.Json;
import com.palabraprohibida.word.WordHash;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Estado vivo del servidor: autoridad unica sobre palabras, castigos y contadores.
 * Todo se ejecuta en el hilo del servidor.
 */
public final class ServerRuntime {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static ServerRuntime instance;

	private final MinecraftServer server;
	private ServerConfig config;
	private GameState state;
	private final Punisher punisher;
	private long salt;
	private boolean dirty;
	private int tickCounter;

	/** Reportes recientes: jugador -> marcas de tiempo. */
	private final Map<UUID, Deque<Long>> reportWindow = new HashMap<>();
	/** Duplicados: "uuid:palabra" -> ultima vez. */
	private final Map<String, Long> recentReports = new HashMap<>();
	/** Tareas diferidas (quitar atributos, restaurar inventarios...). */
	private final List<Task> tasks = new ArrayList<>();

	private static final class Task {
		private final long at;
		private final Runnable action;

		private Task(long at, Runnable action) {
			this.at = at;
			this.action = action;
		}
	}

	private ServerRuntime(MinecraftServer server) {
		this.server = server;
		this.config = loadConfig();
		this.state = StateStore.load(server);
		this.state.mode = config.mode;
		this.salt = RANDOM.nextLong();
		this.punisher = new Punisher(this);
		if (state.lastDecayAt == 0L) {
			state.lastDecayAt = System.currentTimeMillis();
		}
	}

	public static ServerRuntime create(MinecraftServer server) {
		instance = new ServerRuntime(server);
		return instance;
	}

	public static ServerRuntime get() {
		return instance;
	}

	public static void clear() {
		if (instance != null) {
			instance.saveNow();
		}
		instance = null;
	}

	private static Path configPath() {
		return Path.of("config", Pp.MOD_ID, "server.json");
	}

	private ServerConfig loadConfig() {
		try {
			Path path = configPath();
			ServerConfig loaded = Json.read(path, ServerConfig.class, new ServerConfig());
			if (loaded.protectedZones == null) {
				loaded.protectedZones = new ArrayList<>();
			}
			if (loaded.fallbackWords == null) {
				loaded.fallbackWords = new ArrayList<>(new ServerConfig().fallbackWords);
			}
			if (!Files.isRegularFile(path)) {
				Json.write(path, loaded);
			}
			return loaded;
		} catch (Exception e) {
			Pp.LOG.warn("Configuracion por defecto: {}", e.getMessage());
			return new ServerConfig();
		}
	}

	// ---------- Acceso ----------

	public MinecraftServer server() {
		return server;
	}

	public ServerConfig config() {
		return config;
	}

	public GameState state() {
		return state;
	}

	public Punisher punisher() {
		return punisher;
	}

	public long salt() {
		return salt;
	}

	public void markDirty() {
		dirty = true;
	}

	/** Ejecuta algo mas tarde en el hilo del servidor. */
	public void schedule(long delayMillis, Runnable action) {
		tasks.add(new Task(System.currentTimeMillis() + Math.max(1L, delayMillis), action));
	}

	private void runScheduled() {
		if (tasks.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Task> iterator = tasks.iterator();
		while (iterator.hasNext()) {
			Task task = iterator.next();
			if (task.at <= now) {
				iterator.remove();
				try {
					task.action.run();
				} catch (Exception e) {
					Pp.LOG.warn("Tarea diferida fallo: {}", e.getMessage());
				}
			}
		}
	}

	public boolean isAdmin(ServerPlayerEntity player) {
		return Compat.isAdmin(player);
	}

	// ---------- Persistencia ----------

	public void saveNow() {
		dirty = false;
		StateStore.save(server, state);
		Json.write(configPath(), config);
	}

	public void saveConfig() {
		Json.write(configPath(), config);
		dirty = true;
	}

	public void reload() {
		// Conserva las palabras y contadores, relee la configuracion.
		ServerConfig reloaded = loadConfig();
		this.config = reloaded;
		this.state.mode = config.mode;
		ensureModeState();
		syncConfigAll();
		saveNow();
	}

	// ---------- Entrada / salida ----------

	public void onJoin(ServerPlayerEntity player) {
		try {
			if (config.isAccumulative()) {
				ensureGlobalTimer();
			} else {
				ensureWord(player);
			}
			sendConfigTo(player);
			sendStateTo(player);
			// Los demas necesitan saber que hay una palabra nueva arriba.
			for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
				if (other != player) {
					sendStateTo(other);
				}
			}
		} catch (Exception e) {
			Pp.LOG.warn("Error al entrar {}: {}", player.getName().getString(), e.getMessage());
		}
	}

	public void onDisconnect(ServerPlayerEntity player) {
		reportWindow.remove(player.getUUID());
		dirty = true;
	}

	// ---------- Palabras ----------

	private void ensureModeState() {
		if (config.isAccumulative()) {
			ensureGlobalTimer();
		} else {
			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				ensureWord(player);
			}
		}
	}

	private void ensureGlobalTimer() {
		long now = System.currentTimeMillis();
		if (state.nextAddAt <= 0L) {
			state.nextAddAt = now + Math.max(10, config.addIntervalSeconds) * 1000L;
		}
		state.pruneGlobal(now, config);
	}

	private void ensureWord(ServerPlayerEntity player) {
		if (state.wordOf(player.getUUID()) != null) {
			return;
		}
		state.setWord(player.getUUID(), pickFreshWord(player.getUUID()));
		dirty = true;
	}

	private String pickFreshWord(UUID player) {
		List<String> used = new ArrayList<>(state.fixedWords.values());
		List<String> candidates = new ArrayList<>();
		for (String word : config.fallbackWords) {
			String normalized = WordHash.normalize(word);
			if (WordHash.isWatchable(normalized) && !used.contains(normalized)) {
				candidates.add(normalized);
			}
		}
		if (candidates.isEmpty()) {
			candidates.addAll(config.fallbackWords);
		}
		if (candidates.isEmpty()) {
			return "casa";
		}
		return WordHash.normalize(candidates.get(RANDOM.nextInt(candidates.size())));
	}

	/** Nueva palabra para un jugador: la que mas repite, si hay datos. */
	public String rotateWord(UUID player, boolean fromCounters) {
		String current = state.wordOf(player);
		List<String> excluded = new ArrayList<>(config.ignoredWords);
		if (current != null) {
			excluded.add(current);
		}
		String candidate = fromCounters ? state.mostUsedWord(player, excluded) : null;
		if (candidate == null) {
			candidate = pickFreshWord(player);
		}
		state.setWord(player, candidate);
		dirty = true;
		return candidate;
	}

	public Set<Long> watchedHashes() {
		Set<Long> hashes = new HashSet<>();
		if (config.isAccumulative()) {
			for (String word : state.activeWords(System.currentTimeMillis())) {
				hashes.add(WordHash.hash(salt, word));
			}
		} else {
			for (String word : state.fixedWords.values()) {
				hashes.add(WordHash.hash(salt, word));
			}
		}
		return hashes;
	}

	// ---------- Mensajes del cliente ----------

	public void onTalk(ServerPlayerEntity player, String text) {
		if (text == null || text.isEmpty()) {
			return;
		}
		for (String word : WordHash.words(text)) {
			state.addWordUse(player.getUUID(), word, config);
		}
		dirty = true;
	}

	public void onSpokenWord(ServerPlayerEntity reporter, UUID speakerId, String rawWord) {
		String word = WordHash.normalize(rawWord);
		if (word.isEmpty()) {
			return;
		}
		if (!allowReport(reporter.getUUID())) {
			return;
		}
		long now = System.currentTimeMillis();
		String key = speakerId + ":" + word;
		Long last = recentReports.get(key);
		if (last != null && now - last < 2500L) {
			return; // la misma palabra en 2,5 s cuenta una vez
		}
		recentReports.put(key, now);
		if (recentReports.size() > 512) {
			recentReports.entrySet().removeIf(entry -> now - entry.getValue() > 10_000L);
		}

		if (config.isAccumulative()) {
			GameState.GlobalWord global = state.findGlobal(word);
			if (global == null) {
				return;
			}
			state.globalWords.remove(global);
			dirty = true;
			ServerPlayerEntity speaker = server.getPlayerManager().getPlayer(speakerId);
			if (speaker == null) {
				return;
			}
			reveal(speaker, word);
			punisher.punishForWord(speaker, word);
			syncStateAll();
			return;
		}

		// Modo fija: la palabra pertenece a un jugador; el castigo va al dueño.
		UUID owner = null;
		for (Map.Entry<String, String> entry : state.fixedWords.entrySet()) {
			if (entry.getValue().equals(word)) {
				owner = UUID.fromString(entry.getKey());
				break;
			}
		}
		if (owner == null) {
			return;
		}
		ServerPlayerEntity ownerPlayer = server.getPlayerManager().getPlayer(owner);
		ServerPlayerEntity speaker = server.getPlayerManager().getPlayer(speakerId);
		if (ownerPlayer == null) {
			return;
		}
		if (speaker != null && !speaker.getUUID().equals(owner)) {
			String ownerName = ownerPlayer.getName().getString();
			sendNotice(speaker, "palabraprohibida.notice.was_owner_word", ownerName);
		}
		punisher.punishForWord(ownerPlayer, word);
		syncStateAll();
	}

	private boolean allowReport(UUID reporter) {
		long now = System.currentTimeMillis();
		Deque<Long> window = reportWindow.computeIfAbsent(reporter, k -> new ArrayDeque<>());
		while (!window.isEmpty() && now - window.peekFirst() > 1000L) {
			window.pollFirst();
		}
		if (window.size() >= 8) {
			return false;
		}
		window.addLast(now);
		return true;
	}

	private void reveal(ServerPlayerEntity speaker, String word) {
		String display = WordHash.displayWord(word);
		if (config.revealMode == 1) {
			sendNotice(speaker, "palabraprohibida.notice.revealed_private", display);
		} else {
			Text message = Compat.text("palabraprohibida.notice.revealed_public",
					speaker.getName().getString(), display);
			server.getPlayerManager().broadcast(message, false);
		}
		dirty = true;
	}

	// ---------- Configuracion desde el cliente ----------

	public void onConfigUpdate(ServerPlayerEntity player, String json) {
		if (!isAdmin(player)) {
			sendNotice(player, "palabraprohibida.notice.admin_only");
			return;
		}
		try {
			ServerConfig updated = Json.COMPACT.fromJson(json, ServerConfig.class);
			if (updated == null) {
				return;
			}
			// Nunca confiamos en lo que llega: normalizamos rangos.
			updated.addIntervalSeconds = clamp(updated.addIntervalSeconds, 10, 3600);
			updated.maxWords = clamp(updated.maxWords, 1, 40);
			updated.wordTtlMinutes = clamp(updated.wordTtlMinutes, 1, 600);
			updated.punishCooldownSeconds = clamp(updated.punishCooldownSeconds, 0, 600);
			updated.decayMinutes = clamp(updated.decayMinutes, 1, 240);
			updated.rotationMinutes = clamp(updated.rotationMinutes, 0, 240);
			updated.decayFactor = Math.max(0.1, Math.min(1.0, updated.decayFactor));
			dirty = true;
			boolean modeChanged = !updated.mode.equals(config.mode);
			config = updated;
			state.mode = config.mode;
			ensureModeState();
			if (modeChanged) {
				state.nextAddAt = 0L;
			}
			saveConfig();
			syncConfigAll();
			syncStateAll();
			for (ServerPlayerEntity other : server.getPlayerManager().getPlayerList()) {
				if (other != player) {
					sendNotice(other, "palabraprohibida.notice.config_changed");
				}
			}
		} catch (Exception e) {
			Pp.LOG.warn("ConfigUpdate invalido: {}", e.getMessage());
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	// ---------- Tick ----------

	public void tick() {
		tickCounter++;
		long now = System.currentTimeMillis();
		runScheduled();

		if (tickCounter % 20 == 0) {
			state.decay(config, now);
			if (config.isAccumulative()) {
				tickAccumulative(now);
			} else {
				tickFixed(now);
			}
			if (dirty) {
				saveNow();
			}
		}
		if (tickCounter % 40 == 0) {
			syncStateAll();
		}
		if (tickCounter >= 1200) {
			tickCounter = 0;
		}
	}

	private void tickFixed(long now) {
		if (config.rotationMinutes <= 0) {
			return;
		}
		long interval = config.rotationMinutes * 60_000L;
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			long last = state.lastRotate.getOrDefault(player.getUUID().toString(), now);
			if (now - last >= interval) {
				String word = rotateWord(player.getUUID(), true);
				sendNotice(player, "palabraprohibida.notice.word_rotated", WordHash.displayWord(word));
				syncStateAll();
			}
		}
	}

	private void tickAccumulative(long now) {
		state.pruneGlobal(now, config);
		if (state.nextAddAt <= 0L) {
			state.nextAddAt = now + Math.max(10, config.addIntervalSeconds) * 1000L;
			return;
		}
		if (now < state.nextAddAt) {
			return;
		}
		state.nextAddAt = now + Math.max(10, config.addIntervalSeconds) * 1000L;
		List<String> excluded = new ArrayList<>(state.activeWords(now));
		excluded.addAll(config.ignoredWords);
		String candidate = state.globalMostUsed(excluded);
		if (candidate == null) {
			return; // aun no hay datos suficientes
		}
		long ttl = config.wordTtlMinutes > 0 ? now + config.wordTtlMinutes * 60_000L : 0L;
		state.globalWords.add(new GameState.GlobalWord(candidate, now, ttl));
		while (state.globalWords.size() > Math.max(1, config.maxWords)) {
			state.globalWords.remove(0);
		}
		dirty = true;
		syncConfigAll();
		syncStateAll();
	}

	// ---------- Sincronizacion ----------

	public void syncConfigAll() {
		// Nueva sal: los hashes que se envian a continuacion la usan.
		salt = RANDOM.nextLong();
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sendConfigTo(player);
		}
	}

	public void syncStateAll() {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sendStateTo(player);
		}
	}

	public void sendConfigTo(ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, Payloads.ConfigSync.ID)) {
			return;
		}
		Sync.Config payload = new Sync.Config();
		payload.config = config;
		payload.salt = salt;
		payload.hashes = new ArrayList<>(watchedHashes());
		payload.admin = isAdmin(player);
		payload.me = player.getUUID().toString();
		Net.toPlayer(player, new Payloads.ConfigSync(Json.COMPACT.toJson(payload)));
	}

	public void sendStateTo(ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, Payloads.State.ID)) {
			return;
		}
		Sync.Panel panel = new Sync.Panel();
		long now = System.currentTimeMillis();
		panel.mode = config.mode;
		panel.accumulative = config.isAccumulative();
		panel.maxWords = config.maxWords;
		panel.nextAddAt = state.nextAddAt;
		if (config.isAccumulative()) {
			panel.activeWords = state.activeWords(now).size();
		} else {
			panel.activeWords = state.fixedWords.size();
			for (Map.Entry<String, String> entry : state.fixedWords.entrySet()) {
				ServerPlayerEntity owner = server.getPlayerManager().getPlayer(UUID.fromString(entry.getKey()));
				String name = owner != null ? owner.getName().getString() : entry.getKey();
				panel.words.put(name, echoFor(player, entry.getKey(), entry.getValue()));
				panel.wordOwners.put(name, entry.getKey());
			}
		}
		List<Map.Entry<String, Integer>> ranking = new ArrayList<>(state.punishCounts.entrySet());
		ranking.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
		for (int i = 0; i < Math.min(5, ranking.size()); i++) {
			UUID id = safeUuid(ranking.get(i).getKey());
			ServerPlayerEntity owner = id == null ? null : server.getPlayerManager().getPlayer(id);
			String name = owner != null ? owner.getName().getString() : ranking.get(i).getKey();
			panel.counts.put(name, ranking.get(i).getValue());
			panel.ranking.add(new Sync.RankEntry(name, ranking.get(i).getValue()));
		}
		Net.toPlayer(player, new Payloads.State(Json.COMPACT.toJson(panel)));
	}

	/** El dueño de la palabra no la ve salvo que juegue solo o lo permita el admin. */
	private String echoFor(ServerPlayerEntity viewer, String ownerId, String word) {
		boolean alone = server.getPlayerManager().getPlayerList().size() <= 1;
		boolean own = viewer.getUUID().toString().equals(ownerId);
		if (own && !config.ownerSeesOwnWord && !alone) {
			return "";
		}
		return WordHash.displayWord(word);
	}

	private static UUID safeUuid(String value) {
		try {
			return UUID.fromString(value);
		} catch (Exception e) {
			return null;
		}
	}

	public void sendNotice(ServerPlayerEntity player, String key, String... args) {
		Sync.Notice notice = new Sync.Notice(key, args);
		Net.toPlayer(player, new Payloads.Notice(Json.COMPACT.toJson(notice)));
	}

	// ---------- Utilidades para castigos ----------

	public boolean inProtectedZone(ServerPlayerEntity player) {
		if (config.protectedZones == null || config.protectedZones.isEmpty()) {
			return false;
		}
		String dimension = Compat.dimensionId(player.getServerWorld());
		BlockPos pos = player.getBlockPos();
		for (Zone zone : config.protectedZones) {
			if (zone.contains(dimension, pos)) {
				return true;
			}
		}
		return false;
	}

	public boolean canBePunished(ServerPlayerEntity player) {
		if (player == null || player.isRemoved() || player.isDead() || player.isDisconnected()) {
			return false;
		}
		if (!config.punishCreative && Compat.isCreative(player)) {
			return false;
		}
		if (!config.punishSpectator && Compat.isSpectator(player)) {
			return false;
		}
		return !inProtectedZone(player);
	}

	public boolean onPunishCooldown(ServerPlayerEntity player) {
		long last = state.lastPunish.getOrDefault(player.getUUID().toString(), 0L);
		return System.currentTimeMillis() - last < config.punishCooldownSeconds * 1000L;
	}

	public void markPunish(ServerPlayerEntity player) {
		state.lastPunish.put(player.getUUID().toString(), System.currentTimeMillis());
		state.addPunish(player.getUUID());
		dirty = true;
	}

	public List<PunishmentType> enabledPunishments(ServerPlayerEntity target) {
		List<PunishmentType> types = new ArrayList<>();
		for (PunishmentType type : PunishmentType.values()) {
			if (!config.punishment(type).enabled || config.punishment(type).weight <= 0) {
				continue;
			}
			if (type.isClientSide() && !ServerPlayNetworking.canSend(target, Payloads.Punish.ID)) {
				continue;
			}
			types.add(type);
		}
		return types;
	}

	public World worldOf(ServerPlayerEntity player) {
		return Compat.serverWorldOf(player);
	}

	public void broadcastMessage(Text text) {
		server.getPlayerManager().broadcast(text, false);
	}
}
