package me.arcade.games.spectate;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.game.Game;
import me.arcade.games.util.Items;
import me.arcade.games.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

/**
 * Keeps spectators harmless: survival mode + flying, but they cannot touch, break, place, hit, pick up or take damage.
 * All the switches are in spectate.yml.
 */
public class SpectatorListener implements Listener {
    private final ArcadePlugin plugin;
    private BukkitTask task;

    public SpectatorListener(ArcadePlugin plugin) {
        this.plugin = plugin;
    }

    private Game spec(Player p) {
        return plugin.manager().spectatorGame(p);
    }

    private boolean spec(Entity e) {
        return e instanceof Player p && spec(p) != null;
    }

    /** (Re)starts the task that keeps spectators near their arena (spectate.yml: settings.max-distance). */
    public void restart() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            double max = plugin.settings().spectate().getDouble("settings.max-distance", 100.0);
            for (Player p : Bukkit.getOnlinePlayers()) {
                Game g = spec(p);
                if (g == null) continue;
                // spectators must always be able to fly (flying is reset by some plugins / teleports)
                if (plugin.settings().spectate().getBoolean("settings.flight", true) && !p.getAllowFlight()) {
                    p.setAllowFlight(true);
                    p.setFlying(true);
                }
                if (max <= 0) continue;
                Location home = g.arena().spectatorSpawn();
                Location at = p.getLocation();
                if (home.getWorld() == null) continue;
                if (!home.getWorld().equals(at.getWorld()) || home.distanceSquared(at) > max * max) {
                    p.teleport(home);
                    Msg.send(p, plugin.settings().msg("spectate.too-far"));
                }
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Game g = spec(e.getPlayer());
        if (g != null) g.stopSpectating(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Game g = spec(p);
        if (g == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (right && Items.tagOf(plugin, e.getItem(), "spectate_leave") != null) {
            g.stopSpectating(p);
            Msg.send(p, plugin.settings().msg("spectate.stopped"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (spec(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (spec(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (spec(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (spec(e.getEntity())) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity d = by.getDamager();
            if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
            if (spec(d)) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (spec(e.getTarget())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (spec(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (spec(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (spec(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventory(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && spec(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (spec(p) == null || p.hasPermission("arcade.admin")) return;
        if (!plugin.settings().spectate().getBoolean("settings.block-commands", true)) return;
        String msg = e.getMessage().toLowerCase();
        if (msg.startsWith("/arcade") || msg.equals("/ag") || msg.startsWith("/ag ")) return;
        e.setCancelled(true);
        Msg.send(p, plugin.settings().msg("spectate.commands-blocked"));
    }
}
