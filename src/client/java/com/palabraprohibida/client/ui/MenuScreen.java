package com.palabraprohibida.client.ui;

import com.palabraprohibida.client.ClientNet;
import com.palabraprohibida.client.ClientState;
import com.palabraprohibida.client.config.ClientConfig;
import com.palabraprohibida.compat.Compat;
import com.palabraprohibida.config.PunishmentConfig;
import com.palabraprohibida.config.ServerConfig;
import com.palabraprohibida.punish.PunishmentType;
import com.palabraprohibida.voice.ModelManager;
import com.palabraprohibida.voice.SpeechEngine;
import com.palabraprohibida.word.WordHash;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Menu principal en modo inmediato:
 * - Los controles se construyen al cambiar de seccion (nunca por fotograma).
 * - El raton se sondea con GLFW (glfwGetMouseButton), sin depender de las firmas
 *   de Click/KeyInput de 1.21.9+.
 * - El texto libre usa TextFieldWidget de vanilla.
 */
public class MenuScreen extends Screen {
	private static final int SIDEBAR_WIDTH = 118;
	private static final int ROW_HEIGHT = 24;
	private static final String[] SECTION_KEYS = {
			"palabraprohibida.menu.section.home",
			"palabraprohibida.menu.section.mode",
			"palabraprohibida.menu.section.punishments",
			"palabraprohibida.menu.section.words",
			"palabraprohibida.menu.section.subtitles",
			"palabraprohibida.menu.section.mic",
			"palabraprohibida.menu.section.settings"
	};

	private enum Section {
		HOME, MODE, PUNISHMENTS, WORDS, SUBTITLES, MICROPHONE, SETTINGS
	}

	private static final class Row {
		private static final int TOGGLE = 0;
		private static final int SLIDER = 1;
		private static final int DROPDOWN = 2;
		private static final int BUTTON = 3;
		private static final int LABEL = 4;
		private static final int INFO = 5;

		private int type;
		private String label = "";
		private String value = "";
		private int color = Theme.TEXT_DIM;
		private int y;
		private int height = ROW_HEIGHT;
		private boolean adminOnly;
		private BooleanSupplier boolGetter;
		private Consumer<Boolean> boolSetter;
		private DoubleSupplier doubleGetter;
		private DoubleConsumer doubleSetter;
		private double min;
		private double max;
		private double step = 0.01;
		private IntSupplier indexGetter;
		private IntConsumer indexSetter;
		private List<String> options = List.of();
		private Runnable action;
	}

	private Section section = Section.HOME;
	private final List<Row> rows = new ArrayList<>();
	private TextFieldWidget input;
	private String inputPurpose = "";
	private double scroll;
	private double scrollTarget;
	private float indicatorY = -1f;
	private long lastFrameNanos = System.nanoTime();
	private boolean mouseDown;
	private Row activeSlider;
	private boolean confirmReset;
	private long confirmResetAt;

	public MenuScreen() {
		super(Text.translatable("palabraprohibida.menu.title"));
	}

	@Override
	protected void init() {
		rows.clear();
		buildRows();
		input = new TextFieldWidget(this.textRenderer, 0, 0, 120, 18, Text.translatable("palabraprohibida.menu.words.add"));
		input.setMaxLength(40);
		input.setVisible(false);
		input.setDrawsBackground(true);
		addDrawableChild(input);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// ---------- Construccion de controles ----------

	private void buildRows() {
		rows.clear();
		int[] y = {0};
		switch (section) {
			case HOME -> {
				label(y, "palabraprohibida.menu.home.status");
				info(y, "palabraprohibida.menu.home.mic", micStatus());
				info(y, "palabraprohibida.menu.home.model", modelStatus());
				info(y, "palabraprohibida.menu.home.mode", modeLabel());
				info(y, "palabraprohibida.menu.home.words", activeWordsLabel());
				info(y, "palabraprohibida.menu.home.role", ClientState.admin
						? text("palabraprohibida.menu.role.admin")
						: text("palabraprohibida.menu.role.player"));
				MinecraftClient client = MinecraftClient.getInstance();
				if (client.player != null && (Compat.isCreative(client.player) || Compat.isSpectator(client.player))) {
					Row row = row(y, Row.INFO);
					row.value = text("palabraprohibida.menu.home.creative_warning");
					row.color = Theme.DANGER;
					row.height = 28;
				}
				if (!ClientNet.canSend()) {
					Row row = row(y, Row.INFO);
					row.value = text("palabraprohibida.menu.home.no_server");
					row.color = Theme.DANGER;
					row.height = 28;
				}
			}
			case MODE -> {
				label(y, "palabraprohibida.menu.mode.title");
				Row options = row(y, Row.BUTTON);
				options.label = "palabraprohibida.menu.mode.choose";
				options.value = modeLabel();
				options.action = () -> {
					String next = ClientState.accumulative ? "fija" : "acumulativo";
					ClientState.config.mode = next;
					ClientNet.sendConfig();
				};
				options.adminOnly = true;
				if (ClientState.accumulative) {
					slider(y, "palabraprohibida.menu.mode.interval", 15, 600, 15,
							() -> ClientState.config.addIntervalSeconds,
							value -> ClientState.config.addIntervalSeconds = (int) Math.round(value));
					slider(y, "palabraprohibida.menu.mode.max_words", 1, 40, 1,
							() -> ClientState.config.maxWords,
							value -> ClientState.config.maxWords = (int) Math.round(value));
					slider(y, "palabraprohibida.menu.mode.ttl", 5, 240, 5,
							() -> ClientState.config.wordTtlMinutes,
							value -> ClientState.config.wordTtlMinutes = (int) Math.round(value));
					dropdown(y, "palabraprohibida.menu.mode.reveal", List.of(
							text("palabraprohibida.menu.mode.reveal.public"),
							text("palabraprohibida.menu.mode.reveal.private")),
							() -> ClientState.config.revealMode,
							index -> ClientState.config.revealMode = index);
				} else {
					toggle(y, "palabraprohibida.menu.mode.owner_sees", () -> ClientState.config.ownerSeesOwnWord,
							value -> ClientState.config.ownerSeesOwnWord = value);
					slider(y, "palabraprohibida.menu.mode.rotation", 0, 120, 5,
							() -> ClientState.config.rotationMinutes,
							value -> ClientState.config.rotationMinutes = (int) Math.round(value));
					Row newWord = row(y, Row.BUTTON);
					newWord.label = "palabraprohibida.menu.mode.new_word";
					newWord.value = "";
					newWord.action = () -> ClientNet.action("new_word", "");
					newWord.adminOnly = true;
				}
			}
			case PUNISHMENTS -> {
				// La cuadricula se dibuja aparte; solo dejamos la cabecera.
				label(y, "palabraprohibida.menu.punish.title");
			}
			case WORDS -> {
				label(y, "palabraprohibida.menu.words.title");
				if (ClientState.accumulative) {
					info(y, "palabraprohibida.menu.words.hidden",
							ClientState.activeWords + "/" + ClientState.maxWords);
				} else {
					for (var entry : ClientState.words.entrySet()) {
						Row row = row(y, Row.BUTTON);
						row.label = entry.getKey();
						row.value = entry.getValue().isEmpty()
								? text("palabraprohibida.menu.words.new")
								: entry.getValue();
						String owner = ClientState.wordOwners.get(entry.getKey());
						row.action = () -> ClientNet.action("new_word", owner == null ? "" : owner);
						row.adminOnly = true;
					}
					Row newWord = row(y, Row.BUTTON);
					newWord.label = "palabraprohibida.menu.words.new";
					newWord.action = () -> ClientNet.action("new_word", "");
					newWord.adminOnly = true;
				}
				label(y, "palabraprohibida.menu.words.fallback");
				Row addFallback = row(y, Row.BUTTON);
				addFallback.label = "palabraprohibida.menu.words.add";
				addFallback.action = () -> focusInput("fallback");
				addFallback.adminOnly = true;
				Row reset = row(y, Row.BUTTON);
				reset.label = "palabraprohibida.menu.words.reset";
				reset.action = this::requestReset;
				reset.adminOnly = true;
			}
			case SUBTITLES -> {
				label(y, "palabraprohibida.menu.subs.title");
				toggle(y, "palabraprohibida.menu.subs.own", () -> ClientConfig.get().showOwnSubtitles,
						value -> ClientConfig.get().showOwnSubtitles = value);
				toggle(y, "palabraprohibida.menu.subs.others", () -> ClientConfig.get().showOtherSubtitles,
						value -> ClientConfig.get().showOtherSubtitles = value);
				slider(y, "palabraprohibida.menu.subs.size", 0.6, 2.0, 0.05,
						() -> ClientConfig.get().subtitleSize,
						value -> ClientConfig.get().subtitleSize = value);
				slider(y, "palabraprohibida.menu.subs.duration", 1000, 10000, 500,
						() -> ClientConfig.get().subtitleDurationMs,
						value -> ClientConfig.get().subtitleDurationMs = (int) Math.round(value));
				Row editor = row(y, Row.BUTTON);
				editor.label = "palabraprohibida.menu.subs.move";
				editor.action = () -> MinecraftClient.getInstance().setScreen(new OverlayEditorScreen(this));
				toggle(y, "palabraprohibida.menu.subs.panel", () -> ClientConfig.get().showPanel,
						value -> ClientConfig.get().showPanel = value);
				toggle(y, "palabraprohibida.menu.subs.tags", () -> ClientConfig.get().showWordTags,
						value -> ClientConfig.get().showWordTags = value);
			}
			case MICROPHONE -> {
				label(y, "palabraprohibida.menu.mic.title");
				info(y, "palabraprohibida.menu.mic.status", micStatus());
				Row level = row(y, Row.INFO);
				level.label = "palabraprohibida.menu.mic.level";
				level.height = 20;
				dropdown(y, "palabraprohibida.menu.mic.model", List.of(
						text("palabraprohibida.menu.mic.model.fast"),
						text("palabraprohibida.menu.mic.model.precise")),
						() -> ModelManager.normalizeChoice(ClientConfig.get().model).equals(ModelManager.BIG) ? 1 : 0,
						index -> {
							ClientConfig.get().model = index == 1 ? "preciso" : "rapido";
							SpeechEngine.get().start(ClientConfig.get().model, null);
						});
				slider(y, "palabraprohibida.menu.mic.confidence", 0.0, 1.0, 0.05,
						() -> ClientConfig.get().minConfidence,
						value -> ClientConfig.get().minConfidence = value);
				toggle(y, "palabraprohibida.menu.mic.quick", () -> ClientConfig.get().quickDetection,
						value -> ClientConfig.get().quickDetection = value);
				Row test = row(y, Row.BUTTON);
				test.label = "palabraprohibida.menu.mic.test";
				test.value = ClientState.engineStatus;
				test.action = () -> ClientState.pushNotice(text("palabraprohibida.menu.mic.test_hint"));
			}
			case SETTINGS -> {
				label(y, "palabraprohibida.menu.settings.title");
				toggle(y, "palabraprohibida.menu.settings.animations", () -> ClientConfig.get().animations,
						value -> ClientConfig.get().animations = value);
				toggle(y, "palabraprohibida.menu.settings.meter", () -> ClientConfig.get().micMeter,
						value -> ClientConfig.get().micMeter = value);
				Row resetPositions = row(y, Row.BUTTON);
				resetPositions.label = "palabraprohibida.menu.settings.reset_positions";
				resetPositions.action = () -> {
					ClientConfig cfg = ClientConfig.get();
					cfg.subtitleX = 0.5;
					cfg.subtitleY = 0.78;
					cfg.panelX = 0.02;
					cfg.panelY = 0.28;
					cfg.noticeX = 0.5;
					cfg.noticeY = 0.12;
					ClientConfig.save();
				};
			}
		}
	}

	private Row row(int[] y, int type) {
		Row row = new Row();
		row.type = type;
		row.y = y[0];
		y[0] += ROW_HEIGHT + 4;
		rows.add(row);
		return row;
	}

	private void label(int[] y, String key) {
		Row row = row(y, Row.LABEL);
		row.label = key;
		row.height = 20;
	}

	private void info(int[] y, String labelKey, String value) {
		Row row = row(y, Row.INFO);
		row.label = labelKey;
		row.value = value;
		row.height = 18;
	}

	private void toggle(int[] y, String labelKey, BooleanSupplier getter, Consumer<Boolean> setter) {
		Row row = row(y, Row.TOGGLE);
		row.label = labelKey;
		row.boolGetter = getter;
		row.boolSetter = setter;
		row.adminOnly = !isLocal(labelKey);
	}

	private void slider(int[] y, String labelKey, double min, double max, double step,
			DoubleSupplier getter, DoubleConsumer setter) {
		Row row = row(y, Row.SLIDER);
		row.label = labelKey;
		row.min = min;
		row.max = max;
		row.step = step;
		row.doubleGetter = getter;
		row.doubleSetter = setter;
		row.adminOnly = !isLocal(labelKey);
	}

	private void dropdown(int[] y, String labelKey, List<String> options, IntSupplier getter, IntConsumer setter) {
		Row row = row(y, Row.DROPDOWN);
		row.label = labelKey;
		row.options = options;
		row.indexGetter = getter;
		row.indexSetter = setter;
		row.adminOnly = !isLocal(labelKey);
	}

	private static boolean isLocal(String labelKey) {
		return labelKey.startsWith("palabraprohibida.menu.subs")
				|| labelKey.startsWith("palabraprohibida.menu.settings")
				|| labelKey.startsWith("palabraprohibida.menu.mic");
	}

	// ---------- Render ----------

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float frameDelta = Math.min(0.1f, (now - lastFrameNanos) / 1_000_000_000.0f);
		lastFrameNanos = now;

		context.fill(0, 0, this.width, this.height, Theme.BACKGROUND);

		boolean animate = ClientConfig.get().animations;
		// Scroll con inercia.
		scroll += (scrollTarget - scroll) * (animate ? Math.min(1f, frameDelta * 12f) : 1f);

		renderSidebar(context, mouseX, mouseY, frameDelta, animate);
		renderHeader(context);

		int contentX = SIDEBAR_WIDTH + 16;
		int contentWidth = this.width - contentX - 16;
		int top = 52;

		context.enableScissor(contentX, top, contentX + contentWidth, this.height - 8);
		if (section == Section.PUNISHMENTS) {
			renderPunishmentGrid(context, contentX, top, contentWidth, mouseX, mouseY);
		} else {
			renderRows(context, contentX, top, contentWidth, mouseX, mouseY);
		}
		context.disableScissor();
		renderScrollbar(context, contentX, top, contentWidth);

		if (section == Section.WORDS) {
			renderChips(context, contentX, top, contentWidth);
		}
		if (section == Section.SUBTITLES) {
			renderPresets(context, contentX, top, contentWidth, mouseX, mouseY);
		}

		renderInput(context);
		renderFooter(context);

		pollMouse(mouseX, mouseY);
	}

	private void renderSidebar(DrawContext context, int mouseX, int mouseY, float delta, boolean animate) {
		context.fill(0, 0, SIDEBAR_WIDTH, this.height, Theme.SIDEBAR);
		context.fill(SIDEBAR_WIDTH - 1, 0, SIDEBAR_WIDTH, this.height, Theme.BORDER_SOFT);
		Draw.text(context, text("palabraprohibida.menu.title"), 12, 16, Theme.TEXT);
		Draw.text(context, text("palabraprohibida.menu.subtitle"), 12, 28, Theme.TEXT_FAINT);

		int itemTop = 52;
		int itemHeight = 26;
		int target = itemTop + section.ordinal() * itemHeight;
		if (indicatorY < 0f || !animate) {
			indicatorY = target;
		} else {
			indicatorY += (target - indicatorY) * Math.min(1f, delta * 14f);
		}
		Draw.roundRect(context, 6, Math.round(indicatorY), SIDEBAR_WIDTH - 12, itemHeight - 2, 4, Theme.PANEL);
		Draw.roundRect(context, 6, Math.round(indicatorY), 2, itemHeight - 2, 1, Theme.ACCENT);

		for (int i = 0; i < SECTION_KEYS.length; i++) {
			int y = itemTop + i * itemHeight;
			boolean hover = mouseX >= 6 && mouseX <= SIDEBAR_WIDTH - 6 && mouseY >= y && mouseY <= y + itemHeight - 2;
			Draw.icon(context, i, 14, y + 5);
			int color = i == section.ordinal() ? Theme.TEXT : (hover ? Theme.TEXT : Theme.TEXT_DIM);
			Draw.text(context, text(SECTION_KEYS[i]), 36, y + 9, color);
		}
	}

	private void renderHeader(DrawContext context) {
		int x = SIDEBAR_WIDTH + 16;
		Draw.text(context, text(SECTION_KEYS[section.ordinal()]), x, 20, Theme.TEXT);
		String chip = ClientState.accumulative ? "acumulativo" : "fija";
		int chipWidth = Draw.textWidth(chip) + 12;
		Draw.roundRect(context, this.width - 16 - chipWidth, 16, chipWidth, 14, 4, Theme.PANEL);
		Draw.text(context, chip, this.width - 16 - chipWidth + 6, 19, Theme.ACCENT);
		if (!ClientState.admin) {
			Draw.text(context, text("palabraprohibida.menu.locked"), x, 32, Theme.TEXT_FAINT);
		}
	}

	private void renderRows(DrawContext context, int x, int top, int width, int mouseX, int mouseY) {
		for (Row row : rows) {
			int y = (int) Math.round(top + row.y - scroll);
			if (y + row.height < top || y > this.height) {
				continue;
			}
			boolean hover = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + row.height;
			boolean locked = row.adminOnly && !ClientState.admin;
			switch (row.type) {
				case Row.LABEL -> Draw.text(context, text(row.label), x, y + 4, Theme.ACCENT);
				case Row.INFO -> {
					Draw.text(context, text(row.label), x, y + 4, Theme.TEXT_DIM);
					if ("palabraprohibida.menu.mic.level".equals(row.label)) {
						// Nivel en tiempo real.
						int barX = x + width / 2;
						int barWidth = width / 2 - 8;
						Draw.roundRect(context, barX, y + 6, barWidth, 4, 2, Theme.BORDER);
						int filled = (int) (barWidth * Math.max(0f, Math.min(1f, ClientState.micLevel)));
						if (filled > 0) {
							Draw.roundRect(context, barX, y + 6, filled, 4, 2, Theme.ACCENT);
						}
					} else {
						Draw.textRight(context, row.value == null ? "" : row.value, x + width, y + 4,
								row.color == Theme.TEXT_DIM ? Theme.TEXT : row.color);
					}
				}
				case Row.TOGGLE -> {
					Draw.text(context, text(row.label), x + 8, y + (row.height - Draw.fontHeight()) / 2,
							locked ? Theme.TEXT_FAINT : Theme.TEXT);
					Draw.toggle(context, x, y, width, row.height, row.boolGetter != null && row.boolGetter.getAsBoolean(),
							hover && !locked);
					if (locked) {
						Draw.text(context, text("palabraprohibida.menu.admin_only"), x + width / 2, y + row.height - 10,
								Theme.TEXT_FAINT);
					}
				}
				case Row.SLIDER -> {
					double value = row.doubleGetter == null ? 0 : row.doubleGetter.getAsDouble();
					double ratio = (value - row.min) / Math.max(0.0001, row.max - row.min);
					Draw.text(context, text(row.label), x + 8, y + 3, locked ? Theme.TEXT_FAINT : Theme.TEXT);
					Draw.textRight(context, formatValue(row, value), x + width - 6, y + 3, Theme.TEXT_DIM);
					Draw.slider(context, x + 8, y + 14, width - 16, 8, ratio, hover && !locked);
				}
				case Row.DROPDOWN -> {
					Draw.text(context, text(row.label), x + 8, y + (row.height - Draw.fontHeight()) / 2,
							locked ? Theme.TEXT_FAINT : Theme.TEXT);
					int index = row.indexGetter == null ? 0 : row.indexGetter.getAsInt();
					String current = index >= 0 && index < row.options.size() ? row.options.get(index) : "-";
					int boxWidth = 96;
					Draw.roundRect(context, x + width - boxWidth - 6, y + 3, boxWidth, row.height - 6, 4,
							hover && !locked ? Theme.CARD_HOVER : Theme.PANEL);
					Draw.textCenter(context, Draw.trim(current, boxWidth - 10),
							x + width - boxWidth / 2 - 6, y + (row.height - Draw.fontHeight()) / 2, Theme.TEXT);
				}
				case Row.BUTTON -> {
					Draw.text(context, text(row.label), x + 8, y + (row.height - Draw.fontHeight()) / 2,
							locked ? Theme.TEXT_FAINT : Theme.TEXT);
					int buttonWidth = Math.max(56, Draw.textWidth(row.value == null ? "" : row.value) + 20);
					Draw.button(context, x + width - buttonWidth - 6, y + 2, buttonWidth, row.height - 4,
							row.value == null ? "" : row.value, hover && !locked, locked);
				}
				default -> {
				}
			}
		}
	}

	private String formatValue(Row row, double value) {
		if (row.max <= 1.5) {
			return String.format(java.util.Locale.ROOT, "%.2f", value);
		}
		if (row.step >= 1.0) {
			return String.valueOf((int) Math.round(value));
		}
		return String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	private void renderPunishmentGrid(DrawContext context, int x, int top, int width, int mouseX, int mouseY) {
		int columns = 5;
		int cardWidth = Math.max(56, (width - (columns - 1) * 6) / columns);
		int cardHeight = 58;
		long now = System.currentTimeMillis();
		for (int i = 0; i < PunishmentType.values().length; i++) {
			PunishmentType type = PunishmentType.values()[i];
			int column = i % columns;
			int rowIndex = i / columns;
			int cardX = x + column * (cardWidth + 6);
			int cardY = (int) Math.round(top + rowIndex * (cardHeight + 6) - scroll + Math.min(60, (now - enterTime) / 6.0));
			if (cardY > this.height || cardY + cardHeight < top) {
				continue;
			}
			boolean hover = mouseX >= cardX && mouseX <= cardX + cardWidth && mouseY >= cardY
					&& mouseY <= cardY + cardHeight;
			PunishmentConfig config = ClientState.config.punishment(type);
			Draw.card(context, cardX, cardY, cardWidth, cardHeight, hover && ClientState.admin, false);
			Draw.item(context, type.iconItem(), cardX + cardWidth / 2 - 16, cardY + 8, 2.0f);
			Draw.textCenter(context, Draw.trim(text(type.translationKey()), cardWidth - 6),
					cardX + cardWidth / 2, cardY + 40, Theme.TEXT_DIM);
			Draw.dot(context, cardX + 6, cardY + 6, config.enabled ? Theme.ACCENT : Theme.BORDER);
			int barWidth = (int) (config.weight / 10.0 * (cardWidth - 12));
			Draw.roundRect(context, cardX + 6, cardY + cardHeight - 6, cardWidth - 12, 2, 1, Theme.BORDER);
			if (barWidth > 0) {
				Draw.roundRect(context, cardX + 6, cardY + cardHeight - 6, barWidth, 2, 1, Theme.ACCENT_DIM);
			}
		}
	}

	private long enterTime = System.currentTimeMillis();

	private void renderPresets(DrawContext context, int x, int top, int width, int mouseX, int mouseY) {
		// 9 posiciones predefinidas en una cuadricula 3x3, debajo de las filas.
		int startY = (int) Math.round(top + rowsHeight() - scroll + 8);
		int size = 24;
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				int px = x + column * (size + 6);
				int py = startY + row * (size + 6);
				boolean hover = mouseX >= px && mouseX <= px + size && mouseY >= py && mouseY <= py + size;
				Draw.roundRect(context, px, py, size, size, 3, hover ? Theme.CARD_HOVER : Theme.PANEL);
				Draw.roundRect(context, px + 8, py + 8, 8, 8, 2,
						hover ? Theme.ACCENT : Theme.BORDER);
			}
		}
		Draw.text(context, text("palabraprohibida.menu.subs.presets"), x + 3 * (size + 6) + 8, startY, Theme.TEXT_FAINT);
	}

	private int rowsHeight() {
		if (rows.isEmpty()) {
			return 0;
		}
		Row last = rows.get(rows.size() - 1);
		return last.y + last.height + 4;
	}

	private void renderChips(DrawContext context, int x, int top, int width) {
		int startY = (int) Math.round(top + rowsHeight() - scroll + 6);
		String title = text("palabraprohibida.menu.words.fallback_list");
		Draw.text(context, title, x, startY, Theme.TEXT_FAINT);
		int chipX = x;
		int chipY = startY + 14;
		for (String word : ClientState.config.fallbackWords) {
			int chipWidth = Draw.textWidth(word) + 12;
			if (chipX + chipWidth > x + width) {
				chipX = x;
				chipY += 18;
			}
			Draw.roundRect(context, chipX, chipY, chipWidth, 14, 4, Theme.PANEL);
			Draw.text(context, word, chipX + 6, chipY + 3, Theme.TEXT_DIM);
			chipX += chipWidth + 4;
		}
	}

	/** Barra de scroll discreta, solo si hay contenido fuera de la vista. */
	private void renderScrollbar(DrawContext context, int x, int top, int width) {
		int viewHeight = this.height - 8 - top;
		int contentHeight = rowsHeight() + (section == Section.PUNISHMENTS ? 320 : 0);
		if (contentHeight <= viewHeight) {
			return;
		}
		int barHeight = Math.max(20, (int) (viewHeight * (viewHeight / (double) contentHeight)));
		int barY = top + (int) ((viewHeight - barHeight) * (scroll / Math.max(1, contentHeight - viewHeight)));
		Draw.roundRect(context, x + width - 2, barY, 2, barHeight, 1, Theme.BORDER);
	}

	private void renderInput(DrawContext context) {
		if (input == null) {
			return;
		}
		boolean visible = section == Section.WORDS && !inputPurpose.isEmpty();
		input.setVisible(visible);
		if (!visible) {
			return;
		}
		int x = SIDEBAR_WIDTH + 16;
		int y = this.height - 46;
		input.setX(x);
		input.setY(y);
		input.setWidth(160);
		input.render(context, 0, 0, 0f);
		Draw.button(context, x + 168, y, 54, 18, text("palabraprohibida.menu.words.confirm"), false, false);
	}

	private void renderFooter(DrawContext context) {
		String hint = text("palabraprohibida.menu.footer");
		Draw.text(context, hint, SIDEBAR_WIDTH + 16, this.height - 18, Theme.TEXT_FAINT);
	}

	// ---------- Entrada ----------

	private void pollMouse(int mouseX, int mouseY) {
		long handle = MinecraftClient.getInstance().getWindow().getHandle();
		boolean down = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		if (down) {
			if (activeSlider != null) {
				applySlider(activeSlider, mouseX);
			} else if (!mouseDown) {
				onClick(mouseX, mouseY, false);
			}
		} else {
			if (activeSlider != null) {
				commitSlider(activeSlider);
				activeSlider = null;
			}
			mouseDown = false;
		}
	}

	private void onClick(int mouseX, int mouseY, boolean doubled) {
		mouseDown = true;
		if (mouseX < SIDEBAR_WIDTH) {
			int index = (mouseY - 52) / 26;
			if (index >= 0 && index < Section.values().length && mouseY >= 52) {
				Section next = Section.values()[index];
				if (next != section) {
					section = next;
					scroll = 0;
					scrollTarget = 0;
					enterTime = System.currentTimeMillis();
					inputPurpose = "";
					rows.clear();
					buildRows();
				}
			}
			return;
		}

		int x = SIDEBAR_WIDTH + 16;
		int width = this.width - x - 16;
		int top = 52;

		if (section == Section.PUNISHMENTS) {
			clickPunishmentGrid(x, top, width, mouseX, mouseY);
			return;
		}
		if (section == Section.SUBTITLES) {
			if (clickPresets(x, top, mouseX, mouseY)) {
				return;
			}
		}
		if (section == Section.WORDS && clickWords(x, top, width, mouseX, mouseY)) {
			return;
		}
		for (Row row : rows) {
			int y = (int) Math.round(top + row.y - scroll);
			if (mouseY < y || mouseY > y + row.height || mouseX < x || mouseX > x + width) {
				continue;
			}
			boolean locked = row.adminOnly && !ClientState.admin;
			switch (row.type) {
				case Row.TOGGLE -> {
					if (locked) {
						notifyLocked();
						return;
					}
					if (row.boolSetter != null) {
						// Ojo: click alterna; el valor lo decide el modelo, no el clic.
						row.boolSetter.accept(row.boolGetter != null && !row.boolGetter.getAsBoolean());
					}
					afterChange(row);
					return;
				}
				case Row.SLIDER -> {
					if (locked) {
						notifyLocked();
						return;
					}
					activeSlider = row;
					applySlider(row, mouseX);
					return;
				}
				case Row.DROPDOWN -> {
					if (locked) {
						notifyLocked();
						return;
					}
					int index = row.indexGetter == null ? 0 : row.indexGetter.getAsInt();
					int next = (index + 1) % Math.max(1, row.options.size());
					if (row.indexSetter != null) {
						row.indexSetter.accept(next);
					}
					afterChange(row);
					return;
				}
				case Row.BUTTON -> {
					if (locked) {
						notifyLocked();
						return;
					}
					if (row.action != null) {
						row.action.run();
					}
					return;
				}
				default -> {
				}
			}
		}
	}

	private void clickPunishmentGrid(int x, int top, int width, int mouseX, int mouseY) {
		int columns = 5;
		int cardWidth = Math.max(56, (width - (columns - 1) * 6) / columns);
		int cardHeight = 58;
		for (int i = 0; i < PunishmentType.values().length; i++) {
			int column = i % columns;
			int rowIndex = i / columns;
			int cardX = x + column * (cardWidth + 6);
			int cardY = (int) Math.round(top + rowIndex * (cardHeight + 6) - scroll + Math.min(60, (System.currentTimeMillis() - enterTime) / 6.0));
			if (mouseX >= cardX && mouseX <= cardX + cardWidth && mouseY >= cardY && mouseY <= cardY + cardHeight) {
				if (!ClientState.admin) {
					notifyLocked();
					return;
				}
				MinecraftClient.getInstance().setScreen(new PunishmentScreen(this, PunishmentType.values()[i]));
				return;
			}
		}
	}

	private boolean clickWords(int x, int top, int width, int mouseX, int mouseY) {
		// Chips de la lista de respaldo: clic para quitar.
		int startY = (int) Math.round(top + rowsHeight() - scroll + 6) + 14;
		int chipX = x;
		int chipY = startY;
		for (String word : new ArrayList<>(ClientState.config.fallbackWords)) {
			int chipWidth = Draw.textWidth(word) + 12;
			if (chipX + chipWidth > x + width) {
				chipX = x;
				chipY += 18;
			}
			if (mouseX >= chipX && mouseX <= chipX + chipWidth && mouseY >= chipY && mouseY <= chipY + 14) {
				if (!ClientState.admin) {
					notifyLocked();
					return true;
				}
				ClientState.config.fallbackWords.remove(word);
				ClientNet.sendConfig();
				return true;
			}
			chipX += chipWidth + 4;
		}
		// Campo de texto.
		if (input != null && input.isVisible() && mouseY >= input.getY() && mouseY <= input.getY() + 18) {
			if (mouseX >= input.getX() + 168 && mouseX <= input.getX() + 222) {
				String value = WordHash.normalize(input.getText());
				if (WordHash.isWatchable(value) && ClientState.admin) {
					ClientState.config.fallbackWords.add(value);
					input.setText("");
					inputPurpose = "";
					ClientNet.sendConfig();
				}
				return true;
			}
		}
		return false;
	}

	private boolean clickPresets(int x, int top, int mouseX, int mouseY) {
		int startY = (int) Math.round(top + rowsHeight() - scroll + 8);
		int size = 24;
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				int px = x + column * (size + 6);
				int py = startY + row * (size + 6);
				if (mouseX >= px && mouseX <= px + size && mouseY >= py && mouseY <= py + size) {
					ClientConfig config = ClientConfig.get();
					config.subtitleX = column == 0 ? 0.2 : (column == 1 ? 0.5 : 0.8);
					config.subtitleY = row == 0 ? 0.35 : (row == 1 ? 0.6 : 0.85);
					ClientConfig.save();
					return true;
				}
			}
		}
		return false;
	}

	private void applySlider(Row row, int mouseX) {
		int x = SIDEBAR_WIDTH + 16 + 8;
		int width = this.width - SIDEBAR_WIDTH - 16 - 16 - 16;
		double ratio = Math.max(0.0, Math.min(1.0, (mouseX - x) / (double) Math.max(1, width)));
		double value = row.min + ratio * (row.max - row.min);
		if (row.step >= 1.0) {
			value = Math.round(value / row.step) * row.step;
		} else {
			value = Math.round(value / row.step) * row.step;
		}
		value = Math.max(row.min, Math.min(row.max, value));
		if (row.doubleSetter != null) {
			row.doubleSetter.accept(value);
		}
		afterChange(row);
	}

	private void commitSlider(Row row) {
		afterChange(row);
		ClientConfig.save();
	}

	private void afterChange(Row row) {
		boolean serverSide = row.adminOnly || (row.indexSetter != null) || (row.doubleSetter != null);
		if (serverSide && ClientState.admin) {
			ClientNet.sendConfig();
		} else {
			ClientConfig.save();
		}
	}

	private void notifyLocked() {
		ClientState.pushNotice(text("palabraprohibida.menu.admin_only"));
	}

	private void requestReset() {
		long now = System.currentTimeMillis();
		if (confirmReset && now - confirmResetAt < 4000L) {
			ClientNet.action("reset_counters", "");
			confirmReset = false;
			return;
		}
		confirmReset = true;
		confirmResetAt = now;
		ClientState.pushNotice(text("palabraprohibida.menu.words.reset_confirm"));
	}

	private void focusInput(String purpose) {
		inputPurpose = purpose;
		if (input != null) {
			input.setVisible(true);
			input.setText("");
			setFocused(input);
			input.setFocused(true);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scrollTarget = Math.max(0, scrollTarget - verticalAmount * 24);
		return true;
	}

	@Override
	public void close() {
		ClientConfig.save();
		MinecraftClient.getInstance().setScreen(null);
	}

	// ---------- Textos ----------

	private String micStatus() {
		if (!ClientState.voiceConnected) {
			return text("palabraprohibida.menu.mic.disconnected");
		}
		if (ClientState.micMuted) {
			return text("palabraprohibida.menu.mic.muted");
		}
		return text("palabraprohibida.menu.mic.connected");
	}

	private String modelStatus() {
		String name = ModelManager.normalizeChoice(ClientConfig.get().model);
		if (ClientState.downloadProgress >= 0 && ClientState.downloadProgress < 1.0) {
			return text("palabraprohibida.menu.home.model_downloading") + " "
					+ Math.round(ClientState.downloadProgress * 100) + "%";
		}
		if (ModelManager.isReady(name)) {
			return ModelManager.displayName(name);
		}
		return text("palabraprohibida.menu.home.model_pending");
	}

	private String modeLabel() {
		return text(ClientState.accumulative ? "palabraprohibida.mode.accumulative" : "palabraprohibida.mode.fixed");
	}

	private String activeWordsLabel() {
		return ClientState.activeWords + "/" + ClientState.maxWords;
	}

	private static String text(String key) {
		return Text.translatable(key).getString();
	}

	/** Para que la pantalla de castigos pueda volver. */
	public void reopen() {
		MinecraftClient.getInstance().setScreen(this);
	}
}
