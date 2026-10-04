package dev.berrycore;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Loads every per-feature config file and merges in jar defaults so new keys always resolve. */
public final class Cfg {
    public static final String[] FILES = {"config", "messages", "teams", "economy", "market", "homes", "stars", "sellgui", "shulker", "settings"};
    private final BerryCore plugin;
    private final Map<String, YamlConfiguration> map = new HashMap<>();

    public Cfg(BerryCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        for (String f : FILES) {
            File file = new File(plugin.getDataFolder(), f + ".yml");
            if (!file.exists()) plugin.saveResource(f + ".yml", false);
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            try (InputStream in = plugin.getResource(f + ".yml")) {
                if (in != null) y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (IOException ignored) {
            }
            map.put(f, y);
        }
    }

    public YamlConfiguration get(String name) {
        return map.get(name);
    }
}
