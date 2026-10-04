package dev.berrycore;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** "Type your answer in chat" input used for Bedrock players and chest-mode menus. */
public final class ChatPrompt implements Listener {
    private record Pending(Consumer<String> cb, long expires) {
    }

    private final BerryCore plugin;
    private final Map<UUID, Pending> waiting = new ConcurrentHashMap<>();

    public ChatPrompt(BerryCore plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String promptText, Consumer<String> cb) {
        int secs = plugin.cfg.get("config").getInt("chat-prompt.timeout-seconds", 60);
        waiting.put(p.getUniqueId(), new Pending(cb, System.currentTimeMillis() + secs * 1000L));
        p.closeInventory();
        plugin.msg.send(p, "prompt-header", "prompt", promptText, "cancel", plugin.cfg.get("config").getString("chat-prompt.cancel-word", "cancel"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Pending pd = waiting.remove(e.getPlayer().getUniqueId());
        if (pd == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (System.currentTimeMillis() > pd.expires()) {
            plugin.msg.send(p, "prompt-expired");
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (text.equalsIgnoreCase(plugin.cfg.get("config").getString("chat-prompt.cancel-word", "cancel"))) {
            plugin.msg.send(p, "prompt-cancelled");
            return;
        }
        plugin.sync(() -> pd.cb().accept(text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
    }
}
