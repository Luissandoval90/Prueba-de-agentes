package com.palabraprohibida;

import com.palabraprohibida.command.ModCommands;
import com.palabraprohibida.net.Payloads;
import com.palabraprohibida.server.ServerCallbacks;
import net.fabricmc.api.ModInitializer;

/**
 * Entrada comun (cliente y servidor). Aqui solo se registran cosas que existen
 * en ambos lados; nada de clases de cliente.
 */
public class PalabraProhibida implements ModInitializer {
	@Override
	public void onInitialize() {
		Pp.LOG.info("Palabra Prohibida: cargando");
		Payloads.registerTypes();
		ServerCallbacks.register();
		ModCommands.register();
	}
}
