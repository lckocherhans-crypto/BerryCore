package dev.berrycore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Berries balances. Everything is keyed by UUID so offline players work. */
public final class EconomyManager {
    private final BerryCore plugin;
    private final Store store;
    private final Map<UUID, Double> bal = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public EconomyManager(BerryCore plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "balances");
        ConfigurationSection s = store.y.getConfigurationSection("balances");
        if (s != null) for (String k : s.getKeys(false)) {
            try {
                bal.put(UUID.fromString(k), s.getDouble(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
        ConfigurationSection n = store.y.getConfigurationSection("names");
        if (n != null) for (String k : n.getKeys(false)) {
            try {
                names.put(UUID.fromString(k), n.getString(k));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private ConfigurationSection cfg() {
        return plugin.cfg.get("economy");
    }

    public void remember(UUID id, String name) {
        names.put(id, name);
        bal.putIfAbsent(id, cfg().getDouble("starting-balance", 100));
    }

    public String name(UUID id) {
        String n = names.get(id);
        if (n == null) n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "Unknown" : n;
    }

    public UUID find(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        return null;
    }

    public double get(UUID id) {
        return bal.getOrDefault(id, cfg().getDouble("starting-balance", 100));
    }

    public boolean has(UUID id, double amt) {
        return get(id) + 1e-9 >= amt;
    }

    public static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public void set(UUID id, double v) {
        bal.put(id, Math.max(0, round(v)));
    }

    public void deposit(UUID id, double v) {
        set(id, get(id) + v);
    }

    public boolean withdraw(UUID id, double v) {
        if (!has(id, v)) return false;
        set(id, get(id) - v);
        return true;
    }

    public List<Map.Entry<UUID, Double>> top(int n) {
        List<Map.Entry<UUID, Double>> l = new ArrayList<>(bal.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return l.size() > n ? l.subList(0, n) : l;
    }

    /** Number only, e.g. 1,234.50 or 1.2K depending on config. */
    public String num(double v) {
        ConfigurationSection c = cfg().getConfigurationSection("currency");
        int dec = c.getInt("decimals", 2);
        double compact = c.getDouble("compact-above", 0);
        if (compact > 0 && Math.abs(v) >= compact) {
            String[] suf = {"", "K", "M", "B", "T"};
            int i = 0;
            double x = v;
            while (Math.abs(x) >= 1000 && i < suf.length - 1) {
                x /= 1000;
                i++;
            }
            return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US)).format(x) + suf[i];
        }
        String pat = (c.getBoolean("thousands-separator", true) ? "#,##0" : "0") + (dec > 0 ? "." + "0".repeat(dec) : "");
        return new DecimalFormat(pat, DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    /** Fully formatted (MiniMessage): "🍓1,234.50 Berries". */
    public String fmt(double v) {
        ConfigurationSection c = cfg().getConfigurationSection("currency");
        String name = Math.abs(v - 1.0) < 1e-9 ? c.getString("singular", "Berry") : c.getString("plural", "Berries");
        return c.getString("format", "<light_purple>%symbol%%amount% %name%</light_purple>")
                .replace("%symbol%", c.getString("symbol", ""))
                .replace("%amount%", num(v))
                .replace("%name%", name);
    }

    public String plural() {
        return cfg().getString("currency.plural", "Berries");
    }

    /** Parses "100", "1.5k", "2m". Returns -1 when invalid. */
    public static double parse(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double mult = 1;
        if (s.endsWith("k")) { mult = 1_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1_000_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1_000_000_000; s = s.substring(0, s.length() - 1); }
        try {
            double v = Double.parseDouble(s) * mult;
            return Double.isFinite(v) && v >= 0 ? round(v) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void save() {
        store.y.set("balances", null);
        store.y.set("names", null);
        bal.forEach((k, v) -> store.y.set("balances." + k, v));
        names.forEach((k, v) -> store.y.set("names." + k, v));
        store.save();
    }
}
