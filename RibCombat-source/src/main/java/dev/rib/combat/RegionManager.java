package dev.rib.combat;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads and saves safe-zone regions in plugins/RibCombat/regions.yml. */
public class RegionManager {
    private final RibCombat plugin;
    private final File file;
    private final Map<String, Region> regions = new LinkedHashMap<>();

    public RegionManager(RibCombat plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "regions.yml");
    }

    public void load() {
        regions.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("regions");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(name);
            if (r == null) continue;
            regions.put(name.toLowerCase(Locale.ROOT), new Region(name, r.getString("world", "world"),
                    r.getInt("min.x"), r.getInt("min.y"), r.getInt("min.z"),
                    r.getInt("max.x"), r.getInt("max.y"), r.getInt("max.z")));
        }
        plugin.getLogger().info("Loaded " + regions.size() + " safe zone(s).");
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Region r : regions.values()) {
            String p = "regions." + r.name() + ".";
            yml.set(p + "world", r.world());
            yml.set(p + "min.x", r.minX());
            yml.set(p + "min.y", r.minY());
            yml.set(p + "min.z", r.minZ());
            yml.set(p + "max.x", r.maxX());
            yml.set(p + "max.y", r.maxY());
            yml.set(p + "max.z", r.maxZ());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Couldn't save regions.yml: " + e.getMessage());
        }
    }

    public void add(Region region) {
        regions.put(region.name().toLowerCase(Locale.ROOT), region);
        save();
    }

    public boolean remove(String name) {
        boolean removed = regions.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) save();
        return removed;
    }

    public List<Region> all() {
        return new ArrayList<>(regions.values());
    }

    /** The region at this spot, or null. */
    public Region at(Location loc) {
        for (Region r : regions.values()) if (r.contains(loc)) return r;
        return null;
    }
}
