package com.palabraprohibida.server;

import com.palabraprohibida.Pp;
import com.palabraprohibida.punish.PunishmentType;
import com.palabraprohibida.word.WordHash;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/**
 * Acciones puntuales que llegan desde el menu (paquete Action).
 * Todas se validan en el servidor: el cliente no manda nada de fiar.
 */
public final class Actions {
	private Actions() {
	}

	public static void handle(ServerPlayerEntity player, String action, String data) {
		ServerRuntime runtime = ServerRuntime.get();
		if (runtime == null) {
			return;
		}
		boolean admin = runtime.isAdmin(player);

		switch (action) {
			case "new_word" -> {
				if (!admin) {
					runtime.sendNotice(player, "palabraprohibida.notice.admin_only");
					return;
				}
				UUID target = parseUuid(data);
				ServerPlayerEntity targetPlayer = target == null ? player : resolver(runtime, target);
				if (targetPlayer == null) {
					return;
				}
				String word = runtime.rotateWord(targetPlayer.getUuid(), true);
				runtime.sendNotice(targetPlayer, "palabraprohibida.notice.new_word",
						WordHash.displayWord(word));
				runtime.syncStateAll();
				runtime.markDirty();
			}
			case "reset_counters" -> {
				if (!admin) {
					runtime.sendNotice(player, "palabraprohibida.notice.admin_only");
					return;
				}
				runtime.state().counters.clear();
				runtime.state().punishCounts.clear();
				runtime.markDirty();
				runtime.sendNotice(player, "palabraprohibida.notice.counters_reset");
				runtime.syncStateAll();
			}
			case "test_punishment" -> {
				if (!admin) {
					runtime.sendNotice(player, "palabraprohibida.notice.admin_only");
					return;
				}
				PunishmentType type = PunishmentType.byId(data);
				if (type == null) {
					return;
				}
				runtime.punisher().apply(player, type);
			}
			case "punish" -> {
				if (!admin) {
					runtime.sendNotice(player, "palabraprohibida.notice.admin_only");
					return;
				}
				String[] parts = data == null ? new String[0] : data.split(";", 2);
				PunishmentType type = PunishmentType.byId(parts.length > 0 ? parts[0] : "");
				if (type == null) {
					return;
				}
				ServerPlayerEntity target = parts.length > 1 ? runtime.server().getPlayerManager().getPlayer(parts[1]) : player;
				if (target != null) {
					runtime.punisher().apply(target, type);
				}
			}
			case "add_zone" -> {
				if (!admin) {
					return;
				}
				runtime.markDirty();
				runtime.sendNotice(player, "palabraprohibida.notice.zone_saved");
			}
			case "open_request" -> runtime.sendConfigTo(player);
			default -> Pp.LOG.debug("Accion desconocida: {}", action);
		}
	}

	private static UUID parseUuid(String data) {
		try {
			return UUID.fromString(data);
		} catch (Exception e) {
			return null;
		}
	}

	private static ServerPlayerEntity resolver(ServerRuntime runtime, UUID id) {
		return runtime.server().getPlayerManager().getPlayer(id);
	}
}
