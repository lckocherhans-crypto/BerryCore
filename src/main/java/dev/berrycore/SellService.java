package dev.berrycore;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.ShulkerBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pricing, quick-sell (/sell hand, /sell all), the sell chest GUI and shulker tallying. */
public final class SellService {
    /** value = Berries (before multiplier), count = items sold, leftover = what the player gets back (or null). */
    public record One(double value, int count, ItemStack leftover) {
    }

    private final BerryCore plugin;

    public SellService(BerryCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection eco() {
        return plugin.cfg.get("economy");
    }

    public double price(Material m) {
        return eco().getDouble("sell-prices." + m.name(), eco().getDouble("default-price", 0));
    }

    public double multiplier(Player p) {
        double best = eco().getDouble("sell-multiplier-base", 1.0);
        for (Map<?, ?> m : eco().getMapList("sell-multipliers")) {
            Object perm = m.get("permission");
            Object mult = m.get("multiplier");
            if (perm instanceof String s && mult instanceof Number n && p.hasPermission(s)) best = Math.max(best, n.doubleValue());
        }
        return best;
    }

    private boolean sellable(ItemStack s) {
        if (eco().getStringList("sell-blacklist").contains(s.getType().name())) return false;
        if (s.hasItemMeta() && !eco().getBoolean("sell-customized-items", false)) {
            var meta = s.getItemMeta();
            if (meta.hasDisplayName() || meta.hasLore()) return false;
        }
        return true;
    }

    private One leaf(ItemStack s) {
        double pr = price(s.getType());
        if (pr <= 0 || !sellable(s)) return new One(0, 0, s);
        double dmg = 1.0;
        if (s.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d && s.getType().getMaxDurability() > 0 && eco().getBoolean("scale-by-durability", true)) {
            dmg = 1.0 - (double) d.getDamage() / s.getType().getMaxDurability();
        }
        return new One(pr * s.getAmount() * dmg, s.getAmount(), null);
    }

    /** Pure function: never mutates the stack you pass in. */
    public One process(ItemStack s) {
        if (s == null || s.getType().isAir()) return new One(0, 0, null);
        ConfigurationSection sh = plugin.cfg.get("shulker");
        if (sh.getBoolean("enabled", true) && Tag.SHULKER_BOXES.isTagged(s.getType())
                && s.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
            double total = 0;
            int count = 0;
            Inventory bi = box.getInventory();
            for (int i = 0; i < bi.getSize(); i++) {
                ItemStack in = bi.getItem(i);
                if (in == null || in.getType().isAir()) continue;
                One r = leaf(in);
                if (r.value() > 0) {
                    total += r.value();
                    count += r.count();
                    bi.setItem(i, null);
                }
            }
            if (count == 0) return new One(0, 0, s);
            if (sh.getBoolean("sell-box-itself", false)) {
                total += price(s.getType()) * s.getAmount();
                return new One(total, count + 1, null);
            }
            bsm.setBlockState(box);
            ItemStack back = s.clone();
            back.setItemMeta(bsm);
            return new One(total, count, back); // shulker comes back empty (or with only unsellable leftovers)
        }
        return leaf(s);
    }

    public One processAll(ItemStack[] items) {
        double total = 0;
        int count = 0;
        List<ItemStack> back = new ArrayList<>();
        for (ItemStack it : items) {
            One r = process(it);
            total += r.value();
            count += r.count();
            if (r.leftover() != null) back.add(r.leftover());
        }
        return new One(total, count, null);
    }

    private void give(Player p, List<ItemStack> items) {
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) continue;
            p.getInventory().addItem(it).values().forEach(o -> p.getWorld().dropItemNaturally(p.getLocation(), o));
        }
    }

    private void payout(Player p, double raw, int count) {
        double total = EconomyManager.round(raw * multiplier(p));
        plugin.economy.deposit(p.getUniqueId(), total);
        plugin.msg.send(p, "sold", "items", String.valueOf(count), "amount_mm", plugin.economy.fmt(total));
        if (plugin.settings.get(p, "sell-sounds")) {
            try {
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            } catch (Throwable ignored) {
            }
        }
    }

    public void sellHand(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        One r = process(hand);
        if (r.value() <= 0) {
            plugin.msg.send(p, "nothing-sellable");
            return;
        }
        p.getInventory().setItemInMainHand(r.leftover());
        payout(p, r.value(), r.count());
    }

    public void sellAll(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean skipHotbar = eco().getBoolean("sell-all.skip-hotbar", false);
        ItemStack[] contents = inv.getStorageContents();
        double preview = 0;
        int cnt = 0;
        for (int i = skipHotbar ? 9 : 0; i < contents.length; i++) {
            One r = process(contents[i]);
            preview += r.value();
            cnt += r.count();
        }
        if (preview <= 0) {
            plugin.msg.send(p, "nothing-sellable");
            return;
        }
        final double pv = preview;
        final int pc = cnt;
        Runnable go = () -> {
            double total = 0;
            int count = 0;
            ItemStack[] cur = inv.getStorageContents();
            for (int i = skipHotbar ? 9 : 0; i < cur.length; i++) {
                One r = process(cur[i]);
                if (r.value() > 0) {
                    total += r.value();
                    count += r.count();
                    cur[i] = r.leftover();
                }
            }
            inv.setStorageContents(cur);
            payout(p, total, count);
        };
        if (plugin.settings.get(p, "confirm-sell-all")) {
            Ui.confirm(p, "economy", plugin.cfg.get("messages").getString("ui.confirm-sell-title", "<green>Sell everything?"),
                    "Sell " + pc + " items for ~" + plugin.economy.num(pv * multiplier(p)), go);
        } else go.run();
    }

    // ---------------------------------------------------------------- chest GUI

    private ConfigurationSection gui() {
        return plugin.cfg.get("sellgui");
    }

    public void openGui(Player p) {
        int rows = Math.max(2, Math.min(6, gui().getInt("rows", 6)));
        int btn = rows * 9 - 1;
        Menu m = new Menu(Msg.c(p, gui().getString("title", "<gradient:#ff4d8d:#ffb347>Sell Items</gradient>")), rows);
        m.free = true;
        m.set(btn, button(p, m, 0, 0), e -> {
            double v = tally(m, btn).value();
            if (v <= 0) {
                plugin.msg.send(p, "nothing-sellable");
                return;
            }
            if (plugin.settings.get(p, "confirm-sell-gui") && !m.armed) {
                m.armed = true;
                refresh(p, m, btn);
                return;
            }
            m.done = true;
            sellContents(p, m, btn);
            plugin.sync(p::closeInventory);
        });
        m.onChange = () -> {
            m.armed = false;
            refresh(p, m, btn);
        };
        m.onClose = ce -> {
            if (m.done) return;
            m.done = true;
            boolean confirmOn = plugin.settings.get(p, "confirm-sell-gui");
            if (gui().getBoolean("sell-on-close", true) && !confirmOn) {
                sellContents(p, m, btn);
            } else {
                List<ItemStack> back = new ArrayList<>();
                for (int i = 0; i < btn; i++) {
                    ItemStack it = m.inv.getItem(i);
                    if (it != null) back.add(it);
                    m.inv.setItem(i, null);
                }
                give(p, back);
                if (!back.isEmpty()) plugin.msg.send(p, "sell-returned");
            }
        };
        m.open(p);
        refresh(p, m, btn);
    }

    private One tally(Menu m, int btn) {
        ItemStack[] items = new ItemStack[btn];
        for (int i = 0; i < btn; i++) items[i] = m.inv.getItem(i);
        return processAll(items);
    }

    private ItemStack button(Player p, Menu m, double total, int count) {
        ConfigurationSection b = gui().getConfigurationSection(m.armed ? "button-confirm" : "button");
        String totalMm = plugin.economy.fmt(EconomyManager.round(total * multiplier(p)));
        ItemStack it = Items.fromSection(p, b, "total_mm", totalMm, "items", String.valueOf(count),
                "multiplier", String.valueOf(multiplier(p)));
        return it;
    }

    private void refresh(Player p, Menu m, int btn) {
        One r = tally(m, btn);
        m.inv.setItem(btn, button(p, m, r.value(), r.count()));
    }

    private void sellContents(Player p, Menu m, int btn) {
        double total = 0;
        int count = 0;
        List<ItemStack> back = new ArrayList<>();
        for (int i = 0; i < btn; i++) {
            ItemStack it = m.inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            One r = process(it);
            total += r.value();
            count += r.count();
            if (r.leftover() != null) back.add(r.leftover());
            m.inv.setItem(i, null);
        }
        give(p, back);
        if (total > 0) payout(p, total, count);
        else if (!back.isEmpty()) plugin.msg.send(p, "sell-returned");
    }

    public void worth(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            plugin.msg.send(p, "hold-item");
            return;
        }
        One r = process(hand);
        plugin.msg.send(p, r.value() > 0 ? "worth" : "worth-none", "amount_mm", plugin.economy.fmt(EconomyManager.round(r.value() * multiplier(p))),
                "item", hand.getType().name().toLowerCase().replace('_', ' '));
    }
}
