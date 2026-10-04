package dev.berrycore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Generic chest menu. Clicks are cancelled unless {@link #free} is set (then only handled slots are locked). */
public final class Menu implements InventoryHolder {
    public final Inventory inv;
    public final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    public Consumer<InventoryCloseEvent> onClose;
    public Runnable onChange;
    public boolean free;
    public boolean done;
    public boolean armed;

    public Menu(Component title, int rows) {
        this.inv = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    public Menu set(int slot, ItemStack item, Consumer<InventoryClickEvent> h) {
        inv.setItem(slot, item);
        if (h != null) handlers.put(slot, h);
        return this;
    }

    public void open(Player p) {
        p.openInventory(inv);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }
}
