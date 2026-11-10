package dev.catmaid.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** Location 与数据库四列（world, x, y, z）互转。 */
public final class Locations {

    private Locations() {
    }

    /**
     * 世界未加载时返回 null：宁可暂时丢掉家坐标，也不构造一个假世界。
     * 调用方（MaidRepository）需容忍 home 为 null。
     */
    public static Location toBukkit(String worldName, double x, double y, double z) {
        if (worldName == null) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z);
    }

    public static String worldName(Location location) {
        return location == null || location.getWorld() == null ? null : location.getWorld().getName();
    }
}
