package dev.catmaid.listener;

import dev.catmaid.behavior.BehaviorManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;

/**
 * 主人保护：主人被打 → 她名下的战斗模式女仆立刻锁定攻击者；她自己被打 → 就地反击（蓝图 P7）。
 *
 * <p>本类只做"把目标写进战斗模式"这一件事，追击/出手/脱战全在 {@code CombatMode} 里
 * （agent.md 5.3：监听器只做事件转发）。
 *
 * <p>为什么单独一个监听器而不是塞进 {@code MaidDamageListener}：那个类管"她的血量与死亡"，
 * 这个类管"她该打谁"，两者的触发条件与失效条件都不一样。
 */
public final class OwnerProtectionListener implements Listener {

    private final Plugin plugin;
    private final MaidManager maidManager;
    private final BehaviorManager behavior;

    public OwnerProtectionListener(Plugin plugin, MaidManager maidManager, BehaviorManager behavior) {
        this.plugin = plugin;
        this.maidManager = maidManager;
        this.behavior = behavior;
    }

    /** 主人被打：她名下所有战斗模式的女仆一起锁定攻击者（蓝图"群起反击"）。 */
    @EventHandler(ignoreCancelled = true)
    public void onOwnerHurt(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("combat.protect-owner", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Player owner)) {
            return;
        }
        Entity attacker = attackerOf(event);
        if (!(attacker instanceof LivingEntity living)) {
            return;
        }
        for (Maid maid : maidManager.byOwner(owner.getUniqueId())) {
            if (!maid.isAlive()) {
                continue; // 铃铛态没有实体，lockCombatTarget 里也会因为取不到实体而退出
            }
            behavior.lockCombatTarget(maid, living);
        }
    }

    /** 女仆被打：战斗模式下就地反击。非战斗模式不还手——她正在种田，不该突然拔剑。 */
    @EventHandler(ignoreCancelled = true)
    public void onMaidHurt(EntityDamageByEntityEvent event) {
        Maid maid = maidOf(event.getEntity());
        if (maid == null || !maid.isAlive()) {
            return;
        }
        Entity attacker = attackerOf(event);
        if (attacker instanceof LivingEntity living) {
            behavior.lockCombatTarget(maid, living);
        }
    }

    /**
     * 伤害来源的那个"人"。
     *
     * <p>弓箭/投掷物的 damager 是弹射物本身，要顺着 shooter 找回射手——不然骷髅射主人时
     * 女仆会对着半空中的箭发呆。
     */
    private static Entity attackerOf(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    /** 事件实体 → 女仆。这里只用 Citizens 的注册表：挨打时她一定还活着、一定在注册表里。 */
    private Maid maidOf(Entity entity) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
        return npc == null ? null : maidManager.byNpcId(npc.getId()).orElse(null);
    }
}
