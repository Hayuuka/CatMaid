package dev.catmaid.listener;

import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.MaidTrait;
import dev.catmaid.maid.MaidVisibilityManager;
import dev.catmaid.util.MaidItems;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** 上线补挂 NPC、发开局契约、下线保存。 */
public final class PlayerConnectionListener implements Listener {

    /**
     * 已发过开局契约的标记。
     *
     * <p>不用 {@code hasPlayedBefore()}：这个值在 {@code PlayerJoinEvent} 里已经是 true（玩家数据已落盘），
     * 拿它判断"新玩家"会一个都发不出去。
     */
    private static final String KEY_INIT_GIVEN = "init_talisman_given";

    private final Plugin plugin;
    private final MaidManager maidManager;
    private final MaidItems items;
    private final MaidVisibilityManager visibility;

    public PlayerConnectionListener(Plugin plugin, MaidManager maidManager, MaidItems items,
                                    MaidVisibilityManager visibility) {
        this.plugin = plugin;
        this.maidManager = maidManager;
        this.items = items;
        this.visibility = visibility;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        grantInitContract(event.getPlayer());
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
        // P11：hideEntity 状态不跨 relog（Paper 不持久化），opt-out 玩家进服要重新施加
        if (visibility.isOptedOut(event.getPlayer().getUniqueId())) {
            visibility.applyAllFor(event.getPlayer());
        }
    }

    /**
     * 开局赠送一张<b>未唤醒</b>的魂符（对应 TLM「进入世界的新玩家会获得一个魂符」）。
     *
     * <p>为什么必须有这个口：空魂符只负责收纳，能召唤的是"未唤醒"那一态；没有它新玩家
     * 永远招不出第一只女仆。送出的那张写了 `soul_init_owner`，转手给别人也放不出来。
     *
     * <p>用 PDC 标记去重：老玩家升级后也会收到一次（相当于补偿），但绝不会重复发。
     */
    private void grantInitContract(Player player) {
        if (!plugin.getConfig().getBoolean("interaction.grant-init-talisman-on-first-join", true)) {
            return;
        }
        NamespacedKey key = new NamespacedKey(plugin, KEY_INIT_GIVEN);
        var pdc = player.getPersistentDataContainer();
        if (pdc.has(key, PersistentDataType.BYTE)) {
            return;
        }
        pdc.set(key, PersistentDataType.BYTE, (byte) 1);
        player.getInventory().addItem(items.initTalisman(player.getUniqueId())).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        Messages.send(player, "init-talisman-given");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (Maid maid : maidManager.byOwner(event.getPlayer().getUniqueId())) {
            maidManager.saveAsync(maid);
        }
        // P11：hideEntity 随连接销毁，对账集必须同步清，否则重进后"已在账"跳过重新施加
        visibility.handleQuit(event.getPlayer());
    }
}
