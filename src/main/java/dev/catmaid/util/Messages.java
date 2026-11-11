package dev.catmaid.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
    private static JavaPlugin plugin;

    private Messages() {
    }

    public static void load(JavaPlugin owner) {
        plugin = owner;
        File file = new File(owner.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            owner.saveResource("messages.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        mergeMissingKeys(owner, file);
    }

    public static void reload() {
        if (plugin != null) {
            load(plugin);
        }
    }

    /**
     * 版本升级新增的文案键不会自己出现在旧 messages.yml 里，缺键会让玩家看到裸 key
     * （Phase 1 踩过一次：漏掉 depend-missing 就把 "depend-missing" 原样打印出来）。
     * 这里把 jar 内默认模板里有、而文件里没有的键补进去，已有值一律不动。
     */
    private static void mergeMissingKeys(JavaPlugin owner, File file) {
        try (InputStream in = owner.getResource("messages.yml")) {
            if (in == null) {
                return;
            }
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String key : defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(key) || config.contains(key)) {
                    continue;
                }
                config.set(key, defaults.get(key));
                changed = true;
            }
            if (changed) {
                config.save(file);
                owner.getLogger().info("messages.yml 已补齐新版本新增的文案键");
            }
        } catch (IOException e) {
            owner.getLogger().warning("补齐 messages.yml 缺失文案失败：" + e.getMessage());
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
        return getList(key, Map.of());
    }

    /** 列表型文案（GUI 的 lore）；占位符同样在解析前替换。 */
    public static List<Component> getList(String key, Map<String, String> placeholders) {
        List<Component> lines = new ArrayList<>();
        if (config == null) {
            return lines;
        }
        for (String line : config.getStringList(key)) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                line = line.replace("{" + entry.getKey() + "}", entry.getValue());
            }
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
