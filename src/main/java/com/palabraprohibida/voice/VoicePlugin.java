package com.palabraprohibida.voice;

import com.palabraprohibida.Pp;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Plugin de Simple Voice Chat (entrada obligatoria del mod).
 *
 * En el servidor dedicado esta clase no engancha nada. En el cliente carga por
 * reflexion el puente de audio, que vive en el source set de cliente: asi el
 * jar de servidor nunca intenta tocar clases que no existen.
 */
public class VoicePlugin implements VoicechatPlugin {
	public static final String PLUGIN_ID = "palabraprohibida";
	private static final String CLIENT_BRIDGE = "com.palabraprohibida.client.voice.VoiceClientBridge";

	@Override
	public String getPluginId() {
		return PLUGIN_ID;
	}

	@Override
	public void initialize(VoicechatApi api) {
		Pp.LOG.info("Simple Voice Chat disponible");
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		if (FabricLoader.getInstance().getEnvironmentType() != net.fabricmc.api.EnvType.CLIENT) {
			return; // en servidor no se engancha audio
		}
		try {
			Class<?> bridge = Class.forName(CLIENT_BRIDGE);
			bridge.getMethod("register", EventRegistration.class).invoke(null, registration);
		} catch (ReflectiveOperationException | LinkageError e) {
			Pp.LOG.warn("No se pudo enganchar el audio del cliente: {}", e.getMessage());
		}
	}
}
