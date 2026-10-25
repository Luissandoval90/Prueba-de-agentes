package com.palabraprohibida.config;

import com.palabraprohibida.punish.PunishmentType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuracion global del servidor (config/palabraprohibida/server.json).
 * Solo el admin la modifica; se sincroniza a todos los clientes.
 */
public class ServerConfig {
	public String mode = "fija";

	// Modo fija
	public boolean ownerSeesOwnWord = false;
	public int rotationMinutes = 0;
	public List<String> fallbackWords = new ArrayList<>(List.of(
			"casa", "perro", "ventana", "camino", "silla", "nube", "mesa", "puerta",
			"rio", "lampara", "zapato", "arbol", "espejo", "cuchara", "pelota"
	));

	// Modo acumulativo
	public int addIntervalSeconds = 90;
	public int maxWords = 10;
	public int wordTtlMinutes = 45;
	public int revealMode = 0; // 0 = publica, 1 = privada

	// Reglas generales
	public boolean punishCreative = false;
	public boolean punishSpectator = false;
	public boolean noDeathPunishments = true;
	public int punishCooldownSeconds = 20;
	public int decayMinutes = 10;
	public double decayFactor = 0.9;
	public int maxWordsPerPlayer = 200;
	public List<String> ignoredWords = new ArrayList<>();
	public List<Zone> protectedZones = new ArrayList<>();
	public Map<String, PunishmentConfig> punishments = defaults();

	public PunishmentConfig punishment(String id) {
		return punishments.computeIfAbsent(id, key -> new PunishmentConfig(true, 3));
	}

	public PunishmentConfig punishment(PunishmentType type) {
		return punishment(type.id());
	}

	public boolean isAccumulative() {
		return "acumulativo".equalsIgnoreCase(mode);
	}

	public boolean setMode(String value) {
		String normalized = value.toLowerCase(Locale.ROOT);
		if (normalized.equals("fija") || normalized.equals("acumulativo")) {
			mode = normalized;
			return true;
		}
		return false;
	}

	/** Copia profunda para enviar al cliente sin compartir estado. */
	public ServerConfig copy() {
		ServerConfig other = new ServerConfig();
		other.mode = mode;
		other.ownerSeesOwnWord = ownerSeesOwnWord;
		other.rotationMinutes = rotationMinutes;
		other.fallbackWords = new ArrayList<>(fallbackWords);
		other.addIntervalSeconds = addIntervalSeconds;
		other.maxWords = maxWords;
		other.wordTtlMinutes = wordTtlMinutes;
		other.revealMode = revealMode;
		other.punishCreative = punishCreative;
		other.punishSpectator = punishSpectator;
		other.noDeathPunishments = noDeathPunishments;
		other.punishCooldownSeconds = punishCooldownSeconds;
		other.decayMinutes = decayMinutes;
		other.decayFactor = decayFactor;
		other.maxWordsPerPlayer = maxWordsPerPlayer;
		other.ignoredWords = new ArrayList<>(ignoredWords);
		other.protectedZones = new ArrayList<>(protectedZones);
		other.punishments = new LinkedHashMap<>(punishments);
		return other;
	}

	public static Map<String, PunishmentConfig> defaults() {
		Map<String, PunishmentConfig> map = new LinkedHashMap<>();
		for (PunishmentType type : PunishmentType.values()) {
			map.put(type.id(), type.defaultConfig());
		}
		return map;
	}
}
