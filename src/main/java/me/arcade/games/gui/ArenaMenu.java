package me.arcade.games.gui;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.arena.GameType;
import me.arcade.games.game.Game;
import me.arcade.games.util.Msg;
import me.arcade.games.util.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The arena selection menu. Everything about how it looks comes from plugins/ArcadeGames/gui.yml.
 */
public class ArenaMenu implements Listener {

    /** Marks our inventories and remembers what each slot does. */
    private static final class Holder implements InventoryHolder {
        final GameType filter;
        int page;
        Inventory inv;
        final Map<Integer, Arena> arenaAt = new HashMap<>();
        final Map<Integer, String> actionAt = new HashMap<>();

        Holder(GameType filter) {
            this.filter = filter;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final ArcadePlugin plugin;
    private BukkitTask refresh;

    public ArenaMenu(ArcadePlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.settings().gui();
    }

    // ------------------------------------------------------------ open / refresh

    /** Opens the menu. {@code filter} = show only the arenas of that game (null = all games). */
    public void open(Player p, GameType filter) {
        int rows = Math.max(1, Math.min(6, cfg().getInt("rows", 3)));
        Holder h = new Holder(filter);
        String title = cfg().getString("title", "&8Choose an arena");
        h.inv = Bukkit.createInventory(h, rows * 9, Msg.c(title));
        fill(h);
        p.openInventory(h.inv);
    }

    /** (Re)starts the task that keeps open menus up to date (gui.yml: refresh-ticks). */
    public void restartRefresh() {
        if (refresh != null) refresh.cancel();
        refresh = null;
        int every = cfg().getInt("refresh-ticks", 20);
        if (every <= 0) return;
        refresh = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h) fill(h);
            }
        }, every, every);
    }

    // ------------------------------------------------------------ building the menu

    private void fill(Holder h) {
        Inventory inv = h.inv;
        int size = inv.getSize();
        inv.clear();
        h.arenaAt.clear();
        h.actionAt.clear();

        // background
        if (cfg().getBoolean("background.enabled", true)) {
            ItemStack bg = build("background", Material.GRAY_STAINED_GLASS_PANE, 1, new HashMap<>(), null);
            for (int i = 0; i < size; i++) inv.setItem(i, bg);
        }

        // which slots hold arenas
        List<Integer> slots = new ArrayList<>();
        for (int s : cfg().getIntegerList("arena-slots")) {
            if (s >= 0 && s < size && !slots.contains(s)) slots.add(s);
        }
        if (slots.isEmpty()) {
            for (int i = 0; i < size; i++) slots.add(i);
        }

        List<Arena> list = visible(h.filter);
        int perPage = slots.size();
        int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
        h.page = Math.max(0, Math.min(h.page, pages - 1));

        for (int k = 0; k < slots.size(); k++) {
            int slot = slots.get(k);
            int idx = h.page * perPage + k;
            if (idx < list.size()) {
                Arena a = list.get(idx);
                inv.setItem(slot, arenaItem(a));
                h.arenaAt.put(slot, a);
            } else if (cfg().getBoolean("empty-slot.enabled", true)) {
                inv.setItem(slot, build("empty-slot", Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1, new HashMap<>(), null));
            }
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("page", String.valueOf(h.page + 1));
        ph.put("pages", String.valueOf(pages));
        ph.put("arenas", String.valueOf(list.size()));
        button(h, "previous", h.page > 0, ph);
        button(h, "next", h.page < pages - 1, ph);
        button(h, "random", true, ph);
    }

    private void button(Holder h, String name, boolean show, Map<String, String> ph) {
        String base = "buttons." + name;
        if (!show || !cfg().getBoolean(base + ".enabled", true)) return;
        int slot = cfg().getInt(base + ".slot", -1);
        if (slot < 0 || slot >= h.inv.getSize()) return;
        h.inv.setItem(slot, build(base, Material.ARROW, 1, ph, null));
        h.actionAt.put(slot, name);
    }

    /** The arenas shown, filtered and sorted as set in gui.yml. */
    private List<Arena> visible(GameType filter) {
        boolean hideNotReady = cfg().getBoolean("hide-not-ready", true);
        boolean hideDisabled = cfg().getBoolean("hide-disabled-games", true);
        boolean showRunning = cfg().getBoolean("show-running", true);
        List<Arena> l = new ArrayList<>();
        for (Arena a : plugin.manager().arenas()) {
            if (filter != null && a.type != filter) continue;
            boolean ready = a.validate() == null;
            boolean enabled = plugin.settings().enabled(a.type);
            if (!ready && hideNotReady) continue;
            if (!enabled && hideDisabled) continue;
            if (ready && enabled && !showRunning && !plugin.manager().game(a).joinable()) continue;
            l.add(a);
        }
        String sort = cfg().getString("sort", "game").trim().toLowerCase();
        switch (sort) {
            case "name" -> l.sort(Comparator.comparing((Arena a) -> a.name.toLowerCase()));
            case "game" -> l.sort(Comparator.comparingInt((Arena a) -> a.type.ordinal())
                    .thenComparing((Arena a) -> a.name.toLowerCase()));
            case "players" -> l.sort((x, y) -> Integer.compare(count(y), count(x)));
            default -> {
                // "order": keep arenas.yml order
            }
        }
        return l;
    }

    private int count(Arena a) {
        if (a.validate() != null || !plugin.settings().enabled(a.type)) return 0;
        return plugin.manager().game(a).playerCount();
    }

    private ItemStack arenaItem(Arena a) {
        String problem = a.validate();
        int players = 0;
        String key;
        if (problem != null) {
            key = "not-ready";
        } else if (!plugin.settings().enabled(a.type)) {
            key = "disabled";
        } else {
            Game g = plugin.manager().game(a);
            players = g.playerCount();
            key = switch (g.state()) {
                case WAITING -> players >= a.max ? "full" : "waiting";
                case STARTING -> players >= a.max ? "full" : "starting";
                case ACTIVE -> "active";
                case ENDING -> "ending";
            };
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("arena", a.name);
        ph.put("game", a.type.display);
        ph.put("state", cfg().getString("state-names." + key, key));
        ph.put("players", String.valueOf(players));
        ph.put("min", String.valueOf(a.min));
        ph.put("max", String.valueOf(a.max));
        ph.put("free", String.valueOf(Math.max(0, a.max - players)));
        ph.put("needed", String.valueOf(Math.max(0, a.min - players)));
        ph.put("problem", problem == null ? "" : problem);

        Material icon = Settings.material(cfg().getString("game-icons." + a.type.key, ""), Material.PAPER);
        String matName = cfg().getString("states." + key + ".material", "GAME");
        Material override = "GAME".equalsIgnoreCase(matName.trim()) ? icon : null;
        int amount = cfg().getBoolean("show-players-as-amount", true) ? Math.max(1, players) : 1;
        return build("states." + key, icon, amount, ph, override);
    }

    // ------------------------------------------------------------ item building

    /** Builds an item from the gui.yml section {@code base} (material, name, lore, glow). */
    private ItemStack build(String base, Material fallback, int amount, Map<String, String> ph, Material override) {
        Material mat = override;
        if (mat == null) mat = Settings.material(cfg().getString(base + ".material", ""), fallback);
        if (mat.isAir()) return new ItemStack(Material.AIR);
        if (!mat.isItem()) mat = fallback;

        ItemStack is = new ItemStack(mat, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return is;
        meta.displayName(line(cfg().getString(base + ".name", " "), ph));
        List<String> lore = cfg().getStringList(base + ".lore");
        if (!lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(line(s, ph));
            meta.lore(l);
        }
        if (cfg().getBoolean(base + ".glow", false)) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.values());
        is.setItemMeta(meta);
        return is;
    }

    private Component line(String text, Map<String, String> ph) {
        String s = text;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return Msg.c(s).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getInventory().getSize()) return;

        Arena arena = h.arenaAt.get(slot);
        String action = h.actionAt.get(slot);
        if (arena != null) {
            // close + join one tick later: closing an inventory from inside its own click event is unreliable
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                plugin.command().attemptJoin(p, arena, null);
            });
        } else if ("previous".equals(action)) {
            h.page--;
            fill(h);
        } else if ("next".equals(action)) {
            h.page++;
            fill(h);
        } else if ("random".equals(action)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                plugin.command().attemptJoin(p, null, h.filter);
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
