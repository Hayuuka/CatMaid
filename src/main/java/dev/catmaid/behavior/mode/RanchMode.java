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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    /**
     * 喂一口让它求偶多久（蓝图 P6 的 600 tick = 30 秒）。
     *
     * <p>必须走 {@code Animals#setLoveModeTicks}：Paper 26.2 的 {@code Breedable#setBreed(boolean)}
     * 改的是**年龄**（true→setAge(0)、false→setAge(6000) 变成幼崽），跟求偶没关系，
     * 照 api-facts 19.9-2 的旧建议写会「喂了小麦却没有红心」（冲突记录见 20.x）。
     */
    private static final int LOVE_TICKS = 600;

    private final FileConfiguration config;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    private final Map<UUID, State> states = new HashMap<>();
    /** 干活顺序，来自 {@code ranch.action-priority}。构造时解析一次：改配置要重启（P8 才做 reload）。 */
    private final List<String> priority = new ArrayList<>();

    private static final List<String> DEFAULT_PRIORITY = List.of("BREED", "SHEAR", "MILK");

    public RanchMode(FileConfiguration config, AffectionManager affection, DialogueManager dialogue) {
        this.config = config;
        this.affection = affection;
        this.dialogue = dialogue;
        List<String> configured = config.getStringList("ranch.action-priority");
        for (String name : configured.isEmpty() ? DEFAULT_PRIORITY : configured) {
            priority.add(name.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** 当前这一步要做什么。同一时刻只有一件事，避免导航目标互相打架。 */
    private enum Action {
        PICKUP, BREED, SHEAR, MILK
    }

    /** 每只女仆的畜牧状态。Mode 是单例，状态必须按女仆分，否则十只会抢同一个目标。 */
    private static final class State {
        private Action action;
        private Entity target;
        /** 繁殖用的第二只（还没喂的那只）。喂完第一只就换成它做 target。 */
        private Animals partner;
        /** 繁殖里已经喂过的那只。非空 = 正在走第二趟，凑齐两只才结算。 */
        private Animals mate;
        /** 这一次繁殖用的饲料（按动物的类型定，选中时就定下来，走过去的路上背包被翻动也不换）。 */
        private Material food;
        private Location lastPos;
        private long lastProgressAt;
        /** 下一次允许动作的时间戳（毫秒）。 */
        private long nextActionAt;
        /** 走不过去的位置 → 解禁时间戳。物品原地不动、动物被栅栏困住时都靠它止损。 */
        private final Map<BlockVector, Long> blacklist = new HashMap<>();
        /** 已经喊过"没饲料"了。只有补给到位（再次成功选中）才复位，免得每次决策都念一遍。 */
        private boolean warnedNoFood;
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
            // 部分进包：余量留在原地等她腾出空位。getItemStack 是副本，必须写回去才生效
            stack.setAmount(leftover);
            item.setItemStack(stack);
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
        // 顺序由 ranch.action-priority 决定（蓝图 P6）；写错的词直接跳过，不让一个笔误打死整个模式
        for (String name : priority) {
            boolean found = switch (name) {
                case "BREED" -> selectBreed(maid, entity, state);
                case "SHEAR" -> selectShear(maid, entity, state);
                case "MILK" -> selectMilk(maid, entity, state);
                default -> false;
            };
            if (found) {
                return;
            }
        }
    }

    /**
     * 繁殖：找一对能生的动物。
     *
     * <p>成对判定比蓝图多两条：两只都必须成年且 {@code canBreed()}（否则她会对着小牛和冷却中的牛
     * 一直喂小麦，api-facts 19.9-5.3），并且跳过已驯服的（别去配主人家的狗和猫）。
     *
     * <p>饲料要 2 份：原版是"一只一份"，只有 1 份时喂完一只另一只没得吃，等于白喂。
     */
    private boolean selectBreed(Maid maid, LivingEntity entity, State state) {
        Location from = entity.getLocation();
        Map<EntityType, List<Animals>> groups = new EnumMap<>(EntityType.class);
        for (Entity candidate : entity.getWorld().getNearbyEntities(from, radius(), radius(), radius())) {
            // isLoveMode() 必须单独判：canBreed() 只看年龄，已经在求偶的动物能被反复喂（白扣饲料）
            if (!(candidate instanceof Animals animal) || !animal.isAdult() || !animal.canBreed()
                    || animal.isLoveMode() || animal.isDead()
                    || (animal instanceof Tameable tamed && tamed.isTamed())
                    || blacklisted(state, animal.getLocation())) {
                continue;
            }
            groups.computeIfAbsent(animal.getType(), key -> new ArrayList<>()).add(animal);
        }

        Animals first = null;
        Animals second = null;
        Material chosen = null;
        double bestScore = Double.MAX_VALUE;
        boolean starved = false;
        for (List<Animals> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparingDouble(animal -> animal.getLocation().distanceSquared(from)));
            Animals a = group.get(0);
            Animals b = group.get(1);
            Material food = foodFor(maid, a);
            if (food == null || count(maid, food) < 2) {
                // 有对象却没饲料：记一笔，全部落空后只提醒一次（蓝图验收项）
                starved = true;
                continue;
            }
            double score = a.getLocation().distanceSquared(from) + b.getLocation().distanceSquared(from);
            if (score < bestScore) {
                bestScore = score;
                first = a;
                second = b;
                chosen = food;
            }
        }
        if (first == null) {
            if (starved) {
                warnNoFood(maid, state);
            }
            return false;
        }
        // 有饲料了：把"喊过没饲料"的闸门复位，下次再耗尽还能提醒一次
        state.warnedNoFood = false;
        state.action = Action.BREED;
        state.target = first;
        state.partner = second;
        state.mate = null;
        state.food = chosen;
        state.lastPos = null;
        return true;
    }

    /**
     * 这种动物吃什么。
     *
     * <p>{@code ranch.breed-food} 只用来覆盖特例；没写的一律问原版
     * （{@code Animals#isBreedItem} 直接回答"这食物能不能喂它"），省得把七八种动物全配一遍。
     */
    private Material foodFor(Maid maid, Animals sample) {
        String configured = config.getString("ranch.breed-food." + sample.getType().name());
        if (configured != null) {
            Material material = Material.matchMaterial(configured);
            if (material != null) {
                return material;
            }
        }
        int end = MaidInventory.EQUIPMENT_SLOTS + maid.inventory().storageCapacity();
        for (int slot = MaidInventory.EQUIPMENT_SLOTS; slot < end; slot++) {
            ItemStack stack = maid.inventory().get(slot);
            if (stack != null && sample.isBreedItem(stack)) {
                return stack.getType();
            }
        }
        return null;
    }

    /**
     * 饲料耗尽的提醒：每只女仆只说一次。
     *
     * <p>放在"确实有对象却喂不了"的路径上，不是"没饲料就喊"——牧场里没有牛的时候她不该念叨饲料。
     * 台词键 {@code on_no_food} 在 {@code DialogueManager} 里找不到是静默丢弃的，改 dialogue.yml 时别拼错。
     */
    private void warnNoFood(Maid maid, State state) {
        if (state.warnedNoFood) {
            return;
        }
        state.warnedNoFood = true;
        dialogue.send(maid, "on_no_food");
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
            case BREED -> target instanceof Animals animal && animal.isAdult() && animal.canBreed()
                    && !animal.isLoveMode();
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

    /** 到了就干活。先把目标与动作取出来，再交给各动作自己收尾（繁殖是两趟，不能提前清状态）。 */
    private void execute(Maid maid, State state) {
        switch (state.action) {
            case PICKUP -> {
                Item item = (Item) state.target;
                finish(state);
                collect(maid, item, state);
            }
            case SHEAR -> {
                Sheep sheep = (Sheep) state.target;
                finish(state);
                shear(maid, sheep);
            }
            case MILK -> {
                Cow cow = (Cow) state.target;
                finish(state);
                milk(maid, cow);
            }
            case BREED -> breed(maid, state);
        }
    }

    /**
     * 繁殖：一只一份地喂。
     *
     * <p>分两趟走——先喂近的那只、再走去它对象那儿喂第二只。原版只有两只都进了求偶状态才会生，
     * 所以好感与统计在第二只喂上之后才结算；中途对象被牵走就白喂一次，不扣好感也不给奖励。
     */
    private void breed(Maid maid, State state) {
        Animals animal = (Animals) state.target;
        if (!feed(maid, animal, state.food)) {
            finish(state);
            return;
        }
        hearts(animal);
        Animals partner = state.partner;
        state.partner = null;
        if (state.mate == null && partner != null && partner.isValid() && !partner.isDead()
                && partner.canBreed()) {
            // 第一只已点上红心，转身去找它对象；这一趟不收尾
            state.mate = animal;
            state.target = partner;
            state.lastPos = null;
            return;
        }
        if (state.mate != null) {
            maid.stats().addBreed();
            reward(maid);
        }
        finish(state);
    }

    /** 喂一口：扣 1 份饲料、让它进求偶。饲料没了就返回 false（走过去的路上被主人拿走了也算）。 */
    private boolean feed(Maid maid, Animals animal, Material food) {
        if (food == null || !maid.inventory().consumeOne(food)) {
            return false;
        }
        animal.setLoveModeTicks(LOVE_TICKS);
        animal.getWorld().playSound(animal.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 1f);
        return true;
    }

    private static void hearts(Entity entity) {
        entity.getWorld().spawnParticle(Particle.HEART, entity.getLocation().add(0, 1, 0), 6, 0.3, 0.3, 0.3, 0);
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
        state.partner = null;
        state.mate = null;
        state.food = null;
        state.lastPos = null;
    }

    /** 这一趟做完了：收尾 + 进动作冷却（蓝图 P6 的 2 秒，防止一秒内连剪三只羊）。 */
    private static void finish(State state) {
        reset(state);
        state.nextActionAt = System.currentTimeMillis() + ACTION_COOLDOWN_MS;
    }

    private int radius() {
        return Math.max(1, config.getInt("ranch.radius", 8));
    }
}
