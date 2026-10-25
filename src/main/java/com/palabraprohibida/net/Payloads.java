package com.palabraprohibida.net;

import com.palabraprohibida.Pp;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

import java.util.UUID;

/**
 * Paquetes del mod. Todos viajan como texto JSON compacto para mantener el codigo
 * pequeño y el limite de 30 000 bytes bajo control.
 */
public final class Payloads {
	public static final int MAX_JSON = 28_000;
	public static final int MAX_TEXT = 512;

	private Payloads() {
	}

	// ---------- S2C ----------

	public record ConfigSync(String json) implements CustomPayload {
		public static final CustomPayload.Id<ConfigSync> ID = new CustomPayload.Id<>(Pp.id("config_sync"));
		public static final PacketCodec<RegistryByteBuf, ConfigSync> CODEC = PacketCodec.of(
				(value, buf) -> buf.writeString(value.json, MAX_JSON),
				buf -> new ConfigSync(buf.readString(MAX_JSON)));

		@Override
		public CustomPayload.Id<ConfigSync> getId() {
			return ID;
		}
	}

	public record State(String json) implements CustomPayload {
		public static final CustomPayload.Id<State> ID = new CustomPayload.Id<>(Pp.id("state"));
		public static final PacketCodec<RegistryByteBuf, State> CODEC = PacketCodec.of(
				(value, buf) -> buf.writeString(value.json, MAX_JSON),
				buf -> new State(buf.readString(MAX_JSON)));

		@Override
		public CustomPayload.Id<State> getId() {
			return ID;
		}
	}

	public record Punish(String type, String message) implements CustomPayload {
		public static final CustomPayload.Id<Punish> ID = new CustomPayload.Id<>(Pp.id("punish"));
		public static final PacketCodec<RegistryByteBuf, Punish> CODEC = PacketCodec.of(
				(value, buf) -> {
					buf.writeString(value.type, 64);
					buf.writeString(value.message, MAX_TEXT);
				},
				buf -> new Punish(buf.readString(64), buf.readString(MAX_TEXT)));

		@Override
		public CustomPayload.Id<Punish> getId() {
			return ID;
		}
	}

	public record Notice(String json) implements CustomPayload {
		public static final CustomPayload.Id<Notice> ID = new CustomPayload.Id<>(Pp.id("notice"));
		public static final PacketCodec<RegistryByteBuf, Notice> CODEC = PacketCodec.of(
				(value, buf) -> buf.writeString(value.json, 2048),
				buf -> new Notice(buf.readString(2048)));

		@Override
		public CustomPayload.Id<Notice> getId() {
			return ID;
		}
	}

	// ---------- C2S ----------

	public record SpokenWord(UUID speaker, String word) implements CustomPayload {
		public static final CustomPayload.Id<SpokenWord> ID = new CustomPayload.Id<>(Pp.id("spoken_word"));
		public static final PacketCodec<RegistryByteBuf, SpokenWord> CODEC = PacketCodec.of(
				(value, buf) -> {
					buf.writeUuid(value.speaker);
					buf.writeString(value.word, 96);
				},
				buf -> new SpokenWord(buf.readUuid(), buf.readString(96)));

		@Override
		public CustomPayload.Id<SpokenWord> getId() {
			return ID;
		}
	}

	public record Talk(String text) implements CustomPayload {
		public static final CustomPayload.Id<Talk> ID = new CustomPayload.Id<>(Pp.id("talk"));
		public static final PacketCodec<RegistryByteBuf, Talk> CODEC = PacketCodec.of(
				(value, buf) -> buf.writeString(value.text, MAX_TEXT),
				buf -> new Talk(buf.readString(MAX_TEXT)));

		@Override
		public CustomPayload.Id<Talk> getId() {
			return ID;
		}
	}

	public record ConfigUpdate(String json) implements CustomPayload {
		public static final CustomPayload.Id<ConfigUpdate> ID = new CustomPayload.Id<>(Pp.id("config_update"));
		public static final PacketCodec<RegistryByteBuf, ConfigUpdate> CODEC = PacketCodec.of(
				(value, buf) -> buf.writeString(value.json, MAX_JSON),
				buf -> new ConfigUpdate(buf.readString(MAX_JSON)));

		@Override
		public CustomPayload.Id<ConfigUpdate> getId() {
			return ID;
		}
	}

	public record Action(String action, String data) implements CustomPayload {
		public static final CustomPayload.Id<Action> ID = new CustomPayload.Id<>(Pp.id("action"));
		public static final PacketCodec<RegistryByteBuf, Action> CODEC = PacketCodec.of(
				(value, buf) -> {
					buf.writeString(value.action, 64);
					buf.writeString(value.data, MAX_JSON);
				},
				buf -> new Action(buf.readString(64), buf.readString(MAX_JSON)));

		@Override
		public CustomPayload.Id<Action> getId() {
			return ID;
		}
	}

	/** Debe llamarse una vez, antes de registrar ningun receptor. */
	public static void registerTypes() {
		PayloadTypeRegistry.playS2C().register(ConfigSync.ID, ConfigSync.CODEC);
		PayloadTypeRegistry.playS2C().register(State.ID, State.CODEC);
		PayloadTypeRegistry.playS2C().register(Punish.ID, Punish.CODEC);
		PayloadTypeRegistry.playS2C().register(Notice.ID, Notice.CODEC);

		PayloadTypeRegistry.playC2S().register(SpokenWord.ID, SpokenWord.CODEC);
		PayloadTypeRegistry.playC2S().register(Talk.ID, Talk.CODEC);
		PayloadTypeRegistry.playC2S().register(ConfigUpdate.ID, ConfigUpdate.CODEC);
		PayloadTypeRegistry.playC2S().register(Action.ID, Action.CODEC);
	}
}
