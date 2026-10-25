package com.palabraprohibida.net;

import com.palabraprohibida.config.ServerConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contenido JSON de los paquetes de sincronizacion.
 */
public final class Sync {
	private Sync() {
	}

	/** S2C ConfigSync. */
	public static class Config {
		public ServerConfig config = new ServerConfig();
		public long salt;
		public List<Long> hashes = new ArrayList<>();
		public boolean admin;
		public String me = "";
	}

	/** S2C State. */
	public static class Panel {
		public String mode = "fija";
		public boolean accumulative;
		public Map<String, String> words = new LinkedHashMap<>();
		/** nombre del jugador -> uuid, para el boton "Nueva" de la tabla. */
		public Map<String, String> wordOwners = new LinkedHashMap<>();
		public Map<String, Integer> counts = new LinkedHashMap<>();
		public int activeWords;
		public int maxWords;
		public long nextAddAt;
		public List<RankEntry> ranking = new ArrayList<>();
		public int speakers;
	}

	public static class RankEntry {
		public String name;
		public int count;

		public RankEntry() {
		}

		public RankEntry(String name, int count) {
			this.name = name;
			this.count = count;
		}
	}

	/** S2C Notice: clave de traduccion + argumentos. */
	public static class Notice {
		public String key;
		public List<String> args = new ArrayList<>();

		public Notice() {
		}

		public Notice(String key, String... args) {
			this.key = key;
			this.args = List.of(args);
		}
	}

	/** C2S Action / ConfigUpdate usan una clave y datos simples. */
	public static class ActionData {
		public String key;
		public String value;
	}
}
