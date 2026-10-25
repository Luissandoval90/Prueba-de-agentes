package com.palabraprohibida.voice;

import com.palabraprohibida.Pp;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Descarga y extraccion del modelo de Vosk.
 *
 * - "rapido": vosk-model-small-es-0.42 (39 MB), automatico al primer inicio.
 * - "preciso": vosk-model-es-0.42 (1,4 GB), solo bajo demanda.
 *
 * La extraccion se hace en una carpeta temporal y luego se mueve, con proteccion
 * contra zip-slip y un reintento.
 */
public final class ModelManager {
	public static final String SMALL = "vosk-model-small-es-0.42";
	public static final String BIG = "vosk-model-es-0.42";

	private static final String BASE_URL = "https://alphacephei.com/vosk/models/";
	private static volatile String extracting;

	private ModelManager() {
	}

	public static Path baseDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(Pp.MOD_ID).resolve("model");
	}

	public static Path dir(String modelName) {
		return baseDir().resolve(modelName);
	}

	public static boolean isReady(String modelName) {
		Path path = dir(modelName);
		return Files.isDirectory(path.resolve("am")) && Files.isDirectory(path.resolve("conf"));
	}

	public static boolean isDownloading() {
		return extracting != null;
	}

	/**
	 * Se llama desde un hilo aparte (nunca en el hilo principal).
	 *
	 * @param progress 0..1, o -1 si es indeterminado
	 * @param done     true si termino bien
	 */
	public static boolean ensure(String modelName, Consumer<Float> progress, Consumer<Boolean> done) {
		if (isReady(modelName)) {
			done.accept(true);
			return true;
		}
		extracting = modelName;
		Path tempZip = null;
		try {
			Files.createDirectories(baseDir());
			tempZip = Files.createTempFile(baseDir(), modelName, ".zip");
			if (!download(modelName, tempZip, progress)) {
				done.accept(false);
				return false;
			}
			Path tempDir = Files.createTempDirectory(baseDir(), modelName + "-tmp");
			extract(tempZip, tempDir);
			// El zip contiene una carpeta con el nombre del modelo.
			Path inner = findInner(tempDir, modelName);
			Path target = dir(modelName);
			deleteRecursive(target);
			moveOrCopy(inner, target);
			deleteRecursive(tempDir);
			progress.accept(1.0f);
			done.accept(true);
			return true;
		} catch (Exception e) {
			Pp.LOG.warn("No se pudo preparar el modelo {}: {}", modelName, e.getMessage());
			done.accept(false);
			return false;
		} finally {
			extracting = null;
			if (tempZip != null) {
				try {
					Files.deleteIfExists(tempZip);
				} catch (IOException ignored) {
				}
			}
		}
	}

	private static boolean download(String modelName, Path target, Consumer<Float> progress) {
		StringBuilder failures = new StringBuilder();
		for (int attempt = 1; attempt <= 2; attempt++) {
			HttpURLConnection connection = null;
			try {
				URI uri = URI.create(BASE_URL + modelName + ".zip");
				connection = (HttpURLConnection) uri.toURL().openConnection();
				connection.setConnectTimeout(15_000);
				connection.setReadTimeout(30_000);
				connection.setRequestProperty("User-Agent", "palabraprohibida/1.0");
				connection.connect();
				int code = connection.getResponseCode();
				if (code != 200) {
					throw new IOException("HTTP " + code);
				}
				long total = connection.getContentLengthLong();
				try (InputStream in = connection.getInputStream();
						OutputStream out = Files.newOutputStream(target)) {
					byte[] buffer = new byte[64 * 1024];
					long read = 0;
					int count;
					int lastPercent = -1;
					while ((count = in.read(buffer)) > 0) {
						out.write(buffer, 0, count);
						read += count;
						if (total > 0) {
							int percent = (int) (read * 100 / total);
							if (percent != lastPercent) {
								lastPercent = percent;
								progress.accept(percent / 100.0f);
							}
						}
					}
				}
				return true;
			} catch (Exception e) {
				failures.append(attempt).append(": ").append(e.getMessage()).append(' ');
				Pp.LOG.warn("Descarga fallida (intento {}): {}", attempt, e.getMessage());
			} finally {
				if (connection != null) {
					connection.disconnect();
				}
			}
		}
		Pp.LOG.warn("No se pudo descargar el modelo. {}", failures);
		return false;
	}

	private static Path findInner(Path root, String modelName) throws IOException {
		try (var stream = Files.list(root)) {
			return stream.filter(Files::isDirectory)
					.filter(path -> path.getFileName().toString().equals(modelName))
					.findFirst()
					.orElseGet(() -> {
						try (var inner = Files.list(root)) {
							return inner.filter(Files::isDirectory).findFirst().orElse(root);
						} catch (IOException e) {
							return root;
						}
					});
		}
	}

	public static void extract(Path zip, Path target) throws IOException {
		String targetPath = target.toAbsolutePath().toString();
		try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				Path resolved = target.resolve(entry.getName()).normalize();
				if (!resolved.toAbsolutePath().toString().startsWith(targetPath)) {
					throw new IOException("Entrada sospechosa en el zip: " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(resolved);
				} else {
					Files.createDirectories(resolved.getParent());
					try (OutputStream out = Files.newOutputStream(resolved)) {
						zis.transferTo(out);
					}
				}
				zis.closeEntry();
			}
		}
	}

	private static void moveOrCopy(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
			return;
		} catch (IOException e) {
			Pp.LOG.debug("Movimiento directo no posible, copiando: {}", e.getMessage());
		}
		Files.createDirectories(target);
		try (var stream = Files.walk(source)) {
			for (Path path : stream.toList()) {
				Path destination = target.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(destination);
				} else {
					Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void deleteRecursive(Path path) {
		if (!Files.exists(path)) {
			return;
		}
		try (var stream = Files.walk(path)) {
			stream.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		} catch (IOException ignored) {
		}
	}

	public static String sizeLabel(String modelName) {
		return modelName.equals(BIG) ? "1,4 GB" : "39 MB";
	}

	public static String displayName(String modelName) {
		return "preciso".equals(modelName) || BIG.equals(modelName)
				? "Preciso (" + BIG + ")"
				: "Rapido (" + SMALL + ")";
	}

	public static String normalizeChoice(String value) {
		return value != null && value.toLowerCase(Locale.ROOT).startsWith("prec") ? BIG : SMALL;
	}
}
