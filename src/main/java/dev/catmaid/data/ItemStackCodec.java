package dev.catmaid.data;

import org.bukkit.inventory.ItemStack;

/** ItemStack ⇄ byte[]，用于 maid_items.data 列。 */
public final class ItemStackCodec {

    private ItemStackCodec() {
    }

    public static byte[] encode(ItemStack item) {
        return item.serializeAsBytes();
    }

    /**
     * 解码失败返回 null 而不是抛异常：单个槽位的坏数据不该让整只女仆加载失败。
     */
    public static ItemStack decode(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(data);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
