package dev.berrycore;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Bedrock (Geyser/Floodgate) detection with no hard dependency. */
public final class Platform {
    private static Object floodgate;
    private static Method isFloodgate;
    private static boolean tried;

    private Platform() {
    }

    public static boolean isBedrock(Player p) {
        BerryCore pl = BerryCore.get();
        var c = pl.cfg.get("config");
        if (c.getBoolean("bedrock.debug-treat-everyone-as-bedrock", false)) return true;
        if (c.getStringList("bedrock.force-players").stream().anyMatch(n -> n.equalsIgnoreCase(p.getName()))) return true;
        if (!tried) {
            tried = true;
            try {
                Class<?> k = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                floodgate = k.getMethod("getInstance").invoke(null);
                isFloodgate = k.getMethod("isFloodgatePlayer", UUID.class);
            } catch (Throwable ignored) {
                floodgate = null;
            }
        }
        if (floodgate != null) {
            try {
                if ((boolean) isFloodgate.invoke(floodgate, p.getUniqueId())) return true;
            } catch (Throwable ignored) {
            }
        }
        // Floodgate UUIDs always start with 16 zero hex digits.
        if (c.getBoolean("bedrock.uuid-fallback", true) && p.getUniqueId().getMostSignificantBits() == 0L) return true;
        String prefix = c.getString("bedrock.name-prefix", "");
        return !prefix.isEmpty() && p.getName().startsWith(prefix);
    }

    public static List<String> none() {
        return List.of();
    }
}
