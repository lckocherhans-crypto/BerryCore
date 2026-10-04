package dev.berrycore;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * %berrycore_...% placeholders. Full list is in README.md.
 */
public final class BerryExpansion extends PlaceholderExpansion {
    private final BerryCore plugin;

    public BerryExpansion(BerryCore plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "berrycore"; }
    @Override public String getAuthor() { return "BerryCore"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer op, String p) {
        p = p.toLowerCase();
        EconomyManager eco = plugin.economy;
        // ---- leaderboards (no player needed)
        if (p.startsWith("baltop_")) {
            String[] x = p.split("_");
            if (x.length == 3) {
                try {
                    List<Map.Entry<UUID, Double>> top = eco.top(Integer.parseInt(x[1]));
                    int i = Integer.parseInt(x[1]) - 1;
                    if (i >= top.size()) return "-";
                    return x[2].equals("name") ? eco.name(top.get(i).getKey()) : x[2].equals("value") ? eco.num(top.get(i).getValue()) : "";
                } catch (NumberFormatException ignored) {
                    return "";
                }
            }
        }
        if (p.startsWith("starstop_")) {
            String[] x = p.split("_");
            if (x.length == 3) {
                try {
                    var top = plugin.stars.top(Integer.parseInt(x[1]));
                    int i = Integer.parseInt(x[1]) - 1;
                    if (i >= top.size()) return "-";
                    return x[2].equals("name") ? eco.name(top.get(i).getKey()) : String.valueOf(top.get(i).getValue());
                } catch (NumberFormatException ignored) {
                    return "";
                }
            }
        }
        switch (p) {
            case "teams_total": return String.valueOf(plugin.teams.count());
            case "market_listings": return String.valueOf(plugin.market.listingCount());
            case "currency_name": return plugin.cfg.get("economy").getString("currency.plural", "Berries");
            case "currency_symbol": return plugin.cfg.get("economy").getString("currency.symbol", "");
            default: break;
        }
        if (op == null) return "";
        UUID id = op.getUniqueId();
        Player online = op.getPlayer();
        if (p.startsWith("setting_")) return String.valueOf(plugin.settings.get(id, p.substring(8).replace('_', '-')));
        switch (p) {
            // ---- economy
            case "balance": return String.valueOf(eco.get(id));
            case "balance_formatted": return eco.num(eco.get(id));
            case "balance_fancy": return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(Msg.c(eco.fmt(eco.get(id))));
            // ---- stars
            case "stars": return String.valueOf(plugin.stars.get(id));
            case "stars_next": return String.valueOf(plugin.stars.secondsUntilNext(id));
            case "stars_next_minutes": return String.valueOf((plugin.stars.secondsUntilNext(id) + 59) / 60);
            // ---- homes
            case "homes": return String.valueOf(plugin.homes.of(id).size());
            case "homes_max": return online == null ? String.valueOf(plugin.cfg.get("homes").getInt("base-max", 3) + plugin.homes.extraSlots(id)) : String.valueOf(plugin.homes.max(online));
            case "homes_extra": return String.valueOf(plugin.homes.extraSlots(id));
            case "homes_next_cost": return plugin.economy.num(plugin.homes.nextCost(id));
            // ---- market
            case "shop_listings": return String.valueOf(plugin.market.listingCountOf(id));
            default: break;
        }
        // ---- teams
        TeamManager.Team t = plugin.teams.of(id);
        switch (p) {
            case "team": return t == null ? plugin.cfg.get("teams").getString("placeholder-no-team", "") : t.name;
            case "team_display": return t == null ? plugin.cfg.get("teams").getString("placeholder-no-team", "")
                    : net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(Msg.MM.deserialize(plugin.teams.displayMm(t)));
            case "team_minimessage": return t == null ? "" : plugin.teams.displayMm(t);
            case "team_color": return t == null ? "" : t.color;
            case "team_role": return t == null ? "" : plugin.teams.roleName(plugin.teams.role(t, id));
            case "team_members": return t == null ? "0" : String.valueOf(t.members.size());
            case "team_online": return t == null ? "0" : String.valueOf(t.members.keySet().stream().filter(u -> org.bukkit.Bukkit.getPlayer(u) != null).count());
            case "team_homes": return t == null ? "0" : String.valueOf(t.homes.size());
            case "team_chat": return String.valueOf(plugin.teams.chatEnabled(id));
            case "team_ff": return t == null ? "false" : String.valueOf(t.ff);
            default: return null;
        }
    }
}
