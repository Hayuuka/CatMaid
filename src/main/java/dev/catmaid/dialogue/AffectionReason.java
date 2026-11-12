package dev.catmaid.dialogue;

/**
 * 好感度变动的原因（蓝图 P4）。数值在 config.yml 的 {@code affection.gain/loss} 下按名字配置，
 * 代码里只认 this 到配置键的映射，不写死数字。
 *
 * <p>{@link #IDLE_LONG} 目前没有触发点：蓝图只在枚举里要求它，触发点表里没有对应条目。
 * 保留它是为了 P5-P7 做「长时间没活干」的衰减时有现成的 reason，不必再改这个枚举。
 *
 * <p>{@link #ADMIN} 是 P8 的 {@code /catmaid admin setaffection} 专用：数值由命令直接给定，
 * 不走 {@code affection.gain/loss}，因此在 AffectionManager 的映射表里对应 {@code null}。
 * 加枚举值时必须同步那个 switch（它是穷尽式 switch，漏了会编译失败——这是故意的）。
 */
public enum AffectionReason {
    FEED,
    WORK_HARVEST,
    WORK_BREED,
    WORK_KILL,
    FOLLOW_TICK,
    HURT,
    DEATH,
    REVIVE,
    IDLE_LONG,
    ADMIN
}
