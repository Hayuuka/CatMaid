package dev.catmaid.behavior;

import dev.catmaid.maid.Maid;

/**
 * 一种行为模式（蓝图 Phase 3）。
 *
 * <p>{@link #tick} 每个服务器 tick 都会被调用一次，而且每只女仆各自被调用，
 * 所以节流和错峰必须由实现自己负责（见各 Mode 里的 {@code staggered} / {@code nextAt}）。
 * 全局的空值、区块守卫统一放在 {@link BehaviorManager}，实现里不要重复。
 */
public interface BehaviorMode {

    Maid.Mode type();

    default void onEnable(Maid maid) {
    }

    default void onDisable(Maid maid) {
    }

    void tick(Maid maid, int tick);
}
