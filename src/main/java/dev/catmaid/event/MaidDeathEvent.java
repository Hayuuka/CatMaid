package dev.catmaid.event;

import dev.catmaid.maid.Maid;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 女仆倒下（转为铃铛）时触发，不可取消——原版死亡已经发生，能取消死亡的只有伤害阶段的插件。
 *
 * <p>触发时 {@code maid.isAlive()} 已是 false、好感度已按 DEATH 扣完，铃铛也已发到主人手里
 * （见 {@code MaidDamageListener#handleDeath}），留给外部插件的是"事后通知"语义。
 */
public final class MaidDeathEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Maid maid;
    private final Location deathLoc;
    /** 致死来源的实体；摔落/岩浆这类没有直接凶手时为 null。 */
    private final Entity killer;

    public MaidDeathEvent(Maid maid, Location deathLoc, Entity killer) {
        this.maid = maid;
        this.deathLoc = deathLoc == null ? null : deathLoc.clone();
        this.killer = killer;
    }

    public Maid getMaid() {
        return maid;
    }

    public Location getDeathLoc() {
        return deathLoc == null ? null : deathLoc.clone();
    }

    public Entity getKiller() {
        return killer;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
