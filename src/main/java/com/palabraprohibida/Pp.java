package com.palabraprohibida;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Constantes compartidas por todo el mod.
 */
public final class Pp {
	public static final String MOD_ID = "palabraprohibida";
	public static final Logger LOG = LoggerFactory.getLogger("PalabraProhibida");

	/** Limite de bytes de los paquetes C2S. */
	public static final int MAX_C2S_BYTES = 30_000;

	private Pp() {
	}

	public static net.minecraft.util.Identifier id(String path) {
		return net.minecraft.util.Identifier.of(MOD_ID, path);
	}
}
