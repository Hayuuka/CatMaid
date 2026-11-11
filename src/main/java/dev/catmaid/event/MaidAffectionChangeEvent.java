package dev.catmaid.event;

import dev.catmaid.dialogue.AffectionReason;
import dev.catmaid.maid.Maid;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 好感度即将变动时触发，可取消（蓝图 P4）。
 *
 * <p>newValue 是可写的：监听方可以往上加/往下压（比如活动期间双倍好感），
 * 落地前 {@code AffectionManager} 还会再 clamp 一次 0..100。
 */
public final class MaidAffectionChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Maid maid;
    private final double oldValue;
    private final AffectionReason reason;
    private double newValue;
    private boolean cancelled;

    public MaidAffectionChangeEvent(Maid maid, double oldValue, double newValue, AffectionReason reason) {
        this.maid = maid;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = reason;
    }

    public Maid getMaid() {
        return maid;
    }

    public double getOldValue() {
        return oldValue;
    }

    public double getNewValue() {
        return newValue;
    }

    public void setNewValue(double newValue) {
        this.newValue = newValue;
    }

    public AffectionReason getReason() {
        return reason;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
