package dev.berrycore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Personal homes: icon + display name, 3 by default, extra slots cost 500 then x2 each time (all configurable). */
public final class HomeManager implements CommandExecutor, TabCompleter {
    public static final class Home {
        public String key, name;
        public Material icon = Material.RED_BED;
        public Location loc;
    }

    private final BerryCore plugin;
    private final Store store;
    private final Map<UUID, Map<String, Home>> homes = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> extra = new ConcurrentHashMap<>();

    public HomeManager(BerryCore plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "homes");
        ConfigurationSection ps = store.y.getConfigurationSection("players");
        if (ps != null) for (String u : ps.getKeys(false)) {
            UUID id = UUID.fromString(u);
            extra.put(id, ps.getInt(u + ".extra", 0));
            Map<String, Home> m = new LinkedHashMap<>();
            ConfigurationSection hs = ps.getConfigurationSection(u + ".homes");
            if (hs != null) for (String k : hs.getKeys(false)) {
                Home h = new Home();
                h.key = k;
                h.name = hs.getString(k + ".name", k);
                h.icon = Items.mat(hs.getString(k + ".icon"), Material.RED_BED);
                h.loc = hs.getLocation(k + ".loc");
                if (h.loc != null) m.put(k, h);
            }
            homes.put(id, m);
        }
    }

    public void save() {
        store.y.set("players", null);
        homes.forEach((id, m) -> {
            store.y.set("players." + id + ".extra", extra.getOrDefault(id, 0));
            m.forEach((k, h) -> {
                String b = "players." + id + ".homes." + k + ".";
                store.y.set(b + "name", h.name);
                store.y.set(b + "icon", h.icon.name());
                store.y.set(b + "loc", h.loc);
            });
        });
        extra.forEach((id, n) -> {
            if (!homes.containsKey(id)) store.y.set("players." + id + ".extra", n);
        });
        store.save();
    }

    private ConfigurationSection cfg() {
        return plugin.cfg.get("homes");
    }

    public Map<String, Home> of(UUID id) {
        return homes.computeIfAbsent(id, k -> new LinkedHashMap<>());
    }

    public int extraSlots(UUID id) {
        return extra.getOrDefault(id, 0);
    }

    public int max(Player p) {
        int best = cfg().getInt("base-max", 3);
        for (Map<?, ?> m : cfg().getMapList("permission-limits")) {
            if (m.get("permission") instanceof String s && m.get("max") instanceof Number n && p.hasPermission(s)) best = Math.max(best, n.intValue());
        }
        return best + extraSlots(p.getUniqueId());
    }

    public double nextCost(UUID id) {
        double base = cfg().getDouble("purchase.base-cost", 500);
        double mult = cfg().getDouble("purchase.multiplier", 2.0);
        return EconomyManager.round(base * Math.pow(mult, extraSlots(id)));
    }

    public boolean canBuyMore(UUID id) {
        return cfg().getBoolean("purchase.enabled", true) && extraSlots(id) < cfg().getInt("purchase.max-extra", 20);
    }


    public net.kyori.adventure.text.Component display(Player viewer, Home h) {
        return Msg.c(viewer, h.name);
    }

    // ------------------------------------------------------------------ actions

    public void set(Player p, String key, String displayName) {
        key = key.toLowerCase(Locale.ROOT);
        if (!key.matches(cfg().getString("name-regex", "[a-z0-9_-]{1,16}"))) { plugin.msg.send(p, "home-bad-name"); return; }
        if (cfg().getStringList("blocked-worlds").contains(p.getWorld().getName())) { plugin.msg.send(p, "home-blocked-world"); return; }
        Map<String, Home> m = of(p.getUniqueId());
        boolean bypass = p.hasPermission("berrycore.homes.bypass");
        if (!m.containsKey(key) && !bypass && m.size() >= max(p)) {
            plugin.msg.send(p, "home-limit", "max", String.valueOf(max(p)),
                    "hint_mm", canBuyMore(p.getUniqueId()) ? plugin.economy.fmt(nextCost(p.getUniqueId())) : "-");
            return;
        }
        Home h = m.getOrDefault(key, new Home());
        boolean fresh = h.key == null;
        h.key = key;
        h.loc = p.getLocation();
        if (fresh) {
            h.name = displayName != null ? cleanName(p, displayName) : key;
            h.icon = Items.mat(cfg().getString("default-icon", "RED_BED"), Material.RED_BED);
        }
        m.put(key, h);
        plugin.msg.send(p, fresh ? "home-set" : "home-moved", "name", key);
    }

    private String cleanName(Player p, String raw) {
        int max = cfg().getInt("display-name-max-length", 24);
        if (raw.length() > max) raw = raw.substring(0, max);
        boolean fmt = cfg().getBoolean("allow-formatting", true) && p.hasPermission("berrycore.homes.format");
        return fmt ? raw : Msg.esc(raw);
    }

    public void delete(Player p, String key) {
        if (of(p.getUniqueId()).remove(key.toLowerCase(Locale.ROOT)) == null) plugin.msg.send(p, "home-none", "name", key);
        else plugin.msg.send(p, "home-deleted", "name", key);
    }

    public void go(Player p, String key) {
        Home h = of(p.getUniqueId()).get(key.toLowerCase(Locale.ROOT));
        if (h == null) { plugin.msg.send(p, "home-none", "name", key); return; }
        Tp.go(p, h.loc, cfg().getConfigurationSection("teleport"), "home", h.name);
    }

    public void rename(Player p, String key, String name) {
        Home h = of(p.getUniqueId()).get(key.toLowerCase(Locale.ROOT));
        if (h == null) { plugin.msg.send(p, "home-none", "name", key); return; }
        h.name = cleanName(p, name);
        plugin.msg.send(p, "home-renamed", "name_mm", h.name);
    }

    public void icon(Player p, String key, String matName) {
        Home h = of(p.getUniqueId()).get(key.toLowerCase(Locale.ROOT));
        if (h == null) { plugin.msg.send(p, "home-none", "name", key); return; }
        Material m = matName == null || matName.equalsIgnoreCase("hand") ? p.getInventory().getItemInMainHand().getType() : Items.mat(matName, null);
        if (m == null || m.isAir() || cfg().getStringList("blocked-icons").contains(m.name())) { plugin.msg.send(p, "home-bad-icon"); return; }
        h.icon = m;
        plugin.msg.send(p, "home-icon-set", "icon", m.name().toLowerCase().replace('_', ' '));
    }

    public void buy(Player p) {
        UUID id = p.getUniqueId();
        if (!canBuyMore(id)) { plugin.msg.send(p, "home-buy-max"); return; }
        double cost = nextCost(id);
        Ui.confirm(p, "homes", plugin.cfg.get("messages").getString("home-ui.buy-title", "<gold>Buy a home slot"),
                plugin.cfg.get("messages").getString("home-ui.buy-body", "Cost: %cost%").replace("%cost%", plugin.economy.num(cost) + " " + plugin.economy.plural()), () -> {
                    double c = nextCost(id);
                    if (!plugin.economy.withdraw(id, c)) { plugin.msg.send(p, "not-enough", "amount_mm", plugin.economy.fmt(c)); return; }
                    extra.merge(id, 1, Integer::sum);
                    plugin.msg.send(p, "home-bought", "max", String.valueOf(max(p)), "amount_mm", plugin.economy.fmt(c));
                });
    }

    // ------------------------------------------------------------------ UI

    public void menu(Player p) {
        Map<String, Home> m = of(p.getUniqueId());
        var msgs = plugin.cfg.get("messages");
        List<Ui.Opt> o = new ArrayList<>();
        for (Home h : m.values()) {
            o.add(Ui.Opt.of(h.icon, h.name, List.of("<gray>" + h.loc.getWorld().getName() + " " + h.loc.getBlockX() + ", " + h.loc.getBlockY() + ", " + h.loc.getBlockZ(), "<yellow>Click to teleport"), x -> go(x, h.key)));
        }
        o.add(Ui.Opt.of(Material.LIME_DYE, msgs.getString("home-ui.set", "<green>Set a home here"), List.of("<gray>" + m.size() + "/" + max(p) + " used"), x ->
                Ui.form(x, "homes", msgs.getString("home-ui.set-title", "New home"),
                        List.of(Dlg.Field.text("key", msgs.getString("home-ui.key-label", "Home id"), "home"), Dlg.Field.text("name", msgs.getString("home-ui.name-label", "Display name (optional)"), "")),
                        (pp, v) -> { set(pp, v.get("key").trim(), v.get("name").isBlank() ? null : v.get("name")); menu(pp); })));
        if (!m.isEmpty()) o.add(Ui.Opt.of(Material.ANVIL, msgs.getString("home-ui.edit", "<aqua>Edit a home"), List.of(), x -> pickEdit(x)));
        if (canBuyMore(p.getUniqueId())) o.add(Ui.Opt.of(Material.GOLD_INGOT, msgs.getString("home-ui.buy", "<gold>Buy another home slot"),
                List.of("<gray>Cost: " + plugin.economy.fmt(nextCost(p.getUniqueId()))), x -> buy(x)));
        Ui.options(p, "homes", cfg().getString("ui.title", "<gradient:#7bff4d:#4dffd2>Homes</gradient>"), m.size() + "/" + max(p) + " homes used", o);
    }

    private void pickEdit(Player p) {
        List<Ui.Opt> o = new ArrayList<>();
        for (Home h : of(p.getUniqueId()).values()) o.add(Ui.Opt.of(h.icon, h.name, List.of("<yellow>Click to edit"), x -> edit(x, h)));
        Ui.options(p, "homes", cfg().getString("ui.edit-title", "<aqua>Pick a home to edit"), "", o);
    }

    private void edit(Player p, Home h) {
        var msgs = plugin.cfg.get("messages");
        List<Ui.Opt> o = new ArrayList<>();
        o.add(Ui.Opt.of(Material.NAME_TAG, msgs.getString("home-ui.rename", "<aqua>Rename"), List.of(), x ->
                Ui.input(x, "homes", msgs.getString("home-ui.rename-title", "Rename home"), msgs.getString("home-ui.name-label", "Display name"), h.name, s -> { rename(x, h.key, s); menu(x); })));
        o.add(Ui.Opt.of(Material.ITEM_FRAME, msgs.getString("home-ui.icon-hand", "<yellow>Icon: use the item in my hand"), List.of(), x -> { icon(x, h.key, "hand"); menu(x); }));
        o.add(Ui.Opt.of(Material.PAINTING, msgs.getString("home-ui.icon-name", "<yellow>Icon: type a material"), List.of("<gray>e.g. diamond_block"), x ->
                Ui.input(x, "homes", msgs.getString("home-ui.icon-title", "Home icon"), msgs.getString("home-ui.icon-label", "Material name"), h.icon.name().toLowerCase(), s -> { icon(x, h.key, s); menu(x); })));
        o.add(Ui.Opt.of(Material.ENDER_PEARL, msgs.getString("home-ui.move", "<light_purple>Move here"), List.of(), x -> { set(x, h.key, null); menu(x); }));
        o.add(Ui.Opt.of(Material.TNT, msgs.getString("home-ui.delete", "<red>Delete"), List.of(), x ->
                Ui.confirm(x, "homes", msgs.getString("home-ui.delete-title", "Delete home?"), h.key, () -> { delete(x, h.key); menu(x); })));
        Ui.options(p, "homes", h.name, "", o);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { plugin.msg.send(s, "players-only"); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "home" -> {
                Map<String, Home> m = of(p.getUniqueId());
                if (a.length > 0) go(p, a[0]);
                else if (m.isEmpty()) plugin.msg.send(p, "home-none-set");
                else if (m.size() == 1 || !cfg().getBoolean("no-arg-opens-menu", true)) go(p, m.keySet().iterator().next());
                else menu(p);
            }
            case "homes" -> menu(p);
            case "sethome" -> {
                if (a.length == 0 && cfg().getBoolean("setnoarg-opens-menu", false)) menu(p);
                else set(p, a.length > 0 ? a[0] : cfg().getString("default-name", "home"), a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : null);
            }
            case "delhome" -> { if (a.length > 0) delete(p, a[0]); else plugin.msg.send(p, "usage", "usage", "/delhome <name>"); }
            case "renamehome" -> { if (a.length > 1) rename(p, a[0], String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length))); else plugin.msg.send(p, "usage", "usage", "/renamehome <home> <new name>"); }
            case "homeicon" -> { if (a.length > 0) icon(p, a[0], a.length > 1 ? a[1] : "hand"); else plugin.msg.send(p, "usage", "usage", "/homeicon <home> [material|hand]"); }
            case "buyhome" -> buy(p);
            default -> {
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (s instanceof Player p && a.length == 1 && List.of("home", "delhome", "renamehome", "homeicon").contains(cmd.getName().toLowerCase())) out.addAll(of(p.getUniqueId()).keySet());
        if (a.length == 2 && cmd.getName().equalsIgnoreCase("homeicon")) out.addAll(List.of("hand", "diamond_block", "grass_block", "beacon"));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
