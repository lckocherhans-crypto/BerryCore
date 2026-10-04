package dev.berrycore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Player-driven market: everyone owns a shop, listings from every open shop show up in /market (/ah). */
public final class MarketManager implements CommandExecutor, TabCompleter, Listener {
    public static final class Listing {
        public String id;
        public ItemStack item;
        public double price;
        public long created;
    }

    public static final class Shop {
        public UUID owner;
        public String name;
        public Material icon = Material.CHEST;
        public boolean open = true;
        public double pending;
        public final Map<String, Listing> listings = new LinkedHashMap<>();
    }

    public enum Sort { NEWEST, PRICE_LOW, PRICE_HIGH, NAME }

    public static final class Query {
        public Sort sort = Sort.NEWEST;
        public String search;
        public UUID shop;
        public int page;
    }

    private final BerryCore plugin;
    private final Store store;
    private final Map<UUID, Shop> shops = new ConcurrentHashMap<>();

    public MarketManager(BerryCore plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "market");
        ConfigurationSection ss = store.y.getConfigurationSection("shops");
        if (ss != null) for (String u : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(u);
            Shop sh = new Shop();
            sh.owner = UUID.fromString(u);
            sh.name = s.getString("name", "Shop");
            sh.icon = Items.mat(s.getString("icon"), Material.CHEST);
            sh.open = s.getBoolean("open", true);
            sh.pending = s.getDouble("pending", 0);
            ConfigurationSection ls = s.getConfigurationSection("listings");
            if (ls != null) for (String id : ls.getKeys(false)) {
                Listing l = new Listing();
                l.id = id;
                l.item = ls.getItemStack(id + ".item");
                l.price = ls.getDouble(id + ".price");
                l.created = ls.getLong(id + ".created");
                if (l.item != null) sh.listings.put(id, l);
            }
            shops.put(sh.owner, sh);
        }
    }

    public void save() {
        store.y.set("shops", null);
        for (Shop sh : shops.values()) {
            String b = "shops." + sh.owner + ".";
            store.y.set(b + "name", sh.name);
            store.y.set(b + "icon", sh.icon.name());
            store.y.set(b + "open", sh.open);
            store.y.set(b + "pending", sh.pending);
            for (Listing l : sh.listings.values()) {
                store.y.set(b + "listings." + l.id + ".item", l.item);
                store.y.set(b + "listings." + l.id + ".price", l.price);
                store.y.set(b + "listings." + l.id + ".created", l.created);
            }
        }
        store.save();
    }

    private ConfigurationSection cfg() {
        return plugin.cfg.get("market");
    }

    public int listingCount() {
        return shops.values().stream().filter(s -> s.open).mapToInt(s -> s.listings.size()).sum();
    }

    public int listingCountOf(UUID id) {
        Shop s = shops.get(id);
        return s == null ? 0 : s.listings.size();
    }

    public int limit(Player p) {
        int best = cfg().getInt("max-listings", 10);
        for (Map<?, ?> m : cfg().getMapList("listing-limits")) {
            if (m.get("permission") instanceof String s && m.get("max") instanceof Number n && p.hasPermission(s)) best = Math.max(best, n.intValue());
        }
        return best;
    }

    private Shop shopOf(Player p) {
        return shops.computeIfAbsent(p.getUniqueId(), k -> {
            Shop s = new Shop();
            s.owner = k;
            s.name = Msg.esc(cfg().getString("default-shop-name", "%player%'s Shop").replace("%player%", p.getName()));
            s.icon = Items.mat(cfg().getString("default-shop-icon", "CHEST"), Material.CHEST);
            return s;
        });
    }

    private static boolean fits(Inventory inv, ItemStack item) {
        int need = item.getAmount();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) need -= item.getMaxStackSize();
            else if (s.isSimilar(item)) need -= item.getMaxStackSize() - s.getAmount();
            if (need <= 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ actions

    public void list(Player p, double price) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { plugin.msg.send(p, "hold-item"); return; }
        if (cfg().getStringList("blacklist").contains(hand.getType().name())) { plugin.msg.send(p, "market-blacklisted"); return; }
        if (price < cfg().getDouble("min-price", 1) || price > cfg().getDouble("max-price", 1_000_000_000)) {
            plugin.msg.send(p, "market-bad-price", "min", plugin.economy.num(cfg().getDouble("min-price", 1)), "max", plugin.economy.num(cfg().getDouble("max-price", 1_000_000_000)));
            return;
        }
        Shop sh = shopOf(p);
        if (sh.listings.size() >= limit(p)) { plugin.msg.send(p, "market-limit", "max", String.valueOf(limit(p))); return; }
        double fee = EconomyManager.round(price * cfg().getDouble("listing-fee-percent", 0) / 100.0);
        Runnable go = () -> {
            ItemStack cur = p.getInventory().getItemInMainHand();
            if (cur.getType().isAir()) return;
            if (fee > 0 && !plugin.economy.withdraw(p.getUniqueId(), fee)) { plugin.msg.send(p, "not-enough", "amount_mm", plugin.economy.fmt(fee)); return; }
            Listing l = new Listing();
            l.id = Long.toString(System.nanoTime(), 36);
            l.item = cur.clone();
            l.price = price;
            l.created = System.currentTimeMillis();
            sh.listings.put(l.id, l);
            p.getInventory().setItemInMainHand(null);
            plugin.msg.send(p, "market-listed", "item", hand.getType().name().toLowerCase().replace('_', ' '), "amount", String.valueOf(l.item.getAmount()), "price_mm", plugin.economy.fmt(price));
        };
        if (plugin.settings.get(p, "confirm-market-listing"))
            Ui.confirm(p, "market", cfg().getString("ui.list-confirm-title", "<green>List this item?"),
                    hand.getAmount() + "x " + hand.getType().name().toLowerCase().replace('_', ' ') + " for " + plugin.economy.num(price) + (fee > 0 ? " (fee " + plugin.economy.num(fee) + ")" : ""), go);
        else go.run();
    }

    public void unlist(Player p, String id) {
        Shop sh = shops.get(p.getUniqueId());
        Listing l = sh == null ? null : sh.listings.get(id);
        if (l == null) { plugin.msg.send(p, "market-gone"); return; }
        if (!fits(p.getInventory(), l.item)) { plugin.msg.send(p, "inventory-full"); return; }
        sh.listings.remove(id);
        p.getInventory().addItem(l.item);
        plugin.msg.send(p, "market-unlisted");
    }

    public void buy(Player b, UUID owner, String id) {
        Shop sh = shops.get(owner);
        Listing l = sh == null || !sh.open ? null : sh.listings.get(id);
        if (l == null) { plugin.msg.send(b, "market-gone"); return; }
        if (owner.equals(b.getUniqueId())) { unlist(b, id); return; }
        if (!plugin.economy.has(b.getUniqueId(), l.price)) { plugin.msg.send(b, "not-enough", "amount_mm", plugin.economy.fmt(l.price)); return; }
        if (!fits(b.getInventory(), l.item)) { plugin.msg.send(b, "inventory-full"); return; }
        Runnable go = () -> {
            Shop s2 = shops.get(owner);
            if (s2 == null || s2.listings.remove(id) == null) { plugin.msg.send(b, "market-gone"); return; }
            if (!plugin.economy.withdraw(b.getUniqueId(), l.price)) { s2.listings.put(id, l); plugin.msg.send(b, "not-enough", "amount_mm", plugin.economy.fmt(l.price)); return; }
            double tax = EconomyManager.round(l.price * cfg().getDouble("sales-tax-percent", 5) / 100.0);
            double net = l.price - tax;
            plugin.economy.deposit(owner, net);
            b.getInventory().addItem(l.item);
            String item = l.item.getAmount() + "x " + l.item.getType().name().toLowerCase().replace('_', ' ');
            plugin.msg.send(b, "market-bought", "item", item, "price_mm", plugin.economy.fmt(l.price), "seller", plugin.economy.name(owner));
            Player seller = Bukkit.getPlayer(owner);
            if (seller != null) {
                if (plugin.settings.get(seller, "notify-sales")) plugin.msg.send(seller, "market-sold", "item", item, "buyer", b.getName(), "amount_mm", plugin.economy.fmt(net), "tax_mm", plugin.economy.fmt(tax));
            } else s2.pending += net;
        };
        if (plugin.settings.get(b, "confirm-market-purchase")) {
            String item = l.item.getAmount() + "x " + l.item.getType().name().toLowerCase().replace('_', ' ');
            Ui.confirm(b, "market", cfg().getString("ui.buy-confirm-title", "<green>Buy this item?"), item + " for " + plugin.economy.num(l.price) + " " + plugin.economy.plural(), go);
        } else go.run();
    }

    // ------------------------------------------------------------------ GUIs

    public void browse(Player p, Query q) {
        if (q == null) q = new Query();
        final Query query = q;
        List<Map.Entry<Shop, Listing>> all = new ArrayList<>();
        for (Shop sh : shops.values()) {
            if (!sh.open && !sh.owner.equals(p.getUniqueId())) continue;
            if (!sh.open) continue;
            if (query.shop != null && !sh.owner.equals(query.shop)) continue;
            for (Listing l : sh.listings.values()) {
                if (query.search != null && !query.search.isBlank()) {
                    String hay = l.item.getType().name().toLowerCase().replace('_', ' ')
                            + (l.item.hasItemMeta() && l.item.getItemMeta().hasDisplayName()
                            ? " " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(l.item.getItemMeta().displayName()).toLowerCase() : "");
                    if (!hay.contains(query.search.toLowerCase(Locale.ROOT))) continue;
                }
                all.add(Map.entry(sh, l));
            }
        }
        Comparator<Map.Entry<Shop, Listing>> cmp = switch (query.sort) {
            case NEWEST -> Comparator.comparingLong((Map.Entry<Shop, Listing> e) -> e.getValue().created).reversed();
            case PRICE_LOW -> Comparator.comparingDouble(e -> e.getValue().price);
            case PRICE_HIGH -> Comparator.comparingDouble((Map.Entry<Shop, Listing> e) -> e.getValue().price).reversed();
            case NAME -> Comparator.comparing(e -> e.getValue().item.getType().name());
        };
        all.sort(cmp);
        int perPage = 45;
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) perPage));
        query.page = Math.max(0, Math.min(query.page, pages - 1));
        var gui = cfg().getConfigurationSection("gui");
        Menu m = new Menu(Msg.c(p, gui.getString("title", "<gradient:#4dffb5:#4d9dff>Market</gradient> <gray>%page%/%pages%"), "page", String.valueOf(query.page + 1), "pages", String.valueOf(pages)), 6);
        for (int i = 0; i < perPage && query.page * perPage + i < all.size(); i++) {
            Map.Entry<Shop, Listing> e = all.get(query.page * perPage + i);
            Shop sh = e.getKey();
            Listing l = e.getValue();
            ItemStack it = l.item.clone();
            var meta = it.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            boolean mine = sh.owner.equals(p.getUniqueId());
            for (String line : cfg().getStringList(mine ? "gui.listing-lore-own" : "gui.listing-lore"))
                lore.add(Msg.c(p, line, "price_mm", plugin.economy.fmt(l.price), "seller", plugin.economy.name(sh.owner), "shop_mm", sh.name,
                        "age", ago(System.currentTimeMillis() - l.created)));
            meta.lore(lore);
            it.setItemMeta(meta);
            m.set(i, it, ev -> buy(p, sh.owner, l.id));
        }
        ItemStack bar = Items.of(Items.mat(gui.getString("filler", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE), Msg.c(" "), null);
        for (int i = 45; i < 54; i++) m.inv.setItem(i, bar);
        if (query.page > 0) m.set(45, Items.of(p, Material.ARROW, gui.getString("prev", "<yellow>Previous page"), List.of()), e -> { query.page--; browse(p, query); });
        if (query.page < pages - 1) m.set(53, Items.of(p, Material.ARROW, gui.getString("next", "<yellow>Next page"), List.of()), e -> { query.page++; browse(p, query); });
        m.set(47, Items.of(p, Material.HOPPER, gui.getString("sort", "<aqua>Sort: %sort%"), List.of("<gray>Click to change"), "sort", query.sort.name().toLowerCase().replace('_', ' ')), e -> {
            query.sort = Sort.values()[(query.sort.ordinal() + 1) % Sort.values().length];
            query.page = 0;
            browse(p, query);
        });
        m.set(49, Items.of(p, Material.COMPASS, gui.getString("search", "<green>Search"), List.of(query.search == null ? "<gray>Click to search" : "<gray>Current: " + Msg.esc(query.search), "<gray>Right-click to clear")), e -> {
            if (e.isRightClick()) { query.search = null; query.page = 0; browse(p, query); return; }
            Ui.input(p, "market", cfg().getString("ui.search-title", "Search the market"), cfg().getString("ui.search-label", "Item name"), "", s -> { query.search = s; query.page = 0; browse(p, query); });
        });
        m.set(51, Items.of(p, Material.CHEST_MINECART, gui.getString("my-shop", "<gold>My shop"), List.of("<gray>Manage your listings")), e -> manage(p));
        m.set(46, Items.of(p, Material.BOOKSHELF, gui.getString("directory", "<light_purple>All shops"), List.of()), e -> directory(p));
        m.open(p);
    }

    private String ago(long ms) {
        long m = ms / 60000;
        if (m < 60) return m + "m";
        if (m < 1440) return (m / 60) + "h";
        return (m / 1440) + "d";
    }

    public void directory(Player p) {
        List<Shop> list = new ArrayList<>(shops.values().stream().filter(s -> s.open && !s.listings.isEmpty()).toList());
        list.sort((a, b) -> Integer.compare(b.listings.size(), a.listings.size()));
        Menu m = new Menu(Msg.c(p, cfg().getString("gui.directory-title", "<light_purple>Shops")), 6);
        for (int i = 0; i < Math.min(54, list.size()); i++) {
            Shop s = list.get(i);
            ItemStack it = Items.of(p, s.icon, s.name, List.of("<gray>Owner: <white>" + Msg.esc(plugin.economy.name(s.owner)), "<gray>Listings: <white>" + s.listings.size(), "<yellow>Click to browse"));
            m.set(i, it, e -> {
                Query q = new Query();
                q.shop = s.owner;
                browse(p, q);
            });
        }
        m.open(p);
    }

    public void manage(Player p) {
        Shop sh = shopOf(p);
        Menu m = new Menu(Msg.c(p, cfg().getString("gui.manage-title", "<gold>My shop"), "shop", sh.name), 6);
        int i = 0;
        for (Listing l : sh.listings.values()) {
            if (i >= 45) break;
            ItemStack it = l.item.clone();
            var meta = it.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            for (String line : cfg().getStringList("gui.manage-lore")) lore.add(Msg.c(p, line, "price_mm", plugin.economy.fmt(l.price)));
            meta.lore(lore);
            it.setItemMeta(meta);
            m.set(i++, it, e -> { unlist(p, l.id); manage(p); });
        }
        var gui = cfg().getConfigurationSection("gui");
        ItemStack bar = Items.of(Items.mat(gui.getString("filler", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE), Msg.c(" "), null);
        for (int s = 45; s < 54; s++) m.inv.setItem(s, bar);
        m.set(45, Items.of(p, Material.EMERALD, gui.getString("list-hand", "<green>List item in hand"), List.of("<gray>Hold an item, then click")), e ->
                Ui.input(p, "market", cfg().getString("ui.price-title", "List item"), cfg().getString("ui.price-label", "Price (Berries)"), "", s -> {
                    double v = EconomyManager.parse(s);
                    if (v <= 0) plugin.msg.send(p, "invalid-amount"); else list(p, v);
                }));
        m.set(47, Items.of(p, Material.NAME_TAG, gui.getString("rename", "<aqua>Rename shop"), List.of(sh.name)), e ->
                Ui.input(p, "market", cfg().getString("ui.rename-title", "Rename shop"), cfg().getString("ui.rename-label", "Shop name"), "", s -> { rename(p, s); manage(p); }));
        m.set(49, Items.of(p, sh.icon, gui.getString("icon", "<yellow>Icon: item in hand"), List.of("<gray>Click to use your held item")), e -> { icon(p, "hand"); manage(p); });
        m.set(51, Items.of(p, sh.open ? Material.LIME_DYE : Material.GRAY_DYE, sh.open ? gui.getString("open", "<green>Shop is OPEN") : gui.getString("closed", "<red>Shop is CLOSED"), List.of("<gray>Click to toggle")), e -> { sh.open = !sh.open; manage(p); });
        m.set(53, Items.of(p, Material.ARROW, gui.getString("back", "<gray>Back to market"), List.of()), e -> browse(p, null));
        m.open(p);
    }

    public void rename(Player p, String name) {
        int max = cfg().getInt("shop-name-max-length", 24);
        if (name.length() > max) name = name.substring(0, max);
        boolean fmt = cfg().getBoolean("allow-formatting", true) && p.hasPermission("berrycore.homes.format");
        shopOf(p).name = fmt ? name : Msg.esc(name);
        plugin.msg.send(p, "shop-renamed");
    }

    public void icon(Player p, String mat) {
        Material m = mat == null || mat.equalsIgnoreCase("hand") ? p.getInventory().getItemInMainHand().getType() : Items.mat(mat, null);
        if (m == null || m.isAir()) { plugin.msg.send(p, "home-bad-icon"); return; }
        shopOf(p).icon = m;
        plugin.msg.send(p, "shop-icon-set");
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { plugin.msg.send(s, "players-only"); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "market" -> {
                Query q = new Query();
                if (a.length > 0) q.search = String.join(" ", a);
                browse(p, q);
            }
            case "shops" -> directory(p);
            case "shop" -> {
                if (a.length == 0) { manage(p); return true; }
                switch (a[0].toLowerCase()) {
                    case "sell", "list" -> {
                        if (a.length > 1) {
                            double v = EconomyManager.parse(a[1]);
                            if (v <= 0) plugin.msg.send(p, "invalid-amount"); else list(p, v);
                        } else Ui.input(p, "market", cfg().getString("ui.price-title", "List item"), cfg().getString("ui.price-label", "Price (Berries)"), "", x -> {
                            double v = EconomyManager.parse(x);
                            if (v <= 0) plugin.msg.send(p, "invalid-amount"); else list(p, v);
                        });
                    }
                    case "name", "rename" -> { if (a.length > 1) rename(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length))); else plugin.msg.send(p, "usage", "usage", "/shop name <name>"); }
                    case "icon" -> icon(p, a.length > 1 ? a[1] : "hand");
                    case "open" -> { shopOf(p).open = true; plugin.msg.send(p, "shop-opened"); }
                    case "close" -> { shopOf(p).open = false; plugin.msg.send(p, "shop-closed"); }
                    case "visit" -> {
                        UUID t = a.length > 1 ? plugin.economy.find(a[1]) : null;
                        if (t == null) { plugin.msg.send(p, "player-not-found", "name", a.length > 1 ? a[1] : "?"); return true; }
                        Query q = new Query();
                        q.shop = t;
                        browse(p, q);
                    }
                    case "admin" -> {
                        if (!p.hasPermission("berrycore.market.admin") || a.length < 3 || !a[1].equalsIgnoreCase("clear")) { plugin.msg.send(p, "usage", "usage", "/shop admin clear <player>"); return true; }
                        UUID t = plugin.economy.find(a[2]);
                        if (t != null && shops.remove(t) != null) plugin.msg.send(p, "market-admin-cleared"); else plugin.msg.send(p, "player-not-found", "name", a[2]);
                    }
                    default -> manage(p);
                }
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("shop")) {
            if (a.length == 1) out.addAll(List.of("sell", "name", "icon", "open", "close", "visit"));
            else if (a.length == 2 && a[0].equalsIgnoreCase("visit")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Shop sh = shops.get(e.getPlayer().getUniqueId());
        if (sh != null && sh.pending > 0) {
            double amt = sh.pending;
            sh.pending = 0;
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.msg.send(e.getPlayer(), "market-offline-earnings", "amount_mm", plugin.economy.fmt(amt)), 40L);
        }
    }
}
