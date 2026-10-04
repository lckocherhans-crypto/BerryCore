package dev.berrycore;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/** Tiny YAML-backed data file (plugins/BerryCore/data/NAME.yml). */
public final class Store {
    public final YamlConfiguration y;
    private final File file;
    private final BerryCore plugin;

    public Store(BerryCore plugin, String name) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data/" + name + ".yml");
        file.getParentFile().mkdirs();
        this.y = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save " + file.getName() + ": " + e.getMessage());
        }
    }
}
