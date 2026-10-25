package com.palabraprohibida.client.config;

import com.palabraprohibida.Pp;
import com.palabraprohibida.util.Json;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * Ajustes visuales y de microfono. Son locales: no salen del cliente.
 */
public class ClientConfig {
	private static ClientConfig instance;

	public boolean showOwnSubtitles = true;
	public boolean showOtherSubtitles = true;
	public double subtitleSize = 1.0;
	public int subtitleDurationMs = 4000;
	public boolean animations = true;

	public double subtitleX = 0.5;
	public double subtitleY = 0.78;
	public double panelX = 0.02;
	public double panelY = 0.28;
	public double noticeX = 0.5;
	public double noticeY = 0.12;

	public boolean showPanel = true;
	public boolean showWordTags = true;

	public String model = "rapido";
	public double minConfidence = 0.5;
	public boolean quickDetection = false;
	public boolean micMeter = true;

	public static ClientConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(Pp.MOD_ID).resolve("client.json");
	}

	public static void load() {
		instance = Json.read(path(), ClientConfig.class, new ClientConfig());
		instance.sanitize();
	}

	public static void save() {
		if (instance != null) {
			instance.sanitize();
			Json.write(path(), instance);
		}
	}

	private void sanitize() {
		subtitleSize = clamp(subtitleSize, 0.6, 2.0);
		subtitleDurationMs = (int) clamp(subtitleDurationMs, 1000, 10_000);
		subtitleX = clamp(subtitleX, 0.0, 1.0);
		subtitleY = clamp(subtitleY, 0.0, 1.0);
		panelX = clamp(panelX, 0.0, 1.0);
		panelY = clamp(panelY, 0.0, 1.0);
		noticeX = clamp(noticeX, 0.0, 1.0);
		noticeY = clamp(noticeY, 0.0, 1.0);
		minConfidence = clamp(minConfidence, 0.0, 1.0);
		if (model == null || model.isEmpty()) {
			model = "rapido";
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
