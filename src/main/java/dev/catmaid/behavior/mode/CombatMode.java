package dev.catmaid.behavior.mode;

import dev.catmaid.behavior.BehaviorMode;
import dev.catmaid.behavior.Movement;
import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.AffectionReason;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.maid.Maid;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockVector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗：主动索敌、近战迎击、护主反击（蓝图 P7）。
 *
 * <p>每 10 tick 决策一次，按 {@code tick % 10 == npcId % 10} 错峰（agent.md 7.1）。节奏比农业/畜牧快，
 * 因为 "她反应慢半拍" 在战斗里最刺眼：怪已经贴到主人脸上了，她还在等下一轮决策。
 *
 * <p>三件事按优先级排列：
 * <ol>
 *   <li>维护目标：死了/跑远了/换了世界就丢，没目标就找最近的怪；</li>
 *   <li>没目标 = 脱战：交回 {@link FollowMode} 缓步跟着主人（不复制一份距离带逻辑）；</li>
 *   <li>有目标：够得着就砍，够不着就走过去。走路姿态由 BehaviorManager 按导航状态推导。</li>
 * </ol>
 *
 * <p>不做动画。挥剑的 {@code swingMainHand} 只驱动原版手臂，模型盖住原版实体后大概率看不见
 * （api-facts 20.10-5.5）；要让她真的"挥"起来，得先给 bbmodel 补 {@code attack} 动画。
 */
public final class CombatMode implements BehaviorMode {

    /** 决策间隔（蓝图 P7：每 10 tick）。 */
    private static final int DECIDE_INTERVAL = 10;
    /** 近战够得着的距离。蓝图写 2.8：玩家攻击距离是 3.0，留一点寻路误差。 */
    private static final double MELEE_REACH = 2.8;
    /** 追人速度。略快于步行，才追得上逃跑的僵尸。 */
    private static final float CHASE_SPEED = 1.25f;
    /** 8 秒没挪动 0.5 格 → 走不过去（栅栏/坑），拉黑（agent.md 7.2）。 */
    private static final long STUCK_MS = 8000L;
    private static final double STUCK_DISTANCE_SQ = 0.25;
    /** 拉黑 30 秒：给主人 30 秒解决地形，之后允许再试。 */
    private static final long BLACKLIST_MS = 30_000L;
    /**
     * 攻击强度闸门。玩家型 NPC 走 ServerPlayer.attack，伤害受攻击冷却影响；
     * 不判强度的话 20 tick 一刀在斧头上只有 ~80% 伤害，会把"好感加成 15%"整个淹掉
     * （api-facts 20.9-A4）。
     */
    /** 攻强不足 0.9 就等下一刀。 */
    private static final float MIN_ATTACK_STRENGTH = 0.9f;
    /** 打完一只偶尔说一句（与农业/畜牧同口径：十次一句，不至于碎碎念）。 */
    private static final double WORK_LINE_CHANCE = 0.1;

    private final FileConfiguration config;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    /** 脱战后的跟随动作，直接复用（含跨世界 3 秒延迟与 &gt;40 格传送）。 */
    private final FollowMode follow;

    private final Map<UUID, State> states = new HashMap<>();

    /** 每只女仆的战斗状态。Mode 是单例，状态必须按女仆分，否则十只会抢同一个目标。 */
    private static final class State {
        private LivingEntity target;
        private Location lastPos;
        private long lastProgressAt;
        /** 下一次允许出手的时间戳（毫秒）。 */
        private long nextAttackAt;
        /** 走不过去的位置 → 解禁时间戳。怪卡在栅栏里时靠它止损。 */
        private final Map<BlockVector, Long> blacklist = new HashMap<>();
    }

    public CombatMode(FileConfiguration config, AffectionManager affection, DialogueManager dialogue,
                      FollowMode follow) {
        this.config = config;
        this.affection = affection;
        this.dialogue = dialogue;
        this.follow = follow;
    }

    @Override
    public Maid.Mode type() {
        return Maid.Mode.COMBAT;
    }

    @Override
    public void onDisable(Maid maid) {
        states.remove(maid.getId());
    }

    @Override
    public void tick(Maid maid, int tick) {
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        if (!staggered(tick, maid, DECIDE_INTERVAL)) {
            return;
        }
        State state = states.computeIfAbsent(maid.getId(), key -> new State());
        pruneBlacklist(state);
        maintainTarget(maid, entity, state);

        if (state.target == null) {
            // 脱战：站住，把主人交还给跟随逻辑（她会自己处理主人不在线、跨世界、太远传送）
            Movement.stop(maid);
            follow.tick(maid, tick);
            return;
        }
        engage(maid, entity, state);
    }

    /** 错峰：第 tick 该不该这只女仆决策。 */
    private static boolean staggered(int tick, Maid maid, int interval) {
        return Math.floorMod(tick, interval) == Math.floorMod(maid.getNpcId(), interval);
    }

    /**
     * 主人或她自己挨打时由 {@code OwnerProtectionListener} 调进来。
     *
     * <p>这里只负责"把目标写进状态"，追击与出手仍在 {@link #tick} 里——监听器不碰业务
     * （agent.md 5.3）。
     */
    public void lockTarget(Maid maid, LivingEntity attacker) {
        if (attacker == null || attacker.isDead() || !attacker.isValid()) {
            return;
        }
        // 别的女仆/NPC 不打；主人自己打她也不还手（否则"喂食点错"会变成家暴现场）
        if (CitizensAPI.getNPCRegistry().isNPC(attacker)
                || attacker.getUniqueId().equals(maid.getOwnerUuid())) {
            return;
        }
        LivingEntity self = maid.entity();
        if (self == null || !self.getWorld().equals(attacker.getWorld())) {
            return;
        }
        double loseDist = loseDistance();
        if (self.getLocation().distanceSquared(attacker.getLocation()) > loseDist * loseDist) {
            return; // 太远，锁定也会在下一轮被丢掉，不如不锁
        }
        State state = states.computeIfAbsent(maid.getId(), key -> new State());
        state.target = attacker;
        state.lastPos = null;
    }

    // ---- 目标 ----

    /** 目标死了算战绩；死了/跑远/换世界/被拉黑就丢目标，没目标就重新找。 */
    private void maintainTarget(Maid maid, LivingEntity entity, State state) {
        LivingEntity target = state.target;
        if (target != null && target.isDead()) {
            creditKill(maid);
            state.target = null;
            state.lastPos = null;
        } else if (target != null && !target.isValid()) {
            // 实体被卸载/移除（区块卸载、怪被插件清掉）不算击杀，只是目标没了
            state.target = null;
            state.lastPos = null;
        }
        if (state.target != null && !stillValid(entity, state)) {
            state.target = null;
            state.lastPos = null;
        }
        if (state.target == null) {
            state.target = nearestHostile(entity, state);
            state.lastPos = null;
        }
    }

    /**
     * 记一次击杀：统计 + 好感 + 偶尔一句台词（蓝图 P7；agent.md 第 10 节：心跳不能省）。
     *
     * <p>只在"她正在打的这个目标死了"时记，所以站在旁边看主人杀怪不会蹭到战绩。
     * 代价是主人抢了最后一刀也算她的——比起漏记，这个偏差更容易接受。
     */
    private void creditKill(Maid maid) {
        maid.stats().addKill();
        affection.apply(maid, affection.gain(AffectionReason.WORK_KILL), AffectionReason.WORK_KILL);
        if (ThreadLocalRandom.current().nextDouble() < WORK_LINE_CHANCE) {
            dialogue.send(maid, "on_work_done");
        }
    }

    /**
     * 目标还值得追吗：活着、同世界、没超出脱战距离、没被拉黑。
     *
     * <p>目标不必是怪——主人被玩家打时锁定的就是玩家（蓝图"打主人 → 女仆反击"）。
     */
    private boolean stillValid(LivingEntity entity, State state) {
        LivingEntity target = state.target;
        if (target == null || target.isDead() || !target.isValid()) {
            return false;
        }
        if (!target.getWorld().equals(entity.getWorld())) {
            return false;
        }
        if (blacklisted(state, target.getLocation())) {
            return false;
        }
        double loseDist = loseDistance();
        return entity.getLocation().distanceSquared(target.getLocation()) <= loseDist * loseDist;
    }

    /**
     * 半径内最近的敌怪。
     *
     * <p>排除项（蓝图 P7 / api-facts 20.9-B5）：Citizens NPC（包括其他女仆）、已死、隐身、
     * 被拉黑的。不排除的话她会把隔壁女仆当怪砍。
     */
    private LivingEntity nearestHostile(LivingEntity entity, State state) {
        double radius = radius();
        Location from = entity.getLocation();
        LivingEntity best = null;
        double bestSq = radius * radius;
        for (Entity candidate : entity.getWorld().getNearbyEntities(from, radius, radius, radius)) {
            if (!(candidate instanceof Monster monster) || monster.isDead() || !monster.isValid()
                    || monster.isInvisible()
                    || CitizensAPI.getNPCRegistry().isNPC(monster)
                    || blacklisted(state, monster.getLocation())) {
                continue;
            }
            double distance = monster.getLocation().distanceSquared(from);
            if (distance <= bestSq) {
                bestSq = distance;
                best = monster;
            }
        }
        return best;
    }

    // ---- 交战 ----

    /** 有目标：够得着就砍，够不着就走过去。 */
    private void engage(Maid maid, LivingEntity entity, State state) {
        LivingEntity target = state.target;
        if (!Movement.arrived(entity, target.getLocation(), MELEE_REACH)) {
            chase(maid, entity, state, target.getLocation());
            return;
        }
        Movement.stop(maid);
        face(maid, target);

        long now = System.currentTimeMillis();
        if (now < state.nextAttackAt) {
            return;
        }
        // 攻强没满就先不打：这一刀只有八成伤害，等下一轮（25 tick 内必满）
        if (entity instanceof Player player && player.getCooledAttackStrength(0.5f) < MIN_ATTACK_STRENGTH) {
            return;
        }
        entity.attack(target);
        // 原版手臂动画，模型盖住实体后未必可见；命中与伤害不受影响
        entity.swingMainHand();
        state.nextAttackAt = now + attackIntervalMs();
    }

    private void chase(Maid maid, LivingEntity entity, State state, Location target) {
        if (!progressed(entity, state)) {
            giveUp(maid, state);
            return;
        }
        if (!Movement.isNavigating(maid)) {
            // 每轮决策最多下一次目标：重复下发会打断 Citizens 的寻路（P3 踩过朝向乱甩）
            Movement.navigate(maid, target, CHASE_SPEED);
        }
    }

    /** 面向目标：朝脚底会让她明显低头，用眼睛位置。 */
    private void face(Maid maid, LivingEntity target) {
        NPC npc = maid.npc();
        if (npc != null && npc.isSpawned()) {
            npc.faceLocation(target.getEyeLocation());
        }
    }

    // ---- 卡死与拉黑 ----

    /**
     * 这一轮有没有进展。挪过 0.5 格就刷新时间戳。
     *
     * <p>不能只看 {@code isNavigating}：寻路失败时 navigator 会静默停下，只判导航中的话
     * 会变成"原地反复下目标、永不放弃"（P5 踩过）。
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

    /** 放弃当前目标：停下、拉黑这个位置 30 秒，等下一轮换目标。 */
    private void giveUp(Maid maid, State state) {
        Movement.stop(maid);
        if (state.target != null) {
            blacklist(state, state.target.getLocation());
        }
        state.target = null;
        state.lastPos = null;
    }

    private static void blacklist(State state, Location location) {
        state.blacklist.put(new BlockVector(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                System.currentTimeMillis() + BLACKLIST_MS);
    }

    private static boolean blacklisted(State state, Location location) {
        Long until = state.blacklist.get(
                new BlockVector(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        return until != null && until > System.currentTimeMillis();
    }

    /** 清掉过期条目，防止长期运行后拉黑表越攒越大。 */
    private static void pruneBlacklist(State state) {
        long now = System.currentTimeMillis();
        state.blacklist.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    // ---- 配置 ----

    private int radius() {
        return Math.max(1, config.getInt("combat.radius", 12));
    }

    private double loseDistance() {
        return Math.max(1, config.getDouble("combat.lose-dist", 20));
    }

    private long attackIntervalMs() {
        return Math.max(1, config.getInt("combat.attack-interval-ticks", 20)) * 50L;
    }
}
