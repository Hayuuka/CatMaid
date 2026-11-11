package dev.catmaid.listener;

import dev.catmaid.gui.GuiManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * 右键女仆 → 打开主菜单。
 *
 * <p>玩家型 NPC 的交互走 Citizens 的 {@link NPCRightClickEvent}，不是
 * {@code PlayerInteractEntityEvent}（api-facts 3.2 / 15.5-1），方法名是 {@code getClicker()}。
 *
 * <p>开界面延迟 1 tick：Citizens 在交互包的处理过程中同步开容器，客户端偶尔会立刻关掉。
 */
public final class MaidInteractListener implements Listener {

    private final MaidManager maidManager;
    private final GuiManager guiManager;
    private final Plugin plugin;

    public MaidInteractListener(MaidManager maidManager, GuiManager guiManager, Plugin plugin) {
        this.maidManager = maidManager;
        this.guiManager = guiManager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        // 先按 NPC id 反查：没有任何 Maid 认领的 NPC（Citizens 自建、别的插件建的）直接放行
        Maid maid = maidManager.byNpcId(event.getNPC().getId()).orElse(null);
        if (maid == null || !maid.isAlive()) {
            return;
        }
        Player player = event.getClicker();
        if (!player.getUniqueId().equals(maid.getOwnerUuid())) {
            Messages.send(player, "not-your-maid");
            return;
        }
        // TODO(P4): 主人手持 config.interaction.feedable 内的食物时改为喂食，不开菜单
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                guiManager.openMain(player, maid);
            }
        });
    }
}
