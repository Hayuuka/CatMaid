package dev.catmaid.behavior;

import dev.catmaid.behavior.mode.CombatMode;
import dev.catmaid.behavior.mode.FarmMode;
import dev.catmaid.behavior.mode.FollowMode;
import dev.catmaid.behavior.mode.IdleMode;
import dev.catmaid.behavior.mode.RanchMode;
import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.ModelAnimator;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * 行为分发：每 tick 对所有女仆做全局守卫与坐姿拦截，再按当前模式派给具体的 {@link BehaviorMode}。
 *
 * <p>驱动方是 CatMaidPlugin 里的调度器（每 tick 一次），<b>不是</b> Citizens 的 Trait#run——
 * 玩家型 NPC 的 trait 根本不会被 tick，原因写在 {@link dev.catmaid.maid.MaidTrait} 的类注释里。
 *
 * <p>顺带统一驱动两件"周期性"的事，避免各 Mode / Trait 各自记得：
 * <ol>
 *   <li>模型姿态（谁在导航就是走，否则站）；</li>
 *   <li>好感度的跟随计时与随机待机台词（P4）。这两件事都需要"每 tick 推一下"，
 *       而 api-facts 17.2 已经证明 trait 那套推不动，所以统一挂在这里。</li>
 * </ol>
 */
public final class BehaviorManager {

    private final Map<Maid.Mode, BehaviorMode> modes = new EnumMap<>(Maid.Mode.class);
    private final ModelAnimator animator;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    /** 全局 tick 计数器：所有模式用它做 `tick % N == npcId % N` 的错峰。 */
    private int tickCounter;

    public BehaviorManager(FileConfiguration config, ModelAnimator animator,
                           AffectionManager affection, DialogueManager dialogue) {
        this.animator = animator;
        this.affection = affection;
        this.dialogue = dialogue;
        IdleMode idle = new IdleMode(config);
        modes.put(Maid.Mode.IDLE, idle);
        // 主人不在线时 FollowMode 会把女仆交给 IdleMode，两者共用一个实例（它自己没有状态）
        FollowMode follow = new FollowMode(config, idle);
        modes.put(Maid.Mode.FOLLOW, follow);
        modes.put(Maid.Mode.FARM, new FarmMode(config, affection, dialogue));
        modes.put(Maid.Mode.RANCH, new RanchMode(config, affection, dialogue));
        // 战斗模式的"脱战回主人"直接复用 follow 实例，别复制一份距离带逻辑（api-facts 20.10-3）
        modes.put(Maid.Mode.COMBAT, new CombatMode(config, affection, dialogue, follow));
    }

    /** 未实现的模式返回 null（P7 起五个模式全齐，正常不会再走到 null）。 */
    public BehaviorMode get(Maid.Mode mode) {
        return modes.get(mode);
    }

    /**
     * 主人/女仆挨打时锁定反击目标。转发给战斗模式，业务逻辑留在 Mode 里（agent.md 5.3）。
     *
     * <p>目标只在女仆当前是战斗模式时才有意义——别的模式下她不该突然冲出去打架。
     */
    public void lockCombatTarget(Maid maid, LivingEntity attacker) {
        if (maid.getMode() != Maid.Mode.COMBAT) {
            return;
        }
        BehaviorMode mode = modes.get(Maid.Mode.COMBAT);
        if (mode instanceof CombatMode combat) {
            combat.lockTarget(maid, attacker);
        }
    }

    /** 每 tick 调一次：推进计数器后逐只女仆分发。 */
    public void tickAll(Collection<Maid> maids) {
        tickCounter++;
        for (Maid maid : maids) {
            tick(maid, tickCounter);
        }
    }

    public void tick(Maid maid, int tick) {
        // 铃铛态的女仆不能参与任何行为/计时：她的 NPC 可能被 Citizens 按存档"已生成"状态恢复出来
        // （实测重启后会出现一具没有名牌的幽灵身体），放任 tick 会让好感度凭空上涨、还会走动。
        if (!maid.isAlive()) {
            return;
        }
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        // 区块未加载时导航/视线都不可靠，直接跳过这一 tick
        Location loc = entity.getLocation();
        if (!entity.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }

        pulse(maid, tick);

        if (maid.isSitting()) {
            // 坐下是跨模式的硬拦截：任何模式都不许移动，只让待机逻辑做点小动作
            Movement.stop(maid);
            animator.apply(maid, ModelAnimator.Pose.SIT);
            idleTick(maid, tick);
            return;
        }

        BehaviorMode mode = modes.get(maid.getMode());
        if (mode == null) {
            // 配置里写错的模式不该把她变成木头人：退回跟随跑
            mode = modes.get(Maid.Mode.FOLLOW);
        }
        mode.tick(maid, tick);
        animator.apply(maid, Movement.isNavigating(maid) ? ModelAnimator.Pose.WALK : ModelAnimator.Pose.STAND);
    }

    /** 女仆被移除时清掉各模式与动画记录的 per-maid 状态。 */
    public void forget(Maid maid) {
        animator.forget(maid.getId());
        affection.forget(maid.getId());
        dialogue.forget(maid.getId());
        for (BehaviorMode mode : modes.values()) {
            mode.onDisable(maid);
        }
    }

    /**
     * 周期性的养成事项。跟随计时只在"跟随模式"累加（蓝图：跟随状态下每 5 分钟 +0.5），
     * 随机待机台词只在待机/跟随两种"没事干"的模式里掷骰子。
     */
    private void pulse(Maid maid, int tick) {
        Maid.Mode mode = maid.getMode();
        if (mode == Maid.Mode.FOLLOW) {
            affection.tickFollowing(maid);
        }
        if (mode == Maid.Mode.FOLLOW || mode == Maid.Mode.IDLE) {
            dialogue.tickIdle(maid, tick);
        }
    }

    private void idleTick(Maid maid, int tick) {
        BehaviorMode idle = modes.get(Maid.Mode.IDLE);
        if (idle != null) {
            idle.tick(maid, tick);
        }
    }
}
