package dev.catmaid.maid;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 女仆领域对象（内存态）。持久化字段与 0.6 的 maids 表一一对应。
 */
public final class Maid {

    public enum Mode {
        IDLE, FOLLOW, COMBAT, FARM, RANCH
    }

    private final UUID id;
    private UUID ownerUuid;
    private String name;
    private String modelId;
    private Mode mode = Mode.FOLLOW;
    private double affection;
    private String ownerTitle = "主人";
    private boolean alive = true;
    private boolean sitting;
    /** 收纳态（P9）：她在这只魂符里，NPC 处于 despawn、没有实体，但仍占着主人名额。 */
    private boolean stored;
    private Location home;
    private int npcId = -1;
    /** 血量上限，来自 {@code combat.maid-max-health}（启动/召唤时由 MaidManager 灌入）。 */
    private double maxHealth = 40;

    private final MaidInventory inventory = new MaidInventory();
    private final MaidStats stats = new MaidStats();

    public Maid(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Phase 1 只改字段；模式切换事件（MaidModeChangeEvent）与模式台词属 Phase 4/8，
     * 到那一步再接进来。
     */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public double getAffection() {
        return affection;
    }

    /**
     * 好感度是背包容量的唯一来源：改好感度就顺手同步容量，GUI 每次打开按 {@code capacity()} 渲染，
     * 因此不需要在别处再调一次"扩容/缩容"。Phase 4 的 AffectionManager 走这个 setter 即可。
     */
    public void setAffection(double affection) {
        this.affection = affection;
        this.inventory.setStorageCapacity(MaidInventory.capacityForAffection(affection));
    }

    public String getOwnerTitle() {
        return ownerTitle;
    }

    public void setOwnerTitle(String ownerTitle) {
        this.ownerTitle = ownerTitle;
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }

    public boolean isSitting() {
        return sitting;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }

    public boolean isStored() {
        return stored;
    }

    public void setStored(boolean stored) {
        this.stored = stored;
    }

    public Location getHome() {
        return home == null ? null : home.clone();
    }

    public void setHome(Location home) {
        this.home = home == null ? null : home.clone();
    }

    public int getNpcId() {
        return npcId;
    }

    public void setNpcId(int npcId) {
        this.npcId = npcId;
    }

    public double getMaxHealth() {
        return maxHealth;
    }

    /** 由 MaidManager 按 config 灌入（不落库：换配置重启即可生效，不需要每人存一份）。 */
    public void setMaxHealth(double maxHealth) {
        this.maxHealth = maxHealth;
    }

    public MaidInventory inventory() {
        return inventory;
    }

    public MaidStats stats() {
        return stats;
    }

    /** Citizens NPC；未生成或已被删除时返回 null。 */
    public NPC npc() {
        return npcId < 0 ? null : CitizensAPI.getNPCRegistry().getById(npcId);
    }

    /** NPC 的实体；NPC 未生成、区块未加载时为 null。 */
    public LivingEntity entity() {
        NPC npc = npc();
        if (npc == null || !npc.isSpawned()) {
            return null;
        }
        Entity entity = npc.getEntity();
        return entity instanceof LivingEntity living ? living : null;
    }

    /** 把虚拟背包的 6 个装备位写回实体装备槽。 */
    public void applyEquipment() {
        LivingEntity entity = entity();
        if (entity == null) {
            return;
        }
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setItemInMainHand(inventory.get(0));
        equipment.setItemInOffHand(inventory.get(1));
        equipment.setHelmet(inventory.get(2));
        equipment.setChestplate(inventory.get(3));
        equipment.setLeggings(inventory.get(4));
        equipment.setBoots(inventory.get(5));
        // 女仆模型会盖住原版装备层，肉眼和命令都查不到实体穿了什么，
        // 这行日志是核对 Phase 2 "装备同步" 的唯一手段（战斗阶段排查伤害也要用）。
        Bukkit.getLogger().info("[CatMaid] " + name + " 装备同步"
                + " 主手=" + slotName(equipment.getItemInMainHand())
                + " 副手=" + slotName(equipment.getItemInOffHand())
                + " 头盔=" + slotName(equipment.getHelmet())
                + " 胸甲=" + slotName(equipment.getChestplate())
                + " 护腿=" + slotName(equipment.getLeggings())
                + " 靴子=" + slotName(equipment.getBoots()));
    }

    private static String slotName(ItemStack item) {
        return item == null || item.getType().isAir() ? "空" : item.getType().name();
    }

    /**
     * 实体就绪后要补的两件事：装备写回 + 血量上限。
     *
     * <p>为什么必须是一个方法：Citizens 每次 spawn 都会重建实体，只在 onEnable 设一次会在
     * despawn/重生成后失效；漏掉任何一条生成路径，表现就是"她只有 20 血"或"血条 20/40"
     * （api-facts 20.9-A5）。所以 {MaidTrait#onSpawn}、召唤、恢复、上线补挂、铃铛复活
     * 统一走这里。
     */
    public void applyVitals() {
        applyEquipment();
        applyMaxHealth();
    }

    /**
     * 把血量上限写进实体并回满。
     *
     * <p>玩家型 NPC 默认 20 血，不改上限她两三下就倒；而改上限不重设当前血量会留下
     * "20/40"的半血条，所以两步必须一起做。
     */
    private void applyMaxHealth() {
        LivingEntity entity = entity();
        if (entity == null) {
            return;
        }
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(maxHealth);
        entity.setHealth(attribute.getValue());
    }

    /** 入虚拟背包；放不下时返回 false（调用方决定掉落到地面）。 */
    public boolean addItem(ItemStack item) {
        return inventory.addItem(item) == 0;
    }
}
