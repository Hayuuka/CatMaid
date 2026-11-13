package dev.catmaid.util;

import dev.catmaid.maid.Maid;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 女仆相关凭证物品的<b>唯一工厂与识别器</b>（蓝图 P9-9.3）：
 * 铃铛（倒下后的形态）、魂符（三种状态）。
 *
 * <p><b>魂符一物三态</b>（2026-11-13 按车万女仆源码重构，见 api-facts 25.2）：
 * TLM 的 `ItemSmartSlab` 用一个类 + 三个 Type（`INIT` / `EMPTY` / `HAS_MAID`）实现同一件"魂符"：
 * INIT 右键地面召唤一只随机女仆、<b>用掉后原地变空魂符</b>；EMPTY 对着自己的女仆收纳、
 * 变成 HAS_MAID；HAS_MAID 对着空地放出、<b>又变回空魂符</b>。整条链数量守恒、一张符贯穿始终。
 *
 * <p>我们照抄这个结构，三种状态靠 PDC 区分（材质、名字前缀不同）：
 * 未唤醒 = {@code soul_talisman} + {@code soul_init}；空 = 只有 {@code soul_talisman}；
 * 装着 = 再加 {@code maid_id}。状态之间用"原地替换手里物品"迁移，不做消耗。
 *
 * <p>为什么要有"依赖模式"（{@code dependency.provider}）：服主可以在原版物品和 CraftEngine
 * 自定义物品之间二选一——后者外观更好，但需要 CE 侧先定义物品 ID。所有构造/识别都收在这一个类里，
 * 上层（监听器、命令、GUI）只问"这是不是铃铛"，不关心底层是哪个 provider。
 *
 * <p><b>身份认的是 PDC，不是材质</b>：铃铛 = 有 {@code catmaid:maid_id} 且没有魂符标记；
 * 魂符 = 有 {@code catmaid:soul_talisman}；装有女仆的魂符 = 魂符 + {@code maid_id}。
 * 这样切换 provider 后玩家手里的旧铃铛/旧魂符仍然认得出（蓝图 9.3「两模式物品互认」），
 * 也挡住"改个 lore 就伪造别人的铃铛"。
 */
public final class MaidItems {

    public enum Provider {
        VANILLA, CRAFTENGINE
    }

    /** 铃铛/收纳魂符 → 女仆 UUID（字符串）。 */
    private static final String KEY_MAID_ID = "maid_id";
    /** 魂符标记位（三种状态都有）。 */
    private static final String KEY_TALISMAN = "soul_talisman";
    /** 未唤醒标记位：有它 = 还能召唤一只（TLM 的 Type.INIT）。 */
    private static final String KEY_INIT = "soul_init";
    /** 未唤醒魂符的主人锁定（开局赠送时写入，防止转手给别人用）。 */
    private static final String KEY_INIT_OWNER = "soul_init_owner";

    private static final String CE_ITEMS_API = "net.momirealms.craftengine.bukkit.api.CraftEngineItems";

    private final Plugin plugin;
    private final Logger logger;
    private final Provider provider;
    private final Material bellMaterial;
    private final Material talismanMaterial;
    private final String ceBellId;
    private final String ceTalismanId;

    // ---- CraftEngine 反射句柄（它是 paper-plugin，classloader 隔离，只能反射） ----
    private boolean ceTried;
    private boolean ceReady;
    private Method ceById;
    private Method ceBuildBukkitItem;
    private Method ceGetCustomItemId;
    private boolean ceMissingWarned;
    private boolean ceIdentityWarned;

    public MaidItems(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.provider = parseProvider(plugin.getConfig().getString("dependency.provider", "vanilla"));
        this.bellMaterial = firstItem(plugin.getConfig().getString("death.bell-material", "BELL"), Material.BELL);
        this.talismanMaterial = firstItem(
                plugin.getConfig().getString("interaction.talisman-material", "AMETHYST_SHARD"),
                Material.AMETHYST_SHARD);
        this.ceBellId = plugin.getConfig().getString("dependency.craftengine.bell-id", "catmaid:bell");
        this.ceTalismanId = plugin.getConfig().getString("dependency.craftengine.talisman-id", "catmaid:talisman");
    }

    public Provider provider() {
        return provider;
    }

    /** 魂符的材质（三态共用）。释放/召唤后的原版冷却按材质记，所以监听器要拿它。 */
    public Material talismanMaterial() {
        return talismanMaterial;
    }

    private static Provider parseProvider(String raw) {
        if (raw != null && raw.trim().equalsIgnoreCase("craftengine")) {
            return Provider.CRAFTENGINE;
        }
        return Provider.VANILLA;
    }

    private static Material firstItem(String name, Material fallback) {
        Material material = Material.matchMaterial(name == null ? "" : name);
        if (material == null || material.isAir() || !material.isItem()) {
            return fallback;
        }
        return material;
    }

    // ---- 构造 ----

    /** 女仆倒下后掉落/入包的铃铛。 */
    public ItemStack bell(Maid maid) {
        ItemStack item = base(ceBellId, bellMaterial);
        Map<String, String> ph = Map.of("name", maid.getName());
        applyMeta(item, Messages.get("bell-name", ph), Messages.getList("bell-lore", ph), maid.getId());
        return item;
    }

    /** 空魂符：收纳容器。对着自己的女仆右键收人，不对着女仆时什么都不做（不召唤）。 */
    public ItemStack talisman() {
        return talisman(1);
    }

    public ItemStack talisman(int amount) {
        ItemStack item = base(ceTalismanId, talismanMaterial);
        item.setAmount(Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(Messages.get("talisman-name")));
        List<Component> lore = new ArrayList<>();
        Messages.getList("talisman-lore").forEach(line -> lore.add(noItalic(line)));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(key(KEY_TALISMAN), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        warnIfLostCeIdentity(item, ceTalismanId);
        return item;
    }

    /** 装有女仆的魂符（收纳凭证）：外形同魂符，多一个 maid_id。 */
    public ItemStack storedTalisman(Maid maid) {
        ItemStack item = base(ceTalismanId, talismanMaterial);
        Map<String, String> ph = Map.of("name", maid.getName());
        applyMeta(item, Messages.get("stored-talisman-name", ph),
                Messages.getList("stored-talisman-lore", ph), maid.getId());
        // applyMeta 会覆盖 PDC，魂符标记要在它之后补上
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key(KEY_TALISMAN), PersistentDataType.BYTE, (byte) 1);
        glow(meta);
        item.setItemMeta(meta);
        warnIfLostCeIdentity(item, ceTalismanId);
        return item;
    }

    /**
     * 未唤醒的魂符：还能召唤一只（TLM 的 {@code Type.INIT}，物品 ID {@code smart_slab_init}）。
     *
     * <p><b>用掉之后变回空魂符</b>（由监听器原地替换），不是消耗品——这一条是 TLM 的核心设计，
     * 照抄过来玩家才能"一张符先唤醒、再收人、再放出"。
     *
     * @param ownerId 主人 UUID（写进 PDC 做锁定，别人右键放不出来）；传 null 表示不锁定
     */
    public ItemStack initTalisman(UUID ownerId) {
        ItemStack item = base(ceTalismanId, talismanMaterial);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(Messages.get("init-talisman-name")));
        List<Component> lore = new ArrayList<>();
        Messages.getList("init-talisman-lore").forEach(line -> lore.add(noItalic(line)));
        meta.lore(lore);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(key(KEY_TALISMAN), PersistentDataType.BYTE, (byte) 1);
        pdc.set(key(KEY_INIT), PersistentDataType.BYTE, (byte) 1);
        if (ownerId != null) {
            pdc.set(key(KEY_INIT_OWNER), PersistentDataType.STRING, ownerId.toString());
        }
        glow(meta);
        item.setItemMeta(meta);
        warnIfLostCeIdentity(item, ceTalismanId);
        return item;
    }

    /** 未唤醒魂符的主人锁定；没锁定（或数据被改坏）返回 null。 */
    public UUID initOwnerOf(ItemStack item) {
        var pdc = pdc(item);
        if (pdc == null) {
            return null;
        }
        String raw = pdc.get(key(KEY_INIT_OWNER), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 附魔光：TLM 的 {@code isFoil()} 只有非 EMPTY 才发光，玩家一眼能看出"这张符里有人"。
     * 用 UNBREAKING I + 隐藏附魔标记，不产生实际属性。
     */
    private void glow(ItemMeta meta) {
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
    }

    private void applyMeta(ItemStack item, Component name, List<Component> lore, UUID maidId) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(name));
        List<Component> lines = new ArrayList<>();
        lore.forEach(line -> lines.add(noItalic(line)));
        meta.lore(lines);
        meta.getPersistentDataContainer().set(key(KEY_MAID_ID), PersistentDataType.STRING, maidId.toString());
        item.setItemMeta(meta);
    }

    /** 按 provider 取底物品；CE 侧没有这个 ID 时退回原版材质（降级顺序见蓝图 9.3）。 */
    private ItemStack base(String ceId, Material fallback) {
        if (provider == Provider.CRAFTENGINE) {
            ItemStack crafted = craftEngineItem(ceId);
            if (crafted != null) {
                return crafted;
            }
        }
        return new ItemStack(fallback);
    }

    // ---- 识别 ----

    /** 是不是"她倒下后的铃铛"：有 maid_id 且没有魂符标记（旧版铃铛只有 maid_id，照样认）。 */
    public boolean isBell(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        PersistentDataContainer pdc = pdc(item);
        return pdc != null
                && pdc.has(key(KEY_MAID_ID), PersistentDataType.STRING)
                && !pdc.has(key(KEY_TALISMAN), PersistentDataType.BYTE);
    }

    /** 任意魂符（空的和装着女仆的都算）。 */
    public boolean isTalisman(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        PersistentDataContainer pdc = pdc(item);
        return pdc != null && pdc.has(key(KEY_TALISMAN), PersistentDataType.BYTE);
    }

    /** 装着女仆的魂符。 */
    public boolean isStoredTalisman(ItemStack item) {
        if (!isTalisman(item)) {
            return false;
        }
        PersistentDataContainer pdc = pdc(item);
        return pdc != null && pdc.has(key(KEY_MAID_ID), PersistentDataType.STRING);
    }

    /** 未唤醒的魂符（还能召唤一只）。 */
    public boolean isInitTalisman(ItemStack item) {
        if (!isTalisman(item)) {
            return false;
        }
        PersistentDataContainer pdc = pdc(item);
        return pdc != null && pdc.has(key(KEY_INIT), PersistentDataType.BYTE);
    }

    /** 铃铛 / 收纳魂符里记的女仆 UUID；不是这两种物品（或数据被改坏）返回 null。 */
    public UUID maidIdOf(ItemStack item) {
        PersistentDataContainer pdc = pdc(item);
        if (pdc == null) {
            return null;
        }
        String raw = pdc.get(key(KEY_MAID_ID), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null; // 改坏了的凭证，当普通物品处理
        }
    }

    /**
     * 原地替换手里的魂符（TLM 的 {@code setItemInHand} 语义）。
     *
     * <p>魂符三态之间靠这个迁移：未唤醒→空、空→装着、装着→空。不是"消耗 + 新给一张"，
     * 所以数量守恒、不会掉地、也不会因为背包满而丢符。
     *
     * <p>手里只有一张时直接换；万一手里是旧版堆叠（>1），只扣一张、把新状态塞背包——
     * 宁可多一步也不能把玩家的符吃掉。
     */
    public void replaceHeld(Player player, ItemStack held, ItemStack replacement) {
        if (held.getAmount() > 1) {
            held.setAmount(held.getAmount() - 1);
            player.getInventory().setItemInMainHand(held);
            player.getInventory().addItem(replacement).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            return;
        }
        player.getInventory().setItemInMainHand(replacement);
    }

    /** 主手物品减 1，用完即清空。喂食、用铃铛都是这个语义（魂符不走这里，见 replaceHeld）。 */
    public void consumeOne(Player player, ItemStack item) {
        int amount = item.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        item.setAmount(amount - 1);
        player.getInventory().setItemInMainHand(item);
    }

    private PersistentDataContainer pdc(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer();
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    /** 物品名/lore 默认带斜体，去掉更好看（与 GUI 里的处理一致）。 */
    private static Component noItalic(Component source) {
        return Component.text().decoration(TextDecoration.ITALIC, false).append(source).build();
    }

    // ---- CraftEngine（反射） ----

    private ItemStack craftEngineItem(String itemId) {
        if (!ensureCraftEngine()) {
            return null;
        }
        try {
            Object definition = ceById.invoke(null, itemId);
            if (definition == null) {
                warnCeMissing("CraftEngine 里没有物品 " + itemId + "，按原版物品处理"
                        + "（请在 CE 的 pack 里定义该 ID，或把 dependency.provider 改回 vanilla）");
                return null;
            }
            Object built = ceBuildBukkitItem.invoke(definition);
            return built instanceof ItemStack stack ? stack : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            warnCeMissing("CraftEngine 构造物品失败，按原版物品处理：" + e);
            return null;
        }
    }

    /**
     * 写完 PDC 后校验 CE 是否还认这件物品。
     *
     * <p>CE 用自己的组件记物品身份，Bukkit 的 PDC 与它共用 {@code custom_data}。
     * 万一写入把 CE 身份覆盖掉，物品会退回原版外观——功能不受影响（识别靠我们的 PDC），
     * 但视觉降级，必须留痕（蓝图 9.3 的降级顺序）。
     */
    private void warnIfLostCeIdentity(ItemStack item, String ceId) {
        if (provider != Provider.CRAFTENGINE || !ceReady) {
            return;
        }
        try {
            if (isCraftEngineItem(item, ceId)) {
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return;
        }
        if (!ceIdentityWarned) {
            ceIdentityWarned = true;
            logger.warning("写入 catmaid 数据后 CraftEngine 不再识别 " + ceId
                    + "，物品外观会退回原版（功能正常，识别靠 catmaid 自己的 PDC）");
        }
    }

    private boolean isCraftEngineItem(ItemStack item, String ceId) throws ReflectiveOperationException {
        Object customId = ceGetCustomItemId.invoke(null, item);
        return customId != null && ceId.equals(customId.toString());
    }

    private boolean ensureCraftEngine() {
        if (ceTried) {
            return ceReady;
        }
        ceTried = true;
        Plugin craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null) {
            warnCeMissing("dependency.provider=craftengine 但没装 CraftEngine，按原版物品处理");
            return false;
        }
        try {
            ClassLoader loader = craftEngine.getClass().getClassLoader();
            Class<?> api = Class.forName(CE_ITEMS_API, true, loader);
            ceById = api.getMethod("byId", String.class);
            ceGetCustomItemId = api.getMethod("getCustomItemId", ItemStack.class);
            Class<?> definition = Class.forName(
                    "net.momirealms.craftengine.bukkit.item.BukkitItemDefinition", true, loader);
            ceBuildBukkitItem = definition.getMethod("buildBukkitItem");
            ceReady = true;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            warnCeMissing("CraftEngine API 与预期不符，按原版物品处理：" + e);
            return false;
        }
    }

    private void warnCeMissing(String message) {
        if (!ceMissingWarned) {
            ceMissingWarned = true;
            logger.log(Level.WARNING, message);
        }
    }

    /** 部署提示用：当前 provider 与解析到的材质（启动时打一行，便于核对服主改的配置有没有生效）。 */
    public String describe() {
        if (provider == Provider.CRAFTENGINE) {
            return "provider=craftengine（铃铛 " + ceBellId + "，魂符 " + ceTalismanId
                    + "；CE 缺失时回落 " + bellMaterial.name() + " / " + talismanMaterial.name() + "）";
        }
        return "provider=vanilla（铃铛 " + bellMaterial.name() + "，魂符 " + talismanMaterial.name() + "）";
    }
}
