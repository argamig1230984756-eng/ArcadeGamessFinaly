package me.arcade.games;

import me.arcade.games.arena.ArenaManager;
import me.arcade.games.command.ArcadeCommand;
import me.arcade.games.game.GameListener;
import me.arcade.games.game.CourseEditorListener;
import me.arcade.games.gui.ArenaMenu;
import me.arcade.games.gui.GameMenu;
import me.arcade.games.gui.Refreshable;
import me.arcade.games.gui.SpectateMenu;
import me.arcade.games.lobby.LobbyItems;
import me.arcade.games.party.PartyManager;
import me.arcade.games.spectate.SpectatorListener;
import me.arcade.games.util.Settings;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;

public class ArcadePlugin extends JavaPlugin {
    private ArenaManager manager;
    private Settings settings;
    private ArcadeCommand command;
    private ArenaMenu menu;
    private GameMenu gameMenu;
    private SpectateMenu spectateMenu;
    private LobbyItems lobbyItems;
    private SpectatorListener spectatorListener;
    private BukkitTask menuRefresh;
    private PartyManager partyManager;
    private YamlConfiguration stats;
    private File statsFile;

    @Override
    public void onEnable() {
        statsFile = new File(getDataFolder(), "player-stats.yml");
        stats = YamlConfiguration.loadConfiguration(statsFile);
        settings = new Settings(this); // creates config.yml, messages.yml and games/*.yml on the first start
        manager = new ArenaManager(this);
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(new CourseEditorListener(this), this);
        partyManager = new PartyManager(this);
        getServer().getPluginManager().registerEvents(partyManager, this);
        menu = new ArenaMenu(this);
        getServer().getPluginManager().registerEvents(menu, this);
        menu.restartRefresh();
        gameMenu = new GameMenu(this);
        getServer().getPluginManager().registerEvents(gameMenu, this);
        spectateMenu = new SpectateMenu(this);
        getServer().getPluginManager().registerEvents(spectateMenu, this);
        lobbyItems = new LobbyItems(this);
        getServer().getPluginManager().registerEvents(lobbyItems, this);
        spectatorListener = new SpectatorListener(this);
        getServer().getPluginManager().registerEvents(spectatorListener, this);
        spectatorListener.restart();
        restartMenuRefresh();
        ArcadeCommand cmd = new ArcadeCommand(this);
        command = cmd;
        PluginCommand pc = getCommand("arcade");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        PlaceholderHook.register(this); // soft dependency: does nothing if PlaceholderAPI is missing
        // load next tick so every world (Multiverse etc.) is available
        getServer().getScheduler().runTask(this, manager::load);
        // players already online (after /reload): give them the hotbar items
        getServer().getScheduler().runTaskLater(this, () -> lobbyItems.refreshAll(), 20L);
    }

    @Override
    public void onDisable() {
        PlaceholderHook.unregister();
        if (spectatorListener != null) spectatorListener.stop();
        if (menuRefresh != null) menuRefresh.cancel();
        if (manager != null) manager.shutdown();
        saveStats();
    }


    public int stat(java.util.UUID id, String type) { return stats == null ? 0 : stats.getInt("players." + id + "." + type, 0); }
    public void addStat(java.util.UUID id, String type) {
        if (stats == null) return;
        String path = "players." + id + "." + type;
        stats.set(path, stats.getInt(path, 0) + 1);
        saveStats();
    }
    public void saveStats() {
        if (stats == null || statsFile == null) return;
        try { getDataFolder().mkdirs(); stats.save(statsFile); } catch (IOException ex) { getLogger().warning("Could not save player-stats.yml: " + ex.getMessage()); }
    }

    public ArenaManager manager() {
        return manager;
    }

    public Settings settings() {
        return settings;
    }

    public ArcadeCommand command() {
        return command;
    }

    public ArenaMenu menu() {
        return menu;
    }

    public GameMenu gameMenu() {
        return gameMenu;
    }

    public SpectateMenu spectateMenu() {
        return spectateMenu;
    }

    public LobbyItems lobbyItems() { return lobbyItems; }
    public PartyManager partyManager() { return partyManager; }

    /** Keeps the open game / spectate menus up to date (config.yml: menu-refresh-ticks). */
    private void restartMenuRefresh() {
        if (menuRefresh != null) menuRefresh.cancel();
        menuRefresh = null;
        int every = getConfig().getInt("menu-refresh-ticks", 20);
        if (every <= 0) return;
        menuRefresh = getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Refreshable r) r.refresh();
            }
        }, every, every);
    }

    /** Re-reads config.yml, messages.yml and games/*.yml (/arcade reload). */
    public void reloadAll() {
        settings.reload();
        if (menu != null) menu.restartRefresh();
        restartMenuRefresh();
        if (lobbyItems != null) lobbyItems.refreshAll();
    }

    /**
     * Sends a player to the spawn (config.yml: spawn-world / spawn-command). Happens immediately, so it also
     * works while a player is quitting or the plugin is shutting down.
     */
    public void sendToSpawn(Player p) {
        String cmd = getConfig().getString("spawn-command", "");
        if (cmd != null && !cmd.trim().isEmpty()) {
            String c = cmd.trim();
            if (c.startsWith("/")) c = c.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
            return;
        }
        String worldName = getConfig().getString("spawn-world", "");
        World target = null;
        if (worldName != null && !worldName.trim().isEmpty()) {
            target = Bukkit.getWorld(worldName.trim());
            if (target == null) getLogger().warning("spawn-world '" + worldName + "' not found - using main world.");
        }
        if (target == null) {
            List<World> worlds = Bukkit.getWorlds();
            if (!worlds.isEmpty()) target = worlds.get(0);
        }
        if (target != null) p.teleport(target.getSpawnLocation());
    }
}
