package dev.berrycore;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** balance, pay, baltop, eco, sell, worth, berries (hub). */
public final class EconomyCommands implements CommandExecutor, TabCompleter {
    private final BerryCore plugin;

    public EconomyCommands(BerryCore plugin) {
        this.plugin = plugin;
    }

    private EconomyManager eco() {
        return plugin.economy;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase()) {
            case "balance" -> balance(s, a);
            case "pay" -> pay(s, a);
            case "baltop" -> baltop(s);
            case "eco" -> admin(s, a);
            case "worth" -> {
                if (s instanceof Player p) plugin.sell.worth(p);
            }
            case "sell" -> sell(s, a);
            case "berries" -> {
                if (s instanceof Player p) hub(p);
            }
            default -> {
            }
        }
        return true;
    }

    private void balance(CommandSender s, String[] a) {
        UUID target;
        if (a.length > 0) {
            target = eco().find(a[0]);
            if (target == null) { plugin.msg.send(s, "player-not-found", "name", a[0]); return; }
        } else if (s instanceof Player p) target = p.getUniqueId();
        else { plugin.msg.send(s, "players-only"); return; }
        plugin.msg.send(s, target.equals(s instanceof Player pl ? pl.getUniqueId() : null) ? "balance" : "balance-other",
                "amount_mm", eco().fmt(eco().get(target)), "player", eco().name(target));
    }

    private void pay(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { plugin.msg.send(s, "players-only"); return; }
        if (a.length < 2) {
            Ui.form(p, "economy", plugin.cfg.get("economy").getString("pay.dialog-title", "<gold>Pay a player"),
                    List.of(Dlg.Field.text("player", plugin.cfg.get("economy").getString("pay.label-player", "Player name"), a.length > 0 ? a[0] : ""),
                            Dlg.Field.text("amount", plugin.cfg.get("economy").getString("pay.label-amount", "Amount"), "")),
                    (pp, v) -> doPay(pp, v.get("player"), v.get("amount")));
            return;
        }
        doPay(p, a[0], a[1]);
    }

    private void doPay(Player p, String name, String amt) {
        var c = plugin.cfg.get("economy");
        Player t = Bukkit.getPlayerExact(name);
        if (t == null) { plugin.msg.send(p, "player-offline", "name", name); return; }
        if (t.equals(p) && !c.getBoolean("pay.allow-self", false)) { plugin.msg.send(p, "pay-self"); return; }
        double v = EconomyManager.parse(amt);
        if (v <= 0 || v < c.getDouble("pay.minimum", 1)) { plugin.msg.send(p, "invalid-amount"); return; }
        if (!plugin.settings.get(t, "accept-payments")) { plugin.msg.send(p, "pay-refused", "player", t.getName()); return; }
        if (!eco().has(p.getUniqueId(), v)) { plugin.msg.send(p, "not-enough", "amount_mm", eco().fmt(v)); return; }
        double tax = EconomyManager.round(v * c.getDouble("pay.tax-percent", 0) / 100.0);
        eco().withdraw(p.getUniqueId(), v);
        eco().deposit(t.getUniqueId(), v - tax);
        plugin.msg.send(p, "pay-sent", "amount_mm", eco().fmt(v), "player", t.getName());
        if (plugin.settings.get(t, "notify-payments")) plugin.msg.send(t, "pay-received", "amount_mm", eco().fmt(v - tax), "player", p.getName());
    }

    private void baltop(CommandSender s) {
        int n = plugin.cfg.get("economy").getInt("baltop.size", 10);
        plugin.msg.send(s, "baltop-header");
        int i = 1;
        for (Map.Entry<UUID, Double> e : eco().top(n)) {
            plugin.msg.send(s, "baltop-line", "rank", String.valueOf(i++), "player", eco().name(e.getKey()), "amount_mm", eco().fmt(e.getValue()));
        }
    }

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("berrycore.eco.admin")) { plugin.msg.send(s, "no-permission"); return; }
        if (a.length < 2) { plugin.msg.send(s, "usage", "usage", "/eco <give|take|set|reset> <player> [amount]"); return; }
        UUID t = eco().find(a[1]);
        if (t == null) { plugin.msg.send(s, "player-not-found", "name", a[1]); return; }
        double v = a.length > 2 ? EconomyManager.parse(a[2]) : 0;
        switch (a[0].toLowerCase()) {
            case "give" -> eco().deposit(t, v);
            case "take" -> eco().set(t, eco().get(t) - v);
            case "set" -> eco().set(t, v);
            case "reset" -> eco().set(t, plugin.cfg.get("economy").getDouble("starting-balance", 100));
            default -> { plugin.msg.send(s, "usage", "usage", "/eco <give|take|set|reset> <player> [amount]"); return; }
        }
        plugin.msg.send(s, "eco-done", "player", eco().name(t), "amount_mm", eco().fmt(eco().get(t)));
    }

    private void sell(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { plugin.msg.send(s, "players-only"); return; }
        String sub = a.length > 0 ? a[0].toLowerCase() : "gui";
        switch (sub) {
            case "hand" -> plugin.sell.sellHand(p);
            case "all", "inv", "inventory" -> plugin.sell.sellAll(p);
            case "worth" -> plugin.sell.worth(p);
            default -> plugin.sell.openGui(p);
        }
    }

    public void hub(Player p) {
        var c = plugin.cfg.get("economy");
        String bal = eco().fmt(eco().get(p.getUniqueId()));
        List<Ui.Opt> o = new ArrayList<>();
        o.add(Ui.Opt.of(Material.SWEET_BERRIES, c.getString("hub.balance", "<light_purple>Balance"), List.of(bal), x -> balance(x, new String[0])));
        o.add(Ui.Opt.of(Material.CHEST, c.getString("hub.sell", "<green>Sell Items"), List.of("<gray>Drop items in, press the button"), x -> plugin.sell.openGui(x)));
        o.add(Ui.Opt.of(Material.EMERALD, c.getString("hub.market", "<aqua>Market"), List.of("<gray>Browse every player shop"), x -> plugin.market.browse(x, null)));
        o.add(Ui.Opt.of(Material.GOLD_INGOT, c.getString("hub.pay", "<gold>Pay a player"), List.of(), x -> pay(x, new String[0])));
        o.add(Ui.Opt.of(Material.NETHER_STAR, c.getString("hub.stars", "<yellow>Star shop"), List.of(), x -> plugin.stars.openShop(x)));
        o.add(Ui.Opt.of(Material.COMPARATOR, c.getString("hub.settings", "<gray>Settings"), List.of(), x -> plugin.settings.open(x)));
        Ui.options(p, "economy", c.getString("hub.title", "<gradient:#ff4d8d:#b24dff>Berries</gradient>"), "Balance: " + bal, o);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String n = cmd.getName().toLowerCase();
        if (a.length == 1) {
            if (n.equals("sell")) out.addAll(List.of("gui", "hand", "all", "worth"));
            else if (n.equals("eco")) out.addAll(List.of("give", "take", "set", "reset"));
            else Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 2 && n.equals("eco")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
