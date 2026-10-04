package dev.berrycore;

import org.bukkit.entity.Player;

/** Applies PlaceholderAPI placeholders to config text when PlaceholderAPI is installed. */
final class Papi {
    static boolean on = false;

    static String apply(Player p, String s) {
        if (!on || s == null || s.indexOf('%') < 0) return s;
        return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
    }
}
