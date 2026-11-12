package dev.catmaid.behavior.mode;

import dev.catmaid.behavior.BehaviorMode;
import dev.catmaid.behavior.Movement;
import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.AffectionReason;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidInventory;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Sheep;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockVector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 畜牧：捡拾地面产物、繁殖、剪毛、挤奶（蓝图 P6）。
 *
 * <p>节奏被拆成两档，这是蓝图没写但必须这么做的地方（api-facts 19.9-5.1）：
 * <ul>
 *   <li><b>捡拾每 10 tick</b>：只收脚边的（{@link #PICKUP_REACH}），不导航、不决策。鸡蛋在地上躺 1.5 秒
 *       才被看见，观感上就是"她没看见"；</li>
 *   <li><b>决策每 30 tick</b>：挑目标（走得远的东西、动物）、走过去、执行动作。</li>
 * </ul>
 * 两档都按 {@code tick % N == npcId % N} 错峰，10 只女仆不会挤在同一 tick 扫世界（agent.md 7.1）。
 *
 * <p>所有动作共享一个 per-maid 状态机（{@link State}）：目标 + 8 秒卡死判定 + 30 秒拉黑。
 * 目标失效（被主人牵走、羊已经被剪、鸡蛋被玩家捡了）时重新决策，不保留半截状态。
 *
 * <p>动画不在这里碰：姿态由 BehaviorManager 按 {@code Movement.isNavigating} 推导。
 */
public final class RanchMode implements BehaviorMode {

    /** 捡拾：每 10 tick 一次（蓝图 P6 踩坑 1 的口径）。 */
    private static final int PICKUP_INTERVAL = 10;
    /** 决策：每 30 tick 一次（蓝图 P6）。 */
    private static final int DECIDE_INTERVAL = 30;
    /** 走到目标 2.5 格内算够得着。蓝图写 2.0，牛/羊有碰撞箱，卡太紧会永远"差一点"。 */
    private static final double REACH = 2.5;
    /** 脚边这个距离直接收进包，不必再导航一趟。 */
    private static final double PICKUP_REACH = 2.5;
    /** 略快于步行，与农业模式一致。 */
    private static final float WALK_SPEED = 1.1f;
    /** 8 秒没挪动 0.5 格 → 认定走不过去（栅栏/水沟），拉黑（agent.md 7.2）。 */
    private static final long STUCK_MS = 8000L;
    private static final double STUCK_DISTANCE_SQ = 0.25;
    /** 拉黑 30 秒：给主人 30 秒挪开障碍，之后允许再试。 */
    private static final long BLACKLIST_MS = 30_000L;
    /** 完成一次动作后歇 2 秒（蓝图 P6），否则她会一秒内连剪三只羊。 */
    private static final long ACTION_COOLDOWN_MS = 2000L;
    /** 刚掉出来的东西先放着：物品出生 10 tick 内不碰（蓝图 P6）。 */
    private static final int ITEM_MIN_AGE_TICKS = 10;
    /**
     * 玩家亲手丢出的物品带 owner。刚丢的别抢（蓝图意图），但掉了很久的还是要收——
     * 玩家"扔给她"的东西躺在地上没人捡才更怪。
     */
    private static final int OWNER_GRACE_TICKS = 100;
    /** 干完一件活说一句话的概率（与农业模式同口径：十次一句，不至于碎碎念）。 */
    private static final double WORK_LINE_CHANCE = 0.1;

    private final FileConfiguration config;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    private final Map<UUID, State> states = new HashMap<>();

    public RanchMode(FileConfiguration config, AffectionManager affection, DialogueManager dialogue) {
        this.config = config;
        this.affection = affection;
        this.dialogue = dialogue;
    }

    /** 当前这一步要做什么。同一时刻只有一件事，避免导航目标互相打架。 */
    private enum Action {
        PICKUP, SHEAR, MILK
    }

    /** 每只女仆的畜牧状态。Mode 是单例，状态必须按女仆分，否则十只会抢同一个目标。 */
    private static final class State {
        private Action action;
        private Entity target;
        private Location lastPos;
        private long lastProgressAt;
        /** 下一次允许动作的时间戳（毫秒）。 */
        private long nextActionAt;
        /** 走不过去的位置 → 解禁时间戳。物品原地不动、动物被栅栏困住时都靠它止损。 */
        private final Map<BlockVector, Long> blacklist = new HashMap<>();
    }

    @Override
    public Maid.Mode type() {
        return Maid.Mode.RANCH;
    }

    @Override
    public void onDisable(Maid maid) {
        // 状态里握着实体引用与拉黑表，女仆切走/被移除后留着只会涨内存
        states.remove(maid.getId());
    }

    @Override
    public void tick(Maid maid, int tick) {
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        State state = states.computeIfAbsent(maid.getId(), key -> new State());

        if (staggered(tick, maid, PICKUP_INTERVAL)) {
            vacuum(maid, entity, state);
        }
        if (!staggered(tick, maid, DECIDE_INTERVAL)) {
            return;
        }
        if (System.currentTimeMillis() < state.nextActionAt) {
            return;
        }
        decide(maid, entity, state, tick);
    }

    /** 错峰：第 tick 该不该这只女仆干活。 */
    private static boolean staggered(int tick, Maid maid, int interval) {
        return Math.floorMod(tick, interval) == Math.floorMod(maid.getNpcId(), interval);
    }

    // ---- 捡拾 ----

    /**
     * 收脚边的东西。只收 {@link #PICKUP_REACH} 内的：这一步每 10 tick 跑一次，如果在这里开导航，
     * 会把"走去喂牛"的目标顶掉，表现成她一步三回头。
     */
    private void vacuum(Maid maid, LivingEntity entity, State state) {
        if (!config.getBoolean("ranch.pickup", true)) {
            return;
        }
        Item item = nearestItem(maid, entity, PICKUP_REACH, state);
        if (item != null) {
            collect(maid, item, state);
        }
    }

    /**
     * 收一件进包。一次只收一件（api-facts 19.9-5.5）：满包时如果一次吞一堆再吐回地上，
     * 物品会在地上反复重生，看起来像在抽搐。
     *
     * @return 是否收干净了（没进包或只进了一部分都返回 false）
     */
    private boolean collect(Maid maid, Item item, State state) {
        ItemStack stack = item.getItemStack();
        int leftover = maid.inventory().addItem(stack);
        if (leftover == stack.getAmount()) {
            // 一件都没进去：包满。拉黑这件，别每 30 tick 又跑回来试一次
            blacklist(state, item.getLocation());
            return false;
        }
        if (leftover > 0) {
            // 部分进包：余量留在原地等她腾出空位
            stack.setAmount(leftover);
        } else {
            item.remove();
        }
        LivingEntity self = maid.entity();
        item.getWorld().playSound(self == null ? item.getLocation() : self.getLocation(),
                Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.4f);
        return leftover == 0;
    }

    /** 可捡判定：蓝图 P6 的"pickupDelay<=0 且 age>10 tick"，外加 owner 宽限与"怪物能不能捡"。 */
    private static boolean collectible(Item item) {
        if (item.getPickupDelay() > 0 || item.getTicksLived() <= ITEM_MIN_AGE_TICKS) {
            return false;
        }
        if (!item.canMobPickup()) {
            return false;
        }
        UUID owner = item.getOwner();
        return owner == null || item.getTicksLived() >= OWNER_GRACE_TICKS;
    }

    /** 半径内最近的、能捡的、没被拉黑的一件东西。 */
    private Item nearestItem(Maid maid, LivingEntity entity, double radius, State state) {
        World world = entity.getWorld();
        Location from = entity.getLocation();
        Item best = null;
        double bestSq = radius * radius;
        for (Entity candidate : world.getNearbyEntities(from, radius, radius, radius)) {
            if (!(candidate instanceof Item item) || !collectible(item)
                    || blacklisted(state, item.getLocation())) {
                continue;
            }
            double distance = item.getLocation().distanceSquared(from);
            if (distance <= bestSq) {
                bestSq = distance;
                best = item;
            }
        }
        return best;
    }

    // ---- 决策 ----

    private void decide(Maid maid, LivingEntity entity, State state, int tick) {
        if (state.action != null && !stillValid(maid, entity, state)) {
            // 目标没了（被捡走/牵走/剪过）→ 丢掉半截状态重挑，不要对着空地走
            reset(state);
        }
        if (state.action == null) {
            select(maid, entity, state);
        }
        if (state.action == null) {
            // 没活干：站住，台词交给随机待机那套节流（自带间隔/概率/每女仆冷却）
            Movement.stop(maid);
            dialogue.tickIdle(maid, tick);
            return;
        }
        pursue(maid, entity, state);
    }

    /** 挑一件要做的事。捡拾优先（蓝图 P6 顺序）：地上有东西就先收，别让鸡蛋一直躺着。 */
    private void select(Maid maid, LivingEntity entity, State state) {
        // 扫描前顺手清过期拉黑（与扫描同频，不必另找时机）
        pruneBlacklist(state);
        Item item = nearestItem(maid, entity, radius(), state);
        if (item != null) {
            state.action = Action.PICKUP;
            state.target = item;
            state.lastPos = null;
            return;
        }
        if (selectShear(maid, entity, state)) {
            return;
        }
        selectMilk(maid, entity, state);
    }

    /**
     * 找一头能挤的成年牛。前提是背包里有空桶——没桶就不必走过去，走了也是白走。
     *
     * <p>挤奶没有任何原版 API 能"替她挤"（javap 已核，{@code Cow} 上没有 milk 方法）：
     * 空桶换成奶桶这件事只能由我们自己算（见 {@link #milk}）。
     */
    private boolean selectMilk(Maid maid, LivingEntity entity, State state) {
        if (count(maid, Material.BUCKET) <= 0) {
            return false;
        }
        Cow best = null;
        double bestSq = radiusSq();
        Location from = entity.getLocation();
        for (Entity candidate : entity.getWorld().getNearbyEntities(from, radius(), radius(), radius())) {
            if (!(candidate instanceof Cow cow) || !cow.isAdult() || blacklisted(state, cow.getLocation())) {
                continue;
            }
            double distance = cow.getLocation().distanceSquared(from);
            if (distance <= bestSq) {
                bestSq = distance;
                best = cow;
            }
        }
        if (best == null) {
            return false;
        }
        state.action = Action.MILK;
        state.target = best;
        state.lastPos = null;
        return true;
    }

    /** 找一只没剪过的成年羊。剪过的、幼崽都不是目标。 */
    private boolean selectShear(Maid maid, LivingEntity entity, State state) {
        Sheep best = null;
        double bestSq = radiusSq();
        Location from = entity.getLocation();
        for (Entity candidate : entity.getWorld().getNearbyEntities(from, radius(), radius(), radius())) {
            if (!(candidate instanceof Sheep sheep) || !sheep.isAdult() || sheep.isSheared()
                    || blacklisted(state, sheep.getLocation())) {
                continue;
            }
            double distance = sheep.getLocation().distanceSquared(from);
            if (distance <= bestSq) {
                bestSq = distance;
                best = sheep;
            }
        }
        if (best == null) {
            return false;
        }
        state.action = Action.SHEAR;
        state.target = best;
        state.lastPos = null;
        return true;
    }

    private double radiusSq() {
        double radius = radius();
        return radius * radius;
    }

    /**
     * 背包存储位里某种材质的总数。
     *
     * <p>只数存储位：装备位（0-5）是主人特意放上去的，饲料/桶都不该从那里取材
     * （与 P5 的 {@code consumeOne} 同一口径）。
     */
    private static int count(Maid maid, Material material) {
        int total = 0;
        int end = MaidInventory.EQUIPMENT_SLOTS + maid.inventory().storageCapacity();
        for (int slot = MaidInventory.EQUIPMENT_SLOTS; slot < end; slot++) {
            ItemStack stack = maid.inventory().get(slot);
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** 当前目标还成立吗。目标被主人牵走、被玩家捡走、羊已经被剪过，都要重新挑。 */
    private boolean stillValid(Maid maid, LivingEntity entity, State state) {
        Entity target = state.target;
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (!target.getWorld().equals(entity.getWorld())) {
            return false;
        }
        if (target instanceof Item item) {
            return collectible(item) && !blacklisted(state, item.getLocation());
        }
        return switch (state.action) {
            case SHEAR -> target instanceof Sheep sheep && sheep.isAdult() && !sheep.isSheared();
            case MILK -> target instanceof Cow cow && cow.isAdult();
            default -> true;
        };
    }

    /** 朝目标走；到了就干活。 */
    private void pursue(Maid maid, LivingEntity entity, State state) {
        Location target = state.target.getLocation();
        if (Movement.arrived(entity, target, REACH)) {
            execute(maid, state);
            return;
        }
        if (!progressed(entity, state)) {
            giveUp(maid, state);
            return;
        }
        if (!Movement.isNavigating(maid)) {
            // 每轮决策最多下一次目标：重复下发会打断 Citizens 的寻路（P3 踩过朝向乱甩）
            Movement.navigate(maid, target, WALK_SPEED);
        }
    }

    /** 到了就干活。先把目标与动作取出来再 reset，免得收尾时把要用的实体清空。 */
    private void execute(Maid maid, State state) {
        Entity target = state.target;
        Action action = state.action;
        reset(state);
        state.nextActionAt = System.currentTimeMillis() + ACTION_COOLDOWN_MS;
        switch (action) {
            case PICKUP -> collect(maid, (Item) target, state);
            case SHEAR -> shear(maid, (Sheep) target);
            case MILK -> milk(maid, (Cow) target);
        }
    }

    /**
     * 挤奶：扣 1 个空桶、给 1 个奶桶（蓝图 P6）。
     *
     * <p>先扣桶再加奶：桶扣不掉就说明中途被主人拿走了，这一趟白走（不产出奶，也不报错）。
     * 奶桶装不下时掉在原地——原版挤奶也是这样掉的。
     */
    private void milk(Maid maid, Cow cow) {
        if (!maid.inventory().consumeOne(Material.BUCKET)) {
            return;
        }
        ItemStack milk = new ItemStack(Material.MILK_BUCKET);
        int leftover = maid.inventory().addItem(milk);
        if (leftover > 0) {
            milk.setAmount(leftover);
            cow.getWorld().dropItemNaturally(cow.getLocation(), milk);
        }
        cow.getWorld().playSound(cow.getLocation(), Sound.ENTITY_COW_MILK, 1f, 1f);
        maid.stats().addMilk();
        reward(maid);
    }

    /**
     * 剪羊毛：羊变裸，羊毛按羊的颜色进包（蓝图 P6）。
     *
     * <p>原版剪毛要玩家手持剪刀，这里不要求——女仆的"工具"就是她的手（主手拿什么她都不该把剪刀剪坏）。
     * 掉落走 {@code getDrops} 那条路的等价做法：直接给对应颜色的羊毛，不经方块/实体掉落表。
     */
    private void shear(Maid maid, Sheep sheep) {
        DyeColor color = sheep.getColor();
        sheep.setSheared(true);
        ItemStack wool = new ItemStack(woolOf(color));
        int leftover = maid.inventory().addItem(wool);
        if (leftover > 0) {
            wool.setAmount(leftover);
            sheep.getWorld().dropItemNaturally(sheep.getLocation(), wool);
        }
        sheep.getWorld().playSound(sheep.getLocation(), Sound.ENTITY_SHEEP_SHEAR, 1f, 1f);
        maid.stats().addShear();
        reward(maid);
    }

    /** 干完一件活：好感 + 统计（统计由调用方加），偶尔一句话（agent.md 第 10 节：心跳不能省）。 */
    private void reward(Maid maid) {
        affection.apply(maid, affection.gain(AffectionReason.WORK_BREED), AffectionReason.WORK_BREED);
        if (ThreadLocalRandom.current().nextDouble() < WORK_LINE_CHANCE) {
            dialogue.send(maid, "on_work_done");
        }
    }

    /** DyeColor → 羊毛材质（LIGHT_BLUE → LIGHT_BLUE_WOOL）；取不到退回白羊毛，别让一次剪毛崩掉 tick。 */
    private static Material woolOf(DyeColor color) {
        Material wool = color == null ? null : Material.matchMaterial(color.name() + "_WOOL");
        return wool == null ? Material.WHITE_WOOL : wool;
    }

    // ---- 卡死与拉黑 ----

    /**
     * 这一轮有没有进展。挪过 0.5 格就刷新时间戳。
     *
     * <p>不影响"是否在导航"的判断：寻路失败时 navigator 会静默停下（isNavigating 变 false），
     * 只判导航中的话会变成原地反复下目标、永不放弃（P5 踩过）。
     */
    private static boolean progressed(LivingEntity entity, State state) {
        Location pos = entity.getLocation();
        if (state.lastPos == null || state.lastPos.distanceSquared(pos) > STUCK_DISTANCE_SQ) {
            state.lastPos = pos.clone();
            state.lastProgressAt = System.currentTimeMillis();
            return true;
        }
        return System.currentTimeMillis() - state.lastProgressAt < STUCK_MS;
    }

    /** 放弃当前目标：停下、把这个位置拉黑 30 秒、等下一轮换目标。 */
    private void giveUp(Maid maid, State state) {
        Movement.stop(maid);
        if (state.target != null) {
            blacklist(state, state.target.getLocation());
        }
        reset(state);
    }

    private static void blacklist(State state, Location location) {
        state.blacklist.put(new BlockVector(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                System.currentTimeMillis() + BLACKLIST_MS);
    }

    private static boolean blacklisted(State state, Location location) {
        Long until = state.blacklist.get(
                new BlockVector(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        return until != null && until > System.currentTimeMillis();
    }

    /** 清掉过期条目，防止长期运行后拉黑表越攒越大。 */
    private static void pruneBlacklist(State state) {
        long now = System.currentTimeMillis();
        state.blacklist.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    /** 收尾：清空目标，但保留 nextActionAt 与拉黑表。 */
    private static void reset(State state) {
        state.action = null;
        state.target = null;
        state.lastPos = null;
    }

    private int radius() {
        return Math.max(1, config.getInt("ranch.radius", 8));
    }
}
