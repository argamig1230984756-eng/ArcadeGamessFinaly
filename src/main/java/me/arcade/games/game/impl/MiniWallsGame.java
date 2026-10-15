package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wither;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;

import java.util.*;

/**
 * Up to 4 teams. Each team owns a Wither. While your Wither lives you respawn;
 * once it dies you only get one life. Last team standing wins.
 */
public class MiniWallsGame extends Game {
    private static final String[] NAMES = {"", "Red", "Green", "Blue", "Yellow"};
    private static final String[] CODES = {"", "&c", "&a", "&9", "&e"};
    private static final Color[] COLORS = {Color.WHITE, Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
    private double witherHp = 300;
    private int timeLimit; // ticks, 0 = no limit
    private int wallRemovalTicks;
    private boolean wallsRemoved;

    private final Map<UUID, Integer> teamOf = new HashMap<>();
    private final Map<Integer, Integer> teamSize = new HashMap<>();
    private final Map<Integer, Wither> withers = new HashMap<>();
    private final Set<Integer> deadWithers = new HashSet<>();
    private final List<Integer> teams = new ArrayList<>();
    private final Set<Block> placed = new HashSet<>();
    private final Map<Block, BlockData> removedWallBlocks = new LinkedHashMap<>();
    private final Map<UUID, UUID> lastHit = new HashMap<>();

    public MiniWallsGame(ArcadePlugin plugin, Arena arena) {
        super(plugin, arena);
    }

    @Override
    protected void onStart() {
        witherHp = Math.max(1, cfg().getDouble("wither-health"));
        timeLimit = Math.max(0, cfg().getInt("time-limit-minutes")) * 60 * 20;
        wallRemovalTicks = Math.max(0, cfg().getInt("wall-removal-seconds", cfg().getInt("wall-regions.removal-seconds", 30))) * 20;
        if (!cfg().getBoolean("wall-regions.enabled", true)) wallRemovalTicks = 0;
        wallsRemoved = false;
        List<Integer> ids = new ArrayList<>(arena.spawns.keySet());
        int n = Math.min(ids.size(), players.size());
        if (n <= 0) return;
        teams.addAll(ids.subList(0, n));

        List<Player> list = allPlayers();
        Collections.shuffle(list);
        for (int i = 0; i < list.size(); i++) {
            Player p = list.get(i);
            int t = teams.get(i % n);
            teamOf.put(p.getUniqueId(), t);
            teamSize.merge(t, 1, Integer::sum);
            p.teleport(arena.spawns.get(t));
            p.setGameMode(GameMode.SURVIVAL);
            kit(p, t);
            Msg.title(p, t("miniwalls.team-title", "color", CODES[t], "team", NAMES[t]), t("miniwalls.team-subtitle"));
        }

        for (int t : teams) {
            final int team = t;
            Location loc = arena.withers.get(team);
            Wither w = loc.getWorld().spawn(loc, Wither.class, x -> {
                x.setAI(false);
                x.setRemoveWhenFarAway(false);
                x.setPersistent(false);
                x.setCustomNameVisible(true);
                x.customName(Msg.c(t("miniwalls.wither-name", "color", CODES[team], "team", NAMES[team])));
                AttributeInstance ai = x.getAttribute(Attribute.MAX_HEALTH);
                if (ai != null) ai.setBaseValue(witherHp);
                x.setHealth(witherHp);
            });
            withers.put(team, w);
        }
        broadcast(t("miniwalls.start"));
    }

    private void kit(Player p, int t) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setHelmet(leather(Material.LEATHER_HELMET, t));
        inv.setChestplate(leather(Material.LEATHER_CHESTPLATE, t));
        inv.setLeggings(leather(Material.LEATHER_LEGGINGS, t));
        inv.setBoots(leather(Material.LEATHER_BOOTS, t));
        me.arcade.games.util.Loadouts.give(plugin, p, "miniwall");
    }

    private ItemStack leather(Material m, int t) {
        ItemStack is = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) is.getItemMeta();
        meta.setColor(COLORS[t]);
        meta.setUnbreakable(true);
        is.setItemMeta(meta);
        return is;
    }

    private Integer witherTeam(Entity e) {
        for (Map.Entry<Integer, Wither> en : withers.entrySet()) {
            if (en.getValue().getUniqueId().equals(e.getUniqueId())) return en.getKey();
        }
        return null;
    }

    private int lowestSpawnY() {
        double y = Double.MAX_VALUE;
        for (Location l : arena.spawns.values()) y = Math.min(y, l.getY());
        return (int) y;
    }

    @Override
    protected void tick() {
        if (!wallsRemoved && wallRemovalTicks > 0 && ticks >= wallRemovalTicks) removeConfiguredWalls();
        if (!wallsRemoved && wallRemovalTicks > 0 && ticks % 20 == 0) {
            long left = (wallRemovalTicks - ticks) / 20L;
            if (left > 0 && (left <= 5 || left == 10 || left == 30 || left == 60)) {
                broadcast(t("miniwalls.walls-countdown", "seconds", left));
                sound(Sound.BLOCK_NOTE_BLOCK_HAT);
            }
        }
        double voidY = arena.hasRegion() ? arena.minY() - 5 : lowestSpawnY() - 60;
        for (Player p : alivePlayers()) {
            if (state != State.ACTIVE) return;
            if (p.getLocation().getY() < voidY) die(p, null);
        }
        if (state != State.ACTIVE) return;
        if (ticks % 20 == 0) {
            for (Map.Entry<Integer, Wither> en : withers.entrySet()) {
                Wither w = en.getValue();
                if (w.isDead()) continue;
                int t = en.getKey();
                w.customName(Msg.c(t("miniwalls.wither-name-hp", "color", CODES[t], "team", NAMES[t], "hp", (int) w.getHealth())));
            }
        }
        if (timeLimit > 0 && ticks >= timeLimit) timeUp();
    }


    private void removeConfiguredWalls() {
        wallsRemoved = true;
        List<String> configured = cfg().getStringList("wall-materials");
        if (configured.isEmpty()) configured = List.of("GLASS", "WHITE_STAINED_GLASS", "RED_STAINED_GLASS", "BLUE_STAINED_GLASS", "YELLOW_STAINED_GLASS", "GREEN_STAINED_GLASS");
        Set<Material> materials = new HashSet<>();
        for (String name : configured) {
            Material material = Material.matchMaterial(name);
            if (material != null) materials.add(material);
        }
        // Explicit wall cuboids take priority and can be registered independently for each team.
        if (!arena.walls.isEmpty()) {
            for (List<Arena.WallRegion> regions : arena.walls.values()) for (Arena.WallRegion wall : regions) {
                if (wall.pos1 == null || wall.pos2 == null || wall.pos1.getWorld() == null || !wall.pos1.getWorld().equals(wall.pos2.getWorld())) continue;
                int minX = Math.min(wall.pos1.getBlockX(), wall.pos2.getBlockX()), maxX = Math.max(wall.pos1.getBlockX(), wall.pos2.getBlockX());
                int minY = Math.max(wall.pos1.getWorld().getMinHeight(), Math.min(wall.pos1.getBlockY(), wall.pos2.getBlockY())), maxY = Math.min(wall.pos1.getWorld().getMaxHeight()-1, Math.max(wall.pos1.getBlockY(), wall.pos2.getBlockY()));
                int minZ = Math.min(wall.pos1.getBlockZ(), wall.pos2.getBlockZ()), maxZ = Math.max(wall.pos1.getBlockZ(), wall.pos2.getBlockZ());
                for (int x=minX;x<=maxX;x++) for(int y=minY;y<=maxY;y++) for(int z=minZ;z<=maxZ;z++) {
                    Block block = wall.pos1.getWorld().getBlockAt(x,y,z);
                    removedWallBlocks.putIfAbsent(block, block.getBlockData().clone());
                    block.setType(Material.AIR,false);
                }
            }
        } else if (arena.hasRegion() && !materials.isEmpty()) {
            for (int x = arena.minX(); x <= arena.maxX(); x++) for (int y = Math.max(arena.minY(), arena.world().getMinHeight()); y <= Math.min(arena.maxY(), arena.world().getMaxHeight() - 1); y++) for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                Block block = arena.world().getBlockAt(x, y, z); if (materials.contains(block.getType())) {
                    removedWallBlocks.putIfAbsent(block, block.getBlockData().clone());
                    block.setType(Material.AIR, false);
                }
            }
        }
        broadcast(t("miniwalls.walls-removed", "seconds", wallRemovalTicks / 20));
        sound(Sound.ENTITY_ENDER_DRAGON_GROWL);
    }

    /** Alive players / team size at the start, e.g. "3/4", or "-" when that team is not in this match. */
    private String teamStatus(int team) {
        if (!teams.contains(team)) return "-";
        int left = 0;
        for (UUID id : alive) if (Objects.equals(teamOf.get(id), team)) left++;
        return left + "/" + teamSize.getOrDefault(team, 0);
    }

    @Override
    public String scoreboardValue(Player player, String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "team_red" -> teamStatus(1);
            case "team_green" -> teamStatus(2);
            case "team_blue" -> teamStatus(3);
            case "team_yellow" -> teamStatus(4);
            case "team" -> {
                Integer t = teamOf.get(player.getUniqueId());
                yield t == null ? "-" : NAMES[t];
            }
            case "walls" -> {
                if (wallsRemoved) yield "Dropped";
                if (wallRemovalTicks <= 0) yield "-";
                long sec = Math.max(0L, (wallRemovalTicks - ticks + 19L) / 20L);
                yield String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
            }
            default -> super.scoreboardValue(player, key);
        };
    }

    private void timeUp() {
        int best = -1;
        double hp = -1;
        for (int t : teams) {
            Wither w = withers.get(t);
            if (!deadWithers.contains(t) && w != null && w.getHealth() > hp) {
                hp = w.getHealth();
                best = t;
            }
        }
        end(t("miniwalls.time-up", "result", best < 0 ? t("miniwalls.time-up-nobody")
                : t("miniwalls.time-up-winner", "color", CODES[best], "team", NAMES[best])));
    }

    // ------------------------------------------------------------ combat

    @Override
    public void onDamage(Player victim, EntityDamageEvent e) {
        UUID id = victim.getUniqueId();
        if (!alive.contains(id)) {
            e.setCancelled(true);
            return;
        }
        Player a = attacker(e);
        if (a != null && !a.equals(victim)) {
            if (!alive.contains(a.getUniqueId())
                    || Objects.equals(teamOf.get(a.getUniqueId()), teamOf.get(id))) {
                e.setCancelled(true);
                return;
            }
            lastHit.put(id, a.getUniqueId());
        }
        if (victim.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            die(victim, a != null && !a.equals(victim) ? a : null);
        }
    }

    private void die(Player victim, Player killer) {
        UUID id = victim.getUniqueId();
        int t = teamOf.getOrDefault(id, 0);
        if (killer == null) {
            UUID k = lastHit.get(id);
            if (k != null) killer = Bukkit.getPlayer(k);
        }
        lastHit.remove(id);
        String vn = CODES[t] + victim.getName();
        if (killer != null) {
            int kt = teamOf.getOrDefault(killer.getUniqueId(), 0);
            broadcast(t("miniwalls.killed-by", "victim", vn, "killer", CODES[kt] + killer.getName()));
        } else {
            broadcast(t("miniwalls.died", "victim", vn));
        }
        if (deadWithers.contains(t)) {
            eliminate(victim);
        } else {
            for (PotionEffect pe : new ArrayList<>(victim.getActivePotionEffects())) victim.removePotionEffect(pe.getType());
            victim.setHealth(20.0);
            victim.setFoodLevel(20);
            victim.setFireTicks(0);
            victim.setFallDistance(0f);
            victim.teleport(arena.spawns.get(t));
            kit(victim, t);
        }
    }

    @Override
    public boolean ownsEntity(Entity e) {
        return witherTeam(e) != null;
    }

    @Override
    public void onEntityDamage(EntityDamageEvent e) {
        if (state != State.ACTIVE) {
            e.setCancelled(true);
            return;
        }
        Player d = attacker(e);
        if (d == null || !alive.contains(d.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        Integer t = witherTeam(e.getEntity());
        if (t != null && t.equals(teamOf.get(d.getUniqueId()))) {
            e.setCancelled(true);
            Msg.bar(d, t("miniwalls.own-wither"));
        }
    }

    @Override
    public void onEntityDeath(EntityDeathEvent e) {
        e.getDrops().clear();
        e.setDroppedExp(0);
        Integer t = witherTeam(e.getEntity());
        if (t == null || state != State.ACTIVE) return;
        deadWithers.add(t);
        broadcast(t("miniwalls.wither-destroyed", "color", CODES[t], "team", NAMES[t]));
        for (Player p : allPlayers()) {
            Integer pt = teamOf.get(p.getUniqueId());
            if (pt != null && pt.equals(t)) Msg.title(p, t("miniwalls.wither-died-title"), t("miniwalls.wither-died-subtitle"));
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.7f, 1f);
        }
        checkEnd();
    }

    @Override
    protected void checkEnd() {
        if (teams.isEmpty()) return;
        List<Integer> living = new ArrayList<>();
        for (int t : teams) {
            boolean has = !deadWithers.contains(t);
            if (!has) {
                for (UUID id : alive) {
                    if (Objects.equals(teamOf.get(id), t)) {
                        has = true;
                        break;
                    }
                }
            }
            if (has) living.add(t);
        }
        if (living.size() <= 1) {
            if (living.isEmpty()) end(t("miniwalls.nobody-won"));
            else end(t("miniwalls.team-won", "color", CODES[living.get(0)], "team", NAMES[living.get(0)]));
        }
    }

    // ------------------------------------------------------------ blocks

    @Override
    public boolean allowPlace(Player p, Block b) {
        if (!alive.contains(p.getUniqueId())) return false;
        placed.add(b);
        return true;
    }

    @Override
    public boolean allowBreak(Player p, Block b) {
        return alive.contains(p.getUniqueId()) && placed.remove(b);
    }

    @Override public boolean allowDrops() { return true; }
    @Override public boolean allowPickup() { return true; }
    @Override public boolean allowInventory() { return true; }

    @Override
    protected void onEnd() {
        for (Wither w : withers.values()) w.remove();
        // Restore every wall block removed at round start, including its original block data.
        for (Map.Entry<Block, BlockData> entry : removedWallBlocks.entrySet()) {
            entry.getKey().setBlockData(entry.getValue(), false);
        }
        removedWallBlocks.clear();
        for (Block b : placed) b.setType(Material.AIR, false);
        withers.clear();
        placed.clear();
        deadWithers.clear();
        teams.clear();
        teamOf.clear();
        teamSize.clear();
        lastHit.clear();
    }
}
