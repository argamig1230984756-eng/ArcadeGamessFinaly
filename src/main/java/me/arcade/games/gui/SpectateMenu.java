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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * The two spectate menus (clock in the hotbar):
 * 1) every game  ->  2) every arena of the clicked game  ->  click an arena to watch it.
 * Everything is in spectate.yml.
 */
public class SpectateMenu implements Listener {

    private static final class Holder implements InventoryHolder, Refreshable {
        final SpectateMenu menu;
        final GameType game; // null = the games menu
        int page;
        Inventory inv;
        final Map<Integer, GameType> gameAt = new HashMap<>();
        final Map<Integer, Arena> arenaAt = new HashMap<>();
        final Map<Integer, String> actionAt = new HashMap<>();

        Holder(SpectateMenu menu, GameType game) {
            this.menu = menu;
            this.game = game;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        @Override
        public void refresh() {
            if (game == null) menu.fillGames(this);
            else menu.fillArenas(this);
        }
    }

    private final ArcadePlugin plugin;

    public SpectateMenu(ArcadePlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.settings().spectate();
    }

    // ------------------------------------------------------------ menu 1: games

    public void openGames(Player p) {
        if (!cfg().getBoolean("enabled", true)) {
            Msg.send(p, plugin.settings().msg("spectate.disabled"));
            return;
        }
        int rows = Math.max(1, Math.min(6, cfg().getInt("games-menu.rows", 3)));
        Holder h = new Holder(this, null);
        h.inv = Bukkit.createInventory(h, rows * 9, Msg.c(cfg().getString("games-menu.title", "&8Spectate &7- &8Choose a game")));
        fillGames(h);
        p.openInventory(h.inv);
    }

    private void background(Holder h, String base) {
        if (cfg().getBoolean(base + ".background.enabled", true)) {
            ItemStack bg = Items.build(cfg(), base + ".background", Material.GRAY_STAINED_GLASS_PANE, 1, new HashMap<>(), null);
            for (int i = 0; i < h.inv.getSize(); i++) h.inv.setItem(i, bg);
        }
    }

    private void fillGames(Holder h) {
        h.inv.clear();
        h.gameAt.clear();
        h.arenaAt.clear();
        h.actionAt.clear();
        background(h, "games-menu");
        for (GameType t : GameType.values()) {
            String base = "games-menu.games." + t.key;
            if (!cfg().getBoolean(base + ".enabled", true)) continue;
            int slot = cfg().getInt(base + ".slot", -1);
            if (slot < 0 || slot >= h.inv.getSize()) continue;
            int arenas = 0, watching = 0, players = 0;
            for (Arena a : plugin.manager().arenas()) {
                if (a.type != t || a.validate() != null) continue;
                arenas++;
                Game g = plugin.manager().game(a);
                watching += g.spectatorCount();
                players += g.playerCount();
            }
            Map<String, String> ph = new HashMap<>();
            ph.put("game", t.display);
            ph.put("arenas", String.valueOf(arenas));
            ph.put("players", String.valueOf(players));
            ph.put("spectators", String.valueOf(watching));
            Map<String, List<String>> lists = new HashMap<>();
            lists.put("description", cfg().getStringList(base + ".description"));
            String loreKey = arenas == 0 ? "games-menu.lore-no-arenas" : "games-menu.lore";
            h.inv.setItem(slot, Items.build(cfg(), base, Material.PAPER, 1, ph, lists, loreKey));
            h.gameAt.put(slot, t);
        }
        simpleButton(h, "games-menu.buttons.close", "close", Material.BARRIER, new HashMap<>());
    }

    // ------------------------------------------------------------ menu 2: arenas of one game

    public void openArenas(Player p, GameType type) {
        if (!cfg().getBoolean("enabled", true)) {
            Msg.send(p, plugin.settings().msg("spectate.disabled"));
            return;
        }
        int rows = Math.max(1, Math.min(6, cfg().getInt("arenas-menu.rows", 4)));
        Holder h = new Holder(this, type);
        String title = cfg().getString("arenas-menu.title", "&8Spectate &7- &8{game}").replace("{game}", type.display);
        h.inv = Bukkit.createInventory(h, rows * 9, Msg.c(title));
        fillArenas(h);
        p.openInventory(h.inv);
    }

    private List<Arena> arenasOf(GameType type) {
        boolean onlyRunning = !cfg().getBoolean("settings.allow-waiting-arenas", true);
        List<Arena> l = new ArrayList<>();
        for (Arena a : plugin.manager().arenas()) {
            if (a.type != type || a.validate() != null) continue;
            if (onlyRunning) {
                Game.State s = plugin.manager().game(a).state();
                if (s != Game.State.ACTIVE && s != Game.State.ENDING) continue;
            }
            l.add(a);
        }
        l.sort(Comparator.comparing((Arena a) -> a.name.toLowerCase()));
        return l;
    }

    private void fillArenas(Holder h) {
        Inventory inv = h.inv;
        int size = inv.getSize();
        inv.clear();
        h.gameAt.clear();
        h.arenaAt.clear();
        h.actionAt.clear();
        background(h, "arenas-menu");

        List<Integer> slots = new ArrayList<>();
        for (int s : cfg().getIntegerList("arenas-menu.arena-slots")) {
            if (s >= 0 && s < size && !slots.contains(s)) slots.add(s);
        }
        if (slots.isEmpty()) for (int i = 0; i < size; i++) slots.add(i);

        List<Arena> list = arenasOf(h.game);
        int per = slots.size();
        int pages = Math.max(1, (list.size() + per - 1) / per);
        h.page = Math.max(0, Math.min(h.page, pages - 1));

        for (int k = 0; k < slots.size(); k++) {
            int idx = h.page * per + k;
            if (idx >= list.size()) break;
            Arena a = list.get(idx);
            inv.setItem(slots.get(k), arenaItem(a));
            h.arenaAt.put(slots.get(k), a);
        }
        if (list.isEmpty()) {
            int slot = cfg().getInt("arenas-menu.no-arenas.slot", 13);
            if (slot >= 0 && slot < size) {
                Map<String, String> ph = new HashMap<>();
                ph.put("game", h.game.display);
                inv.setItem(slot, Items.build(cfg(), "arenas-menu.no-arenas", Material.BARRIER, 1, ph, null));
            }
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("page", String.valueOf(h.page + 1));
        ph.put("pages", String.valueOf(pages));
        ph.put("game", h.game.display);
        if (h.page > 0) simpleButton(h, "arenas-menu.buttons.previous", "previous", Material.ARROW, ph);
        if (h.page < pages - 1) simpleButton(h, "arenas-menu.buttons.next", "next", Material.ARROW, ph);
        simpleButton(h, "arenas-menu.buttons.back", "back", Material.OAK_DOOR, ph);
    }

    private void simpleButton(Holder h, String base, String action, Material fallback, Map<String, String> ph) {
        if (!cfg().getBoolean(base + ".enabled", true)) return;
        int slot = cfg().getInt(base + ".slot", -1);
        if (slot < 0 || slot >= h.inv.getSize()) return;
        h.inv.setItem(slot, Items.build(cfg(), base, fallback, 1, ph, null));
        h.actionAt.put(slot, action);
    }

    private ItemStack arenaItem(Arena a) {
        Game g = plugin.manager().game(a);
        int players = g.playerCount();
        String key = switch (g.state()) {
            case WAITING -> "waiting";
            case STARTING -> "starting";
            case ACTIVE -> "active";
            case ENDING -> "ending";
        };
        Map<String, String> ph = new HashMap<>();
        ph.put("arena", a.name);
        ph.put("game", a.type.display);
        ph.put("state", cfg().getString("arenas-menu.state-names." + key, key));
        ph.put("players", String.valueOf(players));
        ph.put("max", String.valueOf(a.max));
        ph.put("min", String.valueOf(a.min));
        ph.put("spectators", String.valueOf(g.spectatorCount()));
        String base = "arenas-menu.states." + key;
        int amount = cfg().getBoolean("arenas-menu.show-players-as-amount", true) ? Math.max(1, players) : 1;
        Material icon = Material.PAPER;
        String gameIcon = cfg().getString("games-menu.games." + a.type.key + ".material", "");
        if (!gameIcon.isEmpty()) icon = me.arcade.games.util.Settings.material(gameIcon, Material.PAPER);
        // material: GAME (or anything that is not an item name) = use the icon of the game
        return Items.build(cfg(), base, icon, amount, ph, null);
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
        Arena arena = h.arenaAt.get(slot);
        String action = h.actionAt.get(slot);
        if (game != null) {
            Bukkit.getScheduler().runTask(plugin, () -> openArenas(p, game));
        } else if (arena != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                plugin.command().startSpectating(p, arena);
            });
        } else if ("previous".equals(action)) {
            h.page--;
            fillArenas(h);
        } else if ("next".equals(action)) {
            h.page++;
            fillArenas(h);
        } else if ("back".equals(action)) {
            Bukkit.getScheduler().runTask(plugin, () -> openGames(p));
        } else if ("close".equals(action)) {
            Bukkit.getScheduler().runTask(plugin, (Runnable) p::closeInventory);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
