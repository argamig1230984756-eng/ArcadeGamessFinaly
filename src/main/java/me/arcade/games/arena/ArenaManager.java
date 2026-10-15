package me.arcade.games.arena;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.game.Game;
import me.arcade.games.game.impl.*;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ArenaManager {
    private final ArcadePlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Map<String, Game> games = new HashMap<>();
    private final Map<UUID, Game> byPlayer = new HashMap<>();
    private final Map<UUID, Game> bySpectator = new HashMap<>();

    public ArenaManager(ArcadePlugin plugin) {
        this.plugin = plugin;
    }

    private File file() {
        plugin.getDataFolder().mkdirs();
        return new File(plugin.getDataFolder(), "arenas.yml");
    }

    public void load() {
        arenas.clear();
        File f = file();
        if (!f.exists()) return;
        YamlConfiguration c = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = c.getConfigurationSection("arenas");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection a = root.getConfigurationSection(key);
            if (a == null) continue;
            GameType t = GameType.parse(a.getString("type", ""));
            if (t == null) continue;
            Arena ar = new Arena(key, t);
            ar.min = a.getInt("min", defaultMin(t));
            ar.max = a.getInt("max", defaultMax(t));
            ar.waiting = a.getLocation("waiting");
            ar.pos1 = a.getLocation("pos1");
            ar.pos2 = a.getLocation("pos2");
            ar.spectator = a.getLocation("spectator");
            readLocs(a.getConfigurationSection("spawns"), ar.spawns);
            readLocs(a.getConfigurationSection("withers"), ar.withers);
            readLocs(a.getConfigurationSection("checkpoints"), ar.checkpoints);
            readLocs(a.getConfigurationSection("coins"), ar.coins);
            readLocs(a.getConfigurationSection("finishes"), ar.finishes);
            ConfigurationSection wallRoot = a.getConfigurationSection("walls");
            if (wallRoot != null) for (String teamKey : wallRoot.getKeys(false)) {
                try {
                    int team = Integer.parseInt(teamKey);
                    ConfigurationSection regions = wallRoot.getConfigurationSection(teamKey);
                    if (regions == null) continue;
                    List<Arena.WallRegion> list = new ArrayList<>();
                    for (String id : regions.getKeys(false)) {
                        Location p1 = regions.getLocation(id + ".pos1"), p2 = regions.getLocation(id + ".pos2");
                        if (p1 != null && p2 != null && p1.getWorld() != null && p1.getWorld().equals(p2.getWorld())) list.add(new Arena.WallRegion(p1, p2));
                    }
                    ar.walls.put(team, list);
                } catch (NumberFormatException ignored) {}
            }
            arenas.put(key.toLowerCase(), ar);
        }
        plugin.getLogger().info("Loaded " + arenas.size() + " arena(s).");
    }

    private void readLocs(ConfigurationSection s, Map<Integer, Location> into) {
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                Location l = s.getLocation(k);
                if (l != null) into.put(Integer.parseInt(k), l);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    public void save() {
        YamlConfiguration c = new YamlConfiguration();
        for (Arena ar : arenas.values()) {
            String b = "arenas." + ar.name;
            c.set(b + ".type", ar.type.name());
            c.set(b + ".min", ar.min);
            c.set(b + ".max", ar.max);
            c.set(b + ".waiting", ar.waiting);
            c.set(b + ".pos1", ar.pos1);
            c.set(b + ".pos2", ar.pos2);
            if (ar.spectator != null) c.set(b + ".spectator", ar.spectator);
            for (Map.Entry<Integer, Location> e : ar.spawns.entrySet()) c.set(b + ".spawns." + e.getKey(), e.getValue());
            for (Map.Entry<Integer, Location> e : ar.withers.entrySet()) c.set(b + ".withers." + e.getKey(), e.getValue());
            for (Map.Entry<Integer, Location> e : ar.checkpoints.entrySet()) c.set(b + ".checkpoints." + e.getKey(), e.getValue());
            for (Map.Entry<Integer, Location> e : ar.coins.entrySet()) c.set(b + ".coins." + e.getKey(), e.getValue());
            for (Map.Entry<Integer, Location> e : ar.finishes.entrySet()) c.set(b + ".finishes." + e.getKey(), e.getValue());
            for (Map.Entry<Integer, List<Arena.WallRegion>> team : ar.walls.entrySet()) {
                for (int i = 0; i < team.getValue().size(); i++) {
                    Arena.WallRegion w = team.getValue().get(i);
                    c.set(b + ".walls." + team.getKey() + "." + (i + 1) + ".pos1", w.pos1);
                    c.set(b + ".walls." + team.getKey() + "." + (i + 1) + ".pos2", w.pos2);
                }
            }
        }
        try {
            c.save(file());
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save arenas.yml: " + ex.getMessage());
        }
    }

    public Arena arena(String name) {
        return name == null ? null : arenas.get(name.toLowerCase());
    }

    public Collection<Arena> arenas() {
        return arenas.values();
    }

    public Arena create(String name, GameType type) {
        if (arenas.containsKey(name.toLowerCase())) return null;
        Arena a = new Arena(name, type);
        a.min = Math.max(1, defaultMin(type));
        a.max = Math.max(a.min, defaultMax(type));
        arenas.put(name.toLowerCase(), a);
        return a;
    }

    private int defaultMin(GameType t) {
        int v = plugin.settings().game(t).getInt("default-min-players");
        return v > 0 ? v : t.min;
    }

    private int defaultMax(GameType t) {
        int v = plugin.settings().game(t).getInt("default-max-players");
        return v > 0 ? v : t.max;
    }

    // ------------------------------------------------------------ matchmaking

    /** Arenas of {@code type} (any game if null) that a player could join right now. */
    private List<Arena> open(GameType type, Arena exclude) {
        List<Arena> out = new ArrayList<>();
        for (Arena ar : arenas.values()) {
            if (ar == exclude || (type != null && ar.type != type)) continue;
            if (!plugin.settings().enabled(ar.type) || ar.validate() != null) continue;
            if (game(ar).joinable()) out.add(ar);
        }
        return out;
    }

    private int waiting(Arena a) {
        return game(a).playerCount();
    }

    /** Orders arenas best-first according to matchmaking.mode in config.yml. */
    private List<Arena> ranked(List<Arena> list) {
        String mode = plugin.getConfig().getString("matchmaking.mode", "random").trim().toLowerCase();
        List<Arena> l = new ArrayList<>(list);
        switch (mode) {
            case "ordered", "first" -> {
                // keep arenas.yml order
            }
            case "fullest" -> Collections.shuffle(l); // random tie-break, sorted below
            case "emptiest" -> {
                Collections.shuffle(l);
                l.sort(Comparator.comparingInt(this::waiting));
            }
            default -> Collections.shuffle(l); // random
        }
        // FILL FIRST: an arena that already has players is filled up before any other arena is used.
        // (stable sort - arenas with the same number of players keep the order chosen above)
        boolean fill = plugin.getConfig().getBoolean("matchmaking.fill-first",
                plugin.getConfig().getBoolean("matchmaking.prefer-lobbies-with-players", true));
        if (fill && !mode.equals("emptiest")) {
            l.sort((a, b) -> Integer.compare(waiting(b), waiting(a)));
        }
        return l;
    }

    /** True if somebody is waiting in one of these arenas. */
    private boolean anyWaiting(List<Arena> list) {
        for (Arena ar : list) if (waiting(ar) > 0) return true;
        return false;
    }

    /**
     * The arenas to try for a player, best first. The caller joins the first one that accepts them,
     * so if one fills up in the meantime the next one is used automatically.
     *
     * @param preferred the arena the player asked for by name, or null
     * @param type      the game the player asked for (null = any game); ignored when {@code preferred} is set
     */
    public List<Arena> attempts(Arena preferred, GameType type) {
        List<Arena> out = new ArrayList<>();
        if (preferred != null) {
            if (open(preferred.type, null).contains(preferred)) out.add(preferred);
            if (plugin.getConfig().getBoolean("matchmaking.fallback-if-full", true)) {
                out.addAll(ranked(open(preferred.type, preferred)));
            }
            return out;
        }
        if (type != null) return ranked(open(type, null));

        // any game: choose the game at random first, so a game with many arenas is not favoured
        List<Arena> all = open(null, null);
        List<GameType> order = new ArrayList<>();
        for (Arena ar : all) if (!order.contains(ar.type)) order.add(ar.type);
        Collections.shuffle(order);
        Map<GameType, List<Arena>> byType = new HashMap<>();
        for (GameType t : order) {
            List<Arena> sub = new ArrayList<>();
            for (Arena ar : all) if (ar.type == t) sub.add(ar);
            byType.put(t, ranked(sub));
        }
        // a game that already has players waiting comes first (so lobbies fill up and start)
        if (plugin.getConfig().getBoolean("matchmaking.fill-first", true)) {
            order.sort(Comparator.comparingInt((GameType t) -> anyWaiting(byType.get(t)) ? 0 : 1));
        }
        for (GameType t : order) out.addAll(byType.get(t));
        return out;
    }

    public boolean delete(String name) {
        Arena a = arenas.remove(name.toLowerCase());
        if (a == null) return false;
        Game g = games.remove(name.toLowerCase());
        if (g != null) g.shutdown();
        return true;
    }

    public Game game(Arena a) {
        return games.computeIfAbsent(a.name.toLowerCase(), k -> make(a));
    }

    private Game make(Arena a) {
        return switch (a.type) {
            case TNT_RUN -> new TNTRunGame(plugin, a);
            case BLOCK_PARTY -> new BlockPartyGame(plugin, a);
            case PROP_HUNT -> new PropHuntGame(plugin, a);
            case MINI_WALLS -> new MiniWallsGame(plugin, a);
            case PARTY_GAMES -> new PartyGamesGame(plugin, a);
            case BOMBLOBBERS -> new me.arcade.games.game.impl.BombLobbersGame(plugin, a);
            case TNT_TAG, TEMPLE_RUN, SPLEGG, QUAKE -> new ArcadeModeGame(plugin, a);
        };
    }

    public List<Game> all() {
        return new ArrayList<>(games.values());
    }

    public Game gameOf(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    /** The game this player is spectating, or null. */
    public Game spectatorGame(Player p) {
        return bySpectator.get(p.getUniqueId());
    }

    public void registerSpectator(UUID id, Game g) {
        bySpectator.put(id, g);
    }

    public void unregisterSpectator(UUID id) {
        bySpectator.remove(id);
    }

    public void register(UUID id, Game g) {
        byPlayer.put(id, g);
    }

    public void unregister(UUID id) {
        byPlayer.remove(id);
    }

    public void shutdown() {
        for (Game g : all()) g.shutdown();
        byPlayer.clear();
        bySpectator.clear();
    }
}
