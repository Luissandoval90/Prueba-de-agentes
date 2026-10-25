package com.palabraprohibida.punish;

import com.palabraprohibida.Pp;
import com.palabraprohibida.compat.Compat;
import com.palabraprohibida.config.PunishmentConfig;
import com.palabraprohibida.net.Payloads;
import com.palabraprohibida.server.ServerRuntime;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Elige y aplica castigos. Siempre en el hilo del servidor.
 */
public final class Punisher {
	private static final Random RANDOM = new Random();
	private static final Set<String> DANGEROUS = Set.of(
			"minecraft:water", "minecraft:lava", "minecraft:fire", "minecraft:soul_fire",
			"minecraft:magma_block", "minecraft:cactus", "minecraft:powder_snow", "minecraft:cobweb",
			"minecraft:sweet_berry_bush", "minecraft:wither_rose", "minecraft:campfire"
	);

	private final ServerRuntime runtime;

	public Punisher(ServerRuntime runtime) {
		this.runtime = runtime;
	}

	/** Punto de entrada: castigo aleatorio por peso tras decir una palabra prohibida. */
	public void punishForWord(ServerPlayerEntity target, String word) {
		if (!runtime.canBePunished(target)) {
			runtime.sendNotice(target, "palabraprohibida.notice.punish_skipped");
			return;
		}
		if (runtime.onPunishCooldown(target)) {
			runtime.sendNotice(target, "palabraprohibida.notice.cooldown");
			return;
		}
		List<PunishmentType> pool = new ArrayList<>(runtime.enabledPunishments(target));
		if (pool.isEmpty()) {
			runtime.sendNotice(target, "palabraprohibida.notice.no_punishments");
			return;
		}
		boolean applied = false;
		while (!pool.isEmpty()) {
			PunishmentType type = pickWeighted(pool);
			pool.remove(type);
			if (apply(target, type)) {
				applied = true;
				runtime.markPunish(target);
				if (!runtime.config().isAccumulative()) {
					// Tras el castigo la palabra cambia: la que mas repite el dueno.
					String newWord = runtime.rotateWord(target.getUUID(), true);
					runtime.sendNotice(target, "palabraprohibida.notice.word_rotated",
							com.palabraprohibida.word.WordHash.displayWord(newWord));
					runtime.syncStateAll();
				}
				break;
			}
		}
		if (!applied) {
			runtime.sendNotice(target, "palabraprohibida.notice.no_punishments");
		}
	}

	/** Castigo concreto pedido por comando. */
	public boolean apply(ServerPlayerEntity target, PunishmentType type) {
		PunishmentConfig cfg = runtime.config().punishment(type);
		ServerWorld world = runtime.worldOf(target);
		try {
			boolean ok;
			switch (type) {
				case DAMAGE -> ok = damage(target, world, cfg);
				case ITEM_LOSS -> ok = itemLoss(target, world);
				case DROP_HAND -> ok = dropHand(target);
				case TELEPORT -> ok = shortTeleport(target, world, cfg);
				case SLOWNESS -> {
					Compat.applyEffect(target, "slowness", cfg.intValue("seconds", 8), cfg.intValue("amplifier", 0));
					ok = true;
				}
				case BLINDNESS -> {
					Compat.applyEffect(target, "blindness", cfg.intValue("seconds", 5), 0);
					Compat.applyEffect(target, "darkness", Math.min(10, cfg.intValue("seconds", 5)), 0);
					ok = true;
				}
				case HUNGER -> ok = hunger(target, cfg);
				case HOTBAR_SHUFFLE -> ok = shuffleHotbar(target);
				case ARMOR_DROP -> ok = armorDrop(target, world);
				case MOB -> ok = summonMob(target, cfg);
				case ROULETTE -> ok = roulette(target, type);
				case SWAP -> ok = swapPlaces(target);
				case NO_JUMP -> ok = noJump(target, cfg);
				case INVENTORY_LOCK -> ok = lockInventory(target, cfg);
				case FIRE -> ok = fire(target, cfg);
				case INVERTED_LOOK -> ok = sendClientPunishment(target, type, cfg);
				case SILENCE -> ok = sendClientPunishment(target, type, cfg);
				case CLUMSY_HAND -> ok = sendClientPunishment(target, type, cfg);
				case LEVITATION -> {
					Compat.applyEffect(target, "levitation", cfg.intValue("seconds", 3), 0);
					Compat.applyEffect(target, "feather_falling", cfg.intValue("seconds", 3) + 4, 3);
					ok = true;
				}
				case XP_LOSS -> ok = xpLoss(target, cfg);
				default -> ok = false;
			}
			if (ok) {
				feedback(target, type, cfg);
			}
			return ok;
		} catch (Exception e) {
			Pp.LOG.warn("Castigo {} fallo: {}", type.id(), e.getMessage());
			return false;
		}
	}

	private PunishmentType pickWeighted(List<PunishmentType> options) {
		int total = 0;
		for (PunishmentType type : options) {
			total += Math.max(1, runtime.config().punishment(type).weight);
		}
		int roll = RANDOM.nextInt(Math.max(1, total));
		for (PunishmentType type : options) {
			roll -= Math.max(1, runtime.config().punishment(type).weight);
			if (roll < 0) {
				return type;
			}
		}
		return options.get(0);
	}

	// ---------- Implementaciones ----------

	private boolean damage(ServerPlayerEntity target, ServerWorld world, PunishmentConfig cfg) {
		float amount = (float) Math.max(1.0, cfg.value("amount", 3));
		if (runtime.config().noDeathPunishments && target.getHealth() <= amount) {
			amount = Math.max(1.0f, target.getHealth() - 1.0f);
		}
		return target.damage(world, target.getDamageSources().magic(), amount);
	}

	private boolean itemLoss(ServerPlayerEntity target, ServerWorld world) {
		PlayerInventory inventory = target.getInventory();
		List<Integer> filled = new ArrayList<>();
		for (int i = 0; i < 36; i++) {
			if (!inventory.getStack(i).isEmpty()) {
				filled.add(i);
			}
		}
		if (filled.isEmpty()) {
			return false;
		}
		int slot = filled.get(RANDOM.nextInt(filled.size()));
		ItemStack stack = inventory.getStack(slot);
		inventory.setStack(slot, ItemStack.EMPTY);
		target.dropItem(world, stack);
		return true;
	}

	private boolean dropHand(ServerPlayerEntity target) {
		if (target.getMainHandStack().isEmpty()) {
			return false;
		}
		target.dropSelectedItem(true);
		return true;
	}

	private boolean shortTeleport(ServerPlayerEntity target, ServerWorld world, PunishmentConfig cfg) {
		int radius = MathHelper.clamp(cfg.intValue("distance", 6), 2, 32);
		BlockPos safe = findSafeSpot(world, target.getBlockPos(), radius);
		if (safe == null) {
			return false;
		}
		target.networkHandler.requestTeleport(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5,
				target.getYaw(), target.getPitch());
		Compat.playSound(world, target.getX(), target.getY(), target.getZ(),
				"entity.enderman.teleport", "player", 0.6f, 1.0f);
		return true;
	}

	/** Busca suelo firme sin liquidos, fuego, magma ni cactus. */
	public BlockPos findSafeSpot(ServerWorld world, BlockPos center, int radius) {
		for (int attempt = 0; attempt < 24; attempt++) {
			int x = center.getX() + RANDOM.nextInt(radius * 2 + 1) - radius;
			int z = center.getZ() + RANDOM.nextInt(radius * 2 + 1) - radius;
			for (int y = center.getY() + 3; y >= center.getY() - 8; y--) {
				BlockPos ground = new BlockPos(x, y, z);
				BlockPos feet = ground.up();
				BlockPos head = feet.up();
				if (!isFirmGround(world, ground)) {
					continue;
				}
				if (!world.isAir(feet) || !world.isAir(head)) {
					continue;
				}
				return feet;
			}
		}
		return null;
	}

	private boolean isFirmGround(World world, BlockPos pos) {
		var state = world.getBlockState(pos);
		if (state.isAir() || !state.getFluidState().isEmpty()) {
			return false;
		}
		if (!state.isSolidBlock(world, pos)) {
			return false;
		}
		String id = net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
		return !DANGEROUS.contains(id);
	}

	private boolean hunger(ServerPlayerEntity target, PunishmentConfig cfg) {
		int points = cfg.intValue("points", 6);
		int food = target.getHungerManager().getFoodLevel();
		target.getHungerManager().setFoodLevel(Math.max(0, food - points));
		target.getHungerManager().addExhaustion(points * 0.5f);
		return true;
	}

	private boolean shuffleHotbar(ServerPlayerEntity target) {
		PlayerInventory inventory = target.getInventory();
		List<ItemStack> hotbar = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			hotbar.add(inventory.getStack(i));
		}
		if (hotbar.stream().allMatch(ItemStack::isEmpty)) {
			return false;
		}
		Collections.shuffle(hotbar, RANDOM);
		for (int i = 0; i < 9; i++) {
			inventory.setStack(i, hotbar.get(i));
		}
		return true;
	}

	private boolean armorDrop(ServerPlayerEntity target, ServerWorld world) {
		PlayerInventory inventory = target.getInventory();
		boolean dropped = false;
		for (int i = 36; i <= 39; i++) {
			ItemStack stack = inventory.getStack(i);
			if (!stack.isEmpty()) {
				inventory.setStack(i, ItemStack.EMPTY);
				target.dropItem(world, stack);
				dropped = true;
			}
		}
		return dropped;
	}

	private boolean summonMob(ServerPlayerEntity target, PunishmentConfig cfg) {
		String mob = cfg.flag("creeper", false) && RANDOM.nextBoolean() ? "creeper" : "zombie";
		String command = String.format(Locale.ROOT,
				"execute positioned %.2f %.2f %.2f run summon minecraft:%s ~ ~ ~",
				target.getX(), target.getY(), target.getZ(), mob);
		try {
			runtime.server().getCommandManager().executeWithPrefix(
					runtime.server().getCommandSource().withSilent(), command);
			return true;
		} catch (Exception e) {
			Pp.LOG.warn("No se pudo invocar el mob: {}", e.getMessage());
			return false;
		}
	}

	private boolean roulette(ServerPlayerEntity target, PunishmentType self) {
		List<PunishmentType> options = new ArrayList<>(runtime.enabledPunishments(target));
		options.remove(self);
		options.remove(PunishmentType.ROULETTE);
		if (options.isEmpty()) {
			return apply(target, PunishmentType.SLOWNESS);
		}
		PunishmentType chosen = options.get(RANDOM.nextInt(options.size()));
		runtime.sendNotice(target, "palabraprohibida.notice.roulette", chosen.translationKey());
		return apply(target, chosen);
	}

	private boolean swapPlaces(ServerPlayerEntity target) {
		List<ServerPlayerEntity> others = new ArrayList<>();
		for (ServerPlayerEntity player : runtime.server().getPlayerManager().getPlayerList()) {
			if (player != target && runtime.canBePunished(player)) {
				others.add(player);
			}
		}
		if (others.isEmpty()) {
			return false;
		}
		ServerPlayerEntity other = others.get(RANDOM.nextInt(others.size()));
		double x = target.getX();
		double y = target.getY();
		double z = target.getZ();
		ServerWorld targetWorld = runtime.worldOf(target);
		ServerWorld otherWorld = runtime.worldOf(other);
		if (targetWorld != otherWorld) {
			return false;
		}
		target.networkHandler.requestTeleport(other.getX(), other.getY(), other.getZ(), target.getYaw(), target.getPitch());
		other.networkHandler.requestTeleport(x, y, z, other.getYaw(), other.getPitch());
		runtime.sendNotice(target, "palabraprohibida.notice.swapped", other.getName().getString());
		runtime.sendNotice(other, "palabraprohibida.notice.swapped", target.getName().getString());
		return true;
	}

	private boolean noJump(ServerPlayerEntity target, PunishmentConfig cfg) {
		EntityAttributeInstance instance = Compat.attribute(target, "jump_strength", "generic.jump_strength");
		if (instance == null) {
			return false;
		}
		Identifier modifierId = Pp.id("no_jump");
		instance.removeModifier(modifierId);
		if (!Compat.addNoJumpModifier(instance, modifierId, -0.9D)) {
			return false;
		}
		int seconds = Math.max(1, cfg.intValue("seconds", 8));
		runtime.schedule(seconds * 1000L, () -> instance.removeModifier(modifierId));
		return true;
	}

	private boolean lockInventory(ServerPlayerEntity target, PunishmentConfig cfg) {
		PlayerInventory inventory = target.getInventory();
		ItemStack[] backup = new ItemStack[inventory.size()];
		boolean any = false;
		for (int i = 0; i < backup.length; i++) {
			backup[i] = inventory.getStack(i).copy();
			if (!backup[i].isEmpty()) {
				any = true;
			}
			inventory.setStack(i, ItemStack.EMPTY);
		}
		if (!any) {
			return false;
		}
		int seconds = Math.max(1, cfg.intValue("seconds", 6));
		runtime.schedule(seconds * 1000L, () -> {
			for (int i = 0; i < backup.length && i < inventory.size(); i++) {
				inventory.setStack(i, backup[i]);
			}
		});
		return true;
	}

	private boolean fire(ServerPlayerEntity target, PunishmentConfig cfg) {
		if (target.isTouchingWater() || Compat.hasEffect(target, "fire_resistance")) {
			return false;
		}
		int seconds = Math.max(1, cfg.intValue("seconds", 4));
		if (runtime.config().noDeathPunishments && target.getHealth() <= 5.0f) {
			target.setFireTicks(40);
			runtime.sendNotice(target, "palabraprohibida.notice.fire_soft");
			return true;
		}
		target.setFireTicks(seconds * 20);
		return true;
	}

	private boolean sendClientPunishment(ServerPlayerEntity target, PunishmentType type, PunishmentConfig cfg) {
		int seconds = Math.max(1, cfg.intValue("seconds", 8));
		com.palabraprohibida.net.Net.toPlayer(target, new Payloads.Punish(type.id(), "{\"seconds\":" + seconds + "}"));
		return true;
	}

	private boolean xpLoss(ServerPlayerEntity target, PunishmentConfig cfg) {
		int levels = Math.max(1, cfg.intValue("levels", 5));
		target.addExperienceLevels(-levels);
		return true;
	}

	// ---------- Feedback ----------

	private void feedback(ServerPlayerEntity target, PunishmentType type, PunishmentConfig cfg) {
		String sound = switch (type) {
			case DAMAGE -> "entity.player.hurt";
			case FIRE -> "block.fire.ambient";
			case MOB -> "entity.zombie.ambient";
			case XP_LOSS -> "entity.experience_orb.pickup";
			case LEVITATION -> "entity.shulker.teleport";
			case TELEPORT, SWAP -> "entity.enderman.teleport";
			case SILENCE -> "block.bell.resonate";
			case INVERTED_LOOK -> "block.beacon.deactivate";
			case ITEM_LOSS, ARMOR_DROP, DROP_HAND -> "entity.item.break";
			case HUNGER -> "entity.player.burp";
			default -> "block.note_block.pling";
		};
		Compat.playSoundAt(target, sound, "player", 0.7f, 1.0f);
		String arg = switch (type) {
			case DAMAGE -> String.valueOf(Math.round(cfg.value("amount", 3) / 2.0f));
			case XP_LOSS -> String.valueOf(cfg.intValue("levels", 5));
			case HUNGER -> String.valueOf(cfg.intValue("points", 6));
			case FIRE, LEVITATION, SILENCE, INVERTED_LOOK, CLUMSY_HAND, NO_JUMP, INVENTORY_LOCK, TELEPORT ->
					String.valueOf(cfg.intValue("seconds", 8));
			default -> "";
		};
		runtime.sendNotice(target, "palabraprohibida.punish_msg." + type.id(), arg);
	}
}
