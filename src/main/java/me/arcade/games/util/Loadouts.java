package me.arcade.games.util;

import me.arcade.games.ArcadePlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the loadouts in inventories/&lt;name&gt;.yml and gives them to players.
 * <p>
 * Every entry of {@code items:} may be written as
 * <pre>
 *   - "STONE_SWORD"            one item
 *   - "ARROW:32"               material:amount
 *   - "ARROW: 32"              (YAML turns this into a map - also accepted)
 *   - material: BOW            full form; every key except material is optional
 *     amount: 1
 *     slot: 0                  hotbar/inventory slot 0-35 (default: first free slot)
 *     name: "&amp;bRailgun"
 *     enchants: [ "POWER:3", "UNBREAKING:1" ]
 * </pre>
 * An {@code items:} list that is present but empty gives an empty loadout (it does NOT fall back to the
 * defaults shipped in the jar); only a file that has no {@code items} key at all uses the shipped defaults.
 */
public final class Loadouts {
    private Loadouts() {}

    /** Gives the loadout {@code key} (file name without .yml) to the player. */
    public static void give(ArcadePlugin plugin, Player p, String key) {
        FileConfiguration cfg = plugin.settings().inventory(key);
        List<?> entries = entries(cfg);
        for (Object raw : entries) {
            ItemStack item = null;
            int slot = -1;
            try {
                if (raw instanceof Map<?, ?> map) {
                    Map<String, Object> m = lower(map);
                    if (m.size() == 1 && !m.containsKey("material")) {
                        // "- ARROW: 32"
                        Map.Entry<String, Object> only = m.entrySet().iterator().next();
                        item = make(only.getKey(), String.valueOf(only.getValue()));
                    } else {
                        item = make(String.valueOf(m.get("material")), String.valueOf(m.getOrDefault("amount", "1")));
                        if (item != null) {
                            if (m.containsKey("slot")) slot = Integer.parseInt(String.valueOf(m.get("slot")).trim());
                            decorate(item, m);
                        }
                    }
                } else if (raw != null) {
                    String[] parts = String.valueOf(raw).trim().split(":", 2);
                    item = make(parts[0], parts.length > 1 ? parts[1] : "1");
                }
            } catch (RuntimeException ex) {
                item = null;
            }
            if (item == null) {
                plugin.getLogger().warning("Invalid item '" + raw + "' in inventories/" + key + ".yml - skipped.");
                continue;
            }
            PlayerInventory inv = p.getInventory();
            int max = Math.max(1, item.getType().getMaxStackSize());
            int remaining = item.getAmount();
            if (slot >= 0 && slot < 36) {
                // A fixed slot can only hold one stack; extra amount spills into free slots.
                ItemStack first = item.clone();
                first.setAmount(Math.min(max, remaining));
                inv.setItem(slot, first);
                remaining -= first.getAmount();
            }
            while (remaining > 0) {
                ItemStack part = item.clone();
                part.setAmount(Math.min(max, remaining));
                inv.addItem(part);
                remaining -= part.getAmount();
            }
        }
    }

    /** Number of entries in a loadout (used for the log line after loading). */
    public static int count(FileConfiguration cfg) {
        return entries(cfg).size();
    }

    private static List<?> entries(FileConfiguration cfg) {
        // getKeys(false) only lists keys that really are in the file, so an emptied "items:" is respected.
        if (cfg.getKeys(false).contains("items")) {
            List<?> list = cfg.getList("items");
            return list == null ? List.of() : list;
        }
        List<?> def = cfg.getList("items");
        return def == null ? List.of() : def;
    }

    private static Map<String, Object> lower(Map<?, ?> in) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : in.entrySet()) out.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), e.getValue());
        return out;
    }

    private static ItemStack make(String material, String amount) {
        Material m = Material.matchMaterial(material.trim());
        if (m == null || m.isAir() || !m.isItem()) return null;
        int n = 1;
        try {
            n = Integer.parseInt(amount.trim());
        } catch (NumberFormatException ignored) {
        }
        return new ItemStack(m, Math.max(1, n));
    }

    private static void decorate(ItemStack item, Map<String, Object> m) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        Object name = m.get("name");
        if (name != null) meta.displayName(Msg.c(String.valueOf(name)));
        item.setItemMeta(meta);
        Object enchants = m.get("enchants");
        if (enchants instanceof List<?> list) {
            for (Object o : list) {
                String[] parts = String.valueOf(o).trim().split(":", 2);
                Enchantment e = Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
                if (e == null) continue;
                int level = 1;
                try {
                    if (parts.length > 1) level = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
                item.addUnsafeEnchantment(e, Math.max(1, level));
            }
        }
    }
}
