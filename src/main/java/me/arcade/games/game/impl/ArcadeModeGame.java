package me.arcade.games.game.impl;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.AxisAngle4f;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Egg;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import java.util.*;

/** Shared implementation for the additional arcade modes. */
public final class ArcadeModeGame extends Game {
    private final Map<Block, BlockData> changed = new LinkedHashMap<>();
    private final Set<UUID> tagged = new HashSet<>();
    private final Map<UUID, Long> tagTimes = new HashMap<>();
    private final Map<UUID, ItemStack> oldHelmets = new HashMap<>();
    private final Random random = new Random();
    private final Map<UUID, Location> checkpoints = new HashMap<>();
    private final Map<UUID, Integer> coins = new HashMap<>();
    private final Map<UUID, Set<UUID>> collectedCoins = new HashMap<>(); // player -> coin display entities that player already took (per-player coins)
    private int secondsLeft = 180;
    private final List<UUID> templeFinishOrder = new ArrayList<>();
    private final Set<UUID> completedRunners = new HashSet<>();
    private final Map<UUID, Long> templeFinishTimes = new HashMap<>();
    private final Map<UUID, Integer> templeProgress = new HashMap<>(); // furthest checkpoint number reached

    public ArcadeModeGame(ArcadePlugin plugin, Arena arena) { super(plugin, arena); }


    private void giveConfiguredItems(Player p, String key) {
        me.arcade.games.util.Loadouts.give(plugin, p, key);
    }

    @Override protected void onStart() {
        int i = 0; secondsLeft = arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN ? Math.max(1, cfg().getInt("course.time-limit-seconds", 300)) : Math.max(30, cfg().getInt("round-seconds", 180));
        for (Player p : allPlayers()) {
            p.teleport(spawnFor(i++)); p.setGameMode(GameMode.SURVIVAL);
            switch (arena.type) {
                case TNT_TAG -> { p.getInventory().clear(); p.getInventory().setHelmet(null); }
                case SPLEGG -> giveConfiguredItems(p, "splegg");
                case QUAKE -> giveConfiguredItems(p, "quake");
                case TEMPLE_RUN -> { p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * secondsLeft, 0, false, false)); checkpoints.put(p.getUniqueId(), p.getLocation().clone()); coins.put(p.getUniqueId(), 0); }
                default -> {}
            }
        }
        if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && !alive.isEmpty()) {
            UUID first = new ArrayList<>(alive).get(random.nextInt(alive.size())); tagged.add(first); tagTimes.put(first, ticks + 20L * cfg().getInt("tag-fuse-seconds", 20)); setTaggedHelmet(first, true);
            Player p = org.bukkit.Bukkit.getPlayer(first); if (p != null) Msg.send(p, "&cYou are IT! Tag another player!");
        }
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN && cfg().getBoolean("course.coins-enabled", true) && arena.world() != null) {
            for (Location loc : arena.coins.values()) {
                if (loc == null || loc.getWorld() == null) continue;
                Material coinMaterial = Material.matchMaterial(cfg().getString("course.coin-item", "GOLD_INGOT"));
                if (coinMaterial == null || coinMaterial.isAir()) coinMaterial = Material.GOLD_INGOT;
                double height = cfg().getDouble("course.coin-height", 0.35);
                Location displayLoc = loc.clone().add(0, height, 0);
                Material itemMaterial = coinMaterial;
                displayLoc.getWorld().spawn(displayLoc, ItemDisplay.class, display -> {
                    display.setItemStack(new ItemStack(itemMaterial));
                    // course.coin-face-players: true -> the client turns the coin to look at every player (VERTICAL keeps it upright).
                    // false -> the coin stays put and spins slowly (old behaviour).
                    display.setBillboard(cfg().getBoolean("course.coin-face-players", true) ? ItemDisplay.Billboard.VERTICAL : ItemDisplay.Billboard.FIXED);
                    display.setRotation(0.0f, 0.0f);
                    float scale = (float) Math.max(0.1, Math.min(4.0, cfg().getDouble("course.coin-size", 0.65)));
                    display.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 0, 1, 0), new Vector3f(scale, scale, scale), new AxisAngle4f(0, 0, 1, 0)));
                    display.setGravity(false);
                    display.setInvulnerable(true);
                    display.setSilent(true);
                    display.addScoreboardTag("ag_temple_coin_display");
                });
            }
        }
        broadcast("&a" + arena.type.display + " started! Survive and be the last player standing.");
    }

    @Override protected void tick() {
        // Timers: only Temple Run and Quake have a time limit. Splegg and TNT Tag run until the
        // last player is left. Nothing is shown above the hotbar (see actionbar.enabled in config.yml).
        if (ticks % 20 == 0 && arena.type != me.arcade.games.arena.GameType.TNT_TAG && arena.type != me.arcade.games.arena.GameType.SPLEGG) {
            secondsLeft--;
            if (secondsLeft <= 0) { finishByScore(); return; }
        }
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN && !cfg().getBoolean("course.coin-face-players", true) && ticks % 2 == 0 && arena.world() != null) {
            for (org.bukkit.entity.Entity entity : arena.world().getEntities()) if (entity instanceof ItemDisplay d && d.getScoreboardTags().contains("ag_temple_coin_display") && arena.contains(d.getLocation().getBlock())) {
                float angle = (ticks % 360) * ((float)Math.PI / 180f);
                d.setTransformation(new Transformation(new Vector3f(0, 0, 0), new AxisAngle4f(0, 1, 0, angle), new Vector3f((float)Math.max(.1, Math.min(4, cfg().getDouble("course.coin-size", .65))), (float)Math.max(.1, Math.min(4, cfg().getDouble("course.coin-size", .65))), (float)Math.max(.1, Math.min(4, cfg().getDouble("course.coin-size", .65)))), new AxisAngle4f(0, 0, 1, 0)));
            }
        }
        for (Player p : alivePlayers()) {
            if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) {
                if (p.getLocation().getY() < arena.minY() - 2) {
                    if (cfg().getBoolean("course.return-to-checkpoint-on-fall", true)) {
                        Location cp = checkpoints.getOrDefault(p.getUniqueId(), spawnFor(0));
                        p.teleport(cp); p.setFallDistance(0); p.setHealth(Math.min(20.0, p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue())); p.setFoodLevel(20);
                        Msg.send(p, "&eYou fell! Returned to your latest checkpoint.");
                    } else eliminate(p);
                    continue;
                }
            } else if (p.getLocation().getY() < arena.minY() - 2) { eliminate(p); continue; }
            if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && tagged.contains(p.getUniqueId()) && ticks >= tagTimes.getOrDefault(p.getUniqueId(), Long.MAX_VALUE)) {
                broadcast("&c" + p.getName() + " exploded!"); eliminate(p);
            }
        }
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN && ticks % 100 == 0) {
            for (Player p : alivePlayers()) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, Math.min(2, (int)(ticks / 1200)), false, false));
        }
    }

    private void setTaggedHelmet(UUID id, boolean enabled) {
        Player p = org.bukkit.Bukkit.getPlayer(id);
        if (p == null) return;
        if (enabled) {
            oldHelmets.putIfAbsent(id, p.getInventory().getHelmet() == null ? null : p.getInventory().getHelmet().clone());
            p.getInventory().setHelmet(new ItemStack(Material.TNT));
        } else {
            p.getInventory().setHelmet(oldHelmets.remove(id));
        }
    }

    /** Collects the half-block display coins configured for Temple Run. Coins are per-player: a coin only disappears for the runner who took it. */
    public void tryCollectNearbyCoin(Player player) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN || !alive.contains(player.getUniqueId()) || !cfg().getBoolean("course.coins-enabled", true)) return;
        Set<UUID> mine = collectedCoins.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        for (org.bukkit.entity.Entity entity : player.getNearbyEntities(0.8, 0.8, 0.8)) {
            if (entity instanceof ItemDisplay && entity.getScoreboardTags().contains("ag_temple_coin_display")) {
                if (!mine.add(entity.getUniqueId())) continue; // this player already collected this coin
                player.hideEntity(plugin, entity); // hidden only for this player; everyone else still sees it
                int value = Math.max(1, cfg().getInt("course.coin-value", 1));
                coins.merge(player.getUniqueId(), value, Integer::sum);
                playCoinSound(player);
                Msg.bar(player, "&6+" + value + " coin &8| &fTotal: &e" + coins.get(player.getUniqueId()));
            }
        }
    }

    /** Plays the configurable coin pickup sound to the collecting player only. */
    private void playCoinSound(Player player) {
        if (!cfg().getBoolean("course.coin-sound.enabled", true)) return;
        String name = cfg().getString("course.coin-sound.sound", "ENTITY_EXPERIENCE_ORB_PICKUP");
        Sound sound = null;
        try { // accepts ENTITY_EXPERIENCE_ORB_PICKUP style names
            Object f = Sound.class.getField(name.trim().toUpperCase(Locale.ROOT)).get(null);
            if (f instanceof Sound found) sound = found;
        } catch (Exception ignored) {}
        if (sound == null) { // or namespaced keys like entity.experience_orb.pickup
            try { sound = Registry.SOUNDS.get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT))); } catch (Exception ignored) {}
        }
        if (sound == null) sound = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
        float volume = (float) cfg().getDouble("course.coin-sound.volume", 1.0);
        float pitch = (float) cfg().getDouble("course.coin-sound.pitch", 1.6);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /** Called by the central pickup listener for older tagged Temple Run item entities. */
    public boolean onCoinPickup(Player player, Item item) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN || !item.getScoreboardTags().contains("ag_temple_coin") || !alive.contains(player.getUniqueId())) return false;
        item.remove();
        playCoinSound(player);
        int value = Math.max(1, cfg().getInt("course.coin-value", 1));
        coins.merge(player.getUniqueId(), value, Integer::sum);
        Msg.bar(player, "&6+" + value + " coin &8| &fTotal: &e" + coins.get(player.getUniqueId()));
        return true;
    }


    /**
     * Live Temple Run ranking: runners who already finished come first (in finish order), then everyone
     * still running, ordered by furthest checkpoint reached and then by distance to the next checkpoint
     * (or to the finish once the last checkpoint is passed).
     */
    private List<UUID> templeRanking() {
        List<UUID> ranking = new ArrayList<>(templeFinishOrder);
        List<UUID> runners = new ArrayList<>(alive);
        runners.removeAll(templeFinishOrder);
        Map<UUID, Double> distance = new HashMap<>();
        for (UUID id : runners) distance.put(id, distanceToNextTarget(id));
        runners.sort(Comparator.<UUID>comparingInt(id -> -templeProgress.getOrDefault(id, 0))
                .thenComparingDouble(id -> distance.getOrDefault(id, Double.MAX_VALUE)));
        ranking.addAll(runners);
        return ranking;
    }

    private double distanceToNextTarget(UUID id) {
        Player p = org.bukkit.Bukkit.getPlayer(id);
        if (p == null || !p.getWorld().equals(arena.world())) return Double.MAX_VALUE;
        Map.Entry<Integer, Location> next = arena.checkpoints.higherEntry(templeProgress.getOrDefault(id, 0));
        Location target = null;
        if (next != null) target = next.getValue();
        else {
            double best = Double.MAX_VALUE;
            for (Location f : arena.finishes.values()) {
                if (f.getWorld() == null || !f.getWorld().equals(p.getWorld())) continue;
                double d = f.distanceSquared(p.getLocation());
                if (d < best) { best = d; target = f; }
            }
        }
        if (target == null || target.getWorld() == null || !target.getWorld().equals(p.getWorld())) return Double.MAX_VALUE;
        return target.distanceSquared(p.getLocation());
    }

    private String templeRankName(int index) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN) return "";
        List<UUID> ranking = templeRanking();
        if (index >= ranking.size()) return "-";
        UUID id = ranking.get(index);
        return (templeFinishOrder.contains(id) ? "\u2714 " : "") + nameOf(id);
    }

    private String templePlaceOf(Player player) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN) return "";
        int i = templeRanking().indexOf(player.getUniqueId());
        if (i < 0) return "-";
        int n = i + 1;
        return n == 1 ? "1st" : n == 2 ? "2nd" : n == 3 ? "3rd" : n + "th";
    }

    @Override public String scoreboardValue(Player player, String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "coins" -> String.valueOf(coins.getOrDefault(player.getUniqueId(), 0));
            case "it" -> tagged.stream().map(this::nameOf).findFirst().orElse("None");
            case "top1" -> templeRankName(0);
            case "top2" -> templeRankName(1);
            case "top3" -> templeRankName(2);
            case "place" -> templePlaceOf(player);
            default -> super.scoreboardValue(player, key);
        };
    }

    @Override public void onDamage(Player victim, EntityDamageEvent e) {
        if (arena.type == me.arcade.games.arena.GameType.TNT_TAG && e instanceof EntityDamageByEntityEvent by) {
            Player attacker = by.getDamager() instanceof Player p ? p : (cfg().getBoolean("tagging.allow-projectile-tagging", false) && by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player shooter ? shooter : null);
            boolean bareHands = !cfg().getBoolean("tagging.bare-hands-only", true) || attacker != null && attacker.getInventory().getItemInMainHand().getType().isAir();
            boolean directHit = by.getDamager() instanceof Player || cfg().getBoolean("tagging.allow-projectile-tagging", false);
            if (attacker != null && bareHands && directHit && alive.contains(attacker.getUniqueId()) && alive.contains(victim.getUniqueId()) && tagged.contains(attacker.getUniqueId()) && !tagged.contains(victim.getUniqueId())) {
                tagged.remove(attacker.getUniqueId()); tagTimes.remove(attacker.getUniqueId()); setTaggedHelmet(attacker.getUniqueId(), false);
                tagged.add(victim.getUniqueId()); tagTimes.put(victim.getUniqueId(), ticks + 20L * cfg().getInt("tag-fuse-seconds", 20)); setTaggedHelmet(victim.getUniqueId(), true);
                Msg.send(victim, "&cYou are IT! Run!"); Msg.send(attacker, "&aYou passed the tag!");
            }
            e.setCancelled(true); return;
        }
        if (arena.type == me.arcade.games.arena.GameType.QUAKE && e instanceof EntityDamageByEntityEvent by) {
            Player attacker = by.getDamager() instanceof Player p ? p : (by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null);
            if (attacker != null && attacker != victim && alive.contains(attacker.getUniqueId())) {
                e.setCancelled(true); broadcast("&e" + victim.getName() + " was blasted by " + attacker.getName() + "!"); eliminate(victim); return;
            }
        }
        e.setCancelled(true);
    }

    @Override public void onInteract(Player p, PlayerInteractEvent e) {
        if (arena.type != me.arcade.games.arena.GameType.SPLEGG || !alive.contains(p.getUniqueId())) return;
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        // In Splegg the iron shovel launches eggs; ammo is infinite.
        if (e.getItem() == null || e.getItem().getType() != Material.IRON_SHOVEL) return;
        e.setCancelled(true);
        // Infinite ammo: no eggs are needed in the inventory.
        Egg egg = p.launchProjectile(Egg.class);
        egg.setVelocity(p.getLocation().getDirection().multiply(1.6));
        egg.addScoreboardTag("ag_splegg");
    }

    @Override public void onProjectileHit(ProjectileHitEvent e) {
        if (arena.type != me.arcade.games.arena.GameType.SPLEGG) return;
        if (!(e.getEntity() instanceof Egg egg) || !egg.getScoreboardTags().contains("ag_splegg")) return;
        if (!(egg.getShooter() instanceof Player shooter) || !alive.contains(shooter.getUniqueId())) return;
        Block hit = e.getHitBlock();
        if (hit == null || !arena.contains(hit) || hit.getType().isAir() || !hit.getType().isSolid()) return;
        // Only remove the block the Splegg egg actually struck; restore it when the round ends.
        changed.putIfAbsent(hit, hit.getBlockData());
        hit.setType(Material.AIR, false);
        egg.remove();
    }

    @Override public boolean allowBreak(Player p, Block b) {
        if (arena.type != me.arcade.games.arena.GameType.SPLEGG || !alive.contains(p.getUniqueId()) || !arena.contains(b)) return false;
        changed.putIfAbsent(b, b.getBlockData()); b.setType(Material.AIR, false); return true;
    }

    @Override public boolean allowPlace(Player p, Block b) { return false; }

    @Override protected void checkEnd() {
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) {
            if (!alive.isEmpty()) return;
            finishTempleResults();
            return;
        }
        if (alive.size() > 1) return;
        String winner = alive.isEmpty() ? "Nobody" : nameOf(alive.iterator().next());
        end("&6" + arena.type.display + " winner: &e" + winner);
    }

    private void finishByScore() {
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) { finishTempleResults(); return; }
        String winner = "Nobody";
        if (!alive.isEmpty()) winner = nameOf(alive.iterator().next());
        end("&6" + arena.type.display + " time is up! Winner: &e" + winner);
    }

    /** Handles configured Temple Run checkpoint and finish pressure plates. */
    public void onCourseStep(Player player, Block feetBlock) {
        if (arena.type != me.arcade.games.arena.GameType.TEMPLE_RUN || !alive.contains(player.getUniqueId())) return;
        tryCollectNearbyCoin(player);
        Location here = feetBlock.getLocation();
        if (cfg().getBoolean("course.checkpoints-enabled", true)) for (Map.Entry<Integer, Location> entry : arena.checkpoints.entrySet()) {
            Location marker = entry.getValue();
            if (sameBlock(marker, here) && marker.getBlock().getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) {
                Location cp = marker.clone().add(0.5, 0.15, 0.5);
                templeProgress.merge(player.getUniqueId(), entry.getKey(), Math::max);
                Location old = checkpoints.get(player.getUniqueId());
                if (old == null || old.distanceSquared(cp) > 0.5) {
                    checkpoints.put(player.getUniqueId(), cp);
                    Msg.send(player, "&aCheckpoint " + entry.getKey() + " saved!");
                }
                return;
            }
        }
        if (cfg().getBoolean("course.finishes-enabled", true)) for (Location marker : arena.finishes.values()) {
            if (sameBlock(marker, here) && marker.getBlock().getType() == Material.HEAVY_WEIGHTED_PRESSURE_PLATE) {
                completeRunner(player); return;
            }
        }
    }

    private boolean sameBlock(Location a, Location b) {
        return a != null && a.getWorld() != null && a.getWorld().equals(b.getWorld()) && a.getBlockX() == b.getBlockX() && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private void completeRunner(Player player) {
        UUID id = player.getUniqueId();
        if (!completedRunners.add(id)) return;
        templeFinishOrder.add(id); templeFinishTimes.put(id, ticks / 20L); alive.remove(id);
        int place = templeFinishOrder.size();
        String label = place == 1 ? "1st" : place == 2 ? "2nd" : place == 3 ? "3rd" : place + "th";
        if (cfg().getBoolean("course.announce-finish-order", true)) broadcast("&6" + player.getName() + " finished Temple Run in " + label + " place!");
        player.getInventory().clear(); player.setGameMode(GameMode.SPECTATOR); player.teleport(arena.spectatorSpawn());
        if (alive.isEmpty()) finishTempleResults();
    }

    private void finishTempleResults() {
        if (state != State.ACTIVE) return;
        List<String> result = new ArrayList<>(); result.add("&6" + arena.name + " results:");
        String[] places = {"&6🥇 1st", "&7🥈 2nd", "&c🥉 3rd"};
        int resultCount = Math.max(1, Math.min(3, cfg().getInt("course.results-count", 3)));
        for (int i = 0; i < Math.min(resultCount, templeFinishOrder.size()); i++) {
            UUID id = templeFinishOrder.get(i); long sec = templeFinishTimes.getOrDefault(id, ticks / 20L); result.add(places[i] + " &f" + nameOf(id) + " &8- &e" + String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60));
        }
        if (templeFinishOrder.isEmpty()) result.add("&7Nobody reached the finish.");
        recordStats(templeFinishOrder.isEmpty() ? null : templeFinishOrder.get(0));
        end(result.toArray(new String[0]));
    }

    @Override protected void onEnd() {
        for (Map.Entry<Block, BlockData> entry : changed.entrySet()) entry.getKey().setBlockData(entry.getValue(), false);
        for (UUID id : new HashSet<>(tagged)) setTaggedHelmet(id, false);
        changed.clear(); tagged.clear(); tagTimes.clear(); oldHelmets.clear(); checkpoints.clear(); coins.clear(); collectedCoins.clear(); templeFinishOrder.clear(); completedRunners.clear(); templeFinishTimes.clear(); templeProgress.clear();
        if (arena.world() != null) for (org.bukkit.entity.Entity entity : arena.world().getEntities()) if ((entity.getScoreboardTags().contains("ag_temple_coin") || entity.getScoreboardTags().contains("ag_temple_coin_display")) && arena.contains(entity.getLocation().getBlock())) entity.remove();
    }
}
