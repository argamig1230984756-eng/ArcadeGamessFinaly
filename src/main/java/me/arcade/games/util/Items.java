package me.arcade.games.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.Configuration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds menu / hotbar items from the editable .yml files (material, name, lore, glow). */
public final class Items {
    private Items() {}

    /** Replaces every {key} in {@code s} with its value. */
    public static String fill(String s, Map<String, String> ph) {
        if (s == null) return "";
        if (ph != null) for (Map.Entry<String, String> e : ph.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s;
    }

    public static Component line(String s, Map<String, String> ph) {
        return Msg.c(fill(s, ph)).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * Builds an item from the section {@code base} of a config file:
     * {@code material, name, lore, glow}. A lore line that is exactly {@code {key}} and has an entry in
     * {@code lists} is replaced by all the lines of that list.
     */
    public static ItemStack build(Configuration cfg, String base, Material fallback, int amount,
                                  Map<String, String> ph, Map<String, List<String>> lists) {
        return build(cfg, base, fallback, amount, ph, lists, null);
    }

    public static ItemStack build(Configuration cfg, String base, Material fallback, int amount,
                                  Map<String, String> ph, Map<String, List<String>> lists, String loreOverridePath) {
        Material mat = Settings.material(cfg.getString(base + ".material", ""), fallback);
        if (mat.isAir()) return new ItemStack(Material.AIR);
        if (!mat.isItem()) mat = fallback;
        ItemStack is = new ItemStack(mat, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return is;
        meta.displayName(line(cfg.getString(base + ".name", " "), ph));
        List<String> lore = cfg.getStringList(loreOverridePath != null ? loreOverridePath : base + ".lore");
        if (!lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) {
                String t = s.trim();
                List<String> expand = null;
                if (lists != null && t.length() > 2 && t.startsWith("{") && t.endsWith("}")) {
                    expand = lists.get(t.substring(1, t.length() - 1));
                }
                if (expand != null) {
                    for (String x : expand) l.add(line(x, ph));
                } else {
                    l.add(line(s, ph));
                }
            }
            meta.lore(l);
        }
        if (cfg.getBoolean(base + ".glow", false)) meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.values());
        is.setItemMeta(meta);
        return is;
    }

    // ------------------------------------------------------------ tagging (so we recognise our own items)

    public static ItemStack tag(Plugin plugin, ItemStack is, String key, String value) {
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return is;
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, key), PersistentDataType.STRING, value);
        is.setItemMeta(meta);
        return is;
    }

    public static String tagOf(Plugin plugin, ItemStack is, String key) {
        if (is == null || is.getType().isAir() || !is.hasItemMeta()) return null;
        return is.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, key), PersistentDataType.STRING);
    }
}
