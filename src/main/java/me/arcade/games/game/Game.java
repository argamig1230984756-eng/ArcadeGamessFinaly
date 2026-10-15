package me.arcade.games.game;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.util.Msg;
import me.arcade.games.util.Settings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Shared lifecycle for every arcade game: join/leave, countdown, tick loop, elimination, ending.
 */
public abstract class Game {
    public enum State { WAITING, STARTING, ACTIVE, ENDING }

    private record Saved(Location loc, ItemStack[] contents, ItemStack[] armor, ItemStack[] extra, GameMode mode,
                         double health, int food, float sat, int level, float exp, boolean allowFlight, boolean flying) {}

    protected final ArcadePlugin plugin;
    protected final Arena arena;
    protected State state = State.WAITING;
    protected final Set<UUID> players = new LinkedHashSet<>();
    protected final Set<UUID> alive = new LinkedHashSet<>();
    protected long ticks;

    private final Map<UUID, Saved> saved = new HashMap<>();
    /** People watching this arena (survival mode, flying, invisible - see spectate.yml). */
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private BukkitTask countdownTask, tickTask;
    private int countdownLeft;
    private boolean cleaned;
    private boolean statsRecorded;

    protected Game(ArcadePlugin plugin, Arena arena) {
        this.plugin = plugin;
        this.arena = arena;
    }

    // ------------------------------------------------------------ hooks for subclasses

    protected abstract void onStart();
    protected abstract void tick();
    protected abstract void checkEnd();
    protected void onEnd() {}
    protected void onLeave(Player p, boolean wasAlive) {}
    protected void onEliminated(Player p) {}

    /** Damage to a player taking part in an ACTIVE game. Default: no damage. */
    public void onDamage(Player victim, EntityDamageEvent e) { e.setCancelled(true); }
    public boolean ownsEntity(Entity e) { return false; }
    public void onEntityDamage(EntityDamageEvent e) {}
    public void onEntityDeath(EntityDeathEvent e) {}
    public void onProjectileHit(ProjectileHitEvent e) {}
    public boolean allowBreak(Player p, Block b) { return false; }
    public boolean allowPlace(Player p, Block b) { return false; }
    public boolean allowDrops() { return false; }
    public boolean allowPickup() { return false; }
    public boolean allowInventory() { return false; }
    public void onInteract(Player p, PlayerInteractEvent e) {}
    public void onFlight(Player p, PlayerToggleFlightEvent e) {}
    /** Called when a game-owned explosion is about to destroy these blocks (BombLobbers restores them afterwards). */
    public void recordExplosionBlocks(List<Block> blocks) {}

    // ------------------------------------------------------------ accessors

    public State state() { return state; }
    public Arena arena() { return arena; }
    public int playerCount() { return players.size(); }
    public int aliveCount() { return alive.size(); }
    public int totalCount() { return players.size(); }
    /**
     * Time left for the scoreboard (%arcadegames_time_left%). Games with their own clock override this.
     * Games without a time limit (Splegg, BombLobbers, TNT Tag ...) return "--:--"; use %arcadegames_elapsed% there.
     */
    public String remainingTimeText() {
        if (state != State.ACTIVE) return "--:--";
        if (arena.type == me.arcade.games.arena.GameType.TNT_TAG || arena.type == me.arcade.games.arena.GameType.SPLEGG
                || arena.type == me.arcade.games.arena.GameType.BOMBLOBBERS) return "--:--";
        int duration = cfg().getInt("round-seconds", -1);
        if (arena.type == me.arcade.games.arena.GameType.TEMPLE_RUN) duration = cfg().getInt("course.time-limit-seconds", 300);
        if (duration <= 0) duration = Math.max(0, cfg().getInt("time-limit-minutes", 0)) * 60;
        if (duration <= 0) return "--:--";
        int left = Math.max(0, duration - (int)(ticks / 20L));
        return String.format(java.util.Locale.ROOT, "%d:%02d", left / 60, left % 60);
    }

    /** Time since the game started as m:ss (%arcadegames_elapsed%). */
    public String elapsedText() {
        long sec = state == State.ACTIVE || state == State.ENDING ? ticks / 20L : 0L;
        return String.format(java.util.Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
    }

    /**
     * Extra scoreboard values for %arcadegames_<key>%. Every game can override this and add its own keys
     * (always call super for the keys it does not know). Return "" for an unknown key.
     */
    public String scoreboardValue(Player player, String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "elapsed" -> elapsedText();
            case "state" -> state.name().toLowerCase(Locale.ROOT);
            case "countdown" -> String.valueOf(Math.max(0, countdownLeft));
            case "arena" -> arena.name;
            case "game_name" -> arena.type.display;
            case "min_players" -> String.valueOf(arena.min);
            case "max_players" -> String.valueOf(arena.max);
            default -> "";
        };
    }
    public boolean has(Player p) { return players.contains(p.getUniqueId()); }
    public boolean joinable() {
        return (state == State.WAITING || state == State.STARTING) && players.size() < arena.max;
    }

    /** @return null if a player can join right now, otherwise the reason they can't. */
    public String whyNotJoinable() {
        if (joinable()) return null;
        return state == State.WAITING || state == State.STARTING ? t("common.arena-full") : t("common.game-running");
    }

    /** This game's settings file (games/&lt;game&gt;.yml). */
    protected FileConfiguration cfg() {
        return plugin.settings().game(arena.type);
    }

    /** A message from messages.yml with {placeholders} filled in. */
    protected String t(String key, Object... kv) {
        return plugin.settings().msg(key, kv);
    }

    // ------------------------------------------------------------ join / leave

    /** @return null on success, otherwise an error message. */
    public String join(Player p) {
        String why = whyNotJoinable();
        if (why != null) return why;
        saved.put(p.getUniqueId(), snapshot(p));
        players.add(p.getUniqueId());
        plugin.manager().register(p.getUniqueId(), this);
        reset(p, GameMode.ADVENTURE);
        p.teleport(arena.waiting);
        p.getInventory().setItem(8, Msg.item(plugin.settings().leaveItem(), 1, t("common.leave-item")));
        broadcast(t("common.joined", "player", p.getName(), "count", players.size(), "max", arena.max));
        if (state == State.WAITING && players.size() >= arena.min) startCountdown();
        return null;
    }

    private Saved snapshot(Player p) {
        PlayerInventory inv = p.getInventory();
        return new Saved(p.getLocation(), inv.getContents().clone(), inv.getArmorContents().clone(),
                inv.getExtraContents().clone(), p.getGameMode(), p.getHealth(), p.getFoodLevel(), p.getSaturation(),
                p.getLevel(), p.getExp(), p.getAllowFlight(), p.isFlying());
    }

    // ------------------------------------------------------------ spectating

    public int spectatorCount() { return spectators.size(); }
    public boolean isSpectator(Player p) { return spectators.contains(p.getUniqueId()); }

    /** @return null on success, otherwise an error message. */
    public String spectate(Player p) {
        FileConfiguration sp = plugin.settings().spectate();
        if (!sp.getBoolean("enabled", true)) return t("spectate.disabled");
        if (arena.validate() != null) return t("spectate.unavailable");
        if (state != State.ACTIVE) {
            return t("spectate.not-running");
        }
        if (players.contains(p.getUniqueId())) return t("join.already-in-game");
        UUID id = p.getUniqueId();
        saved.put(id, snapshot(p));
        spectators.add(id);
        plugin.manager().registerSpectator(id, this);

        GameMode mode = GameMode.SURVIVAL;
        try {
            mode = GameMode.valueOf(sp.getString("settings.gamemode", "SURVIVAL").trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
        }
        reset(p, mode);
        p.teleport(arena.spectatorSpawn());
        if (sp.getBoolean("settings.flight", true)) {
            p.setAllowFlight(true);
            p.setFlying(true);
        }
        if (sp.getBoolean("settings.invisible", true)) {
            p.addPotionEffect(new PotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
        }
        p.setInvulnerable(sp.getBoolean("settings.invulnerable", true));
        p.setCollidable(sp.getBoolean("settings.collidable", false));
        // Spectators carry no visible held item. They can leave with /arcade leave.
        p.getInventory().clear();
        return null;
    }

    /** Stops spectating and sends the player to the spawn (always). */
    public void stopSpectating(Player p) {
        UUID id = p.getUniqueId();
        if (!spectators.remove(id)) return;
        plugin.manager().unregisterSpectator(id);
        p.setInvulnerable(false);
        p.setCollidable(true);
        restore(p);
    }

    /** Sends every spectator back to the spawn. */
    private void ejectSpectators(boolean announce) {
        for (UUID id : new ArrayList<>(spectators)) {
            Player sp = Bukkit.getPlayer(id);
            if (sp != null) {
                stopSpectating(sp);
                if (announce) Msg.send(sp, t("spectate.game-ended"));
            } else {
                spectators.remove(id);
                plugin.manager().unregisterSpectator(id);
                saved.remove(id);
            }
        }
    }

    private boolean kickSpectatorsWhenDone() {
        return plugin.settings().spectate().getBoolean("settings.kick-when-game-ends", true);
    }

    public void leave(Player p) {
        UUID id = p.getUniqueId();
        if (!players.remove(id)) return;
        boolean wasAlive = alive.remove(id);
        plugin.manager().unregister(id);
        onLeave(p, wasAlive);
        restore(p);
        broadcast(t("common.left", "player", p.getName()));
        if (state == State.STARTING && players.size() < arena.min) {
            if (countdownTask != null) countdownTask.cancel();
            state = State.WAITING;
            for (Player o : allPlayers()) o.setLevel(0);
            broadcast(t("common.countdown-cancelled"));
        } else if (state == State.ACTIVE) {
            if (players.isEmpty()) stopQuiet(); else checkEnd();
        } else if (state == State.ENDING && players.isEmpty()) {
            finish();
        }
    }

    // ------------------------------------------------------------ lifecycle

    private void startCountdown() {
        state = State.STARTING;
        countdownLeft = Math.max(1, plugin.getConfig().getInt("lobby.countdown-seconds"));
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (countdownLeft <= 0) {
                countdownTask.cancel();
                start();
                return;
            }
            if (countdownLeft <= 5 || countdownLeft % 10 == 0) {
                broadcast(t("common.countdown", "seconds", countdownLeft));
                sound(Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            countdownLeft--;
        }, 0L, 20L);
    }

    public boolean forceStart() {
        if ((state == State.WAITING || state == State.STARTING) && !players.isEmpty()) {
            if (countdownTask != null) countdownTask.cancel();
            start();
            return true;
        }
        return false;
    }

    protected void start() {
        state = State.ACTIVE;
        cleaned = false;
        statsRecorded = false;
        ticks = 0;
        alive.clear();
        alive.addAll(players);
        for (Player p : allPlayers()) {
            p.getInventory().clear();
            p.setLevel(0);
        }
        onStart();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks++;
            if (state == State.ACTIVE) {
                tick();
            }
        }, 1L, 1L);
    }

    protected void eliminate(Player p) {
        if (!alive.remove(p.getUniqueId())) return;
        Msg.title(p, t("common.eliminated-title"), t("common.eliminated-subtitle"));
        onEliminated(p);
        String mode = cfg().getString("elimination.mode", "SPECTATOR").trim().toUpperCase(Locale.ROOT);
        if (mode.equals("LOBBY")) {
            players.remove(p.getUniqueId()); plugin.manager().unregister(p.getUniqueId());
            restore(p);
        } else {
            p.getInventory().clear(); p.getInventory().setArmorContents(new ItemStack[4]);
            p.setGameMode(GameMode.SPECTATOR); p.teleport(arena.spectatorSpawn());
        }
        checkEnd();
    }

    protected void recordStats(UUID winner) {
        if (statsRecorded) return;
        statsRecorded = true;
        for (UUID id : players) plugin.addStat(id, id.equals(winner) ? "wins" : "losses");
    }

    protected void end(String... lines) {
        if (state != State.ACTIVE) return;
        state = State.ENDING;
        recordStats(alive.size() == 1 ? alive.iterator().next() : null);
        if (tickTask != null) tickTask.cancel();
        for (String s : lines) broadcast(s);
        sound(Sound.ENTITY_PLAYER_LEVELUP);
        cleanup();
        Bukkit.getScheduler().runTaskLater(plugin, this::finish,
                Math.max(1, Settings.ticks(plugin.getConfig().getDouble("lobby.end-delay-seconds"))));
    }

    private void cleanup() {
        if (cleaned) return;
        cleaned = true;
        onEnd();
    }

    private void finish() {
        if (state != State.ENDING) return;
        for (Player p : allPlayers()) restore(p); // restore() sends everybody to spawn
        for (UUID id : players) plugin.manager().unregister(id);
        players.clear();
        alive.clear();
        state = State.WAITING;
        cleaned = false;
        if (kickSpectatorsWhenDone()) ejectSpectators(true);
    }

    private void stopQuiet() {
        cancelTasks();
        cleanup();
        if (kickSpectatorsWhenDone()) ejectSpectators(true);
        state = State.WAITING;
        cleaned = false;
        alive.clear();
    }

    private void cancelTasks() {
        if (countdownTask != null) countdownTask.cancel();
        if (tickTask != null) tickTask.cancel();
    }

    /** Hard stop (plugin disable / arena delete / /arcade forcestop). */
    public void shutdown() {
        cancelTasks();
        cleanup();
        for (Player p : allPlayers()) restore(p);
        for (UUID id : players) plugin.manager().unregister(id);
        players.clear();
        alive.clear();
        state = State.WAITING;
        cleaned = false;
        ejectSpectators(false);
    }

    // ------------------------------------------------------------ helpers

    protected void reset(Player p, GameMode mode) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setExtraContents(new ItemStack[1]);
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
        p.setGameMode(mode);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setHealth(maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
    }

    private static double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    private void restore(Player p) {
        Saved s = saved.remove(p.getUniqueId());
        for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
        p.getInventory().clear();
        // Wherever the player was before joining, they now always go to the spawn
        // (set  return-location: previous  in config.yml to send them back to where they were instead).
        boolean back = "previous".equalsIgnoreCase(plugin.getConfig().getString("return-location", "spawn"));
        if (back && s != null) p.teleport(s.loc());
        else plugin.sendToSpawn(p);
        if (s == null) return;
        p.setGameMode(s.mode());
        PlayerInventory inv = p.getInventory();
        inv.setContents(s.contents());
        inv.setArmorContents(s.armor());
        inv.setExtraContents(s.extra());
        p.setHealth(Math.min(s.health(), maxHealth(p)));
        p.setFoodLevel(s.food());
        p.setSaturation(s.sat());
        p.setLevel(s.level());
        p.setExp(s.exp());
        p.setAllowFlight(s.allowFlight());
        p.setFlying(s.flying() && s.allowFlight());
        p.setFireTicks(0);
        plugin.lobbyItems().giveAfterReturn(p);
    }

    protected List<Player> online(Collection<UUID> ids) {
        List<Player> l = new ArrayList<>();
        for (UUID id : ids) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) l.add(p);
        }
        return l;
    }

    protected List<Player> allPlayers() { return online(players); }
    protected List<Player> alivePlayers() { return online(alive); }

    protected String nameOf(UUID id) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(id);
        return o.getName() == null ? "?" : o.getName();
    }

    protected void broadcast(String msg) {
        for (Player p : allPlayers()) Msg.send(p, msg);
    }

    protected void sound(Sound s) {
        for (Player p : allPlayers()) p.playSound(p.getLocation(), s, 1f, 1f);
    }

    protected Location spawnFor(int i) {
        List<Location> l = arena.spawnList();
        return l.get(Math.floorMod(i, l.size())).clone();
    }

    /** The player responsible for a damage event (resolves projectiles), or null. */
    protected Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent by)) return null;
        Entity d = by.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity s) d = s;
        return d instanceof Player p ? p : null;
    }
}
