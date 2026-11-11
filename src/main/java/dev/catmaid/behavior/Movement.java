package dev.catmaid.behavior;

import dev.catmaid.maid.Maid;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 行为层共用的移动原语（蓝图 Phase 3「通用工具」）。
 *
 * <p>速度用 Citizens 的 {@code NavigatorParameters#baseSpeed(float)}：数值是原版移动速度的倍率，
 * 1.0 差不多是玩家步行，1.15 才能跟上走路的玩家（api-facts 3.2）。
 */
public final class Movement {

    private Movement() {
    }

    /** 朝 loc 走。重复调用同一个目标会重算路径，调用方要自己判断是否需要重新下发。 */
    public static void navigate(Maid maid, Location loc, float speed) {
        Navigator navigator = navigator(maid);
        if (navigator == null || loc.getWorld() == null) {
            return;
        }
        navigator.getLocalParameters().baseSpeed(speed);
        navigator.setTarget(loc);
    }

    /** 停下；本来就没在走时什么都不做，可以放心每 tick 调。 */
    public static void stop(Maid maid) {
        Navigator navigator = navigator(maid);
        if (navigator != null && navigator.isNavigating()) {
            navigator.cancelNavigation();
        }
    }

    public static boolean isNavigating(Maid maid) {
        Navigator navigator = navigator(maid);
        return navigator != null && navigator.isNavigating();
    }

    /** 走传送而不是走导航：跨世界、距离过远时用。 */
    public static void teleport(Maid maid, Location loc) {
        NPC npc = maid.npc();
        if (npc == null || loc.getWorld() == null) {
            return;
        }
        stop(maid);
        npc.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    public static boolean arrived(LivingEntity entity, Location loc, double distance) {
        if (!entity.getWorld().equals(loc.getWorld())) {
            return false;
        }
        return entity.getLocation().distanceSquared(loc) <= distance * distance;
    }

    /** NPC 未生成时导航不可用 —— 这是真实边界，统一在这里兜住，调用方不必再判空（agent.md 5.6）。 */
    private static Navigator navigator(Maid maid) {
        NPC npc = maid.npc();
        if (npc == null || !npc.isSpawned()) {
            return null;
        }
        return npc.getNavigator();
    }
}
