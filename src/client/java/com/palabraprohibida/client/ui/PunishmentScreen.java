package com.palabraprohibida.client.ui;

import com.palabraprohibida.client.ClientNet;
import com.palabraprohibida.client.ClientState;
import com.palabraprohibida.config.PunishmentConfig;
import com.palabraprohibida.punish.PunishmentType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuracion de un castigo: interruptor, peso, parametros, Probar y Volver.
 */
public class PunishmentScreen extends Screen {
	private static final class Param {
		private final String key;
		private final double min;
		private final double max;
		private final double step;

		private Param(String key, double min, double max, double step) {
			this.key = key;
			this.min = min;
			this.max = max;
			this.step = step;
		}
	}

	private final Screen parent;
	private final PunishmentType type;
	private final List<Param> params = new ArrayList<>();
	private boolean mouseDown;
	private boolean draggingWeight;
	private int draggingParam = -1;
	private int top;
	private int sliderX;
	private int sliderWidth;

	public PunishmentScreen(Screen parent, PunishmentType type) {
		super(Text.translatable(type.translationKey()));
		this.parent = parent;
		this.type = type;
		addParam("amount", 1, 20, 1);
		addParam("distance", 2, 32, 1);
		addParam("seconds", 1, 60, 1);
		addParam("amplifier", 0, 4, 1);
		addParam("points", 1, 20, 1);
		addParam("levels", 1, 30, 1);
		addParam("count", 1, 5, 1);
	}

	private void addParam(String key, double min, double max, double step) {
		PunishmentConfig defaults = type.defaultConfig();
		if (defaults.values.containsKey(key)) {
			params.add(new Param(key, min, max, step));
		}
	}

	/** Y de los botones: justo debajo del contenido, sin bajar del limite. */
	private int buttonY() {
		int contentBottom = top + 42 + 26 + 24 + params.size() * 20
				+ (type == PunishmentType.MOB ? 24 : 0);
		return Math.min(this.height - 60, contentBottom + 14);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, Theme.BACKGROUND);
		PunishmentConfig config = ClientState.config.punishment(type);

		int panelWidth = Math.min(360, this.width - 40);
		int x = (this.width - panelWidth) / 2;
		top = 44;
		sliderX = x + 120;
		sliderWidth = panelWidth - 140;

		Draw.card(context, x - 8, top - 12, panelWidth + 16, buttonY() + 28 - (top - 12), false, false);
		Draw.item(context, type.iconItem(), x + 6, top + 4, 2.0f);
		Draw.text(context, Text.translatable(type.translationKey()).getString(), x + 64, top + 8, Theme.TEXT);
		Draw.text(context, Text.translatable(type.descriptionKey()).getString(), x + 64, top + 22, Theme.TEXT_FAINT);

		int y = top + 42;
		// Interruptor
		Draw.text(context, Text.translatable("palabraprohibida.punish.enabled").getString(), x, y, Theme.TEXT);
		boolean toggleHover = inside(mouseX, mouseY, x, y - 3, panelWidth, 20);
		Draw.toggle(context, x, y - 3, panelWidth, 20, config.enabled, toggleHover);
		y += 26;

		// Peso
		Draw.text(context, Text.translatable("palabraprohibida.punish.weight").getString(), x, y, Theme.TEXT);
		Draw.textRight(context, String.valueOf(config.weight), x + panelWidth, y, Theme.ACCENT);
		Draw.slider(context, sliderX, y, sliderWidth, 8, config.weight / 10.0,
				inside(mouseX, mouseY, sliderX, y - 4, sliderWidth, 16));
		y += 24;

		// Parametros
		for (int i = 0; i < params.size(); i++) {
			Param param = params.get(i);
			double value = config.value(param.key, param.min);
			double ratio = (value - param.min) / Math.max(0.0001, param.max - param.min);
			String label = Text.translatable("palabraprohibida.punish.param." + param.key).getString();
			Draw.text(context, label, x, y, Theme.TEXT_DIM);
			Draw.textRight(context, String.valueOf((int) Math.round(value)), x + panelWidth, y, Theme.TEXT);
			Draw.slider(context, sliderX, y, sliderWidth, 8, ratio,
					inside(mouseX, mouseY, sliderX, y - 4, sliderWidth, 16));
			y += 20;
		}
		if (type == PunishmentType.MOB) {
			boolean creeper = config.flag("creeper", false);
			Draw.text(context, Text.translatable("palabraprohibida.punish.param.creeper").getString(), x, y, Theme.TEXT_DIM);
			Draw.toggle(context, x, y - 3, panelWidth, 20, creeper, false);
			y += 24;
		}

		int buttonY = buttonY();
		boolean testHover = inside(mouseX, mouseY, x, buttonY, 100, 20);
		boolean backHover = inside(mouseX, mouseY, x + panelWidth - 100, buttonY, 100, 20);
		Draw.button(context, x, buttonY, 100, 20,
				Text.translatable("palabraprohibida.punish.test").getString(), testHover, !ClientState.admin);
		Draw.button(context, x + panelWidth - 100, buttonY, 100, 20,
				Text.translatable("palabraprohibida.punish.back").getString(), backHover, false);

		poll(mouseX, mouseY);
	}

	private void poll(int mouseX, int mouseY) {
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		boolean down = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		int panelWidth = Math.min(360, this.width - 40);
		int x = (this.width - panelWidth) / 2;
		PunishmentConfig config = ClientState.config.punishment(type);

		if (down && !mouseDown) {
			mouseDown = true;
			if (!ClientState.admin) {
				ClientState.pushNotice(Text.translatable("palabraprohibida.menu.admin_only").getString());
				// Volver siempre se permite.
				if (inside(mouseX, mouseY, x + panelWidth - 100, buttonY(), 100, 20)) {
					close();
				}
				return;
			}
			int y = top + 42;
			if (inside(mouseX, mouseY, x, y - 3, panelWidth, 20)) {
				// El interruptor devuelve el valor contrario solo cuando se hace clic.
				config.enabled = !config.enabled;
				sync();
				return;
			}
			y += 26;
			if (inside(mouseX, mouseY, sliderX - 4, y - 4, sliderWidth + 8, 16)) {
				draggingWeight = true;
				updateWeight(config, mouseX);
				return;
			}
			y += 24;
			for (int i = 0; i < params.size(); i++) {
				if (inside(mouseX, mouseY, sliderX - 4, y - 4, sliderWidth + 8, 16)) {
					draggingParam = i;
					updateParam(config, i, mouseX);
					return;
				}
				y += 20;
			}
			if (type == PunishmentType.MOB && inside(mouseX, mouseY, x, y - 3, panelWidth, 20)) {
				config.flags.put("creeper", !config.flag("creeper", false));
				sync();
				return;
			}
			int buttonY = buttonY();
			if (inside(mouseX, mouseY, x, buttonY, 100, 20)) {
				ClientNet.action("test_punishment", type.id());
				return;
			}
			if (inside(mouseX, mouseY, x + panelWidth - 100, buttonY, 100, 20)) {
				close();
			}
		} else if (!down) {
			if (mouseDown && (draggingWeight || draggingParam >= 0)) {
				ClientNet.sendConfig();
			}
			mouseDown = false;
			draggingWeight = false;
			draggingParam = -1;
		} else {
			if (draggingWeight) {
				updateWeight(config, mouseX);
			} else if (draggingParam >= 0) {
				updateParam(config, draggingParam, mouseX);
			}
		}
	}

	private void updateWeight(PunishmentConfig config, int mouseX) {
		double ratio = Math.max(0.0, Math.min(1.0, (mouseX - sliderX) / (double) Math.max(1, sliderWidth)));
		config.weight = (int) Math.round(ratio * 10);
	}

	private void updateParam(PunishmentConfig config, int index, int mouseX) {
		Param param = params.get(index);
		double ratio = Math.max(0.0, Math.min(1.0, (mouseX - sliderX) / (double) Math.max(1, sliderWidth)));
		double value = param.min + ratio * (param.max - param.min);
		value = Math.round(value / param.step) * param.step;
		config.values.put(param.key, Math.max(param.min, Math.min(param.max, value)));
	}

	private void sync() {
		ClientNet.sendConfig();
	}

	private boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}
}
