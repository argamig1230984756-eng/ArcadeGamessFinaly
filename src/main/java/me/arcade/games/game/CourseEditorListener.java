package me.arcade.games.game;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.arena.GameType;
import me.arcade.games.game.impl.ArcadeModeGame;
import me.arcade.games.util.Items;
import me.arcade.games.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.entity.Player;

import java.util.Map;

public final class CourseEditorListener implements Listener {
    private final ArcadePlugin plugin;
    public CourseEditorListener(ArcadePlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onToolUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        String coinArena = Items.tagOf(plugin, e.getItem(), "temple_coin_tool");
        String endArena = Items.tagOf(plugin, e.getItem(), "temple_end_tool");
        if (coinArena == null && endArena == null) return;
        e.setCancelled(true);
        if (!p.hasPermission("arcade.admin")) { Msg.send(p, "&cYou need arcade.admin to use this tool."); return; }
        Arena ar = plugin.manager().arena(coinArena != null ? coinArena : endArena);
        if (ar == null || ar.type != GameType.TEMPLE_RUN) { Msg.send(p, "&cThat Temple Run arena no longer exists."); return; }
        Block place = e.getClickedBlock().getRelative(e.getBlockFace());
        if (place.getType() != Material.AIR && !place.isPassable()) { Msg.send(p, "&cClick the side/top of a block with open space to place the marker."); return; }
        Location loc = place.getLocation();
        if (coinArena != null) {
            int n = ar.coins.isEmpty() ? 1 : ar.coins.lastKey() + 1;
            loc.add(0.5, 0.15, 0.5); ar.coins.put(n, loc); plugin.manager().save();
            Msg.send(p, "&aTemple coin location #" + n + " added to &e" + ar.name + "&a.");
        } else {
            place.setType(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, false);
            ar.finishes.put(ar.finishes.isEmpty() ? 1 : ar.finishes.lastKey() + 1, place.getLocation()); plugin.manager().save();
            Msg.send(p, "&aTemple Run finish pressure plate registered for &e" + ar.name + "&a.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getTo() == null) return;
        var game = plugin.manager().gameOf(e.getPlayer());
        if (game instanceof ArcadeModeGame arcade && game.state() == Game.State.ACTIVE) {
            arcade.tryCollectNearbyCoin(e.getPlayer());
            if (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockY() != e.getTo().getBlockY() || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) arcade.onCourseStep(e.getPlayer(), e.getTo().getBlock());
        }
    }
}
