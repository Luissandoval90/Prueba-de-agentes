package com.palabraprohibida.punish;

import com.palabraprohibida.config.PunishmentConfig;

/**
 * Los 20 castigos. El id es estable: aparece en el JSON, en los comandos y en el idioma.
 */
public enum PunishmentType {
	DAMAGE("damage", "minecraft:iron_sword", false, new PunishmentConfig(true, 3).with("amount", 3)),
	ITEM_LOSS("item_loss", "minecraft:bundle", false, new PunishmentConfig(true, 2)),
	DROP_HAND("drop_hand", "minecraft:dropper", false, new PunishmentConfig(true, 3)),
	TELEPORT("teleport", "minecraft:ender_pearl", false, new PunishmentConfig(true, 3).with("distance", 6)),
	SLOWNESS("slowness", "minecraft:soul_sand", false, new PunishmentConfig(true, 4).with("seconds", 8).with("amplifier", 0)),
	BLINDNESS("blindness", "minecraft:ink_sac", false, new PunishmentConfig(true, 3).with("seconds", 5)),
	HUNGER("hunger", "minecraft:rotten_flesh", false, new PunishmentConfig(true, 3).with("points", 6)),
	HOTBAR_SHUFFLE("hotbar_shuffle", "minecraft:barrel", false, new PunishmentConfig(true, 3)),
	ARMOR_DROP("armor_drop", "minecraft:iron_chestplate", false, new PunishmentConfig(true, 2)),
	MOB("mob", "minecraft:zombie_head", false, new PunishmentConfig(true, 2).with("count", 1).flag("creeper", false)),
	ROULETTE("roulette", "minecraft:target", false, new PunishmentConfig(true, 2)),
	SWAP("swap", "minecraft:ender_eye", false, new PunishmentConfig(true, 2)),
	NO_JUMP("no_jump", "minecraft:iron_boots", false, new PunishmentConfig(true, 3).with("seconds", 8)),
	INVENTORY_LOCK("inventory_lock", "minecraft:iron_bars", false, new PunishmentConfig(true, 2).with("seconds", 6)),
	FIRE("fire", "minecraft:flint_and_steel", false, new PunishmentConfig(true, 2).with("seconds", 4)),
	INVERTED_LOOK("inverted_look", "minecraft:compass", true, new PunishmentConfig(true, 2).with("seconds", 8)),
	SILENCE("silence", "minecraft:bell", true, new PunishmentConfig(true, 2).with("seconds", 8)),
	LEVITATION("levitation", "minecraft:shulker_shell", false, new PunishmentConfig(true, 2).with("seconds", 3)),
	XP_LOSS("xp_loss", "minecraft:experience_bottle", false, new PunishmentConfig(true, 3).with("levels", 5)),
	CLUMSY_HAND("clumsy_hand", "minecraft:leather", true, new PunishmentConfig(true, 2).with("seconds", 8));

	private final String id;
	private final String iconItem;
	private final boolean clientSide;
	private final PunishmentConfig defaults;

	PunishmentType(String id, String iconItem, boolean clientSide, PunishmentConfig defaults) {
		this.id = id;
		this.iconItem = iconItem;
		this.clientSide = clientSide;
		this.defaults = defaults;
	}

	public String id() {
		return id;
	}

	public String iconItem() {
		return iconItem;
	}

	/** Estos tres los aplica el cliente: si no tiene el mod, se omiten. */
	public boolean isClientSide() {
		return clientSide;
	}

	public PunishmentConfig defaultConfig() {
		PunishmentConfig copy = new PunishmentConfig(defaults.enabled, defaults.weight);
		copy.values.putAll(defaults.values);
		copy.flags.putAll(defaults.flags);
		return copy;
	}

	public String translationKey() {
		return "palabraprohibida.punish." + id;
	}

	public String descriptionKey() {
		return "palabraprohibida.punish." + id + ".hint";
	}

	public static PunishmentType byId(String id) {
		if (id == null) {
			return null;
		}
		for (PunishmentType type : values()) {
			if (type.id.equalsIgnoreCase(id)) {
				return type;
			}
		}
		return null;
	}
}
