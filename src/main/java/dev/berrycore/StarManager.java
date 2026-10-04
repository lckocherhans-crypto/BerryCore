package dev.berrycore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boss;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stars: playtime income, kill rewards, star shop and the 3x3 "hammer" pickaxe. */
public final class StarManager implements CommandExecutor, TabCompleter, Listener {
    private final BerryCore plugin;
    private final Store store;
    private final Map<UUID, Long> stars = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> progress = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Map<String, Long> killCooldown = new HashMap<>();
    private final Set<UUID> hammerBusy = new HashSet<>();
    private final NamespacedKey hammerKey;
    private BukkitTask task;

    public StarManager(BerryCore plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "stars");
        this.hammerKey = new NamespacedKey(plugin, "hammer");
        ConfigurationSection s = store.y.getConfigurationSection("stars");
        if (s != null) for (String k : s.getKeys(false)) stars.put(UUID.fromString(k), s.getLong(k));
        ConfigurationSection pr = store.y.getConfigurationSection("progress");
        if (pr != null) for (String k : pr.getKeys(false)) progress.put(UUID.fromString(k), pr.getInt(k));
    }

    private ConfigurationSection cfg() {
        return plugin.cfg.get("stars");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public void save() {
        store.y.set("stars", null);
        store.y.set("progress", null);
        stars.forEach((k, v) -> store.y.set("stars." + k, v));
        progress.forEach((k, v) -> store.y.set("progress." + k, v));
        store.save();
    }

    public long get(UUID id) {
        return stars.getOrDefault(id, cfg().getLong("starting-stars", 0));
    }

    public void set(UUID id, long v) {
        stars.put(id, Math.max(0, v));
    }

    public void add(UUID id, long v) {
        set(id, get(id) + v);
    }

    public int intervalSeconds() {
        return Math.max(1, cfg().getInt("income.interval-seconds", 600));
    }

    public int secondsUntilNext(UUID id) {
        return intervalSeconds() - progress.getOrDefault(id, 0);
    }

    public List<Map.Entry<UUID, Long>> top(int n) {
        List<Map.Entry<UUID, Long>> l = new ArrayList<>(stars.entrySet());
        l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return l.size() > n ? l.subList(0, n) : l;
    }

    // ------------------------------------------------------------------ income

    private void tick() {
        if (!cfg().getBoolean("income.enabled", true)) return;
        long afk = cfg().getBoolean("income.afk.enabled", true) ? cfg().getLong("income.afk.after-seconds", 300) * 1000L : 0;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (afk > 0 && now - lastActive.getOrDefault(id, now) > afk) continue;
            int n = progress.merge(id, 1, Integer::sum);
            if (n >= intervalSeconds()) {
                progress.put(id, 0);
                long amt = cfg().getLong("income.amount", 1);
                add(id, amt);
                if (plugin.settings.get(p, "notify-stars")) plugin.msg.send(p, "star-income", "amount", String.valueOf(amt), "total", String.valueOf(get(id)));
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.hasChangedPosition() || e.hasChangedOrientation()) lastActive.put(e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastActive.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ kill rewards

    private void reward(Player killer, long amt, String reasonKey) {
        if (amt <= 0) return;
        add(killer.getUniqueId(), amt);
        if (plugin.settings.get(killer, "notify-stars")) plugin.msg.send(killer, reasonKey, "amount", String.valueOf(amt), "total", String.valueOf(get(killer.getUniqueId())));
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        ConfigurationSection r = cfg().getConfigurationSection("rewards");
        if (r.getBoolean("block-same-ip", true) && killer.getAddress() != null && victim.getAddress() != null
                && killer.getAddress().getAddress().equals(victim.getAddress().getAddress())) return;
        String key = killer.getUniqueId() + ">" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        long cd = r.getLong("player-kill-cooldown-seconds", 300) * 1000L;
        if (killCooldown.getOrDefault(key, 0L) > now) return;
        killCooldown.put(key, now + cd);
        reward(killer, r.getLong("kill-player", 10), "star-kill-player");
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead instanceof Player) return;
        Player killer = dead.getKiller();
        if (killer == null) return;
        ConfigurationSection r = cfg().getConfigurationSection("rewards");
        boolean boss = (r.getBoolean("use-boss-interface", true) && dead instanceof Boss)
                || r.getStringList("boss-entity-types").contains(dead.getType().name());
        if (boss) reward(killer, r.getLong("kill-boss", 10), "star-kill-boss");
        else reward(killer, r.getLong("kill-mob", 0), "star-kill-mob");
    }

    // ------------------------------------------------------------------ hammer pickaxe

    private int hammerSize(ItemStack tool) {
        if (tool == null || !tool.hasItemMeta()) return 0;
        Integer v = tool.getItemMeta().getPersistentDataContainer().get(hammerKey, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (hammerBusy.contains(p.getUniqueId())) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        int size = hammerSize(tool);
        if (size <= 1) return;
        ConfigurationSection h = cfg().getConfigurationSection("hammer");
        if (h.getBoolean("disable-when-sneaking", true) && p.isSneaking()) return;
        Block center = e.getBlock();
        BlockFace face = BlockFace.UP;
        RayTraceResult rt = p.rayTraceBlocks(7.0);
        if (rt != null && rt.getHitBlock() != null && rt.getHitBlock().equals(center) && rt.getHitBlockFace() != null) face = rt.getHitBlockFace();
        int rad = size / 2;
        float baseHardness = center.getType().getHardness();
        double maxRatio = h.getDouble("max-hardness-ratio", 3.0);
        boolean onlyPreferred = h.getBoolean("only-preferred-tool", true);
        boolean skipTiles = h.getBoolean("skip-tile-entities", true);
        List<String> blacklist = h.getStringList("blacklist");
        int broken = 0;
        hammerBusy.add(p.getUniqueId());
        try {
            for (int a = -rad; a <= rad; a++) {
                for (int b = -rad; b <= rad; b++) {
                    if (a == 0 && b == 0) continue;
                    int dx = 0, dy = 0, dz = 0;
                    if (face.getModX() != 0) { dy = a; dz = b; }
                    else if (face.getModY() != 0) { dx = a; dz = b; }
                    else { dx = a; dy = b; }
                    Block t = center.getRelative(dx, dy, dz);
                    Material tm = t.getType();
                    if (tm.isAir() || t.isLiquid() || tm.getHardness() < 0 || blacklist.contains(tm.name())) continue;
                    if (onlyPreferred && !t.isPreferredTool(tool)) continue;
                    if (skipTiles && t.getState() instanceof TileState) continue;
                    if (baseHardness > 0 && tm.getHardness() > baseHardness * maxRatio) continue;
                    BlockBreakEvent be = new BlockBreakEvent(t, p);
                    Bukkit.getPluginManager().callEvent(be);
                    if (be.isCancelled()) continue;
                    if (be.isDropItems()) t.breakNaturally(tool);
                    else t.setType(Material.AIR);
                    broken++;
                }
            }
        } finally {
            hammerBusy.remove(p.getUniqueId());
        }
        if (broken > 0 && h.getBoolean("extra-durability-cost", true)) damage(p, tool, broken);
    }

    private void damage(Player p, ItemStack tool, int amount) {
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable d) || meta.isUnbreakable()) return;
        int unb = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        int dmg = 0;
        for (int i = 0; i < amount; i++) if (unb == 0 || Math.random() < 1.0 / (unb + 1)) dmg++;
        d.setDamage(d.getDamage() + dmg);
        if (d.getDamage() >= tool.getType().getMaxDurability()) {
            p.getInventory().setItemInMainHand(null);
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            return;
        }
        tool.setItemMeta(meta);
        p.getInventory().setItemInMainHand(tool);
    }

    // ------------------------------------------------------------------ shop

    private ItemStack deliverable(Player p, ConfigurationSection s) {
        ItemStack it = Items.fromSection(p, s);
        int hs = s.getInt("hammer", 0);
        if (hs > 1) {
            ItemMeta m = it.getItemMeta();
            m.getPersistentDataContainer().set(hammerKey, PersistentDataType.INTEGER, hs);
            it.setItemMeta(m);
        }
        return it;
    }

    public void openShop(Player p) {
        ConfigurationSection shop = cfg().getConfigurationSection("shop");
        ConfigurationSection items = shop.getConfigurationSection("items");
        String title = shop.getString("title", "<gradient:#ffe14d:#ff9d4d>Star Shop</gradient>");
        List<String> footer = shop.getStringList("lore-footer");
        long bal = get(p.getUniqueId());
        List<Ui.Opt> dialogOpts = new ArrayList<>();
        boolean useDialog = Ui.dialog(p, "stars");
        Menu m = new Menu(Msg.c(p, title, "stars", String.valueOf(bal)), shop.getInt("rows", 3));
        ConfigurationSection fill = shop.getConfigurationSection("filler");
        if (fill != null && fill.getBoolean("enabled", true)) {
            ItemStack f = Items.of(Items.mat(fill.getString("material"), Material.BLACK_STAINED_GLASS_PANE), Msg.c(" "), null);
            for (int i = 0; i < m.inv.getSize(); i++) m.inv.setItem(i, f);
        }
        if (items != null) for (String id : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(id);
            long cost = s.getLong("cost", 100);
            ItemStack display = Items.fromSection(p, s);
            ItemMeta dm = display.getItemMeta();
            List<net.kyori.adventure.text.Component> lore = dm.lore() == null ? new ArrayList<>() : new ArrayList<>(dm.lore());
            for (String l : footer) lore.add(Msg.c(p, l, "cost", String.valueOf(cost), "stars", String.valueOf(bal)));
            dm.lore(lore);
            display.setItemMeta(dm);
            if (useDialog) {
                List<String> dl = new ArrayList<>(s.getStringList("lore"));
                dialogOpts.add(Ui.Opt.of(Items.mat(s.getString("material"), Material.STONE), s.getString("name", id) + " <gray>(" + cost + ")", dl, x -> buy(x, id)));
            } else {
                m.set(s.getInt("slot", 0), display, e -> buy(p, id));
            }
        }
        if (useDialog) Ui.options(p, "stars", title.replace("%stars%", String.valueOf(bal)), "You have " + bal + " stars.", dialogOpts);
        else m.open(p);
    }

    private void buy(Player p, String id) {
        ConfigurationSection s = cfg().getConfigurationSection("shop.items." + id);
        if (s == null) return;
        long cost = s.getLong("cost", 100);
        if (get(p.getUniqueId()) < cost) { plugin.msg.send(p, "star-not-enough", "cost", String.valueOf(cost), "stars", String.valueOf(get(p.getUniqueId()))); return; }
        String perm = s.getString("permission", "");
        if (!perm.isEmpty() && !p.hasPermission(perm)) { plugin.msg.send(p, "no-permission"); return; }
        Runnable go = () -> {
            if (get(p.getUniqueId()) < cost) return;
            set(p.getUniqueId(), get(p.getUniqueId()) - cost);
            if (s.getBoolean("give-item", true) && s.contains("material")) {
                p.getInventory().addItem(deliverable(p, s)).values().forEach(o -> p.getWorld().dropItemNaturally(p.getLocation(), o));
            }
            for (String c : s.getStringList("commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
            plugin.msg.send(p, "star-bought", "item_mm", s.getString("name", id), "cost", String.valueOf(cost));
        };
        if (plugin.settings.get(p, "confirm-star-purchase")) {
            Ui.confirm(p, "stars", cfg().getString("shop.confirm-title", "<yellow>Buy this item?"), Msg.fill(cfg().getString("shop.confirm-body", "%cost% stars"), "cost", String.valueOf(cost)), go);
        } else go.run();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (cmd.getName().equalsIgnoreCase("starshop")) {
            if (s instanceof Player p) openShop(p); else plugin.msg.send(s, "players-only");
            return true;
        }
        if (a.length == 0 || (a.length == 1 && Bukkit.getPlayerExact(a[0]) != null && !a[0].equalsIgnoreCase("shop") && !a[0].equalsIgnoreCase("top"))) {
            UUID t = a.length == 1 ? Bukkit.getPlayerExact(a[0]).getUniqueId() : s instanceof Player p ? p.getUniqueId() : null;
            if (t == null) { plugin.msg.send(s, "players-only"); return true; }
            plugin.msg.send(s, "stars-balance", "player", plugin.economy.name(t), "stars", String.valueOf(get(t)),
                    "next", String.valueOf(secondsUntilNext(t)));
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "shop" -> { if (s instanceof Player p) openShop(p); }
            case "top" -> {
                plugin.msg.send(s, "stars-top-header");
                int i = 1;
                for (var e : top(10)) plugin.msg.send(s, "stars-top-line", "rank", String.valueOf(i++), "player", plugin.economy.name(e.getKey()), "stars", String.valueOf(e.getValue()));
            }
            case "give", "take", "set", "reset" -> {
                if (!s.hasPermission("berrycore.stars.admin")) { plugin.msg.send(s, "no-permission"); return true; }
                if (a.length < 2) { plugin.msg.send(s, "usage", "usage", "/stars <give|take|set|reset> <player> [amount]"); return true; }
                UUID t = plugin.economy.find(a[1]);
                if (t == null) { plugin.msg.send(s, "player-not-found", "name", a[1]); return true; }
                long v = 0;
                try { if (a.length > 2) v = Long.parseLong(a[2]); } catch (NumberFormatException ex) { plugin.msg.send(s, "invalid-amount"); return true; }
                switch (a[0].toLowerCase()) {
                    case "give" -> add(t, v);
                    case "take" -> set(t, get(t) - v);
                    case "set" -> set(t, v);
                    default -> set(t, 0);
                }
                plugin.msg.send(s, "stars-admin-done", "player", plugin.economy.name(t), "stars", String.valueOf(get(t)));
            }
            default -> plugin.msg.send(s, "usage", "usage", "/stars [player|shop|top]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("starshop")) return out;
        if (a.length == 1) { out.addAll(List.of("shop", "top")); if (s.hasPermission("berrycore.stars.admin")) out.addAll(List.of("give", "take", "set", "reset")); Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName())); }
        else if (a.length == 2) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
