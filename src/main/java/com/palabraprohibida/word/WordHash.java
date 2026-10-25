package com.palabraprohibida.word;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Normalizacion de palabras y hash FNV-1a de 64 bits con sal.
 *
 * El cliente nunca conoce la lista de palabras vigiladas en modo acumulativo:
 * solo recibe los hashes y compara.
 */
public final class WordHash {
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;
	private static final Set<String> CONNECTORS = Set.of(
			"de", "la", "el", "los", "las", "un", "una", "unos", "unas", "y", "o", "u", "a", "ante",
			"con", "en", "para", "por", "que", "se", "su", "al", "del", "lo", "le", "les", "me", "mi",
			"es", "son", "como", "mas", "pero", "si", "no", "ya", "muy", "esto", "eso", "esa", "este"
	);

	private WordHash() {
	}

	/** Quita tildes, pasa a minusculas y descarta todo lo que no sea letra, numero o apostrofo. */
	public static String normalize(String input) {
		if (input == null) {
			return "";
		}
		String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
		StringBuilder sb = new StringBuilder(decomposed.length());
		for (int i = 0; i < decomposed.length(); i++) {
			char c = decomposed.charAt(i);
			if (c >= '\u0300' && c <= '\u036f') {
				continue; // diacritico combinado
			}
			if (c == '\u00f1' || c == '\u00d1') {
				sb.append('n');
				continue;
			}
			if (Character.isLetterOrDigit(c) || c == '\'' || c == '\u2019') {
				sb.append(Character.toLowerCase(c));
			} else {
				sb.append(' ');
			}
		}
		return sb.toString().trim().replaceAll(" +", " ");
	}

	public static List<String> words(String text) {
		String normalized = normalize(text);
		List<String> out = new ArrayList<>();
		if (normalized.isEmpty()) {
			return out;
		}
		for (String part : normalized.split(" ")) {
			if (!part.isEmpty()) {
				out.add(part);
			}
		}
		return out;
	}

	public static String displayWord(String word) {
		if (word == null || word.isEmpty()) {
			return "";
		}
		String lower = word.toLowerCase(Locale.ROOT);
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	public static boolean isConnector(String word) {
		return CONNECTORS.contains(normalize(word));
	}

	/** Una palabra valida para vigilar: al menos 3 letras y no es conector. */
	public static boolean isWatchable(String word) {
		String normalized = normalize(word);
		if (normalized.isEmpty() || normalized.length() < 3 || normalized.contains(" ")) {
			return false;
		}
		return !isConnector(normalized);
	}

	public static long hash(long salt, String normalizedWord) {
		long h = FNV_OFFSET;
		h = mix(h, salt);
		for (int i = 0; i < normalizedWord.length(); i++) {
			h ^= normalizedWord.charAt(i);
			h *= FNV_PRIME;
		}
		return h;
	}

	private static long mix(long h, long value) {
		for (int i = 0; i < 8; i++) {
			h ^= (value >>> (i * 8)) & 0xFFL;
			h *= FNV_PRIME;
		}
		return h;
	}
}
