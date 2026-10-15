package me.arcade.games.lobby;

import me.arcade.games.ArcadePlugin;
import me.arcade.games.arena.Arena;
import me.arcade.games.util.Items;
import me.arcade.games.util.Msg;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The items every player gets in the hotbar (lobby-items.yml): a nether star that opens the game menu,
 * a book with the server info and a clock that opens the spectate menu. Everything is editable.
 */
public class LobbyItems implements Listener {
    private static final String TAG = "lobby_item";
    private final ArcadePlugin plugin;

    public LobbyItems(ArcadePlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.settings().lobbyItems();
    }

    private boolean inGameOrSpectating(Player p) {
        return plugin.manager().gameOf(p) != null || plugin.manager().spectatorGame(p) != null;
    }

    private boolean worldOk(Player p) {
        List<String> worlds = cfg().getStringList("worlds");
        if (worlds.isEmpty()) return true;
        for (String w : worlds) if (w.equalsIgnoreCase(p.getWorld().getName())) return true;
        return false;
    }

    public boolean isLobbyItem(ItemStack is) {
        return Items.tagOf(plugin, is, TAG) != null;
    }

    // ------------------------------------------------------------ giving

    /** Puts the items into the hotbar (replacing old copies). Does nothing for players inside a game. */
    public void give(Player p) {
        if (!cfg().getBoolean("enabled", true) || !p.isOnline()) return;
        if (inGameOrSpectating(p) || !worldOk(p)) return;
        remove(p);
        ConfigurationSection items = cfg().getConfigurationSection("items");
        if (items == null) return;
        PlayerInventory inv = p.getInventory();
        for (String id : items.getKeys(false)) {
            String base = "items." + id;
            if (!cfg().getBoolean(base + ".enabled", true)) continue;
            int slot = Math.max(1, Math.min(9, cfg().getInt(base + ".slot", 5))) - 1;
            ItemStack it = Items.build(cfg(), base, Material.PAPER, 1, placeholders(p), null);
            Items.tag(plugin, it, TAG, id);
            ItemStack old = inv.getItem(slot);
            if (old != null && !old.getType().isAir()) {
                inv.setItem(slot, null);
                Map<Integer, ItemStack> left = inv.addItem(old);
                for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
            }
            inv.setItem(slot, it);
        }
    }

    public void remove(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isLobbyItem(inv.getItem(i))) inv.setItem(i, null);
        }
        if (isLobbyItem(inv.getItemInOffHand())) inv.setItemInOffHand(null);
    }

    /** Called after a player came back from a game / from spectating. */
    public void giveAfterReturn(Player p) {
        if (!cfg().getBoolean("give-on-return", true) || !plugin.isEnabled()) return;
        long delay = Math.max(1, cfg().getLong("give-delay-ticks", 5));
        Bukkit.getScheduler().runTaskLater(plugin, () -> give(p), delay);
    }

    /** /arcade reload: everybody out of a game gets the (possibly changed) items again. */
    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (inGameOrSpectating(p)) continue;
            if (!cfg().getBoolean("enabled", true)) remove(p);
            else give(p);
        }
    }

    private Map<String, String> placeholders(Player p) {
        Map<String, String> ph = new HashMap<>();
        ph.put("player", p.getName());
        return ph;
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!cfg().getBoolean("give-on-join", true)) return;
        Player p = e.getPlayer();
        long delay = Math.max(1, cfg().getLong("give-delay-ticks", 5));
        Bukkit.getScheduler().runTaskLater(plugin, () -> give(p), delay);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!cfg().getBoolean("give-on-respawn", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> give(p), Math.max(1, cfg().getLong("give-delay-ticks", 5)));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isLobbyItem);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) return;
        String id = Items.tagOf(plugin, e.getItem(), TAG);
        if (id == null) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (inGameOrSpectating(p)) return;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        String click = cfg().getString("click", "both").trim().toLowerCase();
        if (click.equals("right") && !right) return;
        if (click.equals("left") && right) return;
        run(p, cfg().getString("items." + id + ".action", "none"));
    }

    /** Runs an action from lobby-items.yml. */
    public void run(Player p, String action) {
        if (action == null) return;
        String a = action.trim();
        switch (a.toLowerCase()) {
            case "games-menu", "gui" -> plugin.command().openMain(p);
            case "spectate-menu", "spectate" -> plugin.spectateMenu().openGames(p);
            case "arena-menu", "arenas" -> plugin.menu().open(p, null);
            case "party-menu", "parties" -> plugin.partyManager().openPartyMenu(p);
            case "info-book", "info", "book" -> openBook(p);
            case "leave" -> p.performCommand("arcade leave");
            case "none", "" -> {
            }
            default -> {
                if (a.toLowerCase().startsWith("command:")) {
                    p.performCommand(a.substring(8).trim().replace("%player%", p.getName()));
                } else if (a.toLowerCase().startsWith("console:")) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), a.substring(8).trim().replace("%player%", p.getName()));
                }
            }
        }
    }

    // ------------------------------------------------------------ the info book

    public void openBook(Player p) {
        FileConfiguration b = plugin.settings().infoBook();
        int ready = 0;
        for (Arena ar : plugin.manager().arenas()) if (ar.validate() == null) ready++;
        int inGames = 0;
        for (var g : plugin.manager().all()) inGames += g.playerCount();
        Map<String, String> ph = new HashMap<>();
        ph.put("player", p.getName());
        ph.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        ph.put("arenas", String.valueOf(ready));
        ph.put("playing", String.valueOf(inGames));
        ph.put("ip", plugin.getConfig().getString("server-ip", "play.nomercy.fun"));

        List<Component> pages = new ArrayList<>();
        for (String page : b.getStringList("pages")) pages.add(bookPage(Items.fill(page, ph)));
        List<String> links = b.getStringList("links");
        if (!links.isEmpty()) {
            Component linkPage = Msg.c("&6&lServer Links\n\n");
            for (String raw : links) {
                String[] parts = raw.split("\\|", 2);
                if (parts.length != 2 || parts[1].isBlank()) continue;
                String label = parts[0].trim();
                String url = parts[1].trim();
                Component clickable = Msg.c("&b&n" + label).clickEvent(ClickEvent.openUrl(url));
                linkPage = linkPage.append(clickable).append(Component.newline());
            }
            pages.add(linkPage);
        }
        if (pages.isEmpty()) pages.add(Msg.c("&7No info yet - edit info-book.yml"));
        Book book = Book.book(Msg.c(b.getString("title", "Server info")), Msg.c(b.getString("author", "Server")), pages);
        p.openBook(book);
    }

    /** Supports clickable inline book links written as [visible text](https://example.com). */
    private Component bookPage(String text) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        Component result = Component.empty();
        int cursor = 0;
        while (matcher.find()) {
            if (matcher.start() > cursor) result = result.append(Msg.c(text.substring(cursor, matcher.start())));
            String visible = matcher.group(1);
            String url = matcher.group(2);
            result = result.append(Msg.c("&9&n" + visible).clickEvent(ClickEvent.openUrl(url)).hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Msg.c("&eClick to open " + url))));
            cursor = matcher.end();
        }
        if (cursor < text.length()) result = result.append(Msg.c(text.substring(cursor)));
        return result;
    }

    // ------------------------------------------------------------ keeping the items safe

    private boolean protect(Player p) {
        if (!cfg().getBoolean("protect", true)) return false;
        return !(p.getGameMode() == GameMode.CREATIVE && p.hasPermission("arcade.admin"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (protect(e.getPlayer()) && isLobbyItem(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (protect(e.getPlayer()) && (isLobbyItem(e.getMainHandItem()) || isLobbyItem(e.getOffHandItem()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !protect(p)) return;
        boolean hit = isLobbyItem(e.getCurrentItem()) || isLobbyItem(e.getCursor());
        if (!hit && e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0) {
            hit = isLobbyItem(p.getInventory().getItem(e.getHotbarButton()));
        }
        if (!hit && e.getClick() == ClickType.SWAP_OFFHAND) hit = isLobbyItem(p.getInventory().getItemInOffHand());
        if (hit) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && protect(p) && isLobbyItem(e.getOldCursor())) e.setCancelled(true);
    }
}
