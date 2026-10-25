package com.palabraprohibida.client.hud;

import com.palabraprohibida.client.ClientState;
import com.palabraprohibida.client.config.ClientConfig;
import com.palabraprohibida.client.ui.Draw;
import com.palabraprohibida.client.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.List;
import java.util.Map;

/**
 * Dibuja subtitulos, avisos, panel lateral y etiquetas de palabra.
 * Todo se calcula a partir de ClientState, que se actualiza con los paquetes.
 */
public final class HudOverlay implements HudElement {
	private static final int SUBTITLE_FADE_MS = 600;

	@Override
	public void render(DrawContext context, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.options.hudHidden) {
			return;
		}
		ClientState.tick();
		renderWordTags(context, client);
		renderSubtitles(context, client);
		renderNotices(context);
		renderPanel(context, client);
	}

	// ---------- Subtitulos ----------

	private void renderSubtitles(DrawContext context, MinecraftClient client) {
		ClientConfig config = ClientConfig.get();
		List<ClientState.Subtitle> snapshot = ClientState.subtitles;
		long now = System.currentTimeMillis();
		int centerX = (int) (config.subtitleX * client.getWindow().getScaledWidth());
		int baseY = (int) (config.subtitleY * client.getWindow().getScaledHeight());
		float scale = (float) config.subtitleSize;

		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(centerX, baseY);
		matrices.scale(scale, scale);

		int line = 0;
		synchronized (snapshot) {
		for (int i = snapshot.size() - 1; i >= 0 && line < 4; i--) {
			ClientState.Subtitle subtitle = snapshot.get(i);
			if (subtitle.own && !config.showOwnSubtitles) {
				continue;
			}
			if (!subtitle.own && !config.showOtherSubtitles) {
				continue;
			}
			String text = subtitle.text;
			if (!subtitle.own) {
				text = subtitle.speaker + ": " + text;
			}
			int color;
			if (subtitle.partial) {
				color = Theme.GREY;
			} else {
				long remaining = subtitle.expiresAt - now;
				float fade = remaining < SUBTITLE_FADE_MS ? Math.max(0f, remaining / (float) SUBTITLE_FADE_MS) : 1f;
				int alpha = (int) (255 * fade);
				color = (alpha << 24) | 0x00F2F2F2;
			}
			int y = -line * (Draw.fontHeight() + 3);
			Draw.textCenter(context, text, 0, y, color);
			line++;
		}
		}
		matrices.popMatrix();
	}

	// ---------- Avisos ----------

	private void renderNotices(DrawContext context) {
		List<ClientState.NoticeMsg> snapshot = ClientState.notices;
		ClientConfig config = ClientConfig.get();
		MinecraftClient client = MinecraftClient.getInstance();
		int centerX = (int) (config.noticeX * client.getWindow().getScaledWidth());
		int y = (int) (config.noticeY * client.getWindow().getScaledHeight());
		long now = System.currentTimeMillis();
		synchronized (snapshot) {
		for (ClientState.NoticeMsg notice : snapshot) {
			long remaining = notice.expiresAt - now;
			float fade = remaining < 500 ? Math.max(0f, remaining / 500f) : 1f;
			int alpha = (int) (200 * fade);
			int width = Draw.textWidth(notice.text) + 12;
			Draw.roundRect(context, centerX - width / 2, y, width, 14, 4, (alpha << 24) | 0x0018181B);
			Draw.textCenter(context, notice.text, centerX, y + 3, Theme.TEXT);
			y += 17;
		}
		}
	}

	// ---------- Panel lateral ----------

	private void renderPanel(DrawContext context, MinecraftClient client) {
		ClientConfig config = ClientConfig.get();
		if (!config.showPanel) {
			return;
		}
		int screenWidth = client.getWindow().getScaledWidth();
		int screenHeight = client.getWindow().getScaledHeight();
		int x = (int) (config.panelX * screenWidth);
		int y = (int) (config.panelY * screenHeight);
		int width = 128;

		if (!ClientState.accumulative) {
			return; // en modo fija las palabras van sobre la cabeza
		}
		int height = 58 + Math.min(5, ClientState.ranking.size()) * 11;
		drawPanelFrame(context, x, y, width, height);
		Draw.text(context, text("palabraprohibida.panel.words"), x + 6, y + 5, Theme.TEXT);
		Draw.textRight(context, ClientState.activeWords + "/" + ClientState.maxWords, x + width - 6, y + 5, Theme.ACCENT);

		String next = nextAddIn();
		Draw.text(context, text("palabraprohibida.panel.next"), x + 6, y + 18, Theme.TEXT_DIM);
		Draw.textRight(context, next, x + width - 6, y + 18, Theme.TEXT);

		Draw.text(context, text("palabraprohibida.panel.ranking"), x + 6, y + 32, Theme.TEXT_DIM);
		int line = 0;
		for (var entry : ClientState.ranking) {
			if (line >= 5) {
				break;
			}
			int lineY = y + 44 + line * 11;
			Draw.text(context, Draw.trim(entry.name, 74), x + 6, lineY, Theme.TEXT);
			Draw.textRight(context, String.valueOf(entry.count), x + width - 6, lineY, Theme.DANGER);
			line++;
		}
	}

	private void drawPanelFrame(DrawContext context, int x, int y, int width, int height) {
		Draw.roundRect(context, x + 1, y + 1, width, height, 4, Theme.SHADOW);
		Draw.roundRect(context, x, y, width, height, 4, Theme.withAlpha(Theme.PANEL, 190));
		Draw.roundRect(context, x, y, 2, height, 1, Theme.ACCENT_DIM);
	}

	private String nextAddIn() {
		if (ClientState.nextAddAt <= 0L) {
			return "--";
		}
		long remaining = Math.max(0, ClientState.nextAddAt - System.currentTimeMillis());
		long seconds = remaining / 1000;
		return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	// ---------- Etiqueta de palabra sobre la cabeza ----------

	private void renderWordTags(DrawContext context, MinecraftClient client) {
		ClientConfig config = ClientConfig.get();
		if (!config.showWordTags || ClientState.accumulative || ClientState.words.isEmpty()) {
			return;
		}
		ClientPlayerEntity self = client.player;
		for (PlayerEntity player : client.world.getPlayers()) {
			if (player == self) {
				continue;
			}
			String word = ClientState.words.get(player.getName().getString());
			if (word == null || word.isEmpty()) {
				continue;
			}
			Vec3d head = player.getPos().add(0.0, player.getHeight() + 0.55, 0.0);
			float[] screen = project(client, head);
			if (screen == null) {
				continue;
			}
			int width = Draw.textWidth(word) + 8;
			int x = (int) screen[0] - width / 2;
			int y = (int) screen[1];
			Draw.roundRect(context, x, y, width, 13, 4, Theme.withAlpha(Theme.BACKGROUND, 170));
			Draw.textCenter(context, word, x + width / 2, y + 3, Theme.ACCENT);
		}
	}

	/**
	 * Proyeccion a pantalla usando el ojo del jugador. Devuelve null si el punto
	 * esta detras de la camara.
	 */
	private float[] project(MinecraftClient client, Vec3d target) {
		ClientPlayerEntity self = client.player;
		if (self == null) {
			return null;
		}
		Vec3d eye = self.getEyePos();
		Vec3d relative = target.subtract(eye);
		double yaw = Math.toRadians(-self.getYaw());
		double pitch = Math.toRadians(self.getPitch());

		double cosYaw = Math.cos(yaw);
		double sinYaw = Math.sin(yaw);
		double x1 = relative.x * cosYaw - relative.z * sinYaw;
		double z1 = relative.x * sinYaw + relative.z * cosYaw;

		double cosPitch = Math.cos(pitch);
		double sinPitch = Math.sin(pitch);
		double y2 = relative.y * cosPitch - z1 * sinPitch;
		double z2 = relative.y * sinPitch + z1 * cosPitch;

		if (z2 <= 0.05) {
			return null; // detras
		}
		double fov = 70.0;
		double screenWidth = client.getWindow().getScaledWidth();
		double screenHeight = client.getWindow().getScaledHeight();
		double scale = 1.0 / Math.tan(Math.toRadians(fov / 2.0));
		double screenX = screenWidth / 2.0 + (x1 / z2) * scale * (screenHeight / 2.0);
		double screenY = screenHeight / 2.0 - (y2 / z2) * scale * (screenHeight / 2.0);
		if (screenX < -200 || screenX > screenWidth + 200 || screenY < -100 || screenY > screenHeight + 100) {
			return null;
		}
		return new float[]{(float) screenX, (float) screenY};
	}

	private static String text(String key) {
		return Text.translatable(key).getString();
	}

	/** Utilidad para el editor de posiciones. */
	public static Map<String, double[]> anchors() {
		ClientConfig config = ClientConfig.get();
		return Map.of(
				"subtitles", new double[]{config.subtitleX, config.subtitleY},
				"panel", new double[]{config.panelX, config.panelY},
				"notices", new double[]{config.noticeX, config.noticeY}
		);
	}
}
