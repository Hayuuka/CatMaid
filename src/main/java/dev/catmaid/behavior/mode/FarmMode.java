package dev.catmaid.behavior.mode;

import dev.catmaid.behavior.BehaviorMode;
import dev.catmaid.behavior.Movement;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.maid.Maid;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockVector;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 农业：在 home（或自身）周围找成熟作物，走过去收割（蓝图 P5）。
 *
 * <p>节奏：每 20 tick 决策一次，按 {@code tick % 20 == npcId % 20} 错峰。一次扫描是 17×3×17 ≈ 870 个方块，
 * 每 tick 做会把主线程吃掉，所以只能在错峰到自己的那一 tick 里做（agent.md 7.1）。
 *
 * <p>坐下由 {@link dev.catmaid.behavior.BehaviorManager} 全局硬拦截（P3），任何模式都不会被 tick，
 * 所以这里不再判断 sitting——蓝图写的那行已被上游统一处理。
 *
 * <p>动画不在这里碰：姿态由 BehaviorManager 按 {@code Movement.isNavigating} 推导，Mode 只管导航。
 */
public final class FarmMode implements BehaviorMode {

    /** 每 20 tick 决策一次（蓝图 P5），并按下标错峰。 */
    private static final int CHECK_INTERVAL = 20;
    /** 略快于步行：农活是"有目标地走"，1.1 与蓝图一致。 */
    private static final float WALK_SPEED = 1.1f;
    /** 到作物 3.5 格内就算够得着。导航常常停在作物旁边一格，卡太紧会永远"差一点"。 */
    private static final double REACH = 3.5;
    /** 8 秒没挪动 0.5 格 → 认定走不过去（栅栏/围墙/水沟），拉黑该方块（agent.md 7.2）。 */
    private static final long STUCK_MS = 8000L;
    private static final double STUCK_DISTANCE_SQ = 0.25;
    /** 拉黑时长：给主人 30 秒拆掉障碍，之后允许再试一次。 */
    private static final long BLACKLIST_MS = 30_000L;

    private static final List<String> DEFAULT_CROPS = List.of(
            "WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA");

    private final FileConfiguration config;
    private final DialogueManager dialogue;
    /** 可收割作物。构造时从 config 解析一次：配置改动在 P8 的 reload 之前都要重启才生效。 */
    private final Set<Material> crops = EnumSet.noneOf(Material.class);
    private final Map<UUID, State> states = new HashMap<>();

    public FarmMode(FileConfiguration config, DialogueManager dialogue) {
        this.config = config;
        this.dialogue = dialogue;
        loadCrops();
    }

    /** 每只女仆的农活状态。Mode 单例、状态按女仆分，避免互相踩目标。 */
    private static final class State {
        private Block target;
        private Location lastPos;
        private long lastProgressAt;
        /** 走不过去的方块 → 解禁时间戳（毫秒）。栅栏围住的作物不能让她撞一整天。 */
        private final Map<BlockVector, Long> blacklist = new HashMap<>();
    }

    @Override
    public Maid.Mode type() {
        return Maid.Mode.FARM;
    }

    @Override
    public void onDisable(Maid maid) {
        // 状态里存着 Block 与拉黑表，女仆被移除/切模式后留着只会涨内存
        states.remove(maid.getId());
    }

    @Override
    public void tick(Maid maid, int tick) {
        if (Math.floorMod(tick, CHECK_INTERVAL) != Math.floorMod(maid.getNpcId(), CHECK_INTERVAL)) {
            return;
        }
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        State state = states.computeIfAbsent(maid.getId(), key -> new State());

        Block target = state.target;
        if (target == null || !harvestable(target, state)) {
            target = findNearest(center(maid, entity), entity, state);
            state.target = target;
            state.lastPos = null;
        }
        if (target == null) {
            // 附近没活干：站住等下一轮。台词交给随机待机那套节流（自带间隔/概率/每女仆冷却），
            // 在这里每 20 tick 掷一次骰子会变成刷屏。
            Movement.stop(maid);
            dialogue.tickIdle(maid, tick);
            return;
        }

        Location crop = target.getLocation().add(0.5, 0, 0.5);
        if (Movement.arrived(entity, crop, REACH)) {
            harvest(maid, target);
            state.target = null;
            state.lastPos = null;
            return;
        }
        if (!progressed(entity, state)) {
            // 8 秒没挪动：导航放弃、被栅栏挡住、或者目标根本走不到，拉黑换下一株
            giveUp(maid, target, state);
            return;
        }
        if (!Movement.isNavigating(maid)) {
            // 每轮决策最多下一次目标：Citizens 的寻路会自己跑，重复下发会打断它（P3 踩过朝向乱甩）
            Movement.navigate(maid, crop, WALK_SPEED);
        }
    }

    // ---- 找目标 ----

    /** 农活中心：{@code farm.center} 为 home 时在家附近干，否则跟着自己的脚走。 */
    private Location center(Maid maid, LivingEntity entity) {
        if ("self".equalsIgnoreCase(config.getString("farm.center", "home"))) {
            return entity.getLocation();
        }
        Location home = maid.getHome();
        if (home == null || home.getWorld() == null || !home.getWorld().equals(entity.getWorld())) {
            // 家不在这个世界（主人把她带出门了）→ 就近干，不要跨世界跑回去
            return entity.getLocation();
        }
        return home;
    }

    /**
     * 双/三层循环扫一遍中心周围，取最近的一株成熟作物。
     *
     * <p>先跳过未加载的区块：{@code getBlockAt} 在未加载区块上会同步加载整块区块，
     * 一个女仆在边界上转一圈就能把服务端卡住。
     */
    private Block findNearest(Location center, LivingEntity entity, State state) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        pruneBlacklist(state);
        int radius = Math.max(1, config.getInt("farm.radius", 8));
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        Block best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = world.getBlockAt(x, cy + dy, z);
                    if (!crops.contains(block.getType()) || !mature(block) || blacklisted(block, state)) {
                        continue;
                    }
                    double distance = entity.getLocation().distanceSquared(block.getLocation().add(0.5, 0, 0.5));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = block;
                    }
                }
            }
        }
        return best;
    }

    /** 目标还成立吗：被玩家收走了、被踩坏了、或者被拉黑了，都要重新挑一株。 */
    private boolean harvestable(Block block, State state) {
        if (!loaded(block) || !crops.contains(block.getType())) {
            return false;
        }
        return mature(block) && !blacklisted(block, state);
    }

    /** Nether Wart / Cocoa 同样是 Ageable，所以不必按材质分别判断成熟度。 */
    private static boolean mature(Block block) {
        return block.getBlockData() instanceof Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge();
    }

    /** 未加载区块上的方块操作会触发同步加载，先判断再碰。 */
    private static boolean loaded(Block block) {
        World world = block.getWorld();
        return world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    // ---- 收割 ----

    /**
     * 收割一株：收成直接进女仆背包，装不下的掉在原地（蓝图 P5）。
     *
     * <p>为什么不用蓝图上写的 {@code block.breakNaturally(tool)}：它在 Paper 26.2 的签名是
     * {@code boolean breakNaturally(ItemStack)}，返回的是"有没有掉落"，不是掉落列表，而且物品会直接
     * 掉进世界、还得再捡一次。这里改成 {@code getDrops(tool)} 先算掉落、再静默清空方块，
     * "入包失败才落地"这条才成立（冲突记录见 docs/api-facts.md 第 19 节）。
     *
     * <p>工具只用它的附魔/效率效果，不扣耐久：作物没有"正确工具"，原版用锄头收作物同样不掉耐久。
     */
    private void harvest(Maid maid, Block block) {
        ItemStack tool = maid.inventory().getMainHand();
        Collection<ItemStack> drops = tool == null || tool.getType().isAir()
                ? block.getDrops()
                : block.getDrops(tool);
        BlockData harvested = block.getBlockData();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        World world = block.getWorld();
        // 关掉物理更新：马上可能要补种，否则这一格会先被邻接更新刷一遍
        block.setType(Material.AIR, false);
        world.playSound(center, Sound.BLOCK_CROP_BREAK, 0.8f, 1.3f);
        world.spawnParticle(Particle.BLOCK, center, 12, 0.25, 0.25, 0.25, harvested);

        for (ItemStack drop : drops) {
            int leftover = maid.inventory().addItem(drop);
            if (leftover > 0) {
                // 背包满：剩下的原地掉出来（蓝图 P5 验收项），别静默吞掉玩家的收成
                ItemStack rest = drop.clone();
                rest.setAmount(leftover);
                world.dropItemNaturally(center, rest);
            }
        }
    }

    // ---- 卡死 ----

    /**
     * 这一轮有没有进展。挪过 0.5 格就刷新时间戳；
     * 之所以放在"是否在导航"之外判断，是因为寻路失败时 navigator 会静默停下（isNavigating 变 false），
     * 只判导航中的话会变成"原地反复下目标、永远不拉黑"。
     */
    private static boolean progressed(LivingEntity entity, State state) {
        Location pos = entity.getLocation();
        if (state.lastPos == null || state.lastPos.distanceSquared(pos) > STUCK_DISTANCE_SQ) {
            state.lastPos = pos.clone();
            state.lastProgressAt = System.currentTimeMillis();
            return true;
        }
        return System.currentTimeMillis() - state.lastProgressAt < STUCK_MS;
    }

    /** 放弃这一株：停下、拉黑 30 秒（短期不再考虑），下一轮换最近的其他作物。 */
    private void giveUp(Maid maid, Block target, State state) {
        Movement.stop(maid);
        state.blacklist.put(new BlockVector(target.getX(), target.getY(), target.getZ()),
                System.currentTimeMillis() + BLACKLIST_MS);
        state.target = null;
        state.lastPos = null;
    }

    private boolean blacklisted(Block block, State state) {
        Long until = state.blacklist.get(new BlockVector(block.getX(), block.getY(), block.getZ()));
        return until != null && until > System.currentTimeMillis();
    }

    /** 清掉过期条目，防止长期运行后拉黑表越攒越大（按方块计，不按时间清理会一直涨）。 */
    private static void pruneBlacklist(State state) {
        long now = System.currentTimeMillis();
        state.blacklist.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    // ---- 配置 ----

    private void loadCrops() {
        List<String> configured = config.getStringList("farm.crops");
        if (configured.isEmpty()) {
            configured = DEFAULT_CROPS;
        }
        for (String name : configured) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                Bukkit.getLogger().warning("[CatMaid] farm.crops 里的 " + name + " 不是有效材质，已忽略");
                continue;
            }
            crops.add(material);
        }
        if (crops.isEmpty()) {
            // 配错了就退回默认表：宁可按蓝图默认作物干活，也不要"农业女仆站着不动"
            for (String name : DEFAULT_CROPS) {
                crops.add(Material.valueOf(name));
            }
            Bukkit.getLogger().warning("[CatMaid] farm.crops 全部无效，已回落到默认作物表");
        }
    }
}
