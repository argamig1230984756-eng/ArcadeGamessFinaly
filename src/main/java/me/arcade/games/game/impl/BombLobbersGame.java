package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Loadouts;
import me.arcade.games.util.Msg;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * BombLobbers: two teams (spawn 1 = Red, spawn 2 = Blue). Throw TNT, blow up the floor under the other
 * team and knock them off the arena. There is no timer - the match lasts until only one team is left.
 */
public final class BombLobbersGame extends Game {
    private static final String[] NAMES = {"", "Red", "Blue"};
    private static final String[] CODES = {"", "&c", "&9"};
    private static final Color[] COLORS = {Color.WHITE, Color.RED, Color.BLUE};

    private final Map<UUID, Integer> teamOf = new HashMap<>();
    private final Map<Integer, Integer> teamSize = new HashMap<>();
    private final Map<Block, BlockData> changed = new LinkedHashMap<>();
    private long lastSupply;

    public BombLobbersGame(ArcadePlugin plugin, Arena arena) {
        super(plugin, arena);
    }

    // ------------------------------------------------------------ start

    private Location spawnOf(int team) {
        Location l = arena.spawns.get(team);
        return l != null ? l.clone() : spawnFor(team - 1);
    }

    @Override
    protected void onStart() {
        List<Player> list = allPlayers();
        Collections.shuffle(list);
        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            int team = (i % 2) + 1;
            teamOf.put(p.getUniqueId(), team);
            teamSize.merge(team, 1, Integer::sum);
            p.teleport(spawnOf(team));
            p.setGameMode(GameMode.SURVIVAL);
            Loadouts.give(plugin, p, "bomblobbers");
            p.getInventory().setBoots(boots(team));
            p.setAllowFlight(true);
            p.setFlying(false);
            Msg.title(p, t("bomblobbers.team-title", "color", CODES[team], "team", NAMES[team]), t("bomblobbers.team-subtitle"));
        }
        lastSupply = ticks;
        broadcast(t("bomblobbers.start"));
    }

    private ItemStack boots(int team) {
        ItemStack boots = new ItemStack(Material.LEATHER_BOOTS);
        if (boots.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(COLORS[team]);
            meta.displayName(Msg.c(CODES[team] + "&lFeathered Boots"));
            meta.lore(List.of(Msg.c("&7Prevents fall damage")));
            meta.setUnbreakable(true);
            boots.setItemMeta(meta);
        }
        return boots;
    }

    // ------------------------------------------------------------ tick

    private int lowestSpawnY() {
        double y = Double.MAX_VALUE;
        for (Location l : arena.spawns.values()) y = Math.min(y, l.getY());
        return (int) y;
    }

    @Override
    protected void tick() {
        if (ticks - lastSupply >= Math.max(1, cfg().getInt("tnt.supply-interval-ticks", 160))) {
            lastSupply = ticks;
            int max = cfg().getInt("tnt.supply-max", 3);
            for (Player p : alivePlayers()) {
                int held = 0;
                for (ItemStack it : p.getInventory().getStorageContents()) {
                    if (it != null && it.getType() == Material.TNT) held += it.getAmount();
                }
                if (held < max) p.getInventory().addItem(new ItemStack(Material.TNT));
            }
        }
        double voidY = arena.hasRegion() ? arena.minY() - 2 : lowestSpawnY() - 60;
        for (Player p : alivePlayers()) {
            if (state != State.ACTIVE) return;
            if (p.isOnGround() && !p.getAllowFlight()) p.setAllowFlight(true);
            if (p.getLocation().getY() < voidY) {
                Integer team = teamOf.get(p.getUniqueId());
                broadcast(t("bomblobbers.fell", "color", team == null ? "&7" : CODES[team], "player", p.getName()));
                eliminate(p);
            }
        }
    }

    // ------------------------------------------------------------ throwing TNT and leaping

    /**
     * Right-click with TNT throws it exactly where you look. The TNT keeps normal gravity, so it flies in an
     * arc (projectile motion): throw-strength is the speed along your view direction, throw-lift adds extra
     * upward speed. Both are set in games/bomblobbers.yml.
     */
    @Override
    public void onInteract(Player p, PlayerInteractEvent e) {
        if (!alive.contains(p.getUniqueId()) || e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.TNT) return;
        e.setCancelled(true);
        hand.setAmount(hand.getAmount() - 1);

        Vector look = p.getEyeLocation().getDirection().normalize();
        double strength = Math.max(0.0, cfg().getDouble("tnt.throw-strength", 1.25));
        double lift = cfg().getDouble("tnt.throw-lift", 0.1);
        Vector velocity = look.clone().multiply(strength).add(new Vector(0.0, lift, 0.0));
        int fuse = Math.max(1, cfg().getInt("tnt.fuse-ticks", 50));
        Location origin = p.getEyeLocation().add(look.clone().multiply(0.6));

        p.getWorld().spawn(origin, TNTPrimed.class, bomb -> {
            bomb.setGravity(true);
            bomb.setVelocity(velocity);
            bomb.setFuseTicks(fuse);
            bomb.setSource(p);
            bomb.addScoreboardTag("ag_bomblobbers_tnt");
        });
    }

    @Override
    public void onFlight(Player p, PlayerToggleFlightEvent e) {
        if (!alive.contains(p.getUniqueId())) return;
        e.setCancelled(true);
        p.setFlying(false);
        p.setAllowFlight(false);
        Vector direction = p.getLocation().getDirection().normalize().multiply(0.75);
        direction.setY(0.85);
        p.setVelocity(direction);
    }

    /** Players only lose by falling off the arena; every kind of damage is cancelled. */
    @Override
    public void onDamage(Player victim, EntityDamageEvent e) {
        e.setCancelled(true);
    }

    @Override
    public void recordExplosionBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            if (arena.contains(block)) changed.putIfAbsent(block, block.getBlockData().clone());
        }
    }

    // ------------------------------------------------------------ end

    @Override
    protected void checkEnd() {
        Set<Integer> living = new TreeSet<>();
        for (UUID id : alive) {
            Integer team = teamOf.get(id);
            if (team != null) living.add(team);
        }
        if (living.size() > 1) return;
        if (living.isEmpty()) {
            end(t("bomblobbers.nobody-won"));
            return;
        }
        int winner = living.iterator().next();
        end(t("bomblobbers.team-won", "color", CODES[winner], "team", NAMES[winner]));
    }

    @Override
    protected void onEnd() {
        for (Map.Entry<Block, BlockData> entry : changed.entrySet()) {
            entry.getKey().setBlockData(entry.getValue(), false);
        }
        changed.clear();
        teamOf.clear();
        teamSize.clear();
    }

    // ------------------------------------------------------------ scoreboard placeholders

    /** Alive players / team size at the start, e.g. "2/3". */
    private String teamStatus(int team) {
        if (!teamSize.containsKey(team)) return "-";
        int left = 0;
        for (UUID id : alive) {
            if (Objects.equals(teamOf.get(id), team)) left++;
        }
        return left + "/" + teamSize.getOrDefault(team, 0);
    }

    @Override
    public String scoreboardValue(Player player, String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "team_red" -> teamStatus(1);
            case "team_blue" -> teamStatus(2);
            case "team" -> {
                Integer team = teamOf.get(player.getUniqueId());
                yield team == null ? "-" : NAMES[team];
            }
            default -> super.scoreboardValue(player, key);
        };
    }
}
