package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import me.arcade.games.util.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleFlightEvent;

import java.util.*;

public class TNTRunGame extends Game {
    private final Map<Block, BlockData> removed = new HashMap<>();
    private final Set<Block> pending = new HashSet<>();
    private final Map<UUID, Integer> jumps = new HashMap<>();
    private int startDelay, vanishDelay;

    public TNTRunGame(ArcadePlugin plugin, Arena arena) {
        super(plugin, arena);
    }

    @Override
    protected void onStart() {
        startDelay = Settings.ticks(cfg().getDouble("start-delay-seconds"));
        vanishDelay = Math.max(1, Settings.ticks(cfg().getDouble("block-vanish-delay-seconds")));
        int maxJumps = Math.max(0, cfg().getInt("double-jumps"));
        int i = 0;
        for (Player p : allPlayers()) {
            p.teleport(spawnFor(i++));
            p.setAllowFlight(maxJumps > 0);
            jumps.put(p.getUniqueId(), maxJumps);
            Msg.title(p, t("tntrun.title"), t("tntrun.subtitle"));
        }
        broadcast(t("tntrun.start", "seconds", Settings.num(cfg().getDouble("start-delay-seconds")), "jumps", maxJumps));
    }

    @Override
    protected void tick() {
        double fallY = arena.minY() - 2;
        for (Player p : alivePlayers()) {
            if (state != State.ACTIVE) return;
            Location l = p.getLocation();
            if (l.getY() < fallY) {
                broadcast(t("tntrun.fell", "player", p.getName(), "left", alive.size() - 1));
                eliminate(p);
                continue;
            }
            if (ticks < startDelay) continue;
            for (double dx : new double[]{-0.3, 0.3}) {
                for (double dz : new double[]{-0.3, 0.3}) {
                    Block b = l.clone().add(dx, -1, dz).getBlock();
                    if (b.getType().isAir() || !arena.contains(b) || !pending.add(b)) continue;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (state != State.ACTIVE) return;
                        removed.putIfAbsent(b, b.getBlockData());
                        b.setType(Material.AIR, false);
                        Block under = b.getRelative(0, -1, 0);
                        if (under.getType() == Material.TNT) {
                            removed.putIfAbsent(under, under.getBlockData());
                            under.setType(Material.AIR, false);
                        }
                    }, vanishDelay);
                }
            }
        }
    }

    @Override
    public void onFlight(Player p, PlayerToggleFlightEvent e) {
        UUID id = p.getUniqueId();
        if (!alive.contains(id)) return;
        e.setCancelled(true);
        p.setFlying(false);
        int j = jumps.getOrDefault(id, 0);
        if (j <= 0) {
            p.setAllowFlight(false);
            return;
        }
        jumps.put(id, j - 1);
        p.setVelocity(p.getLocation().getDirection().multiply(cfg().getDouble("jump-forward-power")).setY(cfg().getDouble("jump-up-power")));
        Msg.bar(p, t("tntrun.jumps-left", "jumps", j - 1));
        if (j - 1 <= 0) p.setAllowFlight(false);
    }

    @Override
    protected void checkEnd() {
        if (alive.size() > 1) return;
        String winner = alive.isEmpty() ? t("common.nobody") : nameOf(alive.iterator().next());
        end(t("tntrun.win", "winner", winner));
    }

    @Override
    protected void onEnd() {
        for (Map.Entry<Block, BlockData> en : removed.entrySet()) en.getKey().setBlockData(en.getValue(), false);
        removed.clear();
        pending.clear();
        jumps.clear();
    }
}
