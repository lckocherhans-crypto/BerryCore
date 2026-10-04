package dev.berrycore;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * ALL Paper Dialog API usage lives in this one class (Java Edition only).
 * If a Paper build changes the dialog API, this is the only file that needs touching.
 */
public final class Dlg {
    private static final ClickCallback.Options OPTS =
            ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(15)).build();

    private Dlg() {
    }

    public record Field(String key, String label, String initial, boolean bool) {
        public static Field text(String key, String label, String initial) {
            return new Field(key, label, initial == null ? "" : initial, false);
        }

        public static Field bool(String key, String label, boolean initial) {
            return new Field(key, label, String.valueOf(initial), true);
        }
    }

    public static ActionButton button(String label, Consumer<Player> run) {
        DialogAction action = run == null ? null : DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player pl) BerryCore.get().sync(() -> run.accept(pl));
        }, OPTS);
        return ActionButton.create(Msg.c(label), null, 200, action);
    }

    private static DialogBase base(String title, String body, List<DialogInput> inputs) {
        List<DialogBody> bodies = new ArrayList<>();
        if (body != null && !body.isBlank()) bodies.add(DialogBody.plainMessage(Msg.c(body)));
        return DialogBase.builder(Msg.c(title)).body(bodies).inputs(inputs).canCloseWithEscape(true).build();
    }

    /** A grid of buttons. */
    public static void menu(Player p, String title, String body, List<ActionButton> buttons, int columns, String closeLabel) {
        DialogBase base = base(title, body, List.of());
        ActionButton exit = button(closeLabel, null);
        Dialog d = Dialog.create(f -> f.empty().base(base).type(DialogType.multiAction(buttons, exit, Math.max(1, columns))));
        p.showDialog(d);
    }

    public static void confirm(Player p, String title, String body, String yes, String no, Runnable onYes) {
        DialogBase base = base(title, body, List.of());
        Dialog d = Dialog.create(f -> f.empty().base(base)
                .type(DialogType.confirmation(button(yes, pl -> onYes.run()), button(no, null))));
        p.showDialog(d);
    }

    /** Text / checkbox inputs with a submit button. Values come back as strings ("true"/"false" for checkboxes). */
    public static void form(Player p, String title, String body, List<Field> fields, String submit, String cancel,
                            BiConsumer<Player, Map<String, String>> onSubmit) {
        List<DialogInput> inputs = new ArrayList<>();
        for (Field f : fields) {
            if (f.bool()) inputs.add(DialogInput.bool(f.key(), Msg.c(f.label())).initial(Boolean.parseBoolean(f.initial())).build());
            else inputs.add(DialogInput.text(f.key(), Msg.c(f.label())).initial(f.initial()).maxLength(128).build());
        }
        DialogBase base = base(title, body, inputs);
        DialogAction action = DialogAction.customClick((view, audience) -> {
            Map<String, String> vals = new LinkedHashMap<>();
            for (Field f : fields) {
                if (f.bool()) vals.put(f.key(), String.valueOf(Boolean.TRUE.equals(view.getBoolean(f.key()))));
                else vals.put(f.key(), view.getText(f.key()) == null ? "" : view.getText(f.key()));
            }
            if (audience instanceof Player pl) BerryCore.get().sync(() -> onSubmit.accept(pl, vals));
        }, OPTS);
        ActionButton ok = ActionButton.create(Msg.c(submit), null, 150, action);
        Dialog d = Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(ok, button(cancel, null))));
        p.showDialog(d);
    }
}
