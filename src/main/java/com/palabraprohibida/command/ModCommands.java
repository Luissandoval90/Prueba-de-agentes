package com.palabraprohibida.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.palabraprohibida.Pp;
import com.palabraprohibida.compat.Compat;
import com.palabraprohibida.punish.PunishmentType;
import com.palabraprohibida.server.ServerRuntime;
import com.palabraprohibida.word.WordHash;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Comandos de administracion. Todos exigen permiso de admin.
 */
public final class ModCommands {
	private ModCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal(Pp.MOD_ID)
					.requires(Compat::isAdmin);

			root.then(CommandManager.literal("modo")
					.then(CommandManager.argument("modo", StringArgumentType.word())
							.suggests((context, builder) -> {
								builder.suggest("fija");
								builder.suggest("acumulativo");
								return builder.buildFuture();
							})
							.executes(context -> setMode(context.getSource(),
									StringArgumentType.getString(context, "modo")))));

			root.then(CommandManager.literal("palabra")
					.then(CommandManager.argument("jugador", EntityArgumentType.player())
							.then(CommandManager.argument("palabra", StringArgumentType.word())
									.executes(context -> setWord(context.getSource(),
											EntityArgumentType.getPlayer(context, "jugador"),
											StringArgumentType.getString(context, "palabra"))))));

			root.then(CommandManager.literal("nueva")
					.then(CommandManager.argument("jugador", EntityArgumentType.player())
							.executes(context -> newWord(context.getSource(),
									EntityArgumentType.getPlayer(context, "jugador")))));

			root.then(CommandManager.literal("castigo")
					.then(CommandManager.argument("tipo", StringArgumentType.word())
							.suggests((context, builder) -> {
								for (PunishmentType type : PunishmentType.values()) {
									builder.suggest(type.id());
								}
								return builder.buildFuture();
							})
							.executes(context -> punish(context.getSource(),
									StringArgumentType.getString(context, "tipo"), null))
							.then(CommandManager.argument("jugador", EntityArgumentType.player())
									.executes(context -> punish(context.getSource(),
											StringArgumentType.getString(context, "tipo"),
											EntityArgumentType.getPlayer(context, "jugador"))))));

			root.then(CommandManager.literal("activar").executes(context -> toggle(context.getSource(), true)));
			root.then(CommandManager.literal("desactivar").executes(context -> toggle(context.getSource(), false)));
			root.then(CommandManager.literal("reload").executes(context -> reload(context.getSource())));

			dispatcher.register(root);
		});
	}

	private static int setMode(ServerCommandSource source, String mode) {
		ServerRuntime runtime = ServerRuntime.get();
		if (runtime == null) {
			return 0;
		}
		if (!runtime.config().setMode(mode)) {
			source.sendError(Compat.text("palabraprohibida.command.bad_mode"));
			return 0;
		}
		runtime.state().mode = runtime.config().mode;
		runtime.state().nextAddAt = 0L;
		runtime.saveConfig();
		runtime.syncConfigAll();
		runtime.syncStateAll();
		source.sendFeedback(() -> Compat.text("palabraprohibida.command.mode", runtime.config().mode), true);
		return 1;
	}

	private static int setWord(ServerCommandSource source, ServerPlayerEntity target, String word) {
		ServerRuntime runtime = ServerRuntime.get();
		if (runtime == null) {
			return 0;
		}
		String normalized = WordHash.normalize(word);
		if (!WordHash.isWatchable(normalized)) {
			source.sendError(Compat.text("palabraprohibida.command.bad_word"));
			return 0;
		}
		runtime.state().setWord(target.getUuid(), normalized);
		runtime.markDirty();
		runtime.syncStateAll();
		source.sendFeedback(() -> Compat.text("palabraprohibida.command.word_set",
				target.getName().getString(), WordHash.displayWord(normalized)), true);
		return 1;
	}

	private static int newWord(ServerCommandSource source, ServerPlayerEntity target) {
		ServerRuntime runtime = ServerRuntime.get();
		if (runtime == null) {
			return 0;
		}
		String word = runtime.rotateWord(target.getUuid(), true);
		runtime.syncStateAll();
		source.sendFeedback(() -> Compat.text("palabraprohibida.command.word_new",
				target.getName().getString(), WordHash.displayWord(word)), true);
		return 1;
	}

	private static int punish(ServerCommandSource source, String typeId, ServerPlayerEntity target) {
		ServerRuntime runtime = ServerRuntime.get();
		if (runtime == null) {
			return 0;
		}
		PunishmentType type = PunishmentType.byId(typeId);
		if (type == null) {
			source.sendError(Compat.text("palabraprohibida.command.bad_punishment"));
			return 0;
		}
		ServerPlayerEntity victim = target;
		if (victim == null) {
			victim = source.getPlayer();
		}
		if (victim == null) {
			source.sendError(Compat.text("palabraprohibida.command.need_player"));
			return 0;
		}
		boolean applied = runtime.punisher().apply(victim, type);
		ServerPlayerEntity finalVictim = victim;
		source.sendFeedback(() -> Compat.text(applied ? "palabraprohibida.command.punished"
				: "palabraprohibida.command.punish_failed", finalVictim.getName().getString(),
				Compat.text(type.translationKey())), true);
		return applied ? 1 : 0;
	}

	private static int toggle(ServerCommandSource source, boolean enabled) {
		ServerRuntime runtime = ServerRuntime.get();
		if (runtime == null) {
			return 0;
		}
		List<PunishmentType> changed = new ArrayList<>();
		for (PunishmentType type : PunishmentType.values()) {
			runtime.config().punishment(type).enabled = enabled;
			changed.add(type);
		}
		runtime.saveConfig();
		runtime.syncConfigAll();
		source.sendFeedback(() -> Compat.text(enabled ? "palabraprohibida.command.enabled"
				: "palabraprohibida.command.disabled"), true);
		return changed.size();
	}

	private static int reload(ServerCommandSource source) {
		ServerRuntime runtime = ServerRuntime.get();
		if (runtime == null) {
			return 0;
		}
		runtime.reload();
		source.sendFeedback(() -> Compat.text("palabraprohibida.command.reloaded"), true);
		return 1;
	}
}
