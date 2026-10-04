package dev.berrycore;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class TeamManager implements CommandExecutor, TabCompleter, Listener {
    public enum Role { MEMBER, OFFICER, LEADER }

    public static final class Team {
        public String id, name, color;
        public UUID leader;
        public final Map<UUID, Role> members = new LinkedHashMap<>();
        public final Map<String, Location> homes = new LinkedHashMap<>();
        public boolean ff;
        public long created = System.currentTimeMillis();
    }

    private record Invite(String teamId, UUID from, long expires) {
    }

    private final BerryCore plugin;
    private final Store store;
    private final Map<String, Team> teams = new ConcurrentHashMap<>();
    private final Map<UUID, String> byPlayer = new ConcurrentHashMap<>();
    private final Set<UUID> chatOn = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();

    public TeamManager(BerryCore plugin) {
        this.plugin = plugin;
        this.store = new Store(plugin, "teams");
        ConfigurationSection ts = store.y.getConfigurationSection("teams");
        if (ts != null) for (String id : ts.getKeys(false)) {
            ConfigurationSection s = ts.getConfigurationSection(id);
            Team t = new Team();
            t.id = id;
            t.name = s.getString("name", id);
            t.color = s.getString("color", "#ffffff");
            t.leader = UUID.fromString(s.getString("leader"));
            t.ff = s.getBoolean("ff", false);
            t.created = s.getLong("created", 0);
            ConfigurationSection ms = s.getConfigurationSection("members");
            if (ms != null) for (String u : ms.getKeys(false)) {
                try {
                    t.members.put(UUID.fromString(u), Role.valueOf(ms.getString(u, "MEMBER")));
                } catch (IllegalArgumentException ignored) {
                }
            }
            ConfigurationSection hs = s.getConfigurationSection("homes");
            if (hs != null) for (String h : hs.getKeys(false)) {
                Location l = hs.getLocation(h);
                if (l != null) t.homes.put(h, l);
            }
            teams.put(id, t);
            t.members.keySet().forEach(u -> byPlayer.put(u, id));
        }
        for (String u : store.y.getStringList("chat-on")) chatOn.add(UUID.fromString(u));
    }

    public void save() {
        store.y.set("teams", null);
        for (Team t : teams.values()) {
            String b = "teams." + t.id + ".";
            store.y.set(b + "name", t.name);
            store.y.set(b + "color", t.color);
            store.y.set(b + "leader", t.leader.toString());
            store.y.set(b + "ff", t.ff);
            store.y.set(b + "created", t.created);
            t.members.forEach((u, r) -> store.y.set(b + "members." + u, r.name()));
            t.homes.forEach((h, l) -> store.y.set(b + "homes." + h, l));
        }
        store.y.set("chat-on", chatOn.stream().map(UUID::toString).toList());
        store.save();
    }

    private ConfigurationSection cfg() {
        return plugin.cfg.get("teams");
    }

    // ------------------------------------------------------------------ lookups / formatting

    public Team of(UUID id) {
        String t = byPlayer.get(id);
        return t == null ? null : teams.get(t);
    }

    public Team byName(String n) {
        return n == null ? null : teams.get(n.toLowerCase(Locale.ROOT));
    }

    public Role role(Team t, UUID id) {
        return t.members.getOrDefault(id, Role.MEMBER);
    }

    public boolean chatEnabled(UUID id) {
        return chatOn.contains(id);
    }

    public int count() {
        return teams.size();
    }

    public String displayMm(Team t) {
        String n = Msg.esc(t.name);
        String[] cols = t.color.split(",");
        if (cols.length == 1) return "<" + cols[0] + ">" + n + "</" + cols[0] + ">";
        return "<gradient:" + String.join(":", cols) + ">" + n + "</gradient>";
    }

    public String roleName(Role r) {
        return cfg().getString("roles." + r.name().toLowerCase(), r.name());
    }

    /** Accepts "#ff0000", "red", or several separated by comma/space/colon for a gradient. Returns normalized or null. */
    public String normalizeColor(Player p, String spec) {
        if (spec == null || spec.isBlank()) return cfg().getString("color.default", "#ffffff");
        String[] parts = spec.trim().split("[,\\s:]+");
        int max = cfg().getInt("color.max-gradient-colors", 4);
        if (parts.length > 1 && (!cfg().getBoolean("color.allow-gradient", true) || (p != null && !p.hasPermission("berrycore.teams.gradient")))) return null;
        if (parts.length > max) return null;
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            String t = s.toLowerCase(Locale.ROOT);
            if (!t.startsWith("#") && t.matches("[0-9a-f]{6}")) t = "#" + t;
            if (t.matches("#[0-9a-f]{6}") && cfg().getBoolean("color.allow-hex", true)) out.add(t);
            else if (cfg().getBoolean("color.allow-named", true) && NamedTextColor.NAMES.value(t) != null) out.add(t);
            else return null;
        }
        return String.join(",", out);
    }

    private String[] kv(Team t, String... more) {
        String[] base = {"team_mm", displayMm(t), "team", t.name};
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    private void broadcast(Team t, String key, String... more) {
        for (UUID u : t.members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) plugin.msg.send(p, key, kv(t, more));
        }
    }

    // ------------------------------------------------------------------ actions

    public void create(Player p, String name, String colorSpec) {
        if (of(p.getUniqueId()) != null) { plugin.msg.send(p, "team-in-team"); return; }
        ConfigurationSection n = cfg().getConfigurationSection("name");
        if (name == null || name.length() < n.getInt("min-length", 3) || name.length() > n.getInt("max-length", 16)
                || !Pattern.matches(n.getString("regex", "[A-Za-z0-9_]+"), name)
                || n.getStringList("blacklist").stream().anyMatch(b -> name.toLowerCase().contains(b.toLowerCase()))) {
            plugin.msg.send(p, "team-bad-name", "min", String.valueOf(n.getInt("min-length", 3)), "max", String.valueOf(n.getInt("max-length", 16)));
            return;
        }
        if (teams.containsKey(name.toLowerCase(Locale.ROOT))) { plugin.msg.send(p, "team-exists"); return; }
        String color = normalizeColor(p, colorSpec);
        if (color == null) { plugin.msg.send(p, "team-bad-color"); return; }
        double cost = cfg().getDouble("create-cost", 0);
        if (cost > 0 && !plugin.economy.withdraw(p.getUniqueId(), cost)) {
            plugin.msg.send(p, "not-enough", "amount_mm", plugin.economy.fmt(cost));
            return;
        }
        Team t = new Team();
        t.id = name.toLowerCase(Locale.ROOT);
        t.name = name;
        t.color = color;
        t.leader = p.getUniqueId();
        t.members.put(p.getUniqueId(), Role.LEADER);
        t.ff = cfg().getBoolean("friendly-fire-default", false);
        teams.put(t.id, t);
        byPlayer.put(p.getUniqueId(), t.id);
        plugin.msg.send(p, "team-created", kv(t));
    }

    private boolean need(Player p, Team t, Role min) {
        if (t == null) { plugin.msg.send(p, "team-not-in-team"); return false; }
        if (role(t, p.getUniqueId()).compareTo(min) < 0) {
            plugin.msg.send(p, min == Role.LEADER ? "team-need-leader" : "team-need-officer");
            return false;
        }
        return true;
    }

    public void disband(Player p) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.LEADER)) return;
        broadcast(t, "team-disbanded");
        t.members.keySet().forEach(u -> { byPlayer.remove(u); chatOn.remove(u); });
        teams.remove(t.id);
    }

    public void invite(Player p, String target) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.OFFICER)) return;
        Player tp = Bukkit.getPlayerExact(target);
        if (tp == null) { plugin.msg.send(p, "player-offline", "name", target); return; }
        if (of(tp.getUniqueId()) != null) { plugin.msg.send(p, "team-target-in-team", "player", tp.getName()); return; }
        if (t.members.size() >= cfg().getInt("max-members", 10)) { plugin.msg.send(p, "team-full"); return; }
        long ttl = cfg().getLong("invite-expire-seconds", 60) * 1000L;
        invites.put(tp.getUniqueId(), new Invite(t.id, p.getUniqueId(), System.currentTimeMillis() + ttl));
        plugin.msg.send(p, "team-invited", kv(t, "player", tp.getName()));
        plugin.msg.send(tp, "team-invite-received", kv(t, "player", p.getName()));
    }

    public void accept(Player p) {
        Invite in = invites.remove(p.getUniqueId());
        if (in == null || in.expires() < System.currentTimeMillis() || teams.get(in.teamId()) == null) { plugin.msg.send(p, "team-no-invite"); return; }
        if (of(p.getUniqueId()) != null) { plugin.msg.send(p, "team-in-team"); return; }
        Team t = teams.get(in.teamId());
        if (t.members.size() >= cfg().getInt("max-members", 10)) { plugin.msg.send(p, "team-full"); return; }
        t.members.put(p.getUniqueId(), Role.MEMBER);
        byPlayer.put(p.getUniqueId(), t.id);
        broadcast(t, "team-member-joined", "player", p.getName());
    }

    public void leave(Player p) {
        Team t = of(p.getUniqueId());
        if (t == null) { plugin.msg.send(p, "team-not-in-team"); return; }
        if (t.leader.equals(p.getUniqueId())) { plugin.msg.send(p, "team-leader-cant-leave"); return; }
        t.members.remove(p.getUniqueId());
        byPlayer.remove(p.getUniqueId());
        chatOn.remove(p.getUniqueId());
        plugin.msg.send(p, "team-left", kv(t));
        broadcast(t, "team-member-left", "player", p.getName());
    }

    public void kick(Player p, String name) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.OFFICER)) return;
        UUID target = plugin.economy.find(name);
        if (target == null || !t.members.containsKey(target)) { plugin.msg.send(p, "team-target-not-member", "player", name); return; }
        if (role(t, target).compareTo(role(t, p.getUniqueId())) >= 0) { plugin.msg.send(p, "team-cant-target"); return; }
        t.members.remove(target);
        byPlayer.remove(target);
        chatOn.remove(target);
        broadcast(t, "team-member-kicked", "player", name);
        Player tp = Bukkit.getPlayer(target);
        if (tp != null) plugin.msg.send(tp, "team-kick-notify", kv(t));
    }

    public void setRole(Player p, String name, Role to) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.LEADER)) return;
        UUID target = plugin.economy.find(name);
        if (target == null || !t.members.containsKey(target) || target.equals(p.getUniqueId())) { plugin.msg.send(p, "team-target-not-member", "player", name); return; }
        if (to == Role.LEADER) {
            t.members.put(p.getUniqueId(), Role.OFFICER);
            t.leader = target;
        }
        t.members.put(target, to);
        broadcast(t, "team-role-changed", "player", name, "role", roleName(to));
    }

    public void rename(Player p, String name) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.LEADER)) return;
        if (!Pattern.matches(cfg().getString("name.regex", "[A-Za-z0-9_]+"), name) || name.length() < cfg().getInt("name.min-length", 3)
                || name.length() > cfg().getInt("name.max-length", 16)) { plugin.msg.send(p, "team-bad-name", "min", cfg().getString("name.min-length", "3"), "max", cfg().getString("name.max-length", "16")); return; }
        if (!name.equalsIgnoreCase(t.name) && teams.containsKey(name.toLowerCase(Locale.ROOT))) { plugin.msg.send(p, "team-exists"); return; }
        teams.remove(t.id);
        t.name = name;
        t.id = name.toLowerCase(Locale.ROOT);
        teams.put(t.id, t);
        t.members.keySet().forEach(u -> byPlayer.put(u, t.id));
        broadcast(t, "team-renamed");
    }

    public void recolor(Player p, String spec) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.LEADER)) return;
        String c = normalizeColor(p, spec);
        if (c == null) { plugin.msg.send(p, "team-bad-color"); return; }
        t.color = c;
        broadcast(t, "team-color-set");
    }

    public void toggleFf(Player p) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.OFFICER)) return;
        t.ff = !t.ff;
        broadcast(t, "team-ff", "state", t.ff ? "ON" : "OFF");
    }

    public void toggleChat(Player p) {
        if (of(p.getUniqueId()) == null) { plugin.msg.send(p, "team-not-in-team"); return; }
        if (chatOn.remove(p.getUniqueId())) plugin.msg.send(p, "team-chat-off");
        else { chatOn.add(p.getUniqueId()); plugin.msg.send(p, "team-chat-on"); }
    }

    public void chat(Player p, String text) {
        Team t = of(p.getUniqueId());
        if (t == null) { plugin.msg.send(p, "team-not-in-team"); return; }
        Component line = Msg.c(p, cfg().getString("chat.team-format", "<dark_gray>[<team_mm>]</dark_gray> <gray>%player%<dark_gray>: <white>%message%"),
                "team_mm", displayMm(t), "player", p.getName(), "message", text);
        for (UUID u : t.members.keySet()) {
            Player m = Bukkit.getPlayer(u);
            if (m != null) m.sendMessage(line);
        }
        if (cfg().getBoolean("chat.spy-enabled", true)) {
            for (Player sp : Bukkit.getOnlinePlayers()) {
                if (sp.hasPermission("berrycore.teams.spy") && !t.members.containsKey(sp.getUniqueId()))
                    sp.sendMessage(Msg.c(cfg().getString("chat.spy-prefix", "<dark_gray>[Spy] ")).append(line));
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    public void setHome(Player p, String name) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.valueOf(cfg().getString("homes.set-role", "OFFICER").toUpperCase()))) return;
        name = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,16}")) { plugin.msg.send(p, "home-bad-name"); return; }
        if (!t.homes.containsKey(name) && t.homes.size() >= cfg().getInt("homes.max", 3)) { plugin.msg.send(p, "team-home-limit", "max", cfg().getString("homes.max", "3")); return; }
        t.homes.put(name, p.getLocation());
        broadcast(t, "team-home-set", "name", name);
    }

    public void delHome(Player p, String name) {
        Team t = of(p.getUniqueId());
        if (!need(p, t, Role.valueOf(cfg().getString("homes.set-role", "OFFICER").toUpperCase()))) return;
        if (t.homes.remove(name.toLowerCase(Locale.ROOT)) == null) { plugin.msg.send(p, "team-home-none", "name", name); return; }
        broadcast(t, "team-home-deleted", "name", name);
    }

    public void home(Player p, String name) {
        Team t = of(p.getUniqueId());
        if (t == null) { plugin.msg.send(p, "team-not-in-team"); return; }
        if (name == null) {
            if (t.homes.size() == 1) name = t.homes.keySet().iterator().next();
            else { homesMenu(p); return; }
        }
        Location l = t.homes.get(name.toLowerCase(Locale.ROOT));
        if (l == null) { plugin.msg.send(p, "team-home-none", "name", name); return; }
        Tp.go(p, l, cfg().getConfigurationSection("teleport"), "teamhome", Msg.esc(name));
    }

    // ------------------------------------------------------------------ UI (dialog on Java, chest on Bedrock)

    public void openMain(Player p) {
        Team t = of(p.getUniqueId());
        List<Ui.Opt> o = new ArrayList<>();
        var m = plugin.cfg.get("messages");
        if (t == null) {
            o.add(Ui.Opt.of(Material.WHITE_BANNER, m.getString("team-ui.create", "<green>Create a team"), List.of("<gray>Pick a name and colour or gradient"), x -> createForm(x)));
            Invite in = invites.get(p.getUniqueId());
            if (in != null && in.expires() > System.currentTimeMillis() && teams.get(in.teamId()) != null)
                o.add(Ui.Opt.of(Material.WRITABLE_BOOK, m.getString("team-ui.accept", "<yellow>Accept invite"), List.of(displayMm(teams.get(in.teamId()))), x -> accept(x)));
            Ui.options(p, "teams", cfg().getString("ui.title", "<gradient:#4dc3ff:#7b4dff>Teams</gradient>"), m.getString("team-ui.no-team-body", "You are not in a team."), o);
            return;
        }
        Role r = role(t, p.getUniqueId());
        o.add(Ui.Opt.of(Material.BOOK, m.getString("team-ui.info", "<aqua>Team info"), List.of(), x -> info(x, t)));
        o.add(Ui.Opt.of(Material.RED_BED, m.getString("team-ui.homes", "<green>Team homes"), List.of("<gray>" + t.homes.size() + "/" + cfg().getInt("homes.max", 3)), x -> homesMenu(x)));
        o.add(Ui.Opt.of(Material.PLAYER_HEAD, m.getString("team-ui.members", "<gold>Members"), List.of("<gray>" + t.members.size() + "/" + cfg().getInt("max-members", 10)), x -> membersMenu(x)));
        o.add(Ui.Opt.of(Material.OAK_SIGN, m.getString("team-ui.chat", "<yellow>Toggle team chat"), List.of(chatOn.contains(p.getUniqueId()) ? "<green>Currently ON" : "<red>Currently OFF"), x -> { toggleChat(x); openMain(x); }));
        if (r.compareTo(Role.OFFICER) >= 0) {
            o.add(Ui.Opt.of(Material.PAPER, m.getString("team-ui.invite", "<green>Invite a player"), List.of(), x ->
                    Ui.input(x, "teams", m.getString("team-ui.invite-title", "Invite"), m.getString("team-ui.invite-label", "Player name"), "", s -> invite(x, s))));
            o.add(Ui.Opt.of(Material.IRON_SWORD, m.getString("team-ui.ff", "<red>Toggle friendly fire"), List.of(t.ff ? "<red>PvP between members: ON" : "<green>PvP between members: OFF"), x -> { toggleFf(x); openMain(x); }));
        }
        if (r == Role.LEADER) {
            o.add(Ui.Opt.of(Material.CYAN_DYE, m.getString("team-ui.color", "<light_purple>Change colour / gradient"), List.of("<gray>Hex like #ff0000 or several for a gradient"), x ->
                    Ui.input(x, "teams", m.getString("team-ui.color-title", "Team colour"), m.getString("team-ui.color-label", "Colour(s), e.g. #ff0000 #0000ff"), t.color.replace(",", " "), s -> recolor(x, s))));
            o.add(Ui.Opt.of(Material.NAME_TAG, m.getString("team-ui.rename", "<aqua>Rename team"), List.of(), x ->
                    Ui.input(x, "teams", m.getString("team-ui.rename-title", "Rename"), m.getString("team-ui.rename-label", "New name"), t.name, s -> rename(x, s))));
            o.add(Ui.Opt.of(Material.TNT, m.getString("team-ui.disband", "<dark_red>Disband team"), List.of("<gray>This cannot be undone"), x ->
                    Ui.confirm(x, "teams", m.getString("team-ui.disband-title", "Disband?"), m.getString("team-ui.disband-body", "Delete the team forever?"), () -> disband(x))));
        } else {
            o.add(Ui.Opt.of(Material.OAK_DOOR, m.getString("team-ui.leave", "<red>Leave team"), List.of(), x ->
                    Ui.confirm(x, "teams", m.getString("team-ui.leave-title", "Leave?"), m.getString("team-ui.leave-body", "Leave your team?"), () -> leave(x))));
        }
        Ui.options(p, "teams", cfg().getString("ui.title", "<gradient:#4dc3ff:#7b4dff>Teams</gradient>") + " <gray>- " + displayMm(t), "", o);
    }

    private void createForm(Player p) {
        var m = plugin.cfg.get("messages");
        Ui.form(p, "teams", m.getString("team-ui.create-title", "Create team"),
                List.of(Dlg.Field.text("name", m.getString("team-ui.name-label", "Team name"), ""),
                        Dlg.Field.text("color", m.getString("team-ui.color-label", "Colour(s), e.g. #ff0000 #0000ff"), cfg().getString("color.default", "#ffffff"))),
                (pp, v) -> create(pp, v.get("name").trim(), v.get("color")));
    }

    private void info(Player p, Team t) {
        for (String line : plugin.cfg.get("messages").getStringList("team-info")) {
            p.sendMessage(Msg.c(p, line, kv(t, "leader", plugin.economy.name(t.leader), "members", String.valueOf(t.members.size()),
                    "max", cfg().getString("max-members", "10"), "homes", String.valueOf(t.homes.size()), "ff", t.ff ? "ON" : "OFF",
                    "online", String.valueOf(t.members.keySet().stream().filter(u -> Bukkit.getPlayer(u) != null).count()))));
        }
    }

    public void homesMenu(Player p) {
        Team t = of(p.getUniqueId());
        if (t == null) { plugin.msg.send(p, "team-not-in-team"); return; }
        List<Ui.Opt> o = new ArrayList<>();
        for (String h : t.homes.keySet()) {
            Location l = t.homes.get(h);
            o.add(Ui.Opt.of(Material.RED_BED, "<green>" + Msg.esc(h), List.of("<gray>" + (l.getWorld() == null ? "?" : l.getWorld().getName()) + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(), "<yellow>Click to teleport"), x -> home(x, h)));
        }
        if (role(t, p.getUniqueId()).compareTo(Role.valueOf(cfg().getString("homes.set-role", "OFFICER").toUpperCase())) >= 0) {
            var m = plugin.cfg.get("messages");
            o.add(Ui.Opt.of(Material.LIME_DYE, m.getString("team-ui.sethome", "<green>Set a home here"), List.of(), x ->
                    Ui.input(x, "teams", m.getString("team-ui.sethome-title", "Team home"), m.getString("team-ui.sethome-label", "Home name"), "base", s -> { setHome(x, s); homesMenu(x); })));
        }
        Ui.options(p, "teams", cfg().getString("ui.homes-title", "<green>Team homes"), "", o);
    }

    public void membersMenu(Player p) {
        Team t = of(p.getUniqueId());
        if (t == null) return;
        Role mine = role(t, p.getUniqueId());
        List<Ui.Opt> o = new ArrayList<>();
        for (Map.Entry<UUID, Role> e : t.members.entrySet()) {
            String name = plugin.economy.name(e.getKey());
            boolean online = Bukkit.getPlayer(e.getKey()) != null;
            List<String> lore = List.of("<gray>" + roleName(e.getValue()), online ? "<green>Online" : "<red>Offline");
            Ui.Opt opt = Ui.Opt.head(e.getKey(), "<white>" + Msg.esc(name), lore, x -> {
                if (mine.compareTo(Role.OFFICER) >= 0 && e.getValue().compareTo(mine) < 0) memberActions(x, t, e.getKey(), name, e.getValue(), mine);
            });
            o.add(opt);
        }
        Ui.options(p, "teams", cfg().getString("ui.members-title", "<gold>Members"), "", o);
    }

    private void memberActions(Player p, Team t, UUID target, String name, Role tr, Role mine) {
        List<Ui.Opt> o = new ArrayList<>();
        if (mine == Role.LEADER) {
            if (tr == Role.MEMBER) o.add(Ui.Opt.of(Material.EMERALD, "<green>Promote to officer", List.of(), x -> setRole(x, name, Role.OFFICER)));
            if (tr == Role.OFFICER) o.add(Ui.Opt.of(Material.COAL, "<yellow>Demote to member", List.of(), x -> setRole(x, name, Role.MEMBER)));
            o.add(Ui.Opt.of(Material.GOLDEN_HELMET, "<gold>Transfer leadership", List.of(), x -> Ui.confirm(x, "teams", "Transfer?", "Make " + name + " the leader?", () -> setRole(x, name, Role.LEADER))));
        }
        o.add(Ui.Opt.of(Material.BARRIER, "<red>Kick", List.of(), x -> Ui.confirm(x, "teams", "Kick?", "Kick " + name + "?", () -> kick(x, name))));
        Ui.options(p, "teams", "<white>" + Msg.esc(name), "", o);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { plugin.msg.send(s, "players-only"); return true; }
        if (cmd.getName().equalsIgnoreCase("teamchat")) {
            if (a.length == 0) toggleChat(p);
            else chat(p, String.join(" ", a));
            return true;
        }
        if (a.length == 0) { openMain(p); return true; }
        String rest = a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : null;
        switch (a[0].toLowerCase()) {
            case "menu" -> openMain(p);
            case "create" -> { if (a.length < 2) createForm(p); else create(p, a[1], a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : null); }
            case "disband" -> disband(p);
            case "invite" -> { if (rest != null) invite(p, a[1]); else usage(p, "/team invite <player>"); }
            case "accept" -> accept(p);
            case "deny" -> { invites.remove(p.getUniqueId()); plugin.msg.send(p, "team-invite-denied"); }
            case "leave" -> leave(p);
            case "kick" -> { if (rest != null) kick(p, a[1]); else usage(p, "/team kick <player>"); }
            case "promote" -> { if (rest != null) setRole(p, a[1], Role.OFFICER); else usage(p, "/team promote <player>"); }
            case "demote" -> { if (rest != null) setRole(p, a[1], Role.MEMBER); else usage(p, "/team demote <player>"); }
            case "transfer" -> { if (rest != null) setRole(p, a[1], Role.LEADER); else usage(p, "/team transfer <player>"); }
            case "rename" -> { if (rest != null) rename(p, a[1]); else usage(p, "/team rename <name>"); }
            case "color", "colour" -> { if (rest != null) recolor(p, rest); else usage(p, "/team color <#hex [#hex...]>"); }
            case "ff", "friendlyfire" -> toggleFf(p);
            case "chat" -> { if (a.length == 1 || a[1].equalsIgnoreCase("toggle")) toggleChat(p); else chat(p, rest); }
            case "sethome" -> setHome(p, rest == null ? "base" : a[1]);
            case "delhome" -> { if (rest != null) delHome(p, a[1]); else usage(p, "/team delhome <name>"); }
            case "home" -> home(p, rest == null ? null : a[1]);
            case "homes" -> homesMenu(p);
            case "members" -> membersMenu(p);
            case "info" -> {
                Team t = rest == null ? of(p.getUniqueId()) : byName(a[1]);
                if (t == null) plugin.msg.send(p, "team-not-found"); else info(p, t);
            }
            case "list" -> {
                plugin.msg.send(p, "team-list-header");
                for (Team t : teams.values()) p.sendMessage(Msg.c(p, "<gray> - %team_mm% <dark_gray>(%n% members)", "team_mm", displayMm(t), "n", String.valueOf(t.members.size())));
            }
            case "admin" -> {
                if (!p.hasPermission("berrycore.teams.admin") || a.length < 3 || !a[1].equalsIgnoreCase("disband")) { usage(p, "/team admin disband <team>"); return true; }
                Team t = byName(a[2]);
                if (t == null) { plugin.msg.send(p, "team-not-found"); return true; }
                broadcast(t, "team-disbanded");
                t.members.keySet().forEach(u -> { byPlayer.remove(u); chatOn.remove(u); });
                teams.remove(t.id);
            }
            default -> openMain(p);
        }
        return true;
    }

    private void usage(Player p, String u) {
        plugin.msg.send(p, "usage", "usage", u);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("teamchat")) return out;
        if (a.length == 1) out.addAll(List.of("menu", "create", "disband", "invite", "accept", "deny", "leave", "kick", "promote", "demote", "transfer", "rename", "color", "ff", "chat", "sethome", "delhome", "home", "homes", "members", "info", "list"));
        else if (a.length == 2 && List.of("invite", "kick", "promote", "demote", "transfer").contains(a[0].toLowerCase())) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (a.length == 2 && List.of("home", "delhome").contains(a[0].toLowerCase()) && s instanceof Player p && of(p.getUniqueId()) != null) out.addAll(of(p.getUniqueId()).homes.keySet());
        else if (a.length == 2 && a[0].equalsIgnoreCase("info")) out.addAll(teams.keySet());
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Team t = of(p.getUniqueId());
        if (t == null) return;
        if (chatOn.contains(p.getUniqueId())) {
            e.setCancelled(true);
            String text = PlainTextComponentSerializer.plainText().serialize(e.message());
            plugin.sync(() -> chat(p, text));
            return;
        }
        if (cfg().getBoolean("chat.public-tag.enabled", true)) {
            Component tag = Msg.c(cfg().getString("chat.public-tag.format", "<dark_gray>[</dark_gray>%team_mm%<dark_gray>]</dark_gray> "), "team_mm", displayMm(t));
            ChatRenderer prev = e.renderer();
            e.renderer((source, name, message, viewer) -> tag.append(prev.render(source, name, message, viewer)));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player damager = null;
        if (e.getDamager() instanceof Player d) damager = d;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player d) damager = d;
        if (damager == null || damager.equals(victim)) return;
        Team a = of(damager.getUniqueId());
        if (a != null && a == of(victim.getUniqueId()) && !a.ff) {
            e.setCancelled(true);
            if (cfg().getBoolean("friendly-fire-message", true)) plugin.msg.bar(damager, "team-no-ff");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Invite in = invites.get(e.getPlayer().getUniqueId());
        if (in != null && in.expires() < System.currentTimeMillis()) invites.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        invites.remove(e.getPlayer().getUniqueId());
    }
}
