package com.palabraprohibida.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuracion sincronizada de un castigo concreto.
 *
 * El getter boolean se llama {@code flag(...)}; el constructor fluido que usan
 * los valores por defecto del enum es {@code withFlag(...)} (misma firma, no
 * puede haber dos).
 */
public class PunishmentConfig {
	public boolean enabled = true;
	public int weight = 3;
	public Map<String, Double> values = new LinkedHashMap<>();
	public Map<String, Boolean> flags = new LinkedHashMap<>();

	public PunishmentConfig() {
	}

	public PunishmentConfig(boolean enabled, int weight) {
		this.enabled = enabled;
		this.weight = Math.max(0, Math.min(10, weight));
	}

	public double value(String key, double fallback) {
		Double v = values.get(key);
		return v == null ? fallback : v;
	}

	public int intValue(String key, int fallback) {
		return (int) Math.round(value(key, fallback));
	}

	public boolean flag(String key, boolean fallback) {
		Boolean v = flags.get(key);
		return v == null ? fallback : v;
	}

	public PunishmentConfig with(String key, double value) {
		values.put(key, value);
		return this;
	}

	/** Version fluida de {@link #flag(String, boolean)} para los valores por defecto. */
	public PunishmentConfig withFlag(String key, boolean value) {
		flags.put(key, value);
		return this;
	}

	public PunishmentConfig weight(int w) {
		this.weight = Math.max(0, Math.min(10, w));
		return this;
	}
}
