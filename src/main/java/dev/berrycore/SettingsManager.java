package dev.berrycore;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Per-player boolean settings, defined (and optionally locked) in settings.yml. */
public final class SettingsManager {
    private final BerryCore plugin;
    private final Store store;

    public SettingsManager(BerryCore plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "settings");
    }

    private ConfigurationSection opts() {
        return plugin.cfg.get("settings").getConfigurationSection("options");
    }

    public boolean get(UUID id, String key) {
        ConfigurationSection o = opts() == null ? null : opts().getConfigurationSection(key);
        boolean def = o == null || o.getBoolean("default", true);
        if (o != null && !o.getBoolean("enabled", true)) return def;
        if (o != null && o.getBoolean("locked", false)) return o.getBoolean("locked-value", def);
        return store.y.getBoolean("players." + id + "." + key, def);
    }

    public boolean get(Player p, String key) {
        return get(p.getUniqueId(), key);
    }

    public void set(UUID id, String key, boolean v) {
        store.y.set("players." + id + "." + key, v);
    }

    public void open(Player p) {
        ConfigurationSection o = opts();
        if (o == null) return;
        String title = plugin.cfg.get("settings").getString("title", "<gradient:#ff4d8d:#b24dff>Settings</gradient>");
        List<String> keys = new ArrayList<>();
        for (String k : o.getKeys(false)) {
            ConfigurationSection s = o.getConfigurationSection(k);
            if (s != null && s.getBoolean("enabled", true) && !s.getBoolean("locked", false)) keys.add(k);
        }
        if (Ui.dialog(p, "settings")) {
            List<Dlg.Field> fields = new ArrayList<>();
            for (String k : keys) fields.add(Dlg.Field.bool(k, o.getString(k + ".name", k), get(p, k)));
            var msgs = plugin.cfg.get("messages");
            Dlg.form(p, title, plugin.cfg.get("settings").getString("dialog-body", ""), fields,
                    msgs.getString("ui.save", "<green>Save"), msgs.getString("ui.cancel", "<red>Cancel"), (pp, vals) -> {
                        vals.forEach((k, v) -> set(pp.getUniqueId(), k, Boolean.parseBoolean(v)));
                        plugin.msg.send(pp, "settings-saved");
                    });
            return;
        }
        int rows = Math.max(1, Math.min(6, (int) Math.ceil(keys.size() / 9.0)));
        Menu m = new Menu(Msg.c(p, title), rows);
        int i = 0;
        for (String k : keys) {
            if (i >= rows * 9) break;
            boolean on = get(p, k);
            ConfigurationSection s = o.getConfigurationSection(k);
            List<String> lore = new ArrayList<>(s.getStringList("description"));
            lore.add("");
            lore.add(on ? plugin.cfg.get("settings").getString("state-on", "<green>ENABLED") : plugin.cfg.get("settings").getString("state-off", "<red>DISABLED"));
            lore.add(plugin.cfg.get("settings").getString("click-hint", "<gray>Click to toggle"));
            Material icon = Items.mat(s.getString("icon"), Material.PAPER);
            var item = Items.of(p, icon, s.getString("name", k), lore);
            if (on) {
                var meta = item.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
            final String key = k;
            m.set(i++, item, e -> {
                set(p.getUniqueId(), key, !get(p, key));
                open(p);
            });
        }
        m.open(p);
    }

    public void save() {
        store.save();
    }
}
