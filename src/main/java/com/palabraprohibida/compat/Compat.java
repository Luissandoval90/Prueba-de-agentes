package com.palabraprohibida.compat;

import com.palabraprohibida.Pp;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Aqui viven TODAS las llamadas que cambiaron entre 1.21.9 y 1.21.11
 * (mundo del entidad, permisos con PlayerConfigEntry, sonidos con SoundEvent...).
 * Si una version futura rompe algo, se arregla en este archivo.
 *
 * Detalle importante: en 1.21.11 los nombres de las constantes de registro
 * (SoundEvents, StatusEffects, EntityAttributes, EntityType) no son estables en
 * los mappings, asi que se resuelven por id en tiempo de ejecucion.
 */
public final class Compat {
	private Compat() {
	}

	public static Identifier identifier(String id) {
		return Identifier.tryParse(id);
	}

	public static Identifier vanilla(String path) {
		return Identifier.ofVanilla(path);
	}

	// ---------- Mundo ----------

	/** Entity#getWorld desaparecio; el nombre estable ahora es getEntityWorld(). */
	public static World worldOf(Entity entity) {
		return entity.getEntityWorld();
	}

	public static ServerWorld serverWorldOf(ServerPlayerEntity player) {
		return player.getServerWorld();
	}

	public static String dimensionId(World world) {
		return world.getRegistryKey().getValue().toString();
	}

	// ---------- Permisos ----------

	public static boolean isAdmin(ServerPlayerEntity player) {
		if (player == null) {
			return false;
		}
		try {
			if (player.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS)) {
				return true;
			}
			return player.getServer() != null && player.getServer().isHost(player.getPlayerConfigEntry());
		} catch (Exception e) {
			Pp.LOG.debug("No se pudo comprobar el permiso: {}", e.getMessage());
			return false;
		}
	}

	public static boolean isAdmin(ServerCommandSource source) {
		try {
			if (source.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS)) {
				return true;
			}
			ServerPlayerEntity player = source.getPlayer();
			if (player == null) {
				return true; // consola o bloque de comandos del servidor
			}
			return source.getServer() != null && source.getServer().isHost(player.getPlayerConfigEntry());
		} catch (Exception e) {
			return false;
		}
	}

	// ---------- Sonidos ----------

	public static SoundEvent sound(String path) {
		Identifier id = path.contains(":") ? Identifier.tryParse(path) : Identifier.ofVanilla(path);
		SoundEvent event = Registries.SOUND_EVENT.get(id);
		return event;
	}

	public static SoundCategory category(String name) {
		for (SoundCategory category : SoundCategory.values()) {
			if (category.getName().equalsIgnoreCase(name)) {
				return category;
			}
		}
		SoundCategory[] values = SoundCategory.values();
		return values.length == 0 ? null : values[0];
	}

	public static void playSound(World world, double x, double y, double z, String soundId, String category,
			float volume, float pitch) {
		if (world == null) {
			return;
		}
		SoundEvent event = sound(soundId);
		SoundCategory cat = category(category);
		if (event == null || cat == null) {
			return;
		}
		try {
			world.playSound(null, x, y, z, event, cat, volume, pitch);
		} catch (Exception e) {
			Pp.LOG.debug("Sonido {} fallo: {}", soundId, e.getMessage());
		}
	}

	public static void playSoundAt(Entity entity, String soundId, String category, float volume, float pitch) {
		if (entity == null) {
			return;
		}
		playSound(worldOf(entity), entity.getX(), entity.getY(), entity.getZ(), soundId, category, volume, pitch);
	}

	// ---------- Efectos y atributos ----------

	public static RegistryEntry<StatusEffect> effect(String id) {
		Identifier identifier = Identifier.ofVanilla(id);
		return Registries.STATUS_EFFECT.getEntry(identifier).orElse(null);
	}

	public static void applyEffect(LivingEntity entity, String effectId, int seconds, int amplifier) {
		RegistryEntry<StatusEffect> entry = effect(effectId);
		if (entry == null) {
			Pp.LOG.debug("Efecto desconocido: {}", effectId);
			return;
		}
		entity.addStatusEffect(new StatusEffectInstance(entry, Math.max(1, seconds) * 20, Math.max(0, amplifier)));
	}

	public static boolean hasEffect(LivingEntity entity, String effectId) {
		return entity.getStatusEffects().stream()
				.anyMatch(instance -> instance.getEffectType().matchesId(Identifier.ofVanilla(effectId)));
	}

	public static EntityAttributeInstance attribute(LivingEntity entity, String... attributeIds) {
		for (String attributeId : attributeIds) {
			RegistryEntry<EntityAttribute> entry = Registries.ATTRIBUTE.getEntry(Identifier.ofVanilla(attributeId)).orElse(null);
			if (entry == null) {
				continue;
			}
			EntityAttributeInstance instance = entity.getAttributeInstance(entry);
			if (instance != null) {
				return instance;
			}
		}
		return null;
	}

	/**
	 * Modificador de atributo para "no saltar". En 1.21.11 los nombres de las
	 * constantes de Operation y AttributeModifierSlot no estan en los mappings, y
	 * el constructor del modificador tampoco, asi que se construye por reflexion
	 * sobre las clases reales del juego. Si algo cambia, el castigo devuelve false
	 * y el servidor elige otro castigo activo.
	 */
	public static boolean addNoJumpModifier(EntityAttributeInstance instance, Identifier id, double amount) {
		if (instance == null) {
			return false;
		}
		Object modifier = buildModifier(id, amount);
		if (modifier == null) {
			return false;
		}
		// EntityAttributeInstance#addModifier(EntityAttributeModifier) existe en las
		// mappings, pero el tipo del parametro es el que acabamos de crear.
		for (java.lang.reflect.Method method : EntityAttributeInstance.class.getMethods()) {
			if (!method.getName().equals("addModifier") || method.getParameterCount() != 1) {
				continue;
			}
			try {
				method.invoke(instance, modifier);
				return true;
			} catch (ReflectiveOperationException | RuntimeException e) {
				Pp.LOG.debug("No se pudo aplicar el modificador de salto: {}", e.getMessage());
				return false;
			}
		}
		return false;
	}

	private static Object buildModifier(Identifier id, double amount) {
		try {
			Class<?> modifierClass = Class.forName("net.minecraft.entity.attribute.EntityAttributeModifier");
			Class<?> operationClass = Class.forName("net.minecraft.entity.attribute.EntityAttributeModifier$Operation");
			Class<?> slotClass = Class.forName("net.minecraft.component.type.AttributeModifierSlot");
			Object operation = firstConstant(operationClass);   // ADDITION
			Object slot = firstConstant(slotClass);             // ANY
			if (operation == null || slot == null) {
				return null;
			}
			try {
				return modifierClass
						.getConstructor(Identifier.class, double.class, operationClass, slotClass)
						.newInstance(id, amount, operation, slot);
			} catch (NoSuchMethodException ignored) {
				// Variante con el id como texto.
				return modifierClass
						.getConstructor(String.class, double.class, operationClass, slotClass)
						.newInstance(id.toString(), amount, operation, slot);
			}
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			Pp.LOG.warn("No se pudo crear el modificador de salto: {}", e.getMessage());
			return null;
		}
	}

	/** Primera constante del enum (Operation.ADDITION y AttributeModifierSlot.ANY). */
	private static Object firstConstant(Class<?> enumClass) {
		Object[] constants = enumClass.getEnumConstants();
		return constants == null || constants.length == 0 ? null : constants[0];
	}

	// ---------- Items ----------

	public static ItemStack item(String id) {
		Identifier identifier = Identifier.tryParse(id);
		if (identifier == null) {
			return ItemStack.EMPTY;
		}
		Item item = Registries.ITEM.get(identifier);
		return item == null ? ItemStack.EMPTY : new ItemStack(item);
	}

	// ---------- Texto ----------

	public static Text text(String key, Object... args) {
		return Text.translatable(key, args);
	}

	public static Text literal(String value) {
		return Text.literal(value);
	}

	// ---------- Jugador ----------

	public static boolean isCreative(PlayerEntity player) {
		return player.isCreative();
	}

	public static boolean isSpectator(PlayerEntity player) {
		return player.isSpectator();
	}
}
