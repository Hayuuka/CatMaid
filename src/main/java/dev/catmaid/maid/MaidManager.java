package dev.catmaid.maid;

import dev.catmaid.behavior.BehaviorManager;
import dev.catmaid.data.MaidRepository;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.event.MaidSummonEvent;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * 女仆的召唤、移除、查询与持久化调度。
 *
 * <p>所有方法都必须在主线程调用：里面碰 NPC / 实体 / 粒子。
 */
public final class MaidManager {

    private final MaidRepository repository;
    private final ModelRegistry models;
    private final ModelApplier modelApplier;
    private final BehaviorManager behavior;
    private final DialogueManager dialogue;
    private final FileConfiguration config;
    private final Logger logger;

    private final Map<UUID, Maid> maids = new ConcurrentHashMap<>();
    /** 还没挂上 NPC 的女仆，由 CatMaidPlugin 的轮询任务逐个消化。 */
    private final Set<UUID> awaitingNpc = ConcurrentHashMap.newKeySet();

    public MaidManager(MaidRepository repository, ModelRegistry models, ModelApplier modelApplier,
                       BehaviorManager behavior, DialogueManager dialogue,
                       FileConfiguration config, Logger logger) {
        this.repository = repository;
        this.models = models;
        this.modelApplier = modelApplier;
        this.behavior = behavior;
        this.dialogue = dialogue;
        this.config = config;
        this.logger = logger;
    }

    // ---- 生命周期 ----

    /** onEnable 调用：把库里的女仆读进内存。 */
    public void loadAll() {
        for (Maid maid : repository.loadAll()) {
            maid.setMaxHealth(maxHealth());
            maids.put(maid.getId(), maid);
            if (maid.isAlive()) {
                awaitingNpc.add(maid.getId());
            }
        }
        logger.info("已载入 " + maids.size() + " 只女仆");
    }

    /**
     * 实体就绪后要补的东西：装备写回 + 血量上限（细节见 {@link Maid#applyVitals()}）。
     *
     * <p>所有生成实体的路径都要调这里——恢复、召唤、上线补挂、铃铛复活。漏一处就会出现
     * "她只有 20 血"或"血条 20/40"，而且只在某些路径下复现，极难排查（api-facts 20.9-A5）。
     */
    public void onEntityReady(Maid maid) {
        maid.applyVitals();
    }

    /**
     * 恢复一轮：把还在等待的 alive 女仆挂上 NPC。
     *
     * <p>Citizens 是异步加载 NPC 的（启动后数秒才完成），所以这里不能"查不到就重建"，
     * 必须由调用方轮询，等 Citizens 就绪；{@code allowRecreate} 为 true 时才允许重建兜底。
     *
     * @return 是否所有待恢复的女仆都已处理完（true 表示轮询可以停了）
     */
    public boolean restoreNpcs(boolean allowRecreate) {
        boolean pending = false;
        // 先处理铃铛态：Citizens 会按存档里的"已生成"状态把她们的实体一起恢复出来，
        // 于是世界里多出一具没有名牌、还会被行为 tick 推动的幽灵身体（实测就是这么来的）。
        despawnDeadNpcs();
        for (UUID maidId : awaitingNpc) {
            Maid maid = maids.get(maidId);
            if (maid == null || !maid.isAlive()) {
                awaitingNpc.remove(maidId);
                continue;
            }
            NPC npc = findNpc(maid);
            if (npc == null) {
                if (!allowRecreate) {
                    pending = true;
                    continue;
                }
                npc = recreate(maid);
            }
            attach(maid, npc);
            awaitingNpc.remove(maidId);
        }
        return !pending;
    }

    /**
     * 把铃铛态女仆残留的实体收掉。死亡时 Citizens 会自己 despawn 一次，但只要存档里带
     * "spawned" 状态，重启后它又会把实体建回来。这里在每轮恢复里兜一遍，防止世界里留下
     * 没有名牌的女仆身体（玩家视角就是"多了一个没名字的女仆"）。
     */
    private void despawnDeadNpcs() {
        for (Maid maid : maids.values()) {
            if (maid.isAlive() || maid.getNpcId() < 0) {
                continue;
            }
            NPC npc = CitizensAPI.getNPCRegistry().getById(maid.getNpcId());
            if (npc == null || !npc.isSpawned()) {
                continue;
            }
            npc.despawn();
            logger.info("已收起铃铛态女仆 " + maid.getName() + " 的残留实体（NPC " + npc.getId() + "）");
        }
    }

    /** 先按 id 查；id 对不上（Citizens 存档重排 / NPC 被清理后又重建过）时按 trait 里的 maidId 反查。 */
    private NPC findNpc(Maid maid) {        NPC byId = CitizensAPI.getNPCRegistry().getById(maid.getNpcId());
        if (byId != null) {
            return byId;
        }
        for (NPC candidate : CitizensAPI.getNPCRegistry()) {
            MaidTrait trait = candidate.getTraitNullable(MaidTrait.class);
            if (trait != null && maid.getId().toString().equals(trait.maidId())) {
                return candidate;
            }
        }
        return null;
    }

    /** 绑定 trait、按需生成、写回模型与装备。 */
    private void attach(Maid maid, NPC npc) {
        npc.getOrAddTrait(MaidTrait.class).bind(maid);
        maid.setNpcId(npc.getId());
        if (!npc.isSpawned()) {
            Location target = maid.getHome() != null ? maid.getHome() : fallbackSpawn();
            if (target == null || target.getWorld() == null) {
                logger.warning("女仆 " + maid.getName() + " 找不到可用世界，跳过生成");
                return;
            }
            npc.spawn(target);
        }
        modelApplier.apply(npc, maid.getModelId());
        onEntityReady(maid);
    }

    // ---- 召唤 / 移除 ----

    /** 蓝图签名；内部转调 summonFor。返回 null 表示超出上限或模型池为空。 */
    public Maid summon(Player owner, String modelId, Location loc) {
        return summonFor(owner, modelId, loc);
    }

    /**
     * 管理员可给离线玩家召唤（控制台验收要用），所以参数放宽到 OfflinePlayer。
     *
     * @return 召唤失败返回 null，调用方负责给用户提示
     */
    public Maid summonFor(OfflinePlayer owner, String modelId, Location loc) {
        int globalCap = config.getInt("performance.global-maid-cap", 60);
        if (maids.size() >= globalCap) {
            logger.warning("已达全局女仆上限 " + globalCap + "，拒绝召唤");
            return null;
        }
        if (byOwner(owner.getUniqueId()).size() >= maxPerPlayer()) {
            return null;
        }
        String resolvedModel = modelId == null || modelId.isBlank() ? models.random() : modelId;
        if (resolvedModel == null) {
            logger.warning("config.yml 的 models 为空，无法召唤");
            return null;
        }

        Location spawn = loc.clone();
        Maid maid = new Maid(UUID.randomUUID());
        maid.setOwnerUuid(owner.getUniqueId());
        maid.setName(randomName());
        maid.setModelId(resolvedModel);
        maid.setMode(Maid.Mode.FOLLOW);
        maid.setAffection(config.getDouble("general.initial-affection", 10));
        maid.setHome(spawn);
        maid.setAlive(true);
        maid.setMaxHealth(maxHealth());

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, maid.getName());
        npc.setProtected(false);
        maid.setNpcId(npc.getId());
        MaidTrait trait = npc.getOrAddTrait(MaidTrait.class);
        trait.bind(maid);

        MaidSummonEvent event = new MaidSummonEvent(owner, resolvedModel, spawn);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            npc.destroy();
            return null;
        }

        if (!npc.spawn(spawn)) {
            logger.warning("NPC 生成失败，位置：" + spawn);
            npc.destroy();
            return null;
        }

        modelApplier.apply(npc, resolvedModel);
        onEntityReady(maid);
        playSummonEffect(spawn);

        maids.put(maid.getId(), maid);
        repository.insert(maid);
        // 台词在落库之后说：她开口时数据已经稳了，重启也不会变成"说了话但没这个人"
        dialogue.send(maid, "on_summon");
        return maid;
    }

    public boolean remove(UUID maidId) {
        Maid maid = maids.remove(maidId);
        if (maid == null) {
            return false;
        }
        NPC npc = CitizensAPI.getNPCRegistry().getById(maid.getNpcId());
        if (npc != null) {
            npc.despawn();
            npc.destroy();
        }
        behavior.forget(maid);
        repository.delete(maidId);
        return true;
    }

    // ---- 查询 ----

    public Optional<Maid> byId(UUID maidId) {
        return Optional.ofNullable(maids.get(maidId));
    }

    /** NPC 事件 → Maid 的唯一查找路径。 */
    public Optional<Maid> byNpcId(int npcId) {
        for (Maid maid : maids.values()) {
            if (maid.getNpcId() == npcId) {
                return Optional.of(maid);
            }
        }
        return Optional.empty();
    }

    public List<Maid> byOwner(UUID ownerUuid) {
        List<Maid> result = new ArrayList<>();
        for (Maid maid : maids.values()) {
            if (ownerUuid.equals(maid.getOwnerUuid())) {
                result.add(maid);
            }
        }
        return result;
    }

    public Collection<Maid> all() {
        return maids.values();
    }

    public int maxPerPlayer() {
        return config.getInt("general.max-per-player", 2);
    }

    /** 女仆血量上限（蓝图 P7 默认 40）：玩家型 NPC 默认 20 血，抗不住战斗模式的换血。 */
    private double maxHealth() {
        return Math.max(1, config.getDouble("combat.maid-max-health", 40));
    }

    // ---- 持久化 ----

    public void saveAsync(Maid maid) {
        repository.save(maid);
    }

    public void saveAll() {
        for (Maid maid : maids.values()) {
            repository.save(maid);
        }
    }

    // ---- 内部 ----

    private NPC recreate(Maid maid) {
        logger.warning("女仆 " + maid.getName() + " 的 NPC 已丢失，正在重建");
        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, maid.getName());
        npc.setProtected(false);
        maid.setNpcId(npc.getId());
        return npc;
    }

    private String randomName() {
        List<String> pool = config.getStringList("names");
        if (pool.isEmpty()) {
            return "女仆";
        }
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private Location fallbackSpawn() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    private void playSummonEffect(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.HEART, loc.clone().add(0, 1.2, 0), 8, 0.45, 0.5, 0.45, 0);
        world.playSound(loc, Sound.ENTITY_CAT_AMBIENT, 0.9f, 1.2f);
    }
}
