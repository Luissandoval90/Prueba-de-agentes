package com.palabraprohibida.client.ui;

import com.palabraprohibida.client.config.ClientConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Editor a pantalla completa: se arrastran los subtitulos, el panel lateral y los
 * avisos. Las posiciones se guardan como fraccion de pantalla.
 */
public class OverlayEditorScreen extends Screen {
	private static final int NONE = 0;
	private static final int SUBTITLES = 1;
	private static final int PANEL = 2;
	private static final int NOTICES = 3;

	private final Screen parent;
	private int dragging = NONE;
	private double dragOffsetX;
	private double dragOffsetY;
	private boolean mouseDown;
	private boolean doneHover;
	private boolean resetHover;

	public OverlayEditorScreen(Screen parent) {
		super(Text.translatable("palabraprohibida.editor.title"));
		this.parent = parent;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, this.width, this.height, Theme.OVERLAY_BG);
		// Guias de centro.
		context.fill(this.width / 2, 0, this.width / 2 + 1, this.height, Theme.withAlpha(Theme.ACCENT, 60));
		context.fill(0, this.height / 2, this.width, this.height / 2 + 1, Theme.withAlpha(Theme.ACCENT, 60));

		ClientConfig config = ClientConfig.get();
		String subtitleText = Text.translatable("palabraprohibida.editor.sample_subtitle").getString();
		String panelTitle = Text.translatable("palabraprohibida.panel.words").getString();
		String noticeText = Text.translatable("palabraprohibida.editor.sample_notice").getString();

		// Subtitulos
		int subtitleWidth = Draw.textWidth(subtitleText) + 12;
		int subtitleX = (int) (config.subtitleX * this.width) - subtitleWidth / 2;
		int subtitleY = (int) (config.subtitleY * this.height);
		Draw.roundRect(context, subtitleX, subtitleY, subtitleWidth, 16, 4, Theme.withAlpha(Theme.CARD, 200));
		Draw.textCenter(context, subtitleText, subtitleX + subtitleWidth / 2, subtitleY + 4, Theme.TEXT);
		Draw.text(context, Text.translatable("palabraprohibida.editor.subtitles").getString(),
				subtitleX, subtitleY - 12, Theme.TEXT_FAINT);

		// Panel
		int panelX = (int) (config.panelX * this.width);
		int panelY = (int) (config.panelY * this.height);
		Draw.roundRect(context, panelX, panelY, 128, 52, 4, Theme.withAlpha(Theme.PANEL, 220));
		Draw.text(context, panelTitle, panelX + 6, panelY + 5, Theme.ACCENT);
		Draw.text(context, "4/10", panelX + 6, panelY + 20, Theme.TEXT_DIM);
		Draw.text(context, Text.translatable("palabraprohibida.editor.panel").getString(),
				panelX, panelY - 12, Theme.TEXT_FAINT);

		// Avisos
		int noticeWidth = Draw.textWidth(noticeText) + 12;
		int noticeX = (int) (config.noticeX * this.width) - noticeWidth / 2;
		int noticeY = (int) (config.noticeY * this.height);
		Draw.roundRect(context, noticeX, noticeY, noticeWidth, 16, 4, Theme.withAlpha(Theme.CARD, 200));
		Draw.textCenter(context, noticeText, noticeX + noticeWidth / 2, noticeY + 4, Theme.TEXT);
		Draw.text(context, Text.translatable("palabraprohibida.editor.notices").getString(),
				noticeX, noticeY - 12, Theme.TEXT_FAINT);

		// Botones
		int buttonY = this.height - 32;
		doneHover = inside(mouseX, mouseY, this.width / 2 - 110, buttonY, 100, 20);
		resetHover = inside(mouseX, mouseY, this.width / 2 + 10, buttonY, 100, 20);
		Draw.button(context, this.width / 2 - 110, buttonY, 100, 20,
				Text.translatable("palabraprohibida.editor.done").getString(), doneHover, false);
		Draw.button(context, this.width / 2 + 10, buttonY, 100, 20,
				Text.translatable("palabraprohibida.editor.reset").getString(), resetHover, false);
		Draw.textCenter(context, Text.translatable("palabraprohibida.editor.hint").getString(),
				this.width / 2, 12, Theme.TEXT_DIM);

		poll(mouseX, mouseY);
	}

	private void poll(int mouseX, int mouseY) {
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		boolean down = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		ClientConfig config = ClientConfig.get();
		if (down && !mouseDown) {
			mouseDown = true;
			int buttonY = this.height - 32;
			if (inside(mouseX, mouseY, this.width / 2 - 110, buttonY, 100, 20)) {
				close();
				return;
			}
			if (inside(mouseX, mouseY, this.width / 2 + 10, buttonY, 100, 20)) {
				config.subtitleX = 0.5;
				config.subtitleY = 0.78;
				config.panelX = 0.02;
				config.panelY = 0.28;
				config.noticeX = 0.5;
				config.noticeY = 0.12;
				ClientConfig.save();
				return;
			}
			dragging = pick(mouseX, mouseY, config);
			if (dragging != NONE) {
				double[] position = position(config, dragging);
				dragOffsetX = mouseX - position[0] * this.width;
				dragOffsetY = mouseY - position[1] * this.height;
			}
		} else if (down) {
			if (dragging == SUBTITLES) {
				config.subtitleX = clamp01((mouseX - dragOffsetX) / this.width);
				config.subtitleY = clamp01((mouseY - dragOffsetY) / this.height);
			} else if (dragging == PANEL) {
				config.panelX = clamp01((mouseX - dragOffsetX) / this.width);
				config.panelY = clamp01((mouseY - dragOffsetY) / this.height);
			} else if (dragging == NOTICES) {
				config.noticeX = clamp01((mouseX - dragOffsetX) / this.width);
				config.noticeY = clamp01((mouseY - dragOffsetY) / this.height);
			}
		} else {
			if (mouseDown && dragging != NONE) {
				ClientConfig.save();
			}
			mouseDown = false;
			dragging = NONE;
		}
	}

	private int pick(int mouseX, int mouseY, ClientConfig config) {
		int subtitleY = (int) (config.subtitleY * this.height);
		if (Math.abs(mouseY - subtitleY) <= 16) {
			return SUBTITLES;
		}
		int panelX = (int) (config.panelX * this.width);
		int panelY = (int) (config.panelY * this.height);
		if (inside(mouseX, mouseY, panelX, panelY, 128, 52)) {
			return PANEL;
		}
		int noticeY = (int) (config.noticeY * this.height);
		if (Math.abs(mouseY - noticeY) <= 16) {
			return NOTICES;
		}
		return NONE;
	}

	private double[] position(ClientConfig config, int which) {
		return switch (which) {
			case SUBTITLES -> new double[]{config.subtitleX, config.subtitleY};
			case PANEL -> new double[]{config.panelX, config.panelY};
			default -> new double[]{config.noticeX, config.noticeY};
		};
	}

	private double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	@Override
	public void close() {
		ClientConfig.save();
		MinecraftClient.getInstance().setScreen(parent);
	}
}
