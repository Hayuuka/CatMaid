package dev.catmaid.event;

import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 召唤女仆前触发。取消后不会创建 NPC，也不会落库。
 *
 * <p>owner 用 OfflinePlayer：管理员可以给离线玩家召唤（Phase 1 验收需要控制台驱动）。
 */
public final class MaidSummonEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final OfflinePlayer owner;
    private final String modelId;
    private final Location spawnLoc;
    private boolean cancelled;

    public MaidSummonEvent(OfflinePlayer owner, String modelId, Location spawnLoc) {
        this.owner = owner;
        this.modelId = modelId;
        this.spawnLoc = spawnLoc.clone();
    }

    public OfflinePlayer getOwner() {
        return owner;
    }

    public String getModelId() {
        return modelId;
    }

    public Location getSpawnLoc() {
        return spawnLoc.clone();
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
