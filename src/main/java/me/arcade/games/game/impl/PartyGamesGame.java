package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import me.arcade.games.util.Settings;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.*;

/**
 * A series of short micro-games. Points are awarded by survival order; highest total wins.
 */
public class PartyGamesGame extends Game {
    /** The mini-games; their title, length and options live in games/partygames.yml under "minigames.&lt;key&gt;". */
    private enum Mini {
        ANVIL_RAIN("anvil-rain"), BOMBARDMENT("bombardment"), SUMO("sumo"), SPLEEF("spleef"),
        PIG_FISHING("pig-fishing"), AVALANCHE("avalanche"), TRAMPOLINO("trampolino"),
        PUNCH_THE_BATS("punch-the-bats"), MINECART_RACING("minecart-racing"), ANIMAL_SLAUGHTER("animal-slaughter"),
        VOLCANO("volcano"), HOE_HOE_HOE("hoe-hoe-hoe"), FROZEN_FLOOR("frozen-floor"),
        FLOOR_IS_LAVA("floor-is-lava"), JUNGLE_JUMP("jungle-jump");

        final String key;

        Mini(String key) {
            this.key = key;
        }
    }

    private enum Phase { INTRO, PLAY, BREAK }

    private final Map<UUID, Integer> points = new HashMap<>();
    private final List<UUID> outOrder = new ArrayList<>();
    private final Map<Block, BlockData> removed = new HashMap<>();
    private final Random rnd = new Random();
    private List<Mini> queue = new ArrayList<>();
    private Mini current;
    private Phase phase = Phase.INTRO;
    private int index, timer, elapsed, floorY;

    public PartyGamesGame(ArcadePlugin plugin, Arena arena) {
        super(plugin, arena);
    }

    private String mini(String field) {
        return cfg().getString("minigames." + current.key + "." + field);
    }

    private int miniInt(String field) {
        return cfg().getInt("minigames." + current.key + "." + field);
    }

    /** Hazards drop faster as the round goes on: ticks per interval step / per extra hazard. */
    private int stepTicks() {
        return Math.max(1, Settings.ticks(cfg().getDouble("difficulty.interval-step-seconds")));
    }

    private int extraTicks() {
        return Math.max(1, Settings.ticks(cfg().getDouble("difficulty.extra-hazard-every-seconds")));
    }

    @Override
    protected void onStart() {
        floorY = arena.minY();
        for (UUID id : players) points.put(id, 0);
        queue = new ArrayList<>();
        for (Mini mi : Mini.values()) {
            if (cfg().getBoolean("minigames." + mi.key + ".enabled")) queue.add(mi);
        }
        if (queue.isEmpty()) queue.addAll(Arrays.asList(Mini.values())); // everything disabled: play them all
        if (cfg().getBoolean("random-order")) Collections.shuffle(queue);
        index = -1;
        nextRound();
    }

    private void nextRound() {
        restoreFloor();
        clearHazards();
        index++;
        if (index >= queue.size()) {
            finishGame();
            return;
        }
        current = queue.get(index);
        phase = Phase.INTRO;
        timer = Math.max(1, Settings.ticks(cfg().getDouble("intro-seconds")));
        elapsed = 0;
        outOrder.clear();
        alive.clear();
        alive.addAll(players);
        if (current == Mini.SPLEEF) buildSpleefFloor();
        int i = 0;
        for (Player p : allPlayers()) {
            reset(p, GameMode.ADVENTURE);
            p.teleport(spawnFor(i++));
            Msg.title(p, t("partygames.round-title", "title", mini("title")), "&7" + mini("description"));
        }
        broadcast(t("partygames.round", "round", index + 1, "total", queue.size(),
                "title", mini("title"), "description", mini("description")));
    }

    private void beginRound() {
        phase = Phase.PLAY;
        timer = Math.max(1, miniInt("seconds")) * 20;
        elapsed = 0;
        for (Player p : alivePlayers()) {
            switch (current) {
                case SUMO -> p.getInventory().addItem(Msg.item(Material.STICK, 1, mini("item-name")));
                case SPLEEF -> {
                    p.setGameMode(GameMode.SURVIVAL);
                    p.getInventory().addItem(Msg.item(Material.IRON_SHOVEL, 1, mini("item-name")));
                }
                case PIG_FISHING -> p.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.FISHING_ROD));
                case PUNCH_THE_BATS -> p.getInventory().addItem(Msg.item(Material.STICK, 1, "&eBat Puncher"));
                case MINECART_RACING -> { p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, timer + 100, 1)); p.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.MINECART)); }
                case ANIMAL_SLAUGHTER -> p.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.IRON_SWORD));
                case VOLCANO -> p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, timer + 100, 0));
                case HOE_HOE_HOE -> p.getInventory().addItem(new org.bukkit.inventory.ItemStack(Material.DIAMOND_HOE));
                case FROZEN_FLOOR -> p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SPEED, timer + 100, 0));
                case TRAMPOLINO, JUNGLE_JUMP -> p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST, timer + 100, 2));
                default -> {}
            }
            Msg.title(p, t("partygames.go"), "");
        }
    }

    @Override
    protected void tick() {
        timer--;
        switch (phase) {
            case INTRO -> {
                if (ticks % 20 == 0) {
                    if (plugin.getConfig().getBoolean("timer.enabled", true)) for (Player p : allPlayers()) Msg.bar(p, t("partygames.intro-countdown", "seconds", timer / 20 + 1));
                }
                if (timer <= 0) beginRound();
            }
            case PLAY -> {
                elapsed++;
                playTick();
                if (phase == Phase.PLAY && timer <= 0) endRound();
            }
            case BREAK -> {
                if (timer <= 0) nextRound();
            }
        }
    }

    private void playTick() {
        for (Player p : alivePlayers()) {
            if (state != State.ACTIVE || phase != Phase.PLAY) return;
            if (p.getLocation().getY() < floorY - 3) {
                broadcast(t("partygames.out", "player", p.getName()));
                out(p);
            }
        }
        if (state != State.ACTIVE || phase != Phase.PLAY) return;
        World w = arena.world();
        if (current == Mini.ANVIL_RAIN) {
            int interval = Math.max(Math.max(1, miniInt("min-interval-ticks")), miniInt("start-interval-ticks") - elapsed / stepTicks());
            if (elapsed % interval == 0) {
                int count = 1 + elapsed / extraTicks();
                for (int i = 0; i < count; i++) {
                    FallingBlock fb = w.spawnFallingBlock(randomTop(), Material.ANVIL.createBlockData());
                    fb.setHurtEntities(true);
                    fb.setDropItem(false);
                    fb.setDamagePerBlock(2f);
                    fb.setMaxDamage(12);
                    fb.addScoreboardTag("ag_fall");
                }
            }
        } else if (current == Mini.BOMBARDMENT || current == Mini.AVALANCHE || current == Mini.VOLCANO) {
            int interval = Math.max(Math.max(1, miniInt("min-interval-ticks")), miniInt("start-interval-ticks") - elapsed / stepTicks());
            if (elapsed % interval == 0) {
                int count = 1 + elapsed / extraTicks();
                for (int i = 0; i < count; i++) {
                    if (current == Mini.AVALANCHE) {
                        FallingBlock fb = w.spawnFallingBlock(randomTop(), Material.SNOW_BLOCK.createBlockData()); fb.setHurtEntities(true); fb.setDropItem(false); fb.addScoreboardTag("ag_fall");
                    } else if (current == Mini.VOLCANO) {
                        Location top = randomTop(); Block b = w.getBlockAt(top.getBlockX(), floorY + 1, top.getBlockZ()); removed.putIfAbsent(b, b.getBlockData()); b.setType(Material.MAGMA_BLOCK, false);
                    } else {
                        w.spawn(randomTop(), TNTPrimed.class, t -> { t.setFuseTicks(Math.max(1, miniInt("fuse-ticks"))); t.addScoreboardTag("ag_tnt"); });
                    }
                }
            }
        } else if (current == Mini.FLOOR_IS_LAVA) {
            for (Player p : alivePlayers()) if (p.getLocation().getY() < floorY + 1.2) out(p);
        } else if (current == Mini.TRAMPOLINO || current == Mini.JUNGLE_JUMP) {
            for (Player p : alivePlayers()) if (p.isOnGround() && p.getLocation().getY() >= floorY) p.setVelocity(p.getVelocity().setY(current == Mini.TRAMPOLINO ? 0.95 : 0.72));
        } else if (current == Mini.FROZEN_FLOOR) {
            for (Player p : alivePlayers()) { Block b = p.getLocation().subtract(0, 1, 0).getBlock(); if (arena.contains(b) && !b.getType().isAir()) { removed.putIfAbsent(b, b.getBlockData()); b.setType(Material.AIR, false); } }
        }
    }

    private Location randomTop() {
        World w = arena.world();
        int y = Math.min(floorY + 15, w.getMaxHeight() - 2);
        List<Player> al = alivePlayers();
        if (!al.isEmpty() && rnd.nextInt(4) == 0) {
            Location pl = al.get(rnd.nextInt(al.size())).getLocation();
            return new Location(w, pl.getX(), y, pl.getZ());
        }
        double x = arena.minX() + rnd.nextInt(arena.maxX() - arena.minX() + 1) + 0.5;
        double z = arena.minZ() + rnd.nextInt(arena.maxZ() - arena.minZ() + 1) + 0.5;
        return new Location(w, x, y, z);
    }

    private void out(Player p) {
        outOrder.add(p.getUniqueId());
        points.merge(p.getUniqueId(), outOrder.size() - 1, Integer::sum);
        eliminate(p);
    }

    private void endRound() {
        if (phase != Phase.PLAY) return;
        phase = Phase.BREAK;
        timer = Math.max(1, Settings.ticks(cfg().getDouble("break-seconds")));
        int bonus = outOrder.size() + cfg().getInt("survivor-bonus");
        List<String> names = new ArrayList<>();
        for (UUID id : alive) {
            points.merge(id, bonus, Integer::sum);
            names.add(nameOf(id));
        }
        broadcast(t("partygames.round-over", "names", names.isEmpty() ? t("partygames.nobody-survived") : String.join(", ", names)));
        broadcast(t("partygames.standings", "list", standings(3)));
        clearHazards();
    }

    private String standings(int limit) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> en : points.entrySet()) if (players.contains(en.getKey())) list.add(en);
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            if (i > 0) sb.append("&8, ");
            sb.append("&e").append(nameOf(list.get(i).getKey())).append(" &7(").append(list.get(i).getValue()).append(")");
        }
        return sb.toString();
    }

    private void finishGame() {
        if (state != State.ACTIVE) return;
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> en : points.entrySet()) if (players.contains(en.getKey())) list.add(en);
        list.sort((a, b) -> b.getValue() - a.getValue());
        List<String> lines = new ArrayList<>();
        lines.add(t("partygames.finished"));
        for (int i = 0; i < Math.min(3, list.size()); i++) {
            lines.add(t("partygames.finished-place", "place", i + 1, "player", nameOf(list.get(i).getKey()),
                    "points", list.get(i).getValue()));
        }
        end(lines.toArray(new String[0]));
    }

    @Override
    protected void checkEnd() {
        if (players.size() < 2) {
            finishGame();
            return;
        }
        if (phase == Phase.PLAY && alive.size() <= 1) endRound();
    }

    @Override
    public void onDamage(Player victim, EntityDamageEvent e) {
        if (phase != Phase.PLAY || !alive.contains(victim.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        switch (current) {
            case SUMO -> {
                Player a = attacker(e);
                if (a != null && !a.equals(victim) && alive.contains(a.getUniqueId())) e.setDamage(0);
                else e.setCancelled(true);
            }
            case SPLEEF -> e.setCancelled(true);
            default -> {
                EntityDamageEvent.DamageCause c = e.getCause();
                if (c != EntityDamageEvent.DamageCause.FALLING_BLOCK
                        && c != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                        && c != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
                    e.setCancelled(true);
                    return;
                }
                if (victim.getHealth() - e.getFinalDamage() <= 0) {
                    e.setCancelled(true);
                    broadcast(t("partygames.crushed", "player", victim.getName()));
                    out(victim);
                }
            }
        }
    }

    @Override
    public boolean allowBreak(Player p, Block b) {
        return phase == Phase.PLAY && current == Mini.SPLEEF
                && alive.contains(p.getUniqueId()) && b.getType() == Material.SNOW_BLOCK;
    }

    private void buildSpleefFloor() {
        World w = arena.world();
        for (int x = arena.minX(); x <= arena.maxX(); x++) {
            for (int z = arena.minZ(); z <= arena.maxZ(); z++) {
                Block b = w.getBlockAt(x, floorY, z);
                removed.putIfAbsent(b, b.getBlockData());
                b.setType(Material.SNOW_BLOCK, false);
            }
        }
    }

    private void restoreFloor() {
        for (Map.Entry<Block, BlockData> en : removed.entrySet()) en.getKey().setBlockData(en.getValue(), false);
        removed.clear();
    }

    private void clearHazards() {
        World w = arena.world();
        if (w == null) return;
        for (Entity en : w.getEntities()) {
            Set<String> tags = en.getScoreboardTags();
            if (tags.contains("ag_fall") || tags.contains("ag_tnt")) en.remove();
        }
    }

    @Override
    protected void onEnd() {
        restoreFloor();
        clearHazards();
    }
}
