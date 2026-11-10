package dev.catmaid.maid;

import dev.catmaid.data.ItemStackCodec;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 女仆虚拟背包（不依附任何 Bukkit Inventory）。
 *
 * <p>槽位语义固定：0 主手、1 副手、2 头盔、3 胸甲、4 护腿、5 靴子，
 * 6 起为存储位。存储容量随好感度等级扩容（Phase 4 接入），Phase 1 固定 27。
 */
public final class MaidInventory {

    public static final int EQUIPMENT_SLOTS = 6;
    public static final int MAX_STORAGE = 36; // 好感度最高级的容量（Phase 4 逐级解锁 27/30/33/36）
    public static final int TOTAL_SLOTS = EQUIPMENT_SLOTS + MAX_STORAGE;

    private final ItemStack[] slots = new ItemStack[TOTAL_SLOTS];
    private final int storageCapacity;

    public MaidInventory() {
        this(EQUIPMENT_SLOTS + 27);
    }

    private MaidInventory(int storageCapacity) {
        this.storageCapacity = storageCapacity;
    }

    public static MaidInventory withStorage(int storageSlots) {
        return new MaidInventory(EQUIPMENT_SLOTS + Math.min(storageSlots, MAX_STORAGE));
    }

    /** 存储位数量（不含 6 个装备位）。 */
    public int storageCapacity() {
        return storageCapacity - EQUIPMENT_SLOTS;
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

    /**
     * 入包：先叠到同类未满堆，再占空位。放不下时返回剩余数量（0 表示全部放入）。
     */
    public int addItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return 0;
        }
        int remaining = item.getAmount();

        for (int i = EQUIPMENT_SLOTS; i < storageCapacity && remaining > 0; i++) {
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
        for (int i = EQUIPMENT_SLOTS; i < storageCapacity && remaining > 0; i++) {
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
