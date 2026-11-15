package dev.rib.combat;

import org.bukkit.Location;

/** A box-shaped safe zone (like spawn) that players in combat can't enter. */
public record Region(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Region of(String name, Location a, Location b) {
        return new Region(name, a.getWorld().getName(),
                Math.min(a.getBlockX(), b.getBlockX()), Math.min(a.getBlockY(), b.getBlockY()), Math.min(a.getBlockZ(), b.getBlockZ()),
                Math.max(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockY(), b.getBlockY()), Math.max(a.getBlockZ(), b.getBlockZ()));
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null || !loc.getWorld().getName().equals(world)) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public int blocks() {
        return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    @Override
    public String toString() {
        return name + " (" + world + " " + minX + "," + minY + "," + minZ + " -> " + maxX + "," + maxY + "," + maxZ + ")";
    }
}
