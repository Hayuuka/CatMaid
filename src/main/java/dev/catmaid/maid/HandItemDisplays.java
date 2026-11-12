package dev.catmaid.maid;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 女仆主手/副手物品的可见显示（蓝图 P9-9.1）。
 *
 * <p>为什么需要这个类：BetterModel 用 display 实体替换玩家外观后，原版装备层被模型整个盖住，
 * 主手举着剑也看不见（P2 起已知，0.2 原本把这条"明确排除"，P9 由服主需求反转）。
 *
 * <p>两条实现路径，按 {@code hand-item.mode} 选择：
 * <ol>
 *   <li><b>bone</b>：把物品挂到模型的手持物品骨上（{@code RenderedBone#itemStack}），位置与朝向
 *       跟着手臂动画走。前提是模型里真有一个"专门放手部物品"的空骨——
 *       实测 foxmaid 的 {@code RightHandLocator} 是纯定位骨、没有渲染载体（{@code getDisplay()==null}），
 *       往它上面挂什么都看不见；而 {@code RightHand} 有渲染载体，但那是手部的**几何体**，
 *       用物品替换等于把手换成剑（松手后还原不回来）。所以现阶段这条路径实际用不上，
 *       等模型补了手持物品骨再用（见 docs/api-facts.md 第 24 节）；</li>
 *   <li><b>display</b>：自己生成 {@link ItemDisplay}，按实体朝向几何推导手部位置。位置不跟手臂动画，
 *       但零 API 风险，是现在真正生效的方案。</li>
 * </ol>
 *
 * <p>为什么用轮询而不是在 {@code Maid#applyEquipment()} 里推：装备改动来自 GUI 关闭回写、召唤恢复、
 * 死亡、收纳、换模型五条路径，逐个埋点必然漏一处；这里每 2 tick 错峰比一次快照，
 * 谁改的都能在 100ms 内跟上（蓝图要求"换装 ≤1s 刷新"）。
 *
 * <p>BetterModel 是 paper-plugin，与 Bukkit 插件的 classloader 隔离，只能反射调用
 * （同 {@link ModelApplier} 的理由，见 docs/api-facts.md 第 2 节）。
 */
public final class HandItemDisplays {

    /** 错峰分母：每 tick 只处理 1/N 的女仆（与 NameTagManager 同款做法，只是更密）。 */
    private static final int STAGGER = 2;

    private static final String BM_API = "kr.toxicity.model.api.BetterModel";
    private static final String BM_BUKKIT_STACK = "kr.toxicity.model.api.bukkit.platform.BukkitItemStack";
    private static final String BM_TRANSFORMED_STACK = "kr.toxicity.model.api.util.TransformedItemStack";
    private static final String BM_PLATFORM_STACK = "kr.toxicity.model.api.platform.PlatformItemStack";

    private enum Mode {
        AUTO, BONE, DISPLAY
    }

    private final Plugin plugin;
    private final Logger logger;
    private final boolean enabled;
    private final Mode mode;
    private final String mainBone;
    private final String offBone;
    private final float displayScale;
    private final double sideOffset;
    private final double forwardOffset;
    private final double handHeight;

    /** 每只女仆两只手的状态。 */
    private final Map<UUID, HandState> states = new HashMap<>();
    private int ticker;

    // ---- BetterModel 反射句柄（首次使用时初始化，失败则永久走降级） ----
    private boolean reflectTried;
    private boolean reflectReady;
    private Method registryOrNull;
    private Method trackerFirst;
    private Method trackerBone;
    private Method boneDisplay;
    private Method boneItemStack;
    private Method transformedOf;
    private Constructor<?> bukkitStackCtor;
    private Object emptyStack;
    private boolean boneWarned;
    private boolean fallbackNoticed;

    public HandItemDisplays(Plugin plugin, Logger logger, FileConfiguration config) {
        this.plugin = plugin;
        this.logger = logger;
        this.enabled = config.getBoolean("hand-item.enabled", true);
        this.mode = parseMode(config.getString("hand-item.mode", "auto"));
        this.mainBone = config.getString("hand-item.main-bone", "RightHandLocator");
        this.offBone = config.getString("hand-item.off-bone", "LeftHandLocator");
        this.displayScale = (float) config.getDouble("hand-item.display-scale", 0.55);
        this.sideOffset = config.getDouble("hand-item.display-side", 0.32);
        this.forwardOffset = config.getDouble("hand-item.display-forward", 0.22);
        this.handHeight = config.getDouble("hand-item.display-height", 1.20);
    }

    private static Mode parseMode(String raw) {
        if (raw == null) {
            return Mode.AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "bone" -> Mode.BONE;
            case "display" -> Mode.DISPLAY;
            default -> Mode.AUTO;
        };
    }

    public void start(Supplier<Collection<Maid>> source) {
        if (!enabled) {
            return;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(source.get()), 2L, 2L);
    }

    /** 由全局任务每 2 tick 调一次；内部按 {@code npcId % STAGGER} 再错峰一半。 */
    public void tick(Collection<Maid> maids) {
        if (!enabled) {
            return;
        }
        ticker++;
        Set<UUID> live = new HashSet<>();
        for (Maid maid : maids) {
            if (!maid.isAlive()) {
                continue; // 铃铛态没有实体，清理由下面的孤儿回收兜住
            }
            live.add(maid.getId());
            if (Math.floorMod(maid.getNpcId(), STAGGER) != Math.floorMod(ticker, STAGGER)) {
                continue;
            }
            update(maid);
        }
        removeOrphans(live);
    }

    /** 立即清掉某只女仆的显示（移除/收纳时调用，不必等下一轮轮询）。 */
    public void remove(Maid maid) {
        HandState state = states.remove(maid.getId());
        if (state != null) {
            state.clearDisplays();
        }
    }

    public void shutdown() {
        for (HandState state : states.values()) {
            state.clearDisplays();
        }
        states.clear();
    }

    // ---- 单个女仆 ----

    private void update(Maid maid) {
        LivingEntity entity = maid.entity();
        if (entity == null) {
            // 实体没了（despawn / 死亡 / 收纳）：连显示一起收掉，下次实体回来会重新挂
            remove(maid);
            return;
        }
        HandState state = states.computeIfAbsent(maid.getId(), id -> new HandState());
        updateHand(maid, state.main, entity, maid.inventory().getMainHand(), true);
        updateHand(maid, state.off, entity, maid.inventory().get(1), false);
    }

    private void updateHand(Maid maid, HandSlot slot, LivingEntity entity, ItemStack raw, boolean mainHand) {
        ItemStack item = raw == null || raw.getType().isAir() ? null : raw.clone();
        if (slot.isDirty(item, maid.getModelId())) {
            boolean onBone = useBone() && pushToBone(entity, item, boneName(mainHand));
            if (onBone) {
                slot.removeDisplay(); // 骨骼路径不需要自带显示，旧的清掉免得同一件物品出现两次
            } else {
                renderOwnDisplay(entity, slot, item, mainHand);
            }
            slot.mark(item, maid.getModelId());
        } else if (slot.displayedId != null) {
            // 自带显示要跟着实体走：每轮（2 tick）挪一次，否则女仆一走动物品就留在原地
            ItemDisplay display = findDisplay(slot.displayedId);
            if (display == null) {
                renderOwnDisplay(entity, slot, item, mainHand);
            } else {
                display.teleport(handLocation(entity, mainHand));
            }
        }
    }

    private boolean useBone() {
        return mode != Mode.DISPLAY;
    }

    private String boneName(boolean mainHand) {
        return mainHand ? mainBone : offBone;
    }

    /**
     * 把物品挂到模型的手持物品骨上。
     *
     * <p>只在模型确实有"专门放手部物品、且带渲染载体"的骨时才可能成功；判断不过直接返回 false
     * 让调用方走自带显示——这也是现在 foxmaid 上每次都会发生的事（见类注释）。
     *
     * @return true 表示这条路径可用且这次写成功了
     */
    private boolean pushToBone(LivingEntity entity, ItemStack item, String boneName) {
        if (!ensureReflection()) {
            return false;
        }
        try {
            Object registry = registryOrNull.invoke(null, entity.getUniqueId());
            if (registry == null) {
                return false; // 模型还没挂上（Citizens 重建实体的瞬间）
            }
            Object tracker = trackerFirst.invoke(registry);
            Object bone = tracker == null ? null : trackerBone.invoke(tracker, boneName);
            if (bone == null) {
                noticeFallback("模型里没有手部骨 " + boneName);
                return false;
            }
            // 定位骨常常只是空节点：没有渲染载体时 itemStack 会静默"成功"但屏幕上什么都没有
            if (boneDisplay.invoke(bone) == null) {
                noticeFallback("手部骨 " + boneName + " 没有渲染载体（纯定位骨）");
                return false;
            }
            Object payload = item == null ? emptyStack : transformedOf.invoke(null,
                    bukkitStackCtor.newInstance(item));
            return (Boolean) boneItemStack.invoke(bone, (Predicate<Object>) ignored -> true, payload);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            warnBoneOnce("挂手部物品到模型骨骼失败，改用独立显示：" + e);
            return false;
        }
    }

    private boolean ensureReflection() {
        if (reflectTried) {
            return reflectReady;
        }
        reflectTried = true;
        Plugin betterModel = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (betterModel == null) {
            noticeFallback("BetterModel 未加载");
            return false;
        }
        try {
            ClassLoader loader = betterModel.getClass().getClassLoader();
            Class<?> api = Class.forName(BM_API, true, loader);
            Class<?> bukkitStack = Class.forName(BM_BUKKIT_STACK, true, loader);
            Class<?> transformed = Class.forName(BM_TRANSFORMED_STACK, true, loader);
            Class<?> platformStack = Class.forName(BM_PLATFORM_STACK, true, loader);

            registryOrNull = api.getMethod("registryOrNull", UUID.class);
            trackerFirst = Class.forName("kr.toxicity.model.api.tracker.EntityTrackerRegistry", true, loader)
                    .getMethod("first");
            trackerBone = Class.forName("kr.toxicity.model.api.tracker.Tracker", true, loader)
                    .getMethod("bone", String.class);
            Class<?> boneClass = Class.forName("kr.toxicity.model.api.bone.RenderedBone", true, loader);
            boneDisplay = boneClass.getMethod("getDisplay");
            boneItemStack = boneClass.getMethod("itemStack", Predicate.class, transformed);
            transformedOf = transformed.getMethod("of", platformStack);
            bukkitStackCtor = bukkitStack.getConstructor(ItemStack.class);
            emptyStack = transformed.getMethod("empty").invoke(null);
            reflectReady = true;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            warnBoneOnce("BetterModel API 与预期不符，手部物品改用独立显示：" + e);
            return false;
        }
    }

    // ---- 降级：自带 ItemDisplay ----

    private void renderOwnDisplay(LivingEntity entity, HandSlot slot, ItemStack item, boolean mainHand) {
        if (item == null) {
            slot.removeDisplay();
            return;
        }
        ItemDisplay display = slot.displayedId == null ? null : findDisplay(slot.displayedId);
        if (display == null) {
            display = spawnDisplay(entity, item, mainHand);
            if (display == null) {
                return;
            }
            slot.displayedId = display.getUniqueId();
            return;
        }
        display.setItemStack(item);
        display.teleport(handLocation(entity, mainHand));
    }

    private ItemDisplay spawnDisplay(LivingEntity entity, ItemStack item, boolean mainHand) {
        Location loc = handLocation(entity, mainHand);
        if (loc.getWorld() == null) {
            return null;
        }
        float scale = displayScale;
        return loc.getWorld().spawn(loc, ItemDisplay.class, display -> {
            display.setPersistent(false); // 不落盘，避免重启后留下孤儿展示实体（同名牌的做法）
            display.setItemStack(item);
            display.setBillboard(Display.Billboard.FIXED);
            display.setItemDisplayTransform(mainHand
                    ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND
                    : ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND);
            display.setRotation(loc.getYaw(), loc.getPitch());
            display.setTeleportDuration(2); // 跟上 2 tick 的同步节奏，避免一卡一卡
            display.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
        });
    }

    /**
     * 手部位置：以实体朝向为基准做几何推导。主手在右侧、副手在左侧，都在身体前方一点。
     *
     * <p>坐标约定：yaw=0 朝 +Z，右方向向量 = (-cos yaw, 0, -sin yaw)。
     * 三个偏移量都做成配置（{@code hand-item.display-*}）：观感只能在客户端调，
     * 让服主不必改代码重编译。
     */
    private Location handLocation(LivingEntity entity, boolean mainHand) {
        Location base = entity.getLocation();
        double yaw = Math.toRadians(base.getYaw());
        double side = mainHand ? sideOffset : -sideOffset;
        double x = base.getX() + Math.cos(yaw) * side - Math.sin(yaw) * forwardOffset;
        double z = base.getZ() + Math.sin(yaw) * side + Math.cos(yaw) * forwardOffset;
        return new Location(base.getWorld(), x, base.getY() + handHeight, z,
                base.getYaw(), base.getPitch());
    }

    private ItemDisplay findDisplay(UUID displayId) {
        Entity entity = Bukkit.getEntity(displayId);
        if (entity instanceof ItemDisplay display && display.isValid()) {
            return display;
        }
        return null;
    }

    // ---- 清理与日志 ----

    private void removeOrphans(Set<UUID> live) {
        Iterator<Map.Entry<UUID, HandState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, HandState> entry = it.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            entry.getValue().clearDisplays();
            it.remove();
        }
    }

    /** 骨骼路径走不通时只提示一次原因，避免每只女仆刷一行。 */
    private void noticeFallback(String reason) {
        if (!fallbackNoticed) {
            fallbackNoticed = true;
            logger.info("手部物品改用独立显示实体：" + reason
                    + "（模型补上带渲染载体的手持物品骨后会自动走骨骼路径）");
        }
    }

    private void warnBoneOnce(String message) {
        if (!boneWarned) {
            boneWarned = true;
            logger.log(Level.INFO, message);
        }
    }

    /** 单只手的显示状态：上次推给模型的物品/模型名 + 自带显示的实体 id。 */
    private static final class HandSlot {
        private ItemStack item;
        private String modelId;
        private boolean pushed;
        private UUID displayedId;

        /** 需不需要重新推一次：物品变了、模型换了、或者还没成功推过。 */
        private boolean isDirty(ItemStack next, String currentModel) {
            if (!pushed) {
                return true;
            }
            if (!Objects.equals(modelId, currentModel)) {
                return true; // 换模型会重建 tracker，旧的挂载跟着没了
            }
            if (item == null || next == null) {
                return item != next;
            }
            return item.getAmount() != next.getAmount() || !item.isSimilar(next);
        }

        private void mark(ItemStack next, String currentModel) {
            this.item = next;
            this.modelId = currentModel;
            this.pushed = true;
        }

        private void removeDisplay() {
            if (displayedId == null) {
                return;
            }
            Entity entity = Bukkit.getEntity(displayedId);
            if (entity != null) {
                entity.remove();
            }
            displayedId = null;
        }
    }

    private static final class HandState {
        private final HandSlot main = new HandSlot();
        private final HandSlot off = new HandSlot();

        private void clearDisplays() {
            main.removeDisplay();
            off.removeDisplay();
        }
    }
}
