package dev.berrycore;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Teleport with warmup / cooldown / cancel-on-move, driven by a "teleport:" config section. */
public final class Tp {
    private static final Map<String, Long> cooldowns = new HashMap<>();
    private static final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    private Tp() {
    }

    public static void go(Player p, Location dest, ConfigurationSection s, String key, String label) {
        BerryCore pl = BerryCore.get();
        if (dest == null || dest.getWorld() == null) {
            pl.msg.send(p, "world-missing");
            return;
        }
        boolean bypass = p.hasPermission("berrycore.tp.bypass");
        long cd = s.getLong("cooldown-seconds", 0) * 1000L;
        String ck = key + p.getUniqueId();
        long now = System.currentTimeMillis();
        if (!bypass && cd > 0 && cooldowns.getOrDefault(ck, 0L) > now) {
            pl.msg.send(p, "tp-cooldown", "seconds", String.valueOf((cooldowns.get(ck) - now) / 1000 + 1));
            return;
        }
        if (pending.contains(p.getUniqueId())) {
            pl.msg.send(p, "tp-already-pending");
            return;
        }
        int warm = s.getInt("warmup-seconds", 3);
        if (bypass || warm <= 0) {
            finish(p, dest, label, ck, cd);
            return;
        }
        boolean cancelMove = s.getBoolean("cancel-on-move", true);
        Location start = p.getLocation();
        pending.add(p.getUniqueId());
        new BukkitRunnable() {
            int left = warm;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    pending.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                Location now = p.getLocation();
                if (cancelMove && (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 0.6)) {
                    pending.remove(p.getUniqueId());
                    pl.msg.send(p, "tp-cancelled");
                    cancel();
                    return;
                }
                if (left <= 0) {
                    pending.remove(p.getUniqueId());
                    finish(p, dest, label, ck, cd);
                    cancel();
                    return;
                }
                pl.msg.bar(p, "tp-warmup", "seconds", String.valueOf(left), "name_mm", label);
                left--;
            }
        }.runTaskTimer(pl, 0L, 20L);
    }

    private static void finish(Player p, Location dest, String label, String ck, long cd) {
        p.teleportAsync(dest);
        if (cd > 0) cooldowns.put(ck, System.currentTimeMillis() + cd);
        BerryCore.get().msg.send(p, "tp-done", "name_mm", label);
    }
}
