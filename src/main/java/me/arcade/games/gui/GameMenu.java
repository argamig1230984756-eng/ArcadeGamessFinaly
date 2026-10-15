package me.arcade.games.gui;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.arena.GameType;
import me.arcade.games.game.Game;
import me.arcade.games.util.Items;
import me.arcade.games.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * The main menu: exactly ONE item per game. Clicking it sends the player to an arena of that game - an arena
 * that already has players is filled first (matchmaking in config.yml). Everything is in game-menu.yml.
 */
public class GameMenu implements Listener {

    private static final class Holder implements InventoryHolder, Refreshable {
        final GameMenu menu;
        Inventory inv;
        final Map<Integer, GameType> gameAt = new HashMap<>();
        final Map<Integer, String> actionAt = new HashMap<>();

        Holder(GameMenu menu) {
            this.menu = menu;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        @Override
        public void refresh() {
            menu.fill(this);
        }
    }

    private final ArcadePlugin plugin;

    public GameMenu(ArcadePlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.settings().gameMenu();
    }

    public boolean enabled() {
        return cfg().getBoolean("enabled", true);
    }

    public void open(Player p) {
        int rows = Math.max(1, Math.min(6, cfg().getInt("rows", 3)));
        Holder h = new Holder(this);
        h.inv = Bukkit.createInventory(h, rows * 9, Msg.c(cfg().getString("title", "&8Arcade &7- &8Choose a game")));
        fill(h);
        p.openInventory(h.inv);
    }

    private void fill(Holder h) {
        Inventory inv = h.inv;
        int size = inv.getSize();
        inv.clear();
        h.gameAt.clear();
        h.actionAt.clear();

        if (cfg().getBoolean("background.enabled", true)) {
            ItemStack bg = Items.build(cfg(), "background", Material.GRAY_STAINED_GLASS_PANE, 1, new HashMap<>(), null);
            for (int i = 0; i < size; i++) inv.setItem(i, bg);
        }

        for (GameType t : GameType.values()) {
            String base = "games." + t.key;
            if (!cfg().getBoolean(base + ".enabled", true)) continue;
            if (!plugin.settings().enabled(t) && cfg().getBoolean("hide-disabled-games", false)) continue;
            int slot = cfg().getInt(base + ".slot", -1);
            if (slot < 0 || slot >= size) continue;
            inv.setItem(slot, gameItem(t));
            h.gameAt.put(slot, t);
        }

        button(h, "any-game", Material.ENDER_EYE);
        button(h, "spectate", Material.CLOCK);
        button(h, "close", Material.BARRIER);
    }

    private void button(Holder h, String name, Material fallback) {
        String base = "buttons." + name;
        if (!cfg().getBoolean(base + ".enabled", true)) return;
        int slot = cfg().getInt(base + ".slot", -1);
        if (slot < 0 || slot >= h.inv.getSize()) return;
        Map<String, String> ph = totals();
        h.inv.setItem(slot, Items.build(cfg(), base, fallback, 1, ph, null));
        h.actionAt.put(slot, name);
    }

    private Map<String, String> totals() {
        int players = 0;
        for (Game g : plugin.manager().all()) players += g.playerCount();
        Map<String, String> ph = new HashMap<>();
        ph.put("players", String.valueOf(players));
        return ph;
    }

    /** The one item of a game. */
    private ItemStack gameItem(GameType t) {
        int ready = 0, joinable = 0, running = 0, players = 0, spectators = 0;
        boolean enabled = plugin.settings().enabled(t);
        for (Arena a : plugin.manager().arenas()) {
            if (a.type != t || a.validate() != null) continue;
            ready++;
            Game g = plugin.manager().game(a);
            players += g.playerCount();
            spectators += g.spectatorCount();
            if (g.joinable()) joinable++;
            if (g.state() == Game.State.ACTIVE || g.state() == Game.State.ENDING) running++;
        }

        String state;
        if (!enabled) state = "disabled";
        else if (ready == 0) state = "no-arenas";
        else if (joinable == 0) state = "busy";
        else state = "available";

        Map<String, String> ph = new HashMap<>();
        ph.put("game", t.display);
        ph.put("players", String.valueOf(players));
        ph.put("arenas", String.valueOf(ready));
        ph.put("free", String.valueOf(joinable));
        ph.put("playing", String.valueOf(running));
        ph.put("spectators", String.valueOf(spectators));
        ph.put("state", cfg().getString("state-names." + state, state));

        Map<String, List<String>> lists = new HashMap<>();
        lists.put("description", cfg().getStringList("games." + t.key + ".description"));

        String base = "games." + t.key;
        int amount = cfg().getBoolean("show-players-as-amount", false) ? Math.max(1, players) : 1;
        ItemStack is = Items.build(cfg(), base, Material.PAPER, amount, ph, lists, "lore." + state);
        return is;
    }

    // ------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        GameType game = h.gameAt.get(slot);
        String action = h.actionAt.get(slot);
        if (game != null) {
            boolean right = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT;
            String rightAction = cfg().getString("right-click", "none").trim().toLowerCase();
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                if (right && rightAction.equals("arenas")) {
                    plugin.menu().open(p, game);
                } else if (right && rightAction.equals("spectate")) {
                    plugin.spectateMenu().openArenas(p, game);
                } else {
                    // random arena of this game - one that already has players is filled first
                    plugin.command().attemptJoin(p, null, game);
                }
            });
        } else if ("any-game".equals(action)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                plugin.command().attemptJoin(p, null, null);
            });
        } else if ("spectate".equals(action)) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.spectateMenu().openGames(p));
        } else if ("close".equals(action)) {
            Bukkit.getScheduler().runTask(plugin, (Runnable) p::closeInventory);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
