package dev.catmaid.maid;

import dev.catmaid.data.ItemStackCodec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 女仆虚拟背包（不依附任何 Bukkit Inventory）。
 *
 * <p>槽位语义固定：0 主手、1 副手、2 头盔、3 胸甲、4 护腿、5 靴子，6 起为存储位。
 * 底层数组恒为 36 格存储，{@link #storageCapacity()} 只决定"当前能放进去多少"——
 * 这样好感度掉级时已存在的物品不会凭空消失，只是那几格在 GUI 里显示为锁定。
 */
public final class MaidInventory {

    public static final int EQUIPMENT_SLOTS = 6;
    public static final int MAX_STORAGE = 36; // 好感度最高级的容量
    public static final int TOTAL_SLOTS = EQUIPMENT_SLOTS + MAX_STORAGE;

    /** 好感度 0-19 的存储格数，也是新建女仆的默认容量。 */
    public static final int MIN_STORAGE = 27;

    private final ItemStack[] slots = new ItemStack[TOTAL_SLOTS];
    private int storageCapacity = MIN_STORAGE;

    /** 存储位数量（不含 6 个装备位）。 */
    public int storageCapacity() {
        return storageCapacity;
    }

    /** 由 {@link Maid#setAffection(double)} 调用，是容量的唯一同步点。 */
    public void setStorageCapacity(int capacity) {
        this.storageCapacity = Math.max(0, Math.min(capacity, MAX_STORAGE));
    }

    /** 好感度 → 存储格数（蓝图 0.7 等级表）。Phase 4 的 AffectionManager 直接复用。 */
    public static int capacityForAffection(double affection) {
        if (affection >= 60) {
            return MAX_STORAGE; // 挚爱、羁绊同为 36 格
        }
        if (affection >= 40) {
            return 33;
        }
        if (affection >= 20) {
            return 30;
        }
        return MIN_STORAGE;
    }

    /** 好感度 → 等级序号 0..4（陌生人/熟识/伙伴/挚爱/羁绊）。 */
    public static int levelForAffection(double affection) {
        if (affection >= 80) {
            return 4;
        }
        if (affection >= 60) {
            return 3;
        }
        if (affection >= 40) {
            return 2;
        }
        if (affection >= 20) {
            return 1;
        }
        return 0;
    }

    public ItemStack get(int slot) {
        return slot >= 0 && slot < TOTAL_SLOTS ? slots[slot] : null;
    }

    public void set(int slot, ItemStack item) {
        if (slot >= 0 && slot < TOTAL_SLOTS) {
            slots[slot] = item == null || item.getType() == Material.AIR ? null : item;
        }
    }

    public ItemStack getMainHand() {
        return get(0);
    }

    /** 已占用的存储格数，仅供信息书展示。 */
    public int usedStorageSlots() {
        int used = 0;
        int end = EQUIPMENT_SLOTS + storageCapacity;
        for (int i = EQUIPMENT_SLOTS; i < end; i++) {
            if (slots[i] != null) {
                used++;
            }
        }
        return used;
    }

    /**
     * 入包：先叠到同类未满堆，再占空位。放不下时返回剩余数量（0 表示全部放入）。
     */
    public int addItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return 0;
        }
        int remaining = item.getAmount();
        int end = EQUIPMENT_SLOTS + storageCapacity;

        for (int i = EQUIPMENT_SLOTS; i < end && remaining > 0; i++) {
            ItemStack existing = slots[i];
            if (existing != null && existing.isSimilar(item)) {
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space > 0) {
                    int move = Math.min(space, remaining);
                    existing.setAmount(existing.getAmount() + move);
                    remaining -= move;
                }
            }
        }
        for (int i = EQUIPMENT_SLOTS; i < end && remaining > 0; i++) {
            if (slots[i] == null) {
                ItemStack copy = item.clone();
                copy.setAmount(remaining);
                slots[i] = copy;
                remaining = 0;
            }
        }
        return remaining;
    }

    /** 供持久化使用：只输出非空槽位。 */
    public Map<Integer, byte[]> serialize() {
        Map<Integer, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            if (slots[i] != null) {
                out.put(i, ItemStackCodec.encode(slots[i]));
            }
        }
        return out;
    }
}
