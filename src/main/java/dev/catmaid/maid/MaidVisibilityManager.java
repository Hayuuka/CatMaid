package dev.catmaid.maid;

import dev.catmaid.data.MaidRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 玩家级猫娘可见性开关（P11，blueprint Phase 11）。
 *
 * <p>为什么不按玩家裁剪资源包：生产服走 craftengine 路线，资源包是全局强制的，
 * 无法按玩家下发不同内容——"看不见猫娘"只能在<b>渲染实体层</b>做。方案是 Paper 原生
 * {@code Player#hideEntity(Plugin, Entity)}（逐玩家逐实体粒度、幂等、零新依赖）：
 * 对 opt-out 玩家把女仆的渲染实体逐个 hide。hide 走实体追踪系统的 removeViewer，
 * 之后 BetterModel 逐帧驱动的 transform/teleport 包也不再向该玩家下发。
 *
 * <p>要隐藏的三类实体（blueprint 11.1）：
 * <ol>
 *   <li>模型全部骨骼 display 实体——BM 的模型是一批服务端真实存在的展示实体，
 *       经 {@code BetterModel.registryOrNull(UUID)} → {@code EntityTrackerRegistry#first()}
 *       → {@code Tracker#bones()} → {@code RenderedBone#getDisplay()} 枚举
 *       （签名已对 testserver 实际 jar javap 复核，api-facts 28.1；部分骨返回 null 要跳过）；</li>
 *   <li>头顶名牌 TextDisplay——来自 {@link NameTagManager}；</li>
 *   <li>NPC 底层实体——BM 的 {@code player-hide-delay} 通常已把它全局隐藏
 *       （api-facts 16.9），但 P11 未实测覆盖 opt-out 场景；hideEntity 幂等，一并 hide 无害。</li>
 * </ol>
 *
 * <p>BetterModel 是 paper-plugin、classloader 隔离，骨骼枚举全部走反射，
 * 姿势与 {@link ModelApplier}/{@link ModelAnimator} 一致（从 BetterModel 自己的
 * classloader 取类）。反射链确认不到时降级为"只隐名牌 + 底层实体"并告警。
 *
 * <p>hide 状态<b>不跨玩家 relog</b>（Paper 不持久化）：玩家上线由
 * PlayerConnectionListener 尾部重新施加；女仆新实体产生处（onEntityReady、换模型、
 * 名牌重建）由 {@link #hideMaidFromOptedOut(Maid)} 重新施加；另有低频 sweep 对账兜底。
 * 内存对账集（maidId × playerUuid → 已 hide 的实体 UUID 集）防重复调用：
 * 只对账集里没有的实体 UUID 才调 hideEntity。
 */
public final class MaidVisibilityManager {

    /** sweep 错峰分母：任务每 20 tick 一轮、只处理 {@code npcId % 5 == 轮次 % 5} 的女仆，
     *  每只女仆实际每 100 tick 对账一次（blueprint 11.5-3 错峰纪律；分母照 NameTagManager 的 5）。
     *  任务本身是 1 秒级的，不是每 tick 任务。 */
    private static final int SWEEP_STAGGER = 5;

    // 反射目标（均已对 testserver/plugins/BetterModel.jar javap 复核，api-facts 28.1）
    private static final String BETTER_MODEL_API = "kr.toxicity.model.api.BetterModel";
    private static final String TRACKER_REGISTRY = "kr.toxicity.model.api.tracker.EntityTrackerRegistry";
    private static final String TRACKER = "kr.toxicity.model.api.tracker.Tracker";
    private static final String RENDERED_BONE = "kr.toxicity.model.api.bone.RenderedBone";
    private static final String IDENTIFIABLE = "kr.toxicity.model.api.nms.Identifiable";

    private final Plugin plugin;
    private final MaidRepository repository;
    private final NameTagManager nameTags;
    private final Supplier<Collection<Maid>> maids;
    private final Logger logger;

    /** opt-out 玩家集合（内存事实来源，启动时从 player_prefs 灌入，toggle 先改这里再异步落库）。 */
    private final Set<UUID> optedOut = new HashSet<>();
    /** 对账集：maidId → (playerUuid → 已对该玩家 hideEntity 过的实体 UUID)。 */
    private final Map<UUID, Map<UUID, Set<UUID>>> ledger = new HashMap<>();

    // 反射句柄（只解析一次；失败一次即整体禁用并只告警一次，同 ModelAnimator）
    private boolean resolved;
    private boolean disabled;
    private boolean warned;
    private Method registryOrNull;
    private Method first;
    private Method bones;
    private Method getDisplay;
    private Method uuid;

    private BukkitTask sweepTask;

    public MaidVisibilityManager(Plugin plugin, MaidRepository repository, NameTagManager nameTags,
                                 Supplier<Collection<Maid>> maids, Logger logger) {
        this.plugin = plugin;
        this.repository = repository;
        this.nameTags = nameTags;
        this.maids = maids;
        this.logger = logger;
    }

    /** onEnable 主线程同步调用（executor 尚无任务，同 loadAll 的安全前提）。 */
    public void loadPrefs() {
        optedOut.addAll(repository.loadHidePrefs());
        if (!optedOut.isEmpty()) {
            logger.info("已载入 " + optedOut.size() + " 位玩家的猫娘隐藏偏好");
        }
    }

    /** 启动兜底对账任务：每 20 tick 一轮、按 npcId 错峰，每只女仆每 100 tick 对账一次。 */
    public void start() {
        sweepTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sweepTick, 20L, 20L);
    }

    public void shutdown() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
    }

    /** 该玩家是否开启了猫娘隐藏。 */
    public boolean isOptedOut(UUID playerUuid) {
        return optedOut.contains(playerUuid);
    }

    /**
     * 切换玩家的猫娘隐藏状态。先改内存态立即生效，持久化异步跟进（blueprint 11.5-2）。
     *
     * @return 切换后是否处于隐藏状态
     */
    public boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        boolean nowHidden;
        if (optedOut.remove(id)) {
            restoreFor(player);
            nowHidden = false;
        } else {
            optedOut.add(id);
            applyAllFor(player);
            nowHidden = true;
        }
        repository.setHideModels(id, nowHidden);
        logger.info(player.getName() + (nowHidden ? " 开启" : " 关闭") + "了猫娘隐藏");
        return nowHidden;
    }

    /** 玩家上线 / 开启 opt-out 时调用：对在场全部女仆逐实体 hide。 */
    public void applyAllFor(Player player) {
        for (Maid maid : maids.get()) {
            if (!maid.isAlive() || maid.isStored()) {
                continue; // 收纳态/铃铛态没有实体（blueprint 11.1）
            }
            LivingEntity entity = maid.entity();
            if (entity == null) {
                continue;
            }
            hideEntities(player, maid, renderEntityUuids(maid));
        }
    }

    /** 玩家关闭 opt-out 时调用：把对账集里 hide 过的实体逐个 showEntity。 */
    public void restoreFor(Player player) {
        UUID playerId = player.getUniqueId();
        for (Map<UUID, Set<UUID>> perMaid : ledger.values()) {
            Set<UUID> recorded = perMaid.remove(playerId);
            if (recorded == null) {
                continue;
            }
            for (UUID entityUuid : recorded) {
                Entity entity = Bukkit.getEntity(entityUuid);
                if (entity != null) {
                    player.showEntity(plugin, entity);
                }
            }
        }
        ledger.values().removeIf(Map::isEmpty);
    }

    /**
     * 某女仆的渲染实体集合发生变化（实体就绪 / 换模型 / 名牌重建）时调用：
     * 对全部在线 opt-out 玩家 hide 该女仆。<b>必须在主线程调用</b>（blueprint 11.5-2）。
     */
    public void hideMaidFromOptedOut(Maid maid) {
        if (optedOut.isEmpty() || !maid.isAlive() || maid.isStored()) {
            return;
        }
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        List<UUID> entityUuids = renderEntityUuids(maid);
        if (entityUuids.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (optedOut.contains(player.getUniqueId())) {
                hideEntities(player, maid, entityUuids);
            }
        }
    }

    /**
     * 玩家退出时清空其全部对账记录（P11 实测修复，2026-11-14）。
     *
     * <p>hideEntity 状态随连接销毁，但对账集是服务端内存、会跨 relog 存活——若不清，
     * 重进后 applyAllFor 会因"实体已在账"跳过 hideEntity，导致隐藏失效。
     */
    public void handleQuit(Player player) {
        UUID playerId = player.getUniqueId();
        for (Map<UUID, Set<UUID>> perMaid : ledger.values()) {
            perMaid.remove(playerId);
        }
        ledger.values().removeIf(Map::isEmpty);
    }

    // ---- 内部 ----

    /**
     * 枚举某女仆当前全部渲染实体的 UUID：骨骼 display + 名牌 + NPC 底层实体。
     * 骨骼枚举走反射；BetterModel 不可用 / 反射链失效时返回后两类（降级，blueprint 11.5-1）。
     */
    private List<UUID> renderEntityUuids(Maid maid) {
        List<UUID> result = new ArrayList<>();
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return result;
        }
        // 底层实体：player-hide-delay 多半已把它藏起来，再 hide 一次幂等无害（blueprint 11.0-5）
        result.add(entity.getUniqueId());
        result.addAll(boneDisplayUuids(entity));
        Entity nametag = nameTags.displayEntity(maid);
        if (nametag != null && nametag.isValid()) {
            result.add(nametag.getUniqueId());
        }
        return result;
    }

    /** 枚举女仆模型的全部骨骼 display 实体 UUID；部分骨 getDisplay()==null，跳过（api-facts 24.3-1）。 */
    private List<UUID> boneDisplayUuids(LivingEntity entity) {
        List<UUID> result = new ArrayList<>();
        resolve();
        if (disabled) {
            return result;
        }
        try {
            Object registry = registryOrNull.invoke(null, entity.getUniqueId());
            if (registry == null) {
                return result;
            }
            Object tracker = first.invoke(registry);
            if (tracker == null) {
                return result;
            }
            Collection<?> boneList = (Collection<?>) bones.invoke(tracker);
            for (Object bone : boneList) {
                Object display = getDisplay.invoke(bone);
                if (display == null) {
                    continue;
                }
                result.add((UUID) uuid.invoke(display));
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            // API_ASSUMPTION: 枚举链 BetterModel.registryOrNull(UUID) → EntityTrackerRegistry#first()
            // → Tracker#bones() → RenderedBone#getDisplay() → Identifiable#uuid() 若随版本变化，
            // 这里会整体失效——降级为只隐名牌 + 底层实体（blueprint 11.5-1 预案）。
            failOnce("枚举猫娘模型骨骼失败，opt-out 玩家仍会看到模型本体（只隐名牌与底层实体）", e);
        }
        return result;
    }

    /**
     * 对单个玩家补齐 hide：只处理对账集里没有的实体 UUID，防重复调用 hideEntity
     * （blueprint 11.4"内存 maidId×playerUUID 对账集防重复调用"）。
     */
    private void hideEntities(Player player, Maid maid, List<UUID> entityUuids) {
        Map<UUID, Set<UUID>> perMaid = ledger.computeIfAbsent(maid.getId(), k -> new HashMap<>());
        Set<UUID> recorded = perMaid.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        for (UUID entityUuid : entityUuids) {
            if (recorded.contains(entityUuid)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(entityUuid);
            if (entity == null) {
                continue; // 实体还没建好或已消失，等挂点/下一轮 sweep 再补
            }
            player.hideEntity(plugin, entity);
            recorded.add(entityUuid);
        }
    }

    /** 兜底对账：只做内存对账补漏（blueprint 11.2），不新增每 tick 扫描。 */
    private int sweepRound;

    private void sweepTick() {
        if (optedOut.isEmpty()) {
            return;
        }
        sweepRound++;
        List<Player> optedOutPlayers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (optedOut.contains(player.getUniqueId())) {
                optedOutPlayers.add(player);
            }
        }
        for (Maid maid : maids.get()) {
            if (!maid.isAlive() || maid.isStored() || maid.getNpcId() < 0) {
                continue;
            }
            if (maid.getNpcId() % SWEEP_STAGGER != sweepRound % SWEEP_STAGGER) {
                continue;
            }
            LivingEntity entity = maid.entity();
            if (entity == null) {
                continue;
            }
            List<UUID> entityUuids = renderEntityUuids(maid);
            for (Player player : optedOutPlayers) {
                hideEntities(player, maid, entityUuids);
            }
        }
    }

    /** 一次性解析反射句柄；失败即整体禁用并告警一次（降级路径，见 boneDisplayUuids）。 */
    private void resolve() {
        if (resolved || disabled) {
            return;
        }
        resolved = true;
        Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null) {
            disabled = true;
            warn("BetterModel 未加载，opt-out 只能隐藏名牌与底层实体");
            return;
        }
        try {
            ClassLoader loader = betterModel.getClass().getClassLoader();
            Class<?> apiClass = Class.forName(BETTER_MODEL_API, true, loader);
            registryOrNull = apiClass.getMethod("registryOrNull", UUID.class);
            first = Class.forName(TRACKER_REGISTRY, true, loader).getMethod("first");
            bones = Class.forName(TRACKER, true, loader).getMethod("bones");
            getDisplay = Class.forName(RENDERED_BONE, true, loader).getMethod("getDisplay");
            uuid = Class.forName(IDENTIFIABLE, true, loader).getMethod("uuid");
        } catch (ReflectiveOperationException | LinkageError e) {
            // API_ASSUMPTION: 上述类名/方法名来自 BetterModel 3.6.0 jar 的 javap 复核（api-facts 28.1）；
            // 若版本变化导致解析不到，降级为只隐名牌 + 底层实体（blueprint 11.5-1 预案）。
            disabled = true;
            warn("BetterModel 骨骼枚举 API 与预期不符，opt-out 降级为只隐名牌与底层实体：" + e);
        }
    }

    private void failOnce(String message, Throwable cause) {
        if (!warned) {
            warned = true;
            logger.log(Level.WARNING, "[CatMaid] " + message, cause);
        }
    }

    private void warn(String message) {
        logger.warning("[CatMaid] " + message);
    }
}
