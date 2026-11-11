package dev.catmaid.maid;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
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
    private Location home;
    private int npcId = -1;

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
    }

    /** 入虚拟背包；放不下时返回 false（调用方决定掉落到地面）。 */
    public boolean addItem(ItemStack item) {
        return inventory.addItem(item) == 0;
    }
}
