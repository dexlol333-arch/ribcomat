package dev.rib.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RibCombat extends JavaPlugin implements Listener, TabExecutor {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private RegionManager regions;

    /** Player -> time (ms) their combat tag ends. */
    private final Map<UUID, Long> tagged = new HashMap<>();
    /** Player -> name of who they last fought. */
    private final Map<UUID, String> opponent = new HashMap<>();
    /** Players being kicked by the server (not punished for logging). */
    private final Set<UUID> kicked = new HashSet<>();
    /** Players being killed for combat logging (hide their death message). */
    private final Set<UUID> loggers = new HashSet<>();
    /** Last time each player was shown the barrier wall (stops spam). */
    private final Map<UUID, Long> lastWall = new HashMap<>();
    /** /ribcombat pos1 + pos2 selections. */
    private final Map<UUID, Location> pos1 = new HashMap<>(), pos2 = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        regions = new RegionManager(this);
        regions.load();
        getServer().getPluginManager().registerEvents(this, this);
        for (String cmd : List.of("combat", "ribcombat")) {
            var c = getCommand(cmd);
            if (c != null) {
                c.setExecutor(this);
                c.setTabCompleter(this);
            }
        }
        // Every half second: update action bars and untag players whose timer ran out.
        Bukkit.getScheduler().runTaskTimer(this, this::tickTags, 10L, 10L);
    }

    @Override
    public void onDisable() {
        tagged.clear();
    }

    // ================================================================== helpers

    private Component color(String s) {
        return LEGACY.deserialize(s == null ? "" : s);
    }

    private String fill(String s, Player p, String region) {
        if (s == null) return "";
        String killer = p == null ? "" : opponent.getOrDefault(p.getUniqueId(), "someone");
        return s.replace("<player>", p == null ? "" : p.getName())
                .replace("<killer>", killer)
                .replace("<time>", p == null ? "0" : String.valueOf(secondsLeft(p)))
                .replace("<region>", region == null ? "" : region);
    }

    private void msg(Player p, String key) {
        msg(p, key, null);
    }

    private void msg(Player p, String key, String region) {
        String text = getConfig().getString("messages." + key, "");
        if (text.isEmpty()) return;
        p.sendMessage(color(getConfig().getString("messages.prefix", "") + fill(text, p, region)));
    }

    public boolean inCombat(Player p) {
        Long end = tagged.get(p.getUniqueId());
        return end != null && end > System.currentTimeMillis();
    }

    private int secondsLeft(Player p) {
        Long end = tagged.get(p.getUniqueId());
        if (end == null) return 0;
        return (int) Math.max(0, Math.ceil((end - System.currentTimeMillis()) / 1000.0));
    }

    private boolean canBeTagged(Player p) {
        GameMode gm = p.getGameMode();
        return !p.hasPermission("ribcombat.bypass") && gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR;
    }

    public void tag(Player p, String against) {
        if (!canBeTagged(p)) return;
        boolean wasTagged = inCombat(p);
        tagged.put(p.getUniqueId(), System.currentTimeMillis() + getConfig().getInt("combat.duration", 15) * 1000L);
        if (against != null) opponent.put(p.getUniqueId(), against);
        if (!wasTagged) {
            msg(p, "tagged");
            if (getConfig().getBoolean("combat.disable-flight", true) && p.getAllowFlight()) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
            if (getConfig().getBoolean("combat.disable-elytra", true) && p.isGliding()) {
                p.setGliding(false);
            }
        }
    }

    public void untag(Player p, boolean notify) {
        if (tagged.remove(p.getUniqueId()) != null && notify && p.isOnline()) {
            msg(p, "untagged");
            p.sendActionBar(Component.empty());
        }
        opponent.remove(p.getUniqueId());
    }

    private void tickTags() {
        long now = System.currentTimeMillis();
        boolean bar = getConfig().getBoolean("combat.action-bar", true);
        Iterator<Map.Entry<UUID, Long>> it = tagged.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            if (e.getValue() <= now) {
                it.remove();
                opponent.remove(p.getUniqueId());
                msg(p, "untagged");
                if (bar) p.sendActionBar(Component.empty());
            } else if (bar) {
                p.sendActionBar(color(fill(getConfig().getString("combat.action-bar-text", "&cIn combat: <time>s"), p, null)));
            }
        }
    }

    // ================================================================== tagging

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        Entity damager = event.getDamager();
        if (damager instanceof Player p) {
            attacker = p;
        } else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p
                && getConfig().getBoolean("combat.tag-projectiles", true)) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;
        tag(victim, attacker.getName());
        tag(attacker, victim.getName());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (loggers.remove(p.getUniqueId())) {
            event.deathMessage(null); // our own combat-log broadcast replaces it
            event.getDrops().clear();  // items were already dropped when they logged out
        }
        untag(p, false);
    }

    // ================================================================== combat logging

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        kicked.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        boolean wasKicked = kicked.remove(p.getUniqueId());
        if (!inCombat(p)) {
            untag(p, false);
            return;
        }
        if (Bukkit.isStopping() || (wasKicked && getConfig().getBoolean("combat-log.ignore-kicks", true))
                || !getConfig().getBoolean("combat-log.kill", true)) {
            untag(p, false);
            return;
        }

        Location loc = p.getLocation();
        String broadcast = getConfig().getString("combat-log.broadcast", "");
        if (broadcast != null && !broadcast.isBlank()) {
            Bukkit.broadcast(color(fill(broadcast, p, null)));
        }
        if (getConfig().getBoolean("combat-log.lightning", true) && loc.getWorld() != null) {
            loc.getWorld().strikeLightningEffect(loc);
        }

        // Drop everything on the ground where they logged out (works even with keepInventory on).
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && !item.getType().isAir() && loc.getWorld() != null) {
                loc.getWorld().dropItemNaturally(loc, item);
            }
        }
        p.getInventory().clear();
        loggers.add(p.getUniqueId());
        untag(p, false);
        p.setHealth(0.0);
    }

    // ================================================================== restrictions

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p) || !inCombat(p)) return;
        if (!getConfig().getBoolean("combat.block-containers", true)) return;
        String type = event.getInventory().getType().name();
        for (String blocked : getConfig().getStringList("combat.blocked-inventories")) {
            if (blocked.equalsIgnoreCase(type)) {
                event.setCancelled(true);
                msg(p, "blocked-container");
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!inCombat(p)) return;
        String label = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1); // essentials:spawn -> spawn
        for (String blocked : getConfig().getStringList("combat.blocked-commands")) {
            if (blocked.equalsIgnoreCase(label)) {
                event.setCancelled(true);
                msg(p, "blocked-command");
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearl(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player p)) return;
        if (inCombat(p) && getConfig().getBoolean("combat.block-ender-pearls", false)) {
            event.setCancelled(true);
            msg(p, "blocked-pearl");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player p && inCombat(p)
                && getConfig().getBoolean("combat.disable-elytra", true)) {
            event.setCancelled(true);
            msg(p, "blocked-elytra");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFly(PlayerToggleFlightEvent event) {
        if (event.isFlying() && inCombat(event.getPlayer()) && getConfig().getBoolean("combat.disable-flight", true)) {
            event.setCancelled(true);
        }
    }

    // ================================================================== safe zones

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom(), to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        Player p = event.getPlayer();
        if (!inCombat(p)) return;
        Region region = regions.at(to);
        if (region == null || region.contains(from)) return; // only stop them ENTERING

        event.setCancelled(true);
        msg(p, "blocked-region", region.name());
        knockBack(p, from, to, region);
        if (getConfig().getBoolean("barrier.show-wall", true)) showWall(p, from, to, region);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        if (!inCombat(p)) return;
        Region region = regions.at(event.getTo());
        if (region != null && !region.contains(event.getFrom())) {
            event.setCancelled(true);
            msg(p, "blocked-teleport", region.name());
        }
    }

    /** Launches the player back out the way they came (~10 blocks with knockback 2.0). */
    private void knockBack(Player p, Location from, Location to, Region region) {
        Vector dir = from.toVector().subtract(to.toVector()).setY(0);
        if (dir.lengthSquared() < 1.0E-4) {
            // No clear direction (e.g. stood still) - push away from the middle of the region.
            double cx = (region.minX() + region.maxX() + 1) / 2.0, cz = (region.minZ() + region.maxZ() + 1) / 2.0;
            dir = new Vector(p.getLocation().getX() - cx, 0, p.getLocation().getZ() - cz);
        }
        if (dir.lengthSquared() < 1.0E-4) dir = p.getLocation().getDirection().multiply(-1).setY(0);
        dir.normalize().multiply(getConfig().getDouble("barrier.knockback", 2.0));
        dir.setY(getConfig().getDouble("barrier.knockback-up", 0.45));
        Vector push = dir;
        // Apply next tick so the cancelled move doesn't eat the velocity.
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) p.setVelocity(push); });
        p.playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, 1f, 0.6f);
    }

    /** Shows a fake red glass wall on the edge of the region, only to this player, for a few seconds. */
    private void showWall(Player p, Location from, Location to, Region region) {
        long now = System.currentTimeMillis();
        Long last = lastWall.get(p.getUniqueId());
        if (last != null && now - last < 750) return;
        lastWall.put(p.getUniqueId(), now);

        Material mat = Material.matchMaterial(getConfig().getString("barrier.wall-block", "RED_STAINED_GLASS"));
        if (mat == null || !mat.isBlock()) mat = Material.RED_STAINED_GLASS;
        BlockData glass = mat.createBlockData();
        int width = Math.max(1, getConfig().getInt("barrier.wall-width", 7));
        int height = Math.max(1, getConfig().getInt("barrier.wall-height", 5));
        int half = width / 2;
        int baseY = from.getBlockY() - 1;

        // Which side of the region did they walk into?
        boolean alongZ; // wall runs along the z axis (a fixed x)
        int fixed;
        if (from.getBlockX() < region.minX() && to.getBlockX() >= region.minX()) { alongZ = true; fixed = region.minX(); }
        else if (from.getBlockX() > region.maxX() && to.getBlockX() <= region.maxX()) { alongZ = true; fixed = region.maxX(); }
        else if (from.getBlockZ() < region.minZ() && to.getBlockZ() >= region.minZ()) { alongZ = false; fixed = region.minZ(); }
        else if (from.getBlockZ() > region.maxZ() && to.getBlockZ() <= region.maxZ()) { alongZ = false; fixed = region.maxZ(); }
        else {
            // Came in from above/below - put the wall right where they tried to go, facing them.
            alongZ = Math.abs(to.getX() - from.getX()) >= Math.abs(to.getZ() - from.getZ());
            fixed = alongZ ? to.getBlockX() : to.getBlockZ();
        }

        List<Location> shown = new ArrayList<>();
        for (int i = -half; i <= half; i++) {
            for (int dy = 0; dy < height; dy++) {
                Location loc = alongZ
                        ? new Location(to.getWorld(), fixed, baseY + dy, to.getBlockZ() + i)
                        : new Location(to.getWorld(), to.getBlockX() + i, baseY + dy, fixed);
                Block block = loc.getBlock();
                if (block.getType().isAir() || block.isPassable()) {
                    p.sendBlockChange(loc, glass);
                    shown.add(loc);
                }
            }
        }
        Location mid = alongZ
                ? new Location(to.getWorld(), fixed + 0.5, baseY + height / 2.0, to.getBlockZ() + 0.5)
                : new Location(to.getWorld(), to.getBlockX() + 0.5, baseY + height / 2.0, fixed + 0.5);
        p.spawnParticle(Particle.DUST, mid, 40, width / 3.0, height / 3.0, width / 3.0, new Particle.DustOptions(Color.RED, 1.6f));
        p.playSound(mid, Sound.BLOCK_GLASS_PLACE, 1f, 0.8f);

        long ticks = Math.max(1, getConfig().getInt("barrier.wall-seconds", 3)) * 20L;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            for (Location loc : shown) p.sendBlockChange(loc, loc.getBlock().getBlockData()); // put the real blocks back
        }, ticks);
    }

    // ================================================================== commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("combat")) {
            if (sender instanceof Player p) {
                msg(p, inCombat(p) ? "combat-time" : "not-in-combat");
            }
            return true;
        }

        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String prefix = getConfig().getString("messages.prefix", "");
        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Location loc = p.getLocation().getBlock().getLocation();
                (sub.equals("pos1") ? pos1 : pos2).put(p.getUniqueId(), loc);
                sender.sendMessage(color(prefix + "&a" + sub.toUpperCase(Locale.ROOT) + " set to &f"
                        + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()));
            }
            case "create" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                if (args.length < 2) { sender.sendMessage(color(prefix + "&c/ribcombat create <name>")); return true; }
                Location a = pos1.get(p.getUniqueId()), b = pos2.get(p.getUniqueId());
                if (a == null || b == null) { sender.sendMessage(color(prefix + "&cSet both corners first with /ribcombat pos1 and pos2.")); return true; }
                if (!a.getWorld().equals(b.getWorld())) { sender.sendMessage(color(prefix + "&cBoth corners must be in the same world.")); return true; }
                // Stretch from bedrock to sky so players can't jump/fly over it.
                Location low = a.clone(), high = b.clone();
                if (args.length < 3 || !args[2].equalsIgnoreCase("exact")) {
                    low.setY(a.getWorld().getMinHeight());
                    high.setY(a.getWorld().getMaxHeight());
                }
                Region r = Region.of(args[1], low, high);
                regions.add(r);
                sender.sendMessage(color(prefix + "&aCreated safe zone &f" + r.name() + "&a. Players in combat can't enter it."));
            }
            case "delete", "remove" -> {
                if (args.length < 2) { sender.sendMessage(color(prefix + "&c/ribcombat delete <name>")); return true; }
                sender.sendMessage(color(prefix + (regions.remove(args[1]) ? "&aDeleted &f" + args[1] : "&cNo region called &f" + args[1])));
            }
            case "list" -> {
                List<Region> all = regions.all();
                sender.sendMessage(color(prefix + "&7Safe zones (&f" + all.size() + "&7):"));
                for (Region r : all) sender.sendMessage(color(" &8- &f" + r));
            }
            case "tag" -> {
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) { sender.sendMessage(color(prefix + "&cPlayer not found.")); return true; }
                tag(t, sender.getName());
                sender.sendMessage(color(prefix + "&aTagged &f" + t.getName()));
            }
            case "untag" -> {
                Player t = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) { sender.sendMessage(color(prefix + "&cPlayer not found.")); return true; }
                untag(t, true);
                sender.sendMessage(color(prefix + "&aUntagged &f" + t.getName()));
            }
            case "reload" -> {
                reloadConfig();
                regions.load();
                sender.sendMessage(color(prefix + "&aRibCombat reloaded."));
            }
            default -> {
                sender.sendMessage(color("&c&lRibCombat"));
                sender.sendMessage(color("&f/ribcombat pos1 &7/ &fpos2 &8- &7set the 2 corners (stand on them)"));
                sender.sendMessage(color("&f/ribcombat create <name> &8- &7make a safe zone (e.g. spawn)"));
                sender.sendMessage(color("&f/ribcombat delete <name> &8- &7remove a safe zone"));
                sender.sendMessage(color("&f/ribcombat list &8- &7list safe zones"));
                sender.sendMessage(color("&f/ribcombat tag|untag <player> &8- &7force combat on/off"));
                sender.sendMessage(color("&f/ribcombat reload &8- &7reload config + regions"));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("ribcombat")) return List.of();
        if (args.length == 1) return List.of("pos1", "pos2", "create", "delete", "list", "tag", "untag", "reload");
        if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("remove"))) {
            List<String> names = new ArrayList<>();
            for (Region r : regions.all()) names.add(r.name());
            return names;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("tag") || args[0].equalsIgnoreCase("untag"))) return null;
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) return List.of("exact");
        return List.of();
    }
}
