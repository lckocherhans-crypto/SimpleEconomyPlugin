package com.example.simpleeconomy;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Low-level helpers for building Paper dialogs. */
public final class Dialogs {
    private final JavaPlugin plugin;

    public Dialogs(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ actions

    /** Button action that runs on the main thread. */
    public DialogAction click(Consumer<Player> handler) {
        return DialogAction.customClick((view, audience) -> {
            if (audience instanceof Player p) {
                Bukkit.getScheduler().runTask(plugin, () -> handler.accept(p));
            }
        }, ClickCallback.Options.builder().build());
    }

    /** Button action that also reads the text inputs (by key) when clicked. */
    public DialogAction clickInputs(List<String> keys, BiConsumer<Player, Map<String, String>> handler) {
        return DialogAction.customClick((view, audience) -> {
            Map<String, String> values = new HashMap<>();
            for (String key : keys) {
                String v = view.getText(key);
                values.put(key, v == null ? "" : v);
            }
            if (audience instanceof Player p) {
                Bukkit.getScheduler().runTask(plugin, () -> handler.accept(p, values));
            }
        }, ClickCallback.Options.builder().build());
    }

    // ------------------------------------------------------------ pieces

    public ActionButton button(Component label, Component tooltip, int width, DialogAction action) {
        return ActionButton.create(label, tooltip, width, action);
    }

    public ActionButton button(String label, NamedTextColor color, DialogAction action) {
        return ActionButton.create(Component.text(label, color), null, 120, action);
    }

    public DialogInput textInput(String key, String label, String initial, int maxLength) {
        return DialogInput.text(key, Component.text(label))
                .width(250)
                .initial(initial == null ? "" : initial)
                .maxLength(maxLength)
                .build();
    }

    private void show(Player p, String title, List<Component> lines, List<DialogInput> inputs, DialogType type) {
        List<DialogBody> body = lines.stream().map(c -> (DialogBody) DialogBody.plainMessage(c)).toList();
        DialogBase base = DialogBase.builder(Component.text(title)).body(body).inputs(inputs).build();
        Dialog dialog = Dialog.create(f -> f.empty().base(base).type(type));
        p.showDialog(dialog);
    }

    // ------------------------------------------------------------ dialog kinds

    /** Text + an OK button. */
    public void notice(Player p, String title, List<Component> lines) {
        show(p, title, lines, List.of(), DialogType.notice());
    }

    /** Yes / No style dialog, optionally with inputs. */
    public void confirm(Player p, String title, List<Component> lines, List<DialogInput> inputs,
                        ActionButton yes, ActionButton no) {
        show(p, title, lines, inputs, DialogType.confirmation(yes, no));
    }

    /** A grid of buttons with an exit button, optionally with inputs. */
    public void menu(Player p, String title, List<Component> lines, List<DialogInput> inputs,
                     List<ActionButton> actions, ActionButton exit, int columns) {
        show(p, title, lines, inputs, DialogType.multiAction(actions, exit, columns));
    }

    /** Search box dialog. Empty text clears the search. */
    public void search(Player p, String title, String current, BiConsumer<Player, String> onSubmit) {
        ActionButton yes = button("Search", NamedTextColor.GREEN,
                clickInputs(List.of("query"), (pl, m) -> onSubmit.accept(pl, m.get("query"))));
        ActionButton no = button("Cancel", NamedTextColor.RED, null);
        confirm(p, title, List.of(Component.text("Type an item name or player. Leave empty to clear the search.")),
                List.of(textInput("query", "Search", current, 64)), yes, no);
    }
}
