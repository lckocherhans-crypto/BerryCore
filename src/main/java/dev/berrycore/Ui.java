package dev.berrycore;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One abstraction, two renderings:
 *  - Java players get Dialogs when the feature config says ui.java: dialog
 *  - everyone else (Bedrock always) gets chest menus + chat prompts
 */
public final class Ui {
    private Ui() {
    }

    public record Opt(Material icon, String name, List<String> lore, UUID head, Consumer<Player> run) {
        public static Opt of(Material icon, String name, List<String> lore, Consumer<Player> run) {
            return new Opt(icon, name, lore == null ? List.of() : lore, null, run);
        }

        public static Opt head(UUID who, String name, List<String> lore, Consumer<Player> run) {
            return new Opt(Material.PLAYER_HEAD, name, lore == null ? List.of() : lore, who, run);
        }
    }

    public static boolean dialog(Player p, String feature) {
        if (Platform.isBedrock(p)) return false;
        return "dialog".equalsIgnoreCase(BerryCore.get().cfg.get(feature).getString("ui.java", "dialog"));
    }

    /** A list of choices. */
    public static void options(Player p, String feature, String title, String body, List<Opt> opts) {
        BerryCore pl = BerryCore.get();
        if (dialog(p, feature)) {
            List<io.papermc.paper.registry.data.dialog.ActionButton> buttons = new ArrayList<>();
            for (Opt o : opts) buttons.add(Dlg.button(o.name(), o.run()));
            int cols = pl.cfg.get(feature).getInt("ui.dialog-columns", 2);
            Dlg.menu(p, title, body, buttons, cols, pl.cfg.get("messages").getString("ui.close", "<gray>Close"));
            return;
        }
        int rows = Math.max(1, Math.min(6, (int) Math.ceil(opts.size() / 9.0)));
        Menu m = new Menu(Msg.c(p, title), rows);
        int i = 0;
        for (Opt o : opts) {
            if (i >= rows * 9) break;
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String s : o.lore()) lore.add(Msg.c(p, s));
            ItemStack it = o.head() != null ? Items.head(o.head(), Msg.c(p, o.name()), lore) : Items.of(o.icon(), Msg.c(p, o.name()), lore);
            m.set(i++, it, e -> {
                if (o.run() != null) o.run().accept(p);
            });
        }
        m.open(p);
    }

    public static void confirm(Player p, String feature, String title, String body, Runnable onYes) {
        BerryCore pl = BerryCore.get();
        var msgs = pl.cfg.get("messages");
        String yes = msgs.getString("ui.confirm", "<green>Confirm");
        String no = msgs.getString("ui.cancel", "<red>Cancel");
        if (dialog(p, feature)) {
            Dlg.confirm(p, title, body, yes, no, onYes);
            return;
        }
        Menu m = new Menu(Msg.c(p, title), 3);
        m.set(11, Items.of(p, Material.LIME_CONCRETE, yes, List.of(body)), e -> {
            p.closeInventory();
            onYes.run();
        });
        m.set(13, Items.of(p, Material.PAPER, title, List.of(body)), null);
        m.set(15, Items.of(p, Material.RED_CONCRETE, no, List.of()), e -> p.closeInventory());
        m.open(p);
    }

    /** Single text input. Dialog for Java, chat prompt otherwise. */
    public static void input(Player p, String feature, String title, String label, String initial, Consumer<String> cb) {
        BerryCore pl = BerryCore.get();
        if (dialog(p, feature)) {
            var msgs = pl.cfg.get("messages");
            Dlg.form(p, title, "", List.of(Dlg.Field.text("v", label, initial)), msgs.getString("ui.submit", "<green>Submit"),
                    msgs.getString("ui.cancel", "<red>Cancel"), (pp, vals) -> cb.accept(vals.get("v").trim()));
        } else {
            pl.prompts.ask(p, label, cb);
        }
    }

    /** Several text inputs (chat mode asks them one after another). */
    public static void form(Player p, String feature, String title, List<Dlg.Field> fields, BiConsumer<Player, Map<String, String>> done) {
        BerryCore pl = BerryCore.get();
        if (dialog(p, feature)) {
            var msgs = pl.cfg.get("messages");
            Dlg.form(p, title, "", fields, msgs.getString("ui.submit", "<green>Submit"), msgs.getString("ui.cancel", "<red>Cancel"), done);
            return;
        }
        Map<String, String> vals = new java.util.LinkedHashMap<>();
        ask(p, fields, 0, vals, done);
    }

    private static void ask(Player p, List<Dlg.Field> fields, int i, Map<String, String> vals, BiConsumer<Player, Map<String, String>> done) {
        if (i >= fields.size()) {
            done.accept(p, vals);
            return;
        }
        Dlg.Field f = fields.get(i);
        BerryCore.get().prompts.ask(p, f.label(), s -> {
            vals.put(f.key(), s);
            ask(p, fields, i + 1, vals, done);
        });
    }
}
