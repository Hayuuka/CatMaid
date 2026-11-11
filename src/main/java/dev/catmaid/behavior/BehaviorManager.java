package dev.catmaid.behavior;

import dev.catmaid.behavior.mode.FollowMode;
import dev.catmaid.behavior.mode.IdleMode;
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
 * <p>顺带统一驱动模型姿态：谁在导航就是走，否则站。放这里而不是放进各 Mode，
 * 是为了让 P5-P7 的农活/放牧/战斗不必各自记得播动画。
 */
public final class BehaviorManager {

    private final Map<Maid.Mode, BehaviorMode> modes = new EnumMap<>(Maid.Mode.class);
    private final ModelAnimator animator;
    /** 全局 tick 计数器：所有模式用它做 `tick % N == npcId % N` 的错峰。 */
    private int tickCounter;

    public BehaviorManager(FileConfiguration config, ModelAnimator animator) {
        this.animator = animator;
        IdleMode idle = new IdleMode(config);
        modes.put(Maid.Mode.IDLE, idle);
        // 主人不在线时 FollowMode 会把女仆交给 IdleMode，两者共用一个实例（它自己没有状态）
        modes.put(Maid.Mode.FOLLOW, new FollowMode(config, idle));
    }

    /** 未实现的模式（COMBAT/FARM/RANCH 属 P5-P7）返回 null。 */
    public BehaviorMode get(Maid.Mode mode) {
        return modes.get(mode);
    }

    /** 每 tick 调一次：推进计数器后逐只女仆分发。 */
    public void tickAll(Collection<Maid> maids) {
        tickCounter++;
        for (Maid maid : maids) {
            tick(maid, tickCounter);
        }
    }

    public void tick(Maid maid, int tick) {
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        // 区块未加载时导航/视线都不可靠，直接跳过这一 tick
        Location loc = entity.getLocation();
        if (!entity.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }

        if (maid.isSitting()) {
            // 坐下是跨模式的硬拦截：任何模式都不许移动，只让待机逻辑做点小动作
            Movement.stop(maid);
            animator.apply(maid, ModelAnimator.Pose.SIT);
            idleTick(maid, tick);
            return;
        }

        BehaviorMode mode = modes.get(maid.getMode());
        if (mode == null) {
            // 蓝图 P5-P7 的模式还没实现，先按跟随跑，免得切过去就变成木头人
            mode = modes.get(Maid.Mode.FOLLOW);
        }
        mode.tick(maid, tick);
        animator.apply(maid, Movement.isNavigating(maid) ? ModelAnimator.Pose.WALK : ModelAnimator.Pose.STAND);
    }

    /** 女仆被移除时清掉各模式与动画记录的 per-maid 状态。 */
    public void forget(Maid maid) {
        animator.forget(maid.getId());
        for (BehaviorMode mode : modes.values()) {
            mode.onDisable(maid);
        }
    }

    private void idleTick(Maid maid, int tick) {
        BehaviorMode idle = modes.get(Maid.Mode.IDLE);
        if (idle != null) {
            idle.tick(maid, tick);
        }
    }
}
