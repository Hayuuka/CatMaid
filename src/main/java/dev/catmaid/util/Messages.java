package dev.catmaid.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * messages.yml 读取。玩家可见文案一律走这里（agent.md 5.5）。
 *
 * <p>占位符用 {key} 形式，在解析 MiniMessage 之前替换，避免玩家输入被当成标签执行。
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static FileConfiguration config;
    private static Plugin plugin;

    private Messages() {
    }

    public static void load(Plugin owner) {
        plugin = owner;
        File file = new File(owner.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            owner.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
    }

    public static void reload() {
        if (plugin != null) {
            load(plugin);
        }
    }

    public static Component get(String key) {
        return get(key, Map.of());
    }

    public static Component get(String key, Map<String, String> placeholders) {
        String raw = config == null ? key : config.getString(key, key);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return MINI.deserialize(raw);
    }

    public static String prefix() {
        String raw = config == null ? "" : config.getString("prefix", "");
        return raw;
    }

    /** 取纯文本（去掉 MiniMessage 标签），用于把一个键的值嵌进另一条消息的占位符。 */
    public static String plain(String key) {
        String raw = config == null ? key : config.getString(key, key);
        return MINI.stripTags(raw);
    }

    public static List<Component> getList(String key) {
        List<Component> lines = new ArrayList<>();
        if (config == null) {
            return lines;
        }
        for (String line : config.getStringList(key)) {
            lines.add(MINI.deserialize(line));
        }
        return lines;
    }

    public static void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(MINI.deserialize(prefix()).append(get(key, placeholders)));
    }

    public static void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }
}
