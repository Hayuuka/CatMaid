package dev.catmaid.dialogue;

import dev.catmaid.data.MaidRepository;
import dev.catmaid.event.MaidAffectionChangeEvent;
import dev.catmaid.maid.AffectionTable;
import dev.catmaid.maid.Maid;
import dev.catmaid.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 好感度：数值、等级、背包容量、伤害加成（蓝图 P4）。
 *
 * <p>数值一律来自 config.yml：增减走 {@code affection.gain/loss}，等级阈值 / 背包容量 / 伤害加成
 * 走 {@code affection.levels}（{@link AffectionTable} 解析），上下界走 {@code affection.min/max}。
 * 改好感度只调 {@link Maid#setAffection(double)}，它是容量的唯一同步点，
 * 绝不再单独调一次 setStorageCapacity（api-facts 16.7-4）。
 *
 * <p>三个触发来源的接入点各不相同，但都必须经过 {@link #apply}，否则事件、台词、特效会被绕过：
 * <ul>
 *   <li>喂食 / 受击 / 死亡 / 复活：监听器里直接调（P4）</li>
 *   <li>跟随计时 FOLLOW_TICK：由 BehaviorManager 每 tick 调 {@link #tickFollowing}（P4）</li>
 *   <li>收割 / 繁殖 / 击杀：P5-P7 的 Mode 里调（本阶段只保证 gain() 能取到配置值）</li>
 * </ul>
 */
public final class AffectionManager {

    /**
     * 跟随计时：per-maid tick 计数，不用取模——它只在「当前是跟随模式」时才累加。
     * 间隔来自 config（默认 300 秒 = 5 分钟，蓝图触发点表）；P8 起归入"全部数值可配"。
     */
    private final Map<UUID, Integer> followTicks = new HashMap<>();

    private final FileConfiguration config;
    private final DialogueManager dialogue;
    private final MaidRepository repository;

    public AffectionManager(FileConfiguration config, DialogueManager dialogue, MaidRepository repository) {
        this.config = config;
        this.dialogue = dialogue;
        this.repository = repository;
    }

    /** 配置里的单次增益；未配置的原因返回 0（不是错误，IDLE_LONG 就是这种情况）。 */
    public double gain(AffectionReason reason) {
        String key = configKey(reason);
        return key == null ? 0 : config.getDouble("affection.gain." + key, 0);
    }

    /**
     * 配置里的单次损失（正数）。注意「80+ 等级死亡减半」不在这里——那需要知道是谁，
     * 由 {@link #apply} 按等级折算，见 {@link #deathLoss(Maid)}。
     */
    public double loss(AffectionReason reason) {
        String key = configKey(reason);
        return key == null ? 0 : config.getDouble("affection.loss." + key, 0);
    }

    /** 死亡实际扣的数值：从配置的 {@code affection.death-halve-from-level} 档起死亡减半。 */
    public double deathLoss(Maid maid) {
        double base = loss(AffectionReason.DEATH);
        return level(maid.getAffection()) >= deathHalveFromLevel() ? base / 2 : base;
    }

    /** 0 起，档数与阈值都来自 config（{@link AffectionTable}）。 */
    public int level(double affection) {
        return AffectionTable.level(affection);
    }

    /** 该等级的存储格数（config 的 {@code affection.levels[].capacity}）。 */
    public int backpackCapacity(int level) {
        return AffectionTable.capacity(level);
    }

    /** 攻击伤害加成，Phase 7 战斗模式使用（config 的 {@code affection.levels[].damage-bonus}）。 */
    public double damageBonus(int level) {
        return AffectionTable.damageBonus(level);
    }

    /**
     * 唯一的好感度改动入口：可取消事件 → clamp(0,100) → 等级变化时放特效/播台词/提示容量。
     *
     * @param delta 正数加、负数减；0 直接返回（复活就是这个语义：死亡时已经扣过了）
     */
    public void apply(Maid maid, double delta, AffectionReason reason) {
        if (delta == 0) {
            return;
        }
        double oldValue = maid.getAffection();
        double target = clamp(oldValue + delta);
        MaidAffectionChangeEvent event = new MaidAffectionChangeEvent(maid, oldValue, target, reason);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        target = clamp(event.getNewValue());
        if (target == oldValue) {
            return;
        }
        int oldLevel = level(oldValue);
        maid.setAffection(target);
        int newLevel = level(target);
        if (newLevel > oldLevel) {
            // 等级变化是玩家能直观感受到的里程碑，日志留着方便核对"背包为什么变了"
            Bukkit.getLogger().info("[CatMaid] " + maid.getName() + " 好感度 "
                    + format(oldValue) + " → " + format(target) + " 升到等级 " + newLevel
                    + "（背包 " + backpackCapacity(newLevel) + " 格）");
            onLevelUp(maid, newLevel);
        } else if (newLevel < oldLevel) {
            Bukkit.getLogger().info("[CatMaid] " + maid.getName() + " 好感度 "
                    + format(oldValue) + " → " + format(target) + " 降到等级 " + newLevel);
            dialogue.send(maid, "on_level_down");
        }
        // 好感度是慢变量（几分钟才变一次），每次落库比"等 5 分钟全量保存"更稳，
        // 崩服时不会把玩家刚喂出来的好感吞掉。
        repository.save(maid);
    }

    /**
     * 跟随计时。由 BehaviorManager 每 tick 调用，但只在女仆当前处于跟随模式时才调，
     * 所以这里放心地无脑累加。
     */
    public void tickFollowing(Maid maid) {
        int ticks = followTicks.merge(maid.getId(), 1, Integer::sum);
        if (ticks < followTickInterval()) {
            return;
        }
        followTicks.put(maid.getId(), 0);
        apply(maid, gain(AffectionReason.FOLLOW_TICK), AffectionReason.FOLLOW_TICK);
    }

    /** 女仆被移除时清掉计时，避免 Map 无限增长。 */
    public void forget(UUID maidId) {
        followTicks.remove(maidId);
    }

    private int followTickInterval() {
        return Math.max(20, config.getInt("affection.follow-tick-interval-seconds", 300) * 20);
    }

    private int deathHalveFromLevel() {
        return config.getInt("affection.death-halve-from-level", AffectionTable.levelCount() - 1);
    }

    /** 好感度上下界来自 config，默认 0..100。 */
    private double clamp(double value) {
        double min = config.getDouble("affection.min", 0);
        double max = config.getDouble("affection.max", 100);
        return Math.max(min, Math.min(max, value));
    }

    private void onLevelUp(Maid maid, int newLevel) {
        // 升级的"心跳"：爱心爆发 + 台词 + 给主人一条容量提示（背包多出来的格子得让玩家知道）
        Location loc = maid.entity() == null ? null : maid.entity().getLocation();
        if (loc != null) {
            World world = loc.getWorld();
            if (world != null) {
                world.spawnParticle(Particle.HEART, loc.clone().add(0, 1.2, 0), 20, 0.6, 0.8, 0.6, 0);
            }
        }
        dialogue.send(maid, "on_level_up");
        Player owner = Bukkit.getPlayer(maid.getOwnerUuid());
        if (owner != null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", maid.getName());
            ph.put("level", Messages.levelName(newLevel));
            ph.put("capacity", String.valueOf(backpackCapacity(newLevel)));
            Messages.send(owner, "affection-level-up", ph);
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    /** 原因 → config.yml 的键名。返回 null 表示这种原因没有配置项。 */
    private static String configKey(AffectionReason reason) {
        return switch (reason) {
            case FEED -> "feed";
            case WORK_HARVEST -> "work-harvest";
            case WORK_BREED -> "work-breed";
            case WORK_KILL -> "work-kill";
            case FOLLOW_TICK -> "follow-tick";
            case HURT -> "hurt";
            case DEATH -> "death";
            case REVIVE, IDLE_LONG -> null;
        };
    }
}
