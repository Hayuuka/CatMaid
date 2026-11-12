package dev.catmaid.listener;

import dev.catmaid.data.MaidRepository;
import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.AffectionReason;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.event.MaidDeathEvent;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.util.MaidItems;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 女仆受击与死亡：受伤台词、好感度扣减、倒下转铃铛（蓝图 P4）。
 *
 * <p>女仆是**玩家型 NPC**（真实 ServerPlayer），死亡链路和普通生物不同：
 * Citizens 在实体死亡时会自行 despawn（实体移除、spawned=false），NPC 注册与 npcId 都保留。
 * 所以这里只负责"业务上的死亡"，不碰 despawn（api-facts 附录 A / 17.8）。
 *
 * <p>死亡事件同时挂在 {@link EntityDeathEvent} 与 {@link PlayerDeathEvent} 上：后者在 Bukkit 里
 * 自带独立 HandlerList，注册前者收不到玩家死亡。玩家型 NPC 到底走哪条要实测，见 api-facts P4 章节。
 * 两条路都进来也无所谓——{@link #handleDeath} 用 {@code maid.isAlive()} 做幂等。
 */
public final class MaidDamageListener implements Listener {

    /** 受伤台词与好感扣减的冷却都来自 config（dialogue.hurt-cooldown-seconds / affection.hurt-cooldown-seconds）。 */
    private final Plugin plugin;
    private final MaidManager maidManager;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    private final MaidRepository repository;

    private final Map<UUID, Long> hurtDialogueAt = new HashMap<>();
    private final Map<UUID, Long> hurtAffectionAt = new HashMap<>();

    public MaidDamageListener(Plugin plugin, MaidManager maidManager, AffectionManager affection,
                              DialogueManager dialogue, MaidRepository repository) {
        this.plugin = plugin;
        this.maidManager = maidManager;
        this.affection = affection;
        this.dialogue = dialogue;
        this.repository = repository;
    }

    /**
     * 受伤。顺带做死亡兜底：血扣到 0 就先按死亡处理，不等死亡事件
     * （玩家型 NPC 的死亡事件是否派发未实测，两条路都留着最稳）。
     */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        Maid maid = maidOf(entity);
        if (maid == null || !maid.isAlive()) {
            return;
        }
        if (entity.getHealth() - event.getFinalDamage() <= 0) {
            handleDeath(maid, entity.getLocation(), event);
            return;
        }
        onHurt(maid);
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Maid maid = maidOf(event.getEntity());
        if (maid == null) {
            return;
        }
        // 她的一切都在虚拟背包与 SQLite 里，掉出来等于凭空复制一份
        event.getDrops().clear();
        event.setDroppedExp(0);
        handleDeath(maid, event.getEntity().getLocation(), event.getEntity().getLastDamageCause());
    }

    /** 玩家型 NPC 的死亡会走玩家死亡事件，它的 HandlerList 与 EntityDeathEvent 是分开的。 */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Maid maid = maidOf(event.getEntity());
        if (maid == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        handleDeath(maid, event.getEntity().getLocation(), event.getEntity().getLastDamageCause());
    }

    private void onHurt(Maid maid) {
        long now = System.currentTimeMillis();
        // 战斗模式下挨打是履行职责，不扣好感（蓝图窗口 P7 裁决）：否则带她打怪时每次挨打 -0.5、
        // 击杀才 +0.2，战斗模式等于惩罚模式。非战斗模式照旧扣——主人让她白白挨打，心疼是设计意图。
        if (maid.getMode() != Maid.Mode.COMBAT) {
            Long lastAffection = hurtAffectionAt.get(maid.getId());
            if (lastAffection == null || now - lastAffection >= hurtAffectionCooldownMs()) {
                hurtAffectionAt.put(maid.getId(), now);
                affection.apply(maid, -affection.loss(AffectionReason.HURT), AffectionReason.HURT);
            }
        }
        Long lastDialogue = hurtDialogueAt.get(maid.getId());
        if (lastDialogue == null || now - lastDialogue >= hurtDialogueCooldownMs()) {
            hurtDialogueAt.put(maid.getId(), now);
            dialogue.send(maid, "on_hurt");
        }
    }

    /**
     * 女仆打出去的伤害：按好感度等级加成（蓝图 P7「好感 60+ 打得痛 +15%」）。
     *
     * <p>放在这个类是因为这里已经有可靠的"实体 → 女仆"反查（含 despawn 兜底）。
     * 箭也要算：damager 是 Arrow 时顺着 shooter 找回她——不然拿弓的女仆永远吃不到加成。
     */
    @EventHandler(ignoreCancelled = true)
    public void onMaidDealDamage(EntityDamageByEntityEvent event) {
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            attacker = shooter;
        }
        Maid maid = maidOf(attacker);
        if (maid == null) {
            return;
        }
        double bonus = affection.damageBonus(affection.level(maid.getAffection()));
        if (bonus <= 0) {
            return;
        }
        event.setDamage(event.getDamage() * (1 + bonus));
    }

    /** 好感扣减冷却：蓝图没提多段伤害，火焰/岩浆每 10 tick 一跳，不节流会把好感放干（见类注释）。 */
    private long hurtAffectionCooldownMs() {
        return Math.max(0, plugin.getConfig().getLong("affection.hurt-cooldown-seconds", 5)) * 1000L;
    }

    /** 受伤台词冷却。 */
    private long hurtDialogueCooldownMs() {
        return Math.max(0, plugin.getConfig().getLong("dialogue.hurt-cooldown-seconds", 30)) * 1000L;
    }

    private void handleDeath(Maid maid, Location deathLoc, EntityDamageEvent cause) {
        if (!maid.isAlive()) {
            return; // 已处理过（伤害兜底与死亡事件都触发时）
        }
        maid.setAlive(false);
        maid.setSitting(false);
        Location loc = deathLoc == null ? null : deathLoc.clone();
        Entity killer = cause instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;

        Bukkit.getPluginManager().callEvent(new MaidDeathEvent(maid, loc, killer));
        // 死亡扣好感排在事件之后：外部插件取消不了死亡，但至少要能看到"扣之前的数值"
        affection.apply(maid, -affection.deathLoss(maid), AffectionReason.DEATH);
        giveBell(maid, loc);
        dialogue.send(maid, "on_death");
        repository.save(maid);

        hurtDialogueAt.remove(maid.getId());
        hurtAffectionAt.remove(maid.getId());
        playDeathEffect(loc);
        // 原版死亡结算结束后再收一次实体：Citizens 通常自己会 despawn，但它也会把"已生成"状态
        // 写进存档，重启后又把身体恢复出来（见 MaidManager#despawnDeadNpcs）。这里晚 2 tick 兜底，
        // 避免在死亡结算过程中动实体。
        NPC deathNpc = maid.npc();
        if (deathNpc != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (deathNpc.isSpawned()) {
                    deathNpc.despawn();
                }
            }, 2L);
        }
        // 死亡是低频但关键的事件，日志留着便于事后核对（好感度、位置、铃铛去向）
        plugin.getLogger().info(maid.getName() + " 倒下了（剩余好感度 "
                + String.format(java.util.Locale.ROOT, "%.1f", maid.getAffection()) + "，位置 " + loc + "）");
    }

    /** 铃铛优先塞主人背包，放不下才掉在原地（蓝图 P4）。 */
    private void giveBell(Maid maid, Location deathLoc) {
        ItemStack bell = MaidItems.bell(plugin, maid);
        Player owner = Bukkit.getPlayer(maid.getOwnerUuid());
        if (owner == null) {
            plugin.getLogger().info("主人不在线，" + maid.getName() + " 的铃铛掉落在 " + deathLoc);
            drop(deathLoc, bell);
            return;
        }
        Map<Integer, ItemStack> leftover = owner.getInventory().addItem(bell);
        for (ItemStack rest : leftover.values()) {
            plugin.getLogger().info(owner.getName() + " 背包已满，" + maid.getName() + " 的铃铛掉落在 " + deathLoc);
            drop(deathLoc, rest);
        }
        Messages.send(owner, "bell-received", Map.of("name", maid.getName()));
    }

    private void drop(Location loc, ItemStack item) {
        if (loc == null || loc.getWorld() == null) {
            Bukkit.getLogger().warning("[CatMaid] 铃铛无处掉落（死亡位置为空），女仆数据仍在库里，可用管理员命令恢复");
            return;
        }
        // 掉在地上的铃铛必须烧不掉、炸不掉、也不会到点消失：
        // 它承载着玩家的整只女仆，死在岩浆/火里时铃铛跟着没掉就等于永久丢人。
        // 实测：不设 unlimitedLifetime 的话，主人不在线时铃铛 5 分钟就被原版清理掉（api-facts 18.3-3）。
        org.bukkit.entity.Item dropped = loc.getWorld().dropItemNaturally(loc, item);
        dropped.setInvulnerable(true);
        dropped.setUnlimitedLifetime(true);
        dropped.setGlowing(plugin.getConfig().getBoolean("death.bell-glowing", true));
    }

    private void playDeathEffect(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return;
        }
        World world = loc.getWorld();
        world.spawnParticle(org.bukkit.Particle.SMOKE, loc.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.02);
        world.playSound(loc, org.bukkit.Sound.ENTITY_CAT_DEATH, 0.8f, 1.0f);
    }

    /**
     * 事件实体 → 女仆。先走 Citizens 的快速路径；NPC 已被 despawn（死亡后就是这么个状态）
     * 导致 getNPC 认不出来时，按 minecraft UUID 反查——它比 getEntity() 稳定，despawn 后仍然有效。
     */
    private Maid maidOf(Entity entity) {
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
        if (npc != null) {
            return maidManager.byNpcId(npc.getId()).orElse(null);
        }
        UUID entityId = entity.getUniqueId();
        for (Maid maid : maidManager.all()) {
            if (maid.getNpcId() < 0) {
                continue;
            }
            NPC registered = CitizensAPI.getNPCRegistry().getById(maid.getNpcId());
            if (registered != null && entityId.equals(registered.getMinecraftUniqueId())) {
                return maid;
            }
        }
        return null;
    }
}
