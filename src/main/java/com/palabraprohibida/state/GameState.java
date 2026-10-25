package com.palabraprohibida.state;

import com.palabraprohibida.config.ServerConfig;
import com.palabraprohibida.word.WordHash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Estado persistente del mundo: palabras, contadores y ranking.
 */
public class GameState {
	public String mode = "fija";
	public Map<String, String> fixedWords = new LinkedHashMap<>();
	public Map<String, Integer> punishCounts = new LinkedHashMap<>();
	public Map<String, Map<String, Integer>> counters = new LinkedHashMap<>();
	public Map<String, Long> lastRotate = new LinkedHashMap<>();
	public Map<String, Long> lastPunish = new LinkedHashMap<>();
	public List<GlobalWord> globalWords = new ArrayList<>();
	public long nextAddAt = 0L;
	public long lastDecayAt = 0L;

	public static class GlobalWord {
		public String word;
		public long addedAt;
		public long expiresAt;

		public GlobalWord() {
		}

		public GlobalWord(String word, long addedAt, long expiresAt) {
			this.word = word;
			this.addedAt = addedAt;
			this.expiresAt = expiresAt;
		}
	}

	public String wordOf(UUID player) {
		return fixedWords.get(player.toString());
	}

	public void setWord(UUID player, String word) {
		fixedWords.put(player.toString(), WordHash.normalize(word));
		lastRotate.put(player.toString(), System.currentTimeMillis());
	}

	public void removePlayer(UUID player) {
		fixedWords.remove(player.toString());
		lastRotate.remove(player.toString());
	}

	public int punishCount(UUID player) {
		return punishCounts.getOrDefault(player.toString(), 0);
	}

	public void addPunish(UUID player) {
		punishCounts.merge(player.toString(), 1, Integer::sum);
	}

	public void addWordUse(UUID player, String word, ServerConfig config) {
		String key = player.toString();
		Map<String, Integer> map = counters.computeIfAbsent(key, k -> new LinkedHashMap<>());
		String normalized = WordHash.normalize(word);
		if (!WordHash.isWatchable(normalized) || config.ignoredWords.contains(normalized)) {
			return;
		}
		map.merge(normalized, 1, Integer::sum);
		if (map.size() > config.maxWordsPerPlayer) {
			// Recorte: nos quedamos con las mas frecuentes.
			List<Map.Entry<String, Integer>> entries = new ArrayList<>(map.entrySet());
			entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
			Map<String, Integer> trimmed = new LinkedHashMap<>();
			for (int i = 0; i < config.maxWordsPerPlayer && i < entries.size(); i++) {
				trimmed.put(entries.get(i).getKey(), entries.get(i).getValue());
			}
			counters.put(key, trimmed);
		}
	}

	public Map<String, Integer> countersOf(UUID player) {
		return counters.getOrDefault(player.toString(), Map.of());
	}

	/** Palabra mas repetida del jugador que no este excluida. */
	public String mostUsedWord(UUID player, List<String> excluded) {
		// excluded incluye la palabra actual y las ignoradas por el admin.
		Map<String, Integer> map = countersOf(player);
		String best = null;
		int bestCount = 0;
		for (Map.Entry<String, Integer> entry : map.entrySet()) {
			String word = entry.getKey();
			if (!WordHash.isWatchable(word) || excluded.contains(word)) {
				continue;
			}
			if (entry.getValue() > bestCount) {
				bestCount = entry.getValue();
				best = word;
			}
		}
		return best;
	}

	/** Palabra global mas hablada por todos los jugadores. */
	public String globalMostUsed(List<String> excluded) {
		Map<String, Integer> totals = new HashMap<>();
		for (Map<String, Integer> map : counters.values()) {
			for (Map.Entry<String, Integer> entry : map.entrySet()) {
				totals.merge(entry.getKey(), entry.getValue(), Integer::sum);
			}
		}
		String best = null;
		int bestCount = 0;
		for (Map.Entry<String, Integer> entry : totals.entrySet()) {
			if (!WordHash.isWatchable(entry.getKey()) || excluded.contains(entry.getKey())) {
				continue;
			}
			if (entry.getValue() > bestCount) {
				bestCount = entry.getValue();
				best = entry.getKey();
			}
		}
		return best;
	}

	public void decay(ServerConfig config, long now) {
		long interval = Math.max(1, config.decayMinutes) * 60_000L;
		if (lastDecayAt == 0L) {
			lastDecayAt = now;
			return;
		}
		if (now - lastDecayAt < interval) {
			return;
		}
		lastDecayAt = now;
		for (Map<String, Integer> map : counters.values()) {
			Iterator<Map.Entry<String, Integer>> it = map.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<String, Integer> entry = it.next();
				int value = (int) Math.floor(entry.getValue() * config.decayFactor);
				if (value <= 0) {
					it.remove();
				} else {
					entry.setValue(value);
				}
			}
		}
	}

	public List<String> activeWords(long now) {
		List<String> words = new ArrayList<>();
		for (GlobalWord word : globalWords) {
			if (word.expiresAt <= 0L || word.expiresAt > now) {
				words.add(word.word);
			}
		}
		return words;
	}

	public void pruneGlobal(long now, ServerConfig config) {
		globalWords.removeIf(word -> word.expiresAt > 0L && word.expiresAt <= now);
		while (globalWords.size() > Math.max(1, config.maxWords)) {
			globalWords.remove(0);
		}
	}

	public GlobalWord findGlobal(String normalizedWord) {
		for (GlobalWord word : globalWords) {
			if (word.word.equals(normalizedWord)) {
				return word;
			}
		}
		return null;
	}
}
