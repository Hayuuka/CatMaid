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

    /** 受伤台词冷却：她不可能每挨一下就喊一句。 */
    private static final long HURT_DIALOGUE_COOLDOWN_MS = 30_000L;
    /**
     * 好感度扣减的冷却。蓝图写的是"受击 -0.5"，但没提多段伤害——火焰每 10 tick 跳一次、
     * 岩浆同理，不节流会在一分钟内把好感度清零。5 秒是"一次挨打事件"的近似。
     */
    private static final long HURT_AFFECTION_COOLDOWN_MS = 5_000L;

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
        Long lastAffection = hurtAffectionAt.get(maid.getId());
        if (lastAffection == null || now - lastAffection >= HURT_AFFECTION_COOLDOWN_MS) {
            hurtAffectionAt.put(maid.getId(), now);
            affection.apply(maid, -affection.loss(AffectionReason.HURT), AffectionReason.HURT);
        }
        Long lastDialogue = hurtDialogueAt.get(maid.getId());
        if (lastDialogue == null || now - lastDialogue >= HURT_DIALOGUE_COOLDOWN_MS) {
            hurtDialogueAt.put(maid.getId(), now);
            dialogue.send(maid, "on_hurt");
        }
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
        dropped.setGlowing(true);
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
