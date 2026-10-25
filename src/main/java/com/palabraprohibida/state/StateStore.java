package com.palabraprohibida.state;

import com.palabraprohibida.Pp;
import com.palabraprohibida.util.Json;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guarda el estado del mundo en la carpeta del mundo, no en la configuracion global.
 */
public final class StateStore {
	private StateStore() {
	}

	public static Path directory(MinecraftServer server) {
		try {
			Path root = server.getSavePath(WorldSavePath.ROOT).resolve(Pp.MOD_ID);
			Files.createDirectories(root);
			return root;
		} catch (Exception e) {
			Pp.LOG.warn("No se pudo abrir la carpeta del mundo: {}", e.getMessage());
			return Path.of(Pp.MOD_ID);
		}
	}

	public static GameState load(MinecraftServer server) {
		Path file = directory(server).resolve("state.json");
		GameState state = Json.read(file, GameState.class, new GameState());
		if (state.counters == null) {
			state.counters = new java.util.LinkedHashMap<>();
		}
		if (state.fixedWords == null) {
			state.fixedWords = new java.util.LinkedHashMap<>();
		}
		if (state.globalWords == null) {
			state.globalWords = new java.util.ArrayList<>();
		}
		return state;
	}

	public static void save(MinecraftServer server, GameState state) {
		if (server == null || state == null) {
			return;
		}
		Json.write(directory(server).resolve("state.json"), state);
	}
}
