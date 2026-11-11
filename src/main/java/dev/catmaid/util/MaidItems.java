package dev.catmaid.util;

import dev.catmaid.maid.Maid;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 女仆相关的特殊物品：铃铛（倒下后的形态）与魂符（召唤凭证）。
 *
 * <p>两者的身份都靠 PersistentDataContainer 里的键区分，不靠 lore 文字——玩家改个 lore
 * 就能伪造"别人的铃铛"，用 PDC 才可靠。键的命名空间用插件名，避免和其他插件撞。
 */
public final class MaidItems {

    /** 铃铛 → 女仆 UUID（字符串）。 */
    private static final String KEY_MAID_ID = "maid_id";
    /** 魂符标记位。 */
    private static final String KEY_TALISMAN = "soul_talisman";

    private MaidItems() {
    }

    /** 铃铛用的材质，config 的 {@code death.bell-material}（默认原版 BELL）。 */
    public static Material bellMaterial(Plugin plugin) {
        Material material = Material.matchMaterial(
                plugin.getConfig().getString("death.bell-material", "BELL"));
        if (material == null || material.isAir() || !material.isItem()) {
            return Material.BELL;
        }
        return material;
    }

    /** 女仆倒下后掉落/入包的铃铛。 */
    public static ItemStack bell(Plugin plugin, Maid maid) {
        ItemStack item = new ItemStack(bellMaterial(plugin));
        ItemMeta meta = item.getItemMeta();
        Map<String, String> ph = Map.of("name", maid.getName());
        meta.displayName(noItalic(Messages.get("bell-name", ph)));
        List<Component> lore = new ArrayList<>();
        Messages.getList("bell-lore", ph).forEach(line -> lore.add(noItalic(line)));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key(plugin, KEY_MAID_ID),
                PersistentDataType.STRING, maid.getId().toString());
        item.setItemMeta(meta);
        return item;
    }

    /** 这枚铃铛属于哪只女仆；不是本插件的铃铛返回 null。 */
    public static UUID bellMaidId(Plugin plugin, ItemStack item) {
        if (item == null || item.getType() != bellMaterial(plugin)) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer().get(key(plugin, KEY_MAID_ID), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null; // 改坏了的铃铛，当普通铃铛处理
        }
    }

    /** 魂符：材质由 config.interaction.talisman-material 决定。 */
    public static ItemStack talisman(Plugin plugin, Material material, int amount) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(Messages.get("talisman-name")));
        List<Component> lore = new ArrayList<>();
        Messages.getList("talisman-lore").forEach(line -> lore.add(noItalic(line)));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key(plugin, KEY_TALISMAN), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isTalisman(Plugin plugin, ItemStack item, Material material) {
        if (item == null || item.getType() != material) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(key(plugin, KEY_TALISMAN), PersistentDataType.BYTE);
    }

    /** 主手物品减 1，用来就清空。喂食、用铃铛、用魂符都是这个语义。 */
    public static void consumeOneInMainHand(Player player, ItemStack item) {
        int amount = item.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        item.setAmount(amount - 1);
        player.getInventory().setItemInMainHand(item);
    }

    private static NamespacedKey key(Plugin plugin, String name) {
        return new NamespacedKey(plugin, name);
    }

    /** 物品名/lore 默认带斜体，去掉更好看（与 GUI 里的处理一致）。 */
    private static Component noItalic(Component source) {
        return Component.text().decoration(TextDecoration.ITALIC, false).append(source).build();
    }
}
