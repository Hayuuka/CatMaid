package dev.catmaid.maid;

import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 由插件驱动的模型动作动画。和 {@link ModelApplier} 一样走反射，原因见那个类的注释。
 *
 * <p>为什么必须有这个类：玩家型 NPC 的 {@code isWalking} 恒为 false
 * （api-facts 3.5，BetterModel 只看玩家的移动输入），所以女仆走起来时模型会整体平移、四肢不动。
 * 唯一解是导航开始/结束时手动 {@code animate("walk")} / {@code stopAnimation("walk")}。
 *
 * <p>调用点是 {@code BehaviorManager}：它每 tick 看一眼导航状态，把结果映射成 {@link Pose} 交给这里。
 * 这样 P5-P7 的农活/放牧/战斗都不必各自记得播动画。
 */
public final class ModelAnimator {

    /** 插件能主动摆出的三种姿态；其余动画（受击、死亡）由 BetterModel 自己的触发器管。 */
    public enum Pose {
        STAND, WALK, SIT
    }

    private static final String BETTER_MODEL_API = "kr.toxicity.model.api.BetterModel";
    private static final String MODIFIER = "kr.toxicity.model.api.animation.AnimationModifier";
    private static final String ITERATOR_TYPE = "kr.toxicity.model.api.animation.AnimationIterator$Type";

    /**
     * 续播间隔。实测玩家型 NPC 的 tracker 上单次 animate 播完就停、stopAnimation 也不一定把骨骼
     * 拉回 bind pose（见 api-facts 17.9），所以持续型姿态要周期性重播一次。
     * walk 取 1.05 秒：动画本身 1 秒一循环、首尾同帧，重播几乎无缝。
     */
    private static final long WALK_REPLAY_MS = 1050L;
    private static final long SIT_REPLAY_MS = 5000L;

    /** 姿态跟着实体走：女仆重生成后实体 UUID 变了，旧状态自动失效，动画会重新播。 */
    private record State(UUID entityUuid, Pose pose, long playedAt) {
    }

    private final Logger logger;
    private final Map<UUID, State> states = new HashMap<>();

    private boolean resolved;
    private boolean disabled;
    private boolean warned;

    private Method registryOrNull;
    private Method animate;
    private Method stopAnimation;
    private Object loopModifier;

    public ModelAnimator(Logger logger) {
        this.logger = logger;
    }

    /**
     * 切换到指定姿态。姿态没变、且还没到续播时间就什么都不做，可以每 tick 调。
     */
    public void apply(Maid maid, Pose pose) {
        if (disabled) {
            return;
        }
        LivingEntity entity = maid.entity();
        if (entity == null) {
            // 实体不在（未生成/区块卸载）就别记状态，等它回来后按当时的姿态补上
            return;
        }
        UUID entityUuid = entity.getUniqueId();
        State current = states.get(maid.getId());
        long now = System.currentTimeMillis();
        if (current != null && current.pose() == pose && current.entityUuid().equals(entityUuid)
                && now - current.playedAt() < replayInterval(pose)) {
            return;
        }
        Object tracker = tracker(entity);
        if (tracker == null) {
            return;
        }
        switch (pose) {
            case STAND -> {
                // 光 stop 不够：sit 的骨骼姿势会留在模型上（实测站起来还是坐姿），
                // 必须再播一次 idle 把这些骨头显式拉回 bind pose
                stop(tracker, "walk");
                stop(tracker, "sit");
                play(tracker, "idle");
            }
            case WALK -> {
                stop(tracker, "sit");
                play(tracker, "walk");
            }
            case SIT -> {
                stop(tracker, "walk");
                play(tracker, "sit");
            }
        }
        states.put(maid.getId(), new State(entityUuid, pose, now));
    }

    /** 站姿是单帧动画，重播没有意义；持续型姿态按各自的间隔续播。 */
    private static long replayInterval(Pose pose) {
        return switch (pose) {
            case STAND -> Long.MAX_VALUE;
            case WALK -> WALK_REPLAY_MS;
            case SIT -> SIT_REPLAY_MS;
        };
    }

    /** 女仆被移除时清掉记录，避免 states 无限增长。 */
    public void forget(UUID maidId) {
        states.remove(maidId);
    }

    private Object tracker(LivingEntity entity) {
        resolve();
        if (!resolved) {
            return null;
        }
        try {
            Object registry = registryOrNull.invoke(null, entity.getUniqueId());
            if (registry == null) {
                return null;
            }
            return registry.getClass().getMethod("first").invoke(registry);
        } catch (ReflectiveOperationException | LinkageError e) {
            fail("获取女仆模型 tracker 失败", e);
            return null;
        }
    }

    private void play(Object tracker, String animation) {
        try {
            animate.invoke(tracker, animation, loopModifier);
        } catch (ReflectiveOperationException | LinkageError e) {
            fail("播放动画 " + animation + " 失败", e);
        }
    }

    private void stop(Object tracker, String animation) {
        try {
            stopAnimation.invoke(tracker, animation);
        } catch (ReflectiveOperationException | LinkageError e) {
            fail("停止动画 " + animation + " 失败", e);
        }
    }

    /**
     * 一次性取好反射句柄。Modifier 用 LOOP 而不是 {@code animate(String)} 的默认值：
     * 默认值不指定循环方式，走路动画放一遍就停会让"走起来四肢不动"换个形式再出现。
     */
    private void resolve() {
        if (resolved || disabled) {
            return;
        }
        Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null) {
            disabled = true;
            warn("BetterModel 未加载，女仆不会有行走/坐下动画");
            return;
        }
        try {
            ClassLoader loader = betterModel.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(BETTER_MODEL_API, true, loader);
            Class<?> modifierClass = Class.forName(MODIFIER, true, loader);
            Class<?> typeClass = Class.forName(ITERATOR_TYPE, true, loader);

            registryOrNull = apiClass.getMethod("registryOrNull", UUID.class);

            Object builder = modifierClass.getMethod("builder").invoke(null);
            Object loop = typeClass.getField("LOOP").get(null);
            builder.getClass().getMethod("type", typeClass).invoke(builder, loop);
            loopModifier = builder.getClass().getMethod("build").invoke(builder);

            Class<?> trackerClass = Class.forName("kr.toxicity.model.api.tracker.Tracker", true, loader);
            animate = trackerClass.getMethod("animate", String.class, modifierClass);
            stopAnimation = trackerClass.getMethod("stopAnimation", String.class);

            resolved = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            disabled = true;
            warn("BetterModel 动画 API 与预期不符，已放弃驱动动画：" + e);
        }
    }

    private void fail(String what, Throwable cause) {
        if (!warned) {
            warned = true;
            logger.log(Level.WARNING, "[CatMaid] " + what + "（后续同类错误不再重复打印）", cause);
        }
    }

    private void warn(String message) {
        if (!warned) {
            warned = true;
            logger.warning("[CatMaid] " + message);
        }
    }
}
