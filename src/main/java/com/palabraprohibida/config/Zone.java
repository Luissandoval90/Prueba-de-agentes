package com.palabraprohibida.config;

import net.minecraft.util.math.BlockPos;

/**
 * Zona protegida: dentro de ella no se aplican castigos.
 */
public class Zone {
	public String dimension = "minecraft:overworld";
	public int x1;
	public int y1;
	public int z1;
	public int x2;
	public int y2;
	public int z2;

	public Zone() {
	}

	public Zone(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
		this.dimension = dimension;
		this.x1 = Math.min(x1, x2);
		this.y1 = Math.min(y1, y2);
		this.z1 = Math.min(z1, z2);
		this.x2 = Math.max(x1, x2);
		this.y2 = Math.max(y1, y2);
		this.z2 = Math.max(z1, z2);
	}

	public boolean contains(String dimensionId, BlockPos pos) {
		if (dimension != null && !dimension.equals(dimensionId)) {
			return false;
		}
		return pos.getX() >= x1 && pos.getX() <= x2
				&& pos.getY() >= y1 && pos.getY() <= y2
				&& pos.getZ() >= z1 && pos.getZ() <= z2;
	}
}
