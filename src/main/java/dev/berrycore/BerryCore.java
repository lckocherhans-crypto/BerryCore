package dev.berrycore;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class BerryCore extends JavaPlugin implements Listener {
    private static BerryCore inst;
    public Cfg cfg;
    public Msg msg;
    public ChatPrompt prompts;
    public EconomyManager economy;
    public SettingsManager settings;
    public SellService sell;
    public TeamManager teams;
    public HomeManager homes;
    public StarManager stars;
    public MarketManager market;
    private EconomyCommands ecoCmds;

    public static BerryCore get() {
        return inst;
    }

    public void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(this, r);
    }

    @Override
    public void onEnable() {
        inst = this;
        getDataFolder().mkdirs();
        cfg = new Cfg(this);
        msg = new Msg(this);
        Papi.on = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
        prompts = new ChatPrompt(this);
        economy = new EconomyManager(this);
        settings = new SettingsManager(this);
        sell = new SellService(this);
        teams = new TeamManager(this);
        homes = new HomeManager(this);
        stars = new StarManager(this);
        market = new MarketManager(this);
        ecoCmds = new EconomyCommands(this);

        var pm = Bukkit.getPluginManager();
        for (Listener l : List.<Listener>of(this, new MenuListener(this), prompts, teams, stars, market)) pm.registerEvents(l, this);

        bind("team", teams); bind("teamchat", teams);
        bind("home", homes); bind("homes", homes); bind("sethome", homes); bind("delhome", homes);
        bind("renamehome", homes); bind("homeicon", homes); bind("buyhome", homes);
        bind("stars", stars); bind("starshop", stars);
        bind("market", market); bind("shops", market); bind("shop", market);
        for (String c : List.of("balance", "pay", "baltop", "eco", "sell", "worth", "berries")) bind(c, ecoCmds);
        getCommand("settings").setExecutor((s, c, l, a) -> {
            if (s instanceof Player p) settings.open(p); else msg.send(s, "players-only");
            return true;
        });
        getCommand("berrycore").setExecutor((s, c, l, a) -> {
            if (!s.hasPermission("berrycore.admin")) { msg.send(s, "no-permission"); return true; }
            if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                cfg.reload();
                msg.send(s, "reloaded");
            } else if (a.length > 0 && a[0].equalsIgnoreCase("save")) {
                saveAll();
                msg.send(s, "saved");
            } else {
                msg.send(s, "usage", "usage", "/berrycore <reload|save>");
            }
            return true;
        });
        getCommand("berrycore").setTabCompleter((s, c, l, a) -> a.length == 1 ? List.of("reload", "save") : List.<String>of());

        if (Papi.on) {
            new BerryExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered (%berrycore_...%).");
        }
        for (Player online : Bukkit.getOnlinePlayers()) economy.remember(online.getUniqueId(), online.getName());
        stars.start();
        long every = Math.max(1, cfg.get("config").getLong("autosave-minutes", 3)) * 60 * 20;
        Bukkit.getScheduler().runTaskTimer(this, this::saveAll, every, every);
        getLogger().info("BerryCore enabled.");
    }

    private void bind(String name, Object exec) {
        PluginCommand c = getCommand(name);
        if (c == null) return;
        c.setExecutor((org.bukkit.command.CommandExecutor) exec);
        if (exec instanceof org.bukkit.command.TabCompleter t) c.setTabCompleter(t);
    }

    public void saveAll() {
        economy.save(); settings.save(); teams.save(); homes.save(); stars.save(); market.save();
    }

    @Override
    public void onDisable() {
        // closing menus first so the sell GUI pays out / returns items before data is written
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
        }
        if (stars != null) stars.stop();
        if (economy != null) saveAll();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        economy.remember(e.getPlayer().getUniqueId(), e.getPlayer().getName());
    }
}
