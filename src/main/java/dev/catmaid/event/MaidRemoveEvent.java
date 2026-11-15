package dev.catmaid.event;

import dev.catmaid.maid.Maid;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 女仆被彻底移除（admin remove / 自助送走）时触发，不可取消——NPC 与 DB 记录已在删除流程中。
 *
 * <p>蓝图 0.5 预留的事件。领域层已有的 forget（行为/好感/台词）由 {@code MaidManager#remove}
 * 直接调用；各处按 maidId 记的零散缓存（受击冷却、喂食冷却、送走待确认、可见性对账集）
 * 没有别的清理时机，监听本事件统一回收（issue #5）。
 */
public final class MaidRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Maid maid;

    public MaidRemoveEvent(Maid maid) {
        this.maid = maid;
    }

    public Maid getMaid() {
        return maid;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
