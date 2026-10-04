package dev.berrycore;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Items {
    private Items() {
    }

    public static Material mat(String name, Material def) {
        if (name == null) return def;
        Material m = Material.matchMaterial(name.trim());
        return m == null || !m.isItem() ? def : m;
    }

    public static ItemStack of(Material m, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        if (name != null) meta.displayName(name);
        if (lore != null && !lore.isEmpty()) meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack of(Player viewer, Material m, String name, List<String> lore, String... kv) {
        List<Component> l = new ArrayList<>();
        if (lore != null) for (String s : lore) l.add(Msg.c(viewer, s, kv));
        return of(m, Msg.c(viewer, name, kv), l);
    }

    public static ItemStack head(UUID id, Component name, List<Component> lore) {
        ItemStack it = of(Material.PLAYER_HEAD, name, lore);
        if (id != null && it.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(org.bukkit.Bukkit.getOfflinePlayer(id));
            it.setItemMeta(sm);
        }
        return it;
    }

    /** Builds an item from a config section: material, name, lore, amount, glow, enchants, unbreakable. */
    public static ItemStack fromSection(Player viewer, ConfigurationSection s, String... kv) {
        Material m = mat(s.getString("material"), Material.STONE);
        ItemStack it = of(viewer, m, s.getString("name", "<white>" + m.name()), s.getStringList("lore"), kv);
        it.setAmount(Math.max(1, Math.min(64, s.getInt("amount", 1))));
        ItemMeta meta = it.getItemMeta();
        if (s.getBoolean("glow", false)) meta.setEnchantmentGlintOverride(true);
        if (s.getBoolean("unbreakable", false)) meta.setUnbreakable(true);
        ConfigurationSection en = s.getConfigurationSection("enchants");
        if (en != null) for (String k : en.getKeys(false)) {
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(k.toLowerCase()));
            if (e != null) meta.addEnchant(e, en.getInt(k), true);
        }
        it.setItemMeta(meta);
        return it;
    }
}
