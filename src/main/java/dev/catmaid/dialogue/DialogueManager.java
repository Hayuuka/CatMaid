package dev.catmaid.dialogue;

import dev.catmaid.maid.Maid;
import dev.catmaid.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 台词：从 dialogue.yml 随机挑一句发给主人（蓝图 P4）。
 *
 * <p>为什么只发给主人：这是"她对你说话"，发给全服既吵又破坏归属感（agent.md 第 10 节）。
 * 主人不在线时静默丢弃——攒着等他上线再补播反而更怪。
 *
 * <p>随机待机台词有**每女仆独立冷却**（蓝图要求 5 分钟）。它和"多久掷一次骰子"是两件事：
 * {@link #tickIdle} 每 3 分钟按 npcId 错峰掷一次 5%，掷中了还要过冷却闸门才真的说话，
 * 否则冷启动后前几分钟会连说好几句。
 */
public final class DialogueManager {

    private static final String FILE_NAME = "dialogue.yml";
    /** 每 3 分钟掷一次骰子（蓝图：每 3 分钟 5% 概率）。 */
    private static final int IDLE_CHECK_TICKS = 20 * 60 * 3;
    private static final double IDLE_CHANCE = 0.05;
    /** 低频台词每女仆 5 分钟一条，防止连续触发。 */
    private static final long IDLE_COOLDOWN_MS = 5 * 60 * 1000L;

    private final Plugin plugin;
    private YamlConfiguration dialogue;
    private final Map<UUID, Long> idleSpokenAt = new HashMap<>();

    public DialogueManager(Plugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** 首次运行释放默认文件；P8 的 /catmaid admin reload 也走这里。 */
    public void load() {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            plugin.saveResource(FILE_NAME, false);
        }
        dialogue = YamlConfiguration.loadConfiguration(file);
    }

    public void reload() {
        load();
    }

    public void send(Maid maid, String key) {
        send(maid, key, Map.of());
    }

    /** 二级键，如 {@code on_mode_change} + {@code COMBAT}。 */
    public void send(Maid maid, String key, String subKey) {
        send(maid, key + "." + subKey, Map.of());
    }

    /** 带自定义占位符（喂食用 {@code item}）。 */
    public void send(Maid maid, String key, Map<String, String> extra) {
        List<String> lines = dialogue.getStringList(key);
        if (lines.isEmpty()) {
            return;
        }
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        deliver(maid, line, extra);
    }

    /**
     * 随机待机台词的心跳。由 BehaviorManager 每 tick 调，但只在待机/跟随模式下调。
     * 错峰到 npcId 上，避免所有女仆在同一 tick 一起掷骰子。
     */
    public void tickIdle(Maid maid, int tick) {
        if (Math.floorMod(tick, IDLE_CHECK_TICKS) != Math.floorMod(maid.getNpcId(), IDLE_CHECK_TICKS)) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= IDLE_CHANCE) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = idleSpokenAt.get(maid.getId());
        if (last != null && now - last < IDLE_COOLDOWN_MS) {
            return;
        }
        idleSpokenAt.put(maid.getId(), now);
        send(maid, "on_idle_random");
    }

    public void forget(UUID maidId) {
        idleSpokenAt.remove(maidId);
    }

    private void deliver(Maid maid, String raw, Map<String, String> extra) {
        Player owner = Bukkit.getPlayer(maid.getOwnerUuid());
        if (owner == null) {
            return;
        }
        String line = placeholders(raw, maid, extra);
        if ("actionbar".equalsIgnoreCase(plugin.getConfig().getString("dialogue.channel", "chat"))) {
            owner.sendActionBar(Component.text(line));
            return;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", maid.getName());
        ph.put("line", line);
        owner.sendMessage(Messages.get("dialogue.chat-format", ph));
    }

    /** dialogue.yml 注释里约定的占位符：{name} 女仆名、{owner} 女仆对主人的称呼、{item} 物品名。 */
    private static String placeholders(String line, Maid maid, Map<String, String> extra) {
        String out = line
                .replace("{name}", maid.getName())
                .replace("{owner}", maid.getOwnerTitle());
        for (Map.Entry<String, String> entry : extra.entrySet()) {
            out = out.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }
}
