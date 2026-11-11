package dev.catmaid.behavior.mode;

import dev.catmaid.behavior.BehaviorMode;
import dev.catmaid.behavior.Movement;
import dev.catmaid.maid.Maid;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 跟随（默认模式）。距离带是三段式：
 * <pre>
 *   &lt; stop-dist         停下面向主人
 *   stop-dist ~ start-dist  缓冲带，什么都不做（防止在阈值上反复起步/刹车）
 *   &gt; start-dist        追
 *   &gt; teleport-dist     直接传送到主人身边
 *   跨世界              等 3 秒再传送（主人穿传送门/上下线切换世界时不要跟着抽风）
 * </pre>
 *
 * <p>每 5 tick 判断一次，并按 npcId 错峰（{@code tick % 5 == npcId % 5}）。
 */
public final class FollowMode implements BehaviorMode {

    private static final int CHECK_INTERVAL = 5;
    private static final long CROSS_WORLD_DELAY_MS = 3000L;
    /** 在追但 8 秒没挪动 0.5 格 → 认定卡住，放弃这轮导航（agent.md 7.2）。 */
    private static final long STUCK_MS = 8000L;
    private static final double STUCK_DISTANCE_SQ = 0.25;
    /** 主人挪动超过这么多格就重算路径，避免每 5 tick 都打断一次寻路。 */
    private static final double RETARGET_DISTANCE = 2.0;

    private final FileConfiguration config;
    private final IdleMode idle;

    private final Map<UUID, Long> crossWorldSince = new HashMap<>();
    private final Map<UUID, MoveState> moves = new HashMap<>();

    public FollowMode(FileConfiguration config, IdleMode idle) {
        this.config = config;
        this.idle = idle;
    }

    private static final class MoveState {
        private Location target;
        private Location lastPos;
        private long lastProgressAt;
    }

    @Override
    public Maid.Mode type() {
        return Maid.Mode.FOLLOW;
    }

    @Override
    public void onDisable(Maid maid) {
        crossWorldSince.remove(maid.getId());
        moves.remove(maid.getId());
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

        Player owner = Bukkit.getPlayer(maid.getOwnerUuid());
        if (owner == null) {
            // 主人不在线：把女仆交回给待机逻辑，在 home 附近消磨时间
            crossWorldSince.remove(maid.getId());
            moves.remove(maid.getId());
            idle.tick(maid, tick);
            return;
        }

        Location ownerLoc = owner.getLocation();
        if (!entity.getWorld().equals(ownerLoc.getWorld())) {
            followAcrossWorlds(maid, ownerLoc);
            return;
        }
        crossWorldSince.remove(maid.getId());

        double distance = entity.getLocation().distance(ownerLoc);
        if (distance > config.getDouble("follow.teleport-dist", 40)) {
            Movement.teleport(maid, besideOwner(owner));
            moves.remove(maid.getId());
            return;
        }
        if (distance <= config.getDouble("follow.stop-dist", 2.5)) {
            Movement.stop(maid);
            moves.remove(maid.getId());
            face(maid, ownerLoc);
            return;
        }
        if (distance > config.getDouble("follow.start-dist", 6)) {
            pursue(maid, entity, ownerLoc);
        }
        // stop-dist ~ start-dist 之间保持现状：已经在追就继续追，已经停下就继续站着
    }

    private void followAcrossWorlds(Maid maid, Location ownerLoc) {
        long now = System.currentTimeMillis();
        Long since = crossWorldSince.get(maid.getId());
        if (since == null) {
            crossWorldSince.put(maid.getId(), now);
            return;
        }
        if (now - since < CROSS_WORLD_DELAY_MS) {
            return;
        }
        crossWorldSince.remove(maid.getId());
        moves.remove(maid.getId());
        Movement.teleport(maid, ownerLoc);
    }

    private void pursue(Maid maid, LivingEntity entity, Location ownerLoc) {
        MoveState state = moves.computeIfAbsent(maid.getId(), key -> new MoveState());
        boolean navigating = Movement.isNavigating(maid);
        boolean needRetarget = !navigating
                || state.target == null
                || !state.target.getWorld().equals(ownerLoc.getWorld())
                || state.target.distanceSquared(ownerLoc) > RETARGET_DISTANCE * RETARGET_DISTANCE;
        if (needRetarget) {
            Movement.navigate(maid, ownerLoc, (float) config.getDouble("follow.speed", 1.15));
            state.target = ownerLoc.clone();
            state.lastPos = entity.getLocation();
            state.lastProgressAt = System.currentTimeMillis();
            return;
        }
        checkStuck(maid, entity, state);
    }

    private void checkStuck(Maid maid, LivingEntity entity, MoveState state) {
        Location now = entity.getLocation();
        if (state.lastPos == null || state.lastPos.distanceSquared(now) > STUCK_DISTANCE_SQ) {
            state.lastPos = now;
            state.lastProgressAt = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - state.lastProgressAt < STUCK_MS) {
            return;
        }
        // 卡住了（挡墙、栅栏、流水）：这轮放弃，下一轮会重新寻路；距离真远了还有传送兜底
        Movement.stop(maid);
        state.target = null;
        state.lastProgressAt = System.currentTimeMillis();
    }

    private void face(Maid maid, Location target) {
        NPC npc = maid.npc();
        if (npc != null && npc.isSpawned()) {
            npc.faceLocation(target);
        }
    }

    /** 传送到主人身后一格，直接落在主人脚下会和她重叠。 */
    private Location besideOwner(Player owner) {
        Location base = owner.getLocation().clone();
        double yaw = Math.toRadians(base.getYaw());
        // MC 里 yaw=0 面向 +Z，朝向向量是 (-sin, 0, cos)，所以「身后」是 (sin, 0, -cos)
        base.add(Math.sin(yaw), 0, -Math.cos(yaw));
        base.setYaw(base.getYaw() + 180f);
        base.setPitch(0f);
        return base;
    }
}
