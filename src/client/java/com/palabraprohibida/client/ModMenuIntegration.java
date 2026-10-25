package com.palabraprohibida.client;

import com.palabraprohibida.client.ui.MenuScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Integracion opcional con Mod Menu. Si el mod no esta, esta clase nunca se carga.
 */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new MenuScreen();
	}
}
