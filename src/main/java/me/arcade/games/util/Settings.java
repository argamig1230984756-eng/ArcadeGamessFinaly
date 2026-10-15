package me.arcade.games.util;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.GameType;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * Loads every editable file in plugins/ArcadeGames/:
 * <pre>
 *   config.yml          general settings (lobby, matchmaking, spawn)
 *   messages.yml        every message players see
 *   gui.yml             the arena list menu (/arcade arenas)
 *   game-menu.yml       the main menu: one item per game
 *   lobby-items.yml     the nether star / book / clock in the hotbar
 *   info-book.yml       the server info book
 *   spectate.yml        spectating + the two spectate menus
 *   games/&lt;game&gt;.yml   settings for one game
 * </pre>
 * Each file is copied out of the jar the first time. A key that is missing from a file (for example after
 * a plugin update) silently falls back to the default that ships inside the jar.
 */
public final class Settings {
    private final ArcadePlugin plugin;
    private final Map<GameType, FileConfiguration> games = new EnumMap<>(GameType.class);
    private FileConfiguration messages = new YamlConfiguration();
    private FileConfiguration gui = new YamlConfiguration();
    private FileConfiguration lobbyItems = new YamlConfiguration();
    private FileConfiguration infoBook = new YamlConfiguration();
    private FileConfiguration gameMenu = new YamlConfiguration();
    private FileConfiguration spectate = new YamlConfiguration();
    private final Map<String, FileConfiguration> inventories = new java.util.HashMap<>();

    public Settings(ArcadePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /** (Re)reads every file from disk. */
    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        Msg.setPrefix(plugin.getConfig().getString("prefix", Msg.DEFAULT_PREFIX));
        Msg.setActionBar(plugin.getConfig().getBoolean("actionbar.enabled", false));
        messages = load("messages.yml");
        gui = load("gui.yml");
        lobbyItems = load("lobby-items.yml");
        infoBook = load("info-book.yml");
        gameMenu = load("game-menu.yml");
        spectate = load("spectate.yml");
        games.clear();
        for (GameType t : GameType.values()) games.put(t, load("games/" + t.key + ".yml"));
        inventories.clear();
        for (String key : java.util.List.of("quake", "splegg", "miniwall", "bomblobbers")) {
            FileConfiguration inv = load("inventories/" + key + ".yml");
            inventories.put(key, inv);
            plugin.getLogger().info("Loaded loadout '" + key + "': " + Loadouts.count(inv) + " item(s).");
        }
    }

    private FileConfiguration load(String path) {
        File f = new File(plugin.getDataFolder(), path);
        if (!f.exists()) {
            try {
                plugin.saveResource(path, false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Missing bundled file " + path);
            }
        }
        YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        try (InputStream in = plugin.getResource(path)) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
        }
        return cfg;
    }

    /** The arena menu settings (gui.yml). */
    public FileConfiguration gui() {
        return gui;
    }

    /** The hotbar items (lobby-items.yml): nether star, info book, clock. */
    public FileConfiguration lobbyItems() {
        return lobbyItems;
    }

    /** The server info book (info-book.yml). */
    public FileConfiguration infoBook() {
        return infoBook;
    }

    /** The game menu with one item per game (game-menu.yml). */
    public FileConfiguration gameMenu() {
        return gameMenu;
    }

    /** Spectating: behaviour + both spectate menus (spectate.yml). */
    public FileConfiguration spectate() {
        return spectate;
    }

    /** Separately editable in-game loadouts from inventories/*.yml. */
    public FileConfiguration inventory(String key) { return inventories.getOrDefault(key.toLowerCase(java.util.Locale.ROOT), new YamlConfiguration()); }

    /** The settings file of one game (games/&lt;game&gt;.yml). */
    public FileConfiguration game(GameType t) {
        return games.get(t);
    }

    public boolean enabled(GameType t) {
        return game(t).getBoolean("enabled");
    }

    /** Text from messages.yml; {name} placeholders are replaced with the given key/value pairs. */
    public String msg(String key, Object... kv) {
        String s = messages.getString(key);
        if (s == null) return key; // visible on purpose, so a missing key is easy to spot
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    /** The item used to leave a waiting lobby (config.yml: lobby.leave-item). */
    public Material leaveItem() {
        return material(plugin.getConfig().getString("lobby.leave-item"), Material.RED_BED);
    }

    public static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.trim());
        return m == null ? fallback : m;
    }

    /** Seconds to server ticks. */
    public static int ticks(double seconds) {
        return (int) Math.max(0, Math.round(seconds * 20.0));
    }

    /** 3.0 becomes "3", 7.5 stays "7.5" (for messages). */
    public static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
