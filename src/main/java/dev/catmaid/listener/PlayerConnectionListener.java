package dev.catmaid.listener;

import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.MaidTrait;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 上线补挂 NPC、下线保存。 */
public final class PlayerConnectionListener implements Listener {

    private final MaidManager maidManager;

    public PlayerConnectionListener(MaidManager maidManager) {
        this.maidManager = maidManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (Maid maid : maidManager.byOwner(event.getPlayer().getUniqueId())) {
            if (!maid.isAlive() || maid.isStored()) {
                continue; // 铃铛态没有实体；收纳态在魂符里，不该上线就冒出来
            }
            NPC npc = maid.npc();
            if (npc == null || npc.isSpawned()) {
                continue; // 已被 restoreNpcs 恢复或已在场
            }
            Location target = maid.getHome() != null ? maid.getHome() : event.getPlayer().getLocation();
            if (target.getWorld() == null) {
                continue;
            }
            npc.getOrAddTrait(MaidTrait.class).bind(maid);
            npc.spawn(target);
            maidManager.onEntityReady(maid);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Maid maid : maidManager.byOwner(event.getPlayer().getUniqueId())) {
            maidManager.saveAsync(maid);
        }
    }
}
