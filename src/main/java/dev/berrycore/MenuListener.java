package dev.berrycore;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {
    private final BerryCore plugin;

    public MenuListener(BerryCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        int size = m.inv.getSize();
        boolean top = e.getRawSlot() >= 0 && e.getRawSlot() < size;
        if (!m.free) {
            e.setCancelled(true);
            if (top && m.handlers.containsKey(e.getRawSlot())) m.handlers.get(e.getRawSlot()).accept(e);
            return;
        }
        if (top && m.handlers.containsKey(e.getRawSlot())) {
            e.setCancelled(true);
            m.handlers.get(e.getRawSlot()).accept(e);
            return;
        }
        // Free slot interaction: block the actions that could dupe or pull items out of locked slots.
        switch (e.getAction()) {
            case COLLECT_TO_CURSOR -> e.setCancelled(true);
            default -> {
            }
        }
        changed(m);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        int size = m.inv.getSize();
        for (int s : e.getRawSlots()) {
            if (s < size && (!m.free || m.handlers.containsKey(s))) {
                e.setCancelled(true);
                return;
            }
        }
        changed(m);
    }

    private void changed(Menu m) {
        if (m.onChange != null) Bukkit.getScheduler().runTask(plugin, m.onChange);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Menu m && m.onClose != null) m.onClose.accept(e);
    }
}
