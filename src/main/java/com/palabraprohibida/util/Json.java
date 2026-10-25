package com.palabraprohibida.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.palabraprohibida.Pp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Lectura y escritura de JSON con proteccion basica:
 * si el archivo esta corrupto se renombra a ".corrupto" y se devuelve el valor por defecto.
 */
public final class Json {
	public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
	public static final Gson COMPACT = new Gson();

	private Json() {
	}

	public static <T> T read(Path path, Class<T> type, T fallback) {
		if (!Files.isRegularFile(path)) {
			return fallback;
		}
		try {
			String raw = Files.readString(path, StandardCharsets.UTF_8);
			T value = PRETTY.fromJson(raw, type);
			return value == null ? fallback : value;
		} catch (IOException | RuntimeException e) {
			Pp.LOG.warn("JSON invalido en {}: {}", path, e.getMessage());
			quarantine(path);
			return fallback;
		}
	}

	public static void quarantine(Path path) {
		try {
			Path target = path.resolveSibling(path.getFileName() + ".corrupto");
			Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
			Pp.LOG.warn("Archivo movido a {}", target.getFileName());
		} catch (IOException e) {
			Pp.LOG.warn("No se pudo mover {}: {}", path, e.getMessage());
		}
	}

	public static void write(Path path, Object value) {
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(tmp, PRETTY.toJson(value), StandardCharsets.UTF_8);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Pp.LOG.warn("No se pudo escribir {}: {}", path, e.getMessage());
		}
	}

	public static String compact(Object value) {
		return COMPACT.toJson(value);
	}
}
