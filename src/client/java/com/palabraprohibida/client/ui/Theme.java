package com.palabraprohibida.client.ui;

/**
 * Paleta plana y sobria: grises neutros, un dorado apagado y un rojo apagado
 * solo para avisos. Sin degradados ni brillos.
 */
public final class Theme {
	public static final int BACKGROUND = 0xFF141416;
	public static final int SIDEBAR = 0xFF1B1B1E;
	public static final int PANEL = 0xFF202023;
	public static final int CARD = 0xFF26262A;
	public static final int CARD_HOVER = 0xFF2E2E33;
	public static final int CARD_ACTIVE = 0xFF33332F;
	public static final int BORDER = 0xFF3A3A40;
	public static final int BORDER_SOFT = 0xFF2E2E33;

	public static final int TEXT = 0xFFE4E4E6;
	public static final int TEXT_DIM = 0xFF9A9AA1;
	public static final int TEXT_FAINT = 0xFF6E6E75;

	public static final int ACCENT = 0xFFC2A14A;
	public static final int ACCENT_DIM = 0xFF7A6626;
	public static final int DANGER = 0xFFA9564D;

	public static final int SHADOW = 0x55000000;
	public static final int OVERLAY_BG = 0x99000000;
	public static final int WHITE = 0xFFFFFFFF;
	public static final int GREY = 0xFFAFAFB4;

	private Theme() {
	}

	public static int withAlpha(int color, int alpha) {
		return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
	}

	public static int lerp(int from, int to, float t) {
		t = Math.max(0f, Math.min(1f, t));
		int a = (int) (((from >> 24) & 0xFF) + (((to >> 24) & 0xFF) - ((from >> 24) & 0xFF)) * t);
		int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
		int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
		int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
