package dev.catmaid.maid;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 好感度等级表：阈值 / 背包容量 / 伤害加成，全部来自 config.yml 的 {@code affection.levels}。
 *
 * <p>为什么要单独一个类，而不是塞进 AffectionManager：容量是 {@link Maid#setAffection(double)}
 * 同步的，GUI 与领域对象都要读，而它们都不该依赖 dialogue 包里的服务。这里只存一张静态表，
 * 启动时由 CatMaidPlugin 用配置灌一次，之后全局只读。
 *
 * <p>表结构（每级一项，第 0 项必须从 min: 0 开始）：
 * <pre>
 * affection:
 *   levels:
 *     - {min: 0,  capacity: 27, damage-bonus: 0.0}
 *     - {min: 20, capacity: 30, damage-bonus: 0.05}
 * </pre>
 * 等级名在 messages.yml 的 {@code gui.level-<序号>}，序号与这里的下标一一对应。
 */
public final class AffectionTable {

    private static final int[] DEFAULT_THRESHOLDS = {20, 40, 60, 80};
    private static final int[] DEFAULT_CAPACITIES = {27, 30, 33, 36, 36};
    private static final double[] DEFAULT_BONUSES = {0.0, 0.05, 0.10, 0.15, 0.20};

    /** 各等级的进入阈值（不含第 0 级），长度 = 等级数 - 1。 */
    private static int[] thresholds = DEFAULT_THRESHOLDS.clone();
    /** 各等级对应的存储格数，长度 = 等级数。 */
    private static int[] capacities = DEFAULT_CAPACITIES.clone();
    private static double[] bonuses = DEFAULT_BONUSES.clone();

    private AffectionTable() {
    }

    /**
     * 启动时调用。配置写坏（空表、阈值不递增、容量越界）时整表回落到默认值并打一条警告，
     * 不做"逐项纠错"——半对半错的表比默认值更难排查。
     */
    public static void load(FileConfiguration config, Logger logger) {
        List<Map<?, ?>> raw = config.getMapList("affection.levels");
        if (raw.size() < 2) {
            logger.warning("config 的 affection.levels 少于 2 档，改用内置默认等级表");
            resetToDefault();
            return;
        }
        int size = raw.size();
        int[] parsedThresholds = new int[size - 1];
        int[] parsedCapacities = new int[size];
        double[] parsedBonuses = new double[size];
        int previous = Integer.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            Map<?, ?> entry = raw.get(i);
            int min = intValue(entry.get("min"), i == 0 ? 0 : previous + 1);
            int capacity = intValue(entry.get("capacity"),
                    i < DEFAULT_CAPACITIES.length ? DEFAULT_CAPACITIES[i] : MaidInventory.MAX_STORAGE);
            double bonus = doubleValue(entry.get("damage-bonus"),
                    i < DEFAULT_BONUSES.length ? DEFAULT_BONUSES[i] : 0);
            if (i == 0 && min != 0) {
                logger.warning("affection.levels 第一档的 min 不是 0（" + min + "），已按 0 处理");
                min = 0;
            }
            if (i > 0 && min <= previous) {
                logger.warning("affection.levels 第 " + i + " 档的 min（" + min + "）没有递增，整表回落到默认值");
                resetToDefault();
                return;
            }
            if (i > 0) {
                parsedThresholds[i - 1] = min;
            }
            parsedCapacities[i] = Math.max(0, Math.min(capacity, MaidInventory.MAX_STORAGE));
            parsedBonuses[i] = Math.max(0, bonus);
            previous = min;
        }
        thresholds = parsedThresholds;
        capacities = parsedCapacities;
        bonuses = parsedBonuses;
    }

    /** 好感度落在第几档（0 起）。 */
    public static int level(double affection) {
        int level = 0;
        for (int i = 0; i < thresholds.length; i++) {
            if (affection >= thresholds[i]) {
                level = i + 1;
            }
        }
        return level;
    }

    /** 该等级的存储格数。 */
    public static int capacity(int level) {
        return capacities[Math.max(0, Math.min(level, capacities.length - 1))];
    }

    /** 该等级的攻击伤害加成（Phase 7 用）。 */
    public static double damageBonus(int level) {
        return bonuses[Math.max(0, Math.min(level, bonuses.length - 1))];
    }

    public static int levelCount() {
        return capacities.length;
    }

    private static void resetToDefault() {
        thresholds = DEFAULT_THRESHOLDS.clone();
        capacities = DEFAULT_CAPACITIES.clone();
        bonuses = DEFAULT_BONUSES.clone();
    }

    private static int intValue(Object raw, int fallback) {
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static double doubleValue(Object raw, double fallback) {
        return raw instanceof Number number ? number.doubleValue() : fallback;
    }
}
