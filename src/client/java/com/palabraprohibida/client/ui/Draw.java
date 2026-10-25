package com.palabraprohibida.client.ui;

import com.palabraprohibida.Pp;
import com.palabraprohibida.compat.Compat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;

/**
 * Primitivas de dibujo. Todo lo que podria cambiar de version (matrices de joml,
 * pipelines de textura, drawItem escalado) esta aqui.
 */
public final class Draw {
	public static final Identifier ICONS = Pp.id("textures/gui/icons.png");
	/** Cache de iconos: construir un ItemStack por fotograma seria basura para el GC. */
	private static final java.util.Map<String, ItemStack> ITEM_CACHE = new java.util.HashMap<>();

	private Draw() {
	}

	private static MinecraftClient client() {
		return MinecraftClient.getInstance();
	}

	private static ItemStack itemStack(String itemId) {
		return ITEM_CACHE.computeIfAbsent(itemId, Compat::item);
	}

	public static void fill(DrawContext context, int x, int y, int width, int height, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}
		context.fill(x, y, x + width, y + height, color);
	}

	/** Rectangulo con esquinas recortadas (bordes redondeados planos). */
	public static void roundRect(DrawContext context, int x, int y, int width, int height, int radius, int color) {
		if (width <= 0 || height <= 0) {
			return;
		}
		int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
		if (r == 0) {
			fill(context, x, y, width, height, color);
			return;
		}
		fill(context, x, y + r, width, height - r * 2, color);
		for (int i = 0; i < r; i++) {
			int inset = r - (int) Math.sqrt((double) r * r - (double) (r - i - 1) * (r - i - 1));
			fill(context, x + inset, y + i, width - inset * 2, 1, color);
			fill(context, x + inset, y + height - 1 - i, width - inset * 2, 1, color);
		}
	}

	public static void shadow(DrawContext context, int x, int y, int width, int height) {
		roundRect(context, x + 2, y + 2, width, height, 5, Theme.SHADOW);
	}

	public static void card(DrawContext context, int x, int y, int width, int height, boolean hover, boolean active) {
		int color = active ? Theme.CARD_ACTIVE : (hover ? Theme.CARD_HOVER : Theme.CARD);
		shadow(context, x, y, width, height);
		roundRect(context, x, y, width, height, 5, color);
		roundRect(context, x, y, 1, height, 0, active ? Theme.ACCENT : Theme.BORDER_SOFT);
	}

	public static void text(DrawContext context, String value, int x, int y, int color) {
		context.drawText(client().textRenderer, value, x, y, color, false);
	}

	public static void text(DrawContext context, Text value, int x, int y, int color) {
		context.drawText(client().textRenderer, value, x, y, color, false);
	}

	public static void textShadow(DrawContext context, String value, int x, int y, int color) {
		context.drawText(client().textRenderer, value, x, y, color, true);
	}

	public static int textWidth(String value) {
		return client().textRenderer.getWidth(value);
	}

	public static int fontHeight() {
		return client().textRenderer.fontHeight;
	}

	public static void textRight(DrawContext context, String value, int right, int y, int color) {
		text(context, value, right - textWidth(value), y, color);
	}

	public static void textCenter(DrawContext context, String value, int centerX, int y, int color) {
		text(context, value, centerX - textWidth(value) / 2, y, color);
	}

	/** Texto recortado con puntos suspensivos. */
	public static String trim(String value, int maxWidth) {
		if (textWidth(value) <= maxWidth) {
			return value;
		}
		String ellipsis = "...";
		int limit = maxWidth - textWidth(ellipsis);
		if (limit <= 0) {
			return "";
		}
		return client().textRenderer.trimToWidth(value, limit) + ellipsis;
	}

	/** Icono de item escalado (escala 2 por defecto en la cuadricula). */
	public static void item(DrawContext context, String itemId, int x, int y, float scale) {
		ItemStack stack = itemStack(itemId);
		if (stack.isEmpty()) {
			roundRect(context, x, y, 16, 16, 3, Theme.BORDER);
			return;
		}
		Matrix3x2fStack matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(x, y);
		matrices.scale(scale, scale);
		context.drawItem(stack, 0, 0);
		matrices.popMatrix();
	}

	/** Icono del mapa de bits propio (16x16 por celda). */
	public static void icon(DrawContext context, int index, int x, int y) {
		int u = (index % 4) * 16;
		int v = (index / 4) * 16;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, ICONS, x, y, u, v, 16, 16, 64, 64);
	}

	// ---------- Controles ----------

	public static void button(DrawContext context, int x, int y, int width, int height, String label,
			boolean hover, boolean disabled) {
		int color = disabled ? Theme.PANEL : (hover ? Theme.CARD_HOVER : Theme.CARD);
		roundRect(context, x, y, width, height, 4, color);
		roundRect(context, x, y, width, 1, 0, hover && !disabled ? Theme.ACCENT_DIM : Theme.BORDER_SOFT);
		int textColor = disabled ? Theme.TEXT_FAINT : Theme.TEXT;
		textCenter(context, label, x + width / 2, y + (height - fontHeight()) / 2, textColor);
	}

	public static void toggle(DrawContext context, int x, int y, int width, int height, boolean value, boolean hover) {
		int trackWidth = 26;
		int trackX = x + width - trackWidth - 4;
		int trackY = y + (height - 12) / 2;
		int track = value ? Theme.ACCENT_DIM : Theme.BORDER;
		roundRect(context, trackX, trackY, trackWidth, 12, 6, track);
		int knobX = value ? trackX + trackWidth - 10 : trackX + 2;
		roundRect(context, knobX, trackY + 2, 8, 8, 4, hover ? Theme.WHITE : Theme.TEXT);
	}

	public static void slider(DrawContext context, int x, int y, int width, int height, double value,
			boolean hover) {
		int barY = y + (height - 4) / 2;
		roundRect(context, x, barY, width, 4, 2, Theme.BORDER);
		int filled = (int) Math.round((width - 6) * Math.max(0.0, Math.min(1.0, value)));
		roundRect(context, x, barY, filled + 3, 4, 2, Theme.ACCENT_DIM);
		int knobX = x + filled;
		roundRect(context, knobX, barY - 3, 6, 10, 3, hover ? Theme.WHITE : Theme.TEXT);
	}

	public static void dot(DrawContext context, int x, int y, int color) {
		roundRect(context, x, y, 5, 5, 2, color);
	}
}
