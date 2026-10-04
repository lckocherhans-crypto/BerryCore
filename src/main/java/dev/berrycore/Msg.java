package dev.berrycore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Message helper. Placeholders are written %key% and passed as key,value pairs.
 * Values are MiniMessage-escaped unless the key ends in "_mm" (trusted, already MiniMessage).
 */
public final class Msg {
    public static final MiniMessage MM = MiniMessage.miniMessage();
    private final BerryCore plugin;

    public Msg(BerryCore plugin) {
        this.plugin = plugin;
    }

    public static String esc(String s) {
        return MM.escapeTags(s == null ? "" : s);
    }

    public static String fill(String s, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = kv[i];
            String v = kv[i + 1] == null ? "" : kv[i + 1];
            if (!k.endsWith("_mm")) v = MM.escapeTags(v);
            s = s.replace("%" + k + "%", v);
        }
        return s;
    }

    public static Component c(Player pl, String s, String... kv) {
        s = fill(s, kv);
        if (pl != null) s = Papi.apply(pl, s);
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component c(String s, String... kv) {
        return MM.deserialize(fill(s, kv)).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public Component get(CommandSender to, String key, String... kv) {
        String raw = plugin.cfg.get("messages").getString("messages." + key);
        if (raw == null) raw = "<red>Missing message: " + key;
        return c(to instanceof Player p ? p : null, raw, kv);
    }

    public void send(CommandSender to, String key, String... kv) {
        var m = plugin.cfg.get("messages");
        Component prefix = m.getBoolean("use-prefix", true) ? c(m.getString("prefix", "")) : Component.empty();
        to.sendMessage(prefix.append(get(to, key, kv)));
    }

    public void bar(Player p, String key, String... kv) {
        p.sendActionBar(get(p, key, kv));
    }
}
