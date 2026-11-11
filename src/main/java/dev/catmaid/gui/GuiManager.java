package dev.catmaid.gui;

import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidInventory;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.ModelApplier;
import dev.catmaid.maid.ModelRegistry;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 5 个 GUI 的打开与事件分发。
 *
 * <p>数据流是"打开时从 MaidInventory 拷进 GUI，关闭时全量回读"（蓝图 Phase 2 同步规则）。
 * 因此 GUI 里的 Inventory 只是编辑缓冲区，任何时刻只有关闭动作会让改动落到领域对象上。
 * 在两个 GUI 之间跳转时会先手动 {@link #sync} 一次，否则新界面会显示跳转前的旧数据。
 */
public final class GuiManager implements Listener {

    /** 模式选择页的顺序，与 GuiLayout.MODE_BASE 起始的五个槽位一一对应。 */
    private static final Maid.Mode[] MODE_ORDER = {
            Maid.Mode.IDLE, Maid.Mode.FOLLOW, Maid.Mode.COMBAT, Maid.Mode.FARM, Maid.Mode.RANCH
    };
    private static final Material[] MODE_ICONS = {
            Material.SHIELD, Material.LEAD, Material.IRON_SWORD, Material.IRON_HOE, Material.CARROT_ON_A_STICK
    };

    /**
     * 空装备位的"位置提示"标记。
     *
     * <p>提示物本身是一件真物品（铁头盔等），玩家不拦截就能拿走、也会被关闭回写塞进女仆背包，
     * 所以用 PDC 打标：点它等于点空格，回写时当 null 处理。
     */
    private static final NamespacedKey HINT_KEY = new NamespacedKey("catmaid", "slot_hint");

    /** 女仆槽位 0..5 的提示材质与文案键，顺序与 MaidInventory 的装备位语义一致。 */
    private static final Material[] EQUIP_HINT_MATERIALS = {
            Material.IRON_SWORD, Material.SHIELD, Material.IRON_HELMET,
            Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS
    };
    private static final String[] EQUIP_HINT_KEYS = {
            "gui.slot-mainhand", "gui.slot-offhand", "gui.slot-helmet",
            "gui.slot-chest", "gui.slot-leggings", "gui.slot-boots"
    };

    private final MaidManager maidManager;
    private final ModelRegistry models;
    private final ModelApplier modelApplier;
    private final DialogueManager dialogue;

    public GuiManager(MaidManager maidManager, ModelRegistry models, ModelApplier modelApplier,
                      DialogueManager dialogue) {
        this.maidManager = maidManager;
        this.models = models;
        this.modelApplier = modelApplier;
        this.dialogue = dialogue;
    }

    // ---- 打开 ----

    public void openMain(Player player, Maid maid) {
        MaidMenuHolder holder = new MaidMenuHolder(MaidMenuHolder.MenuType.MAIN, maid, 0);
        Inventory inv = Bukkit.createInventory(holder, GuiLayout.MAIN_SIZE,
                Messages.get("gui.main-title", Map.of("name", maid.getName())));
        holder.setInventory(inv);

        inv.setItem(GuiLayout.MAIN_INFO, infoBook(maid));
        inv.setItem(GuiLayout.MAIN_MODE, icon(Material.IRON_SWORD, "gui.button-mode",
                Map.of("mode", modeName(maid.getMode())), null, false));
        inv.setItem(GuiLayout.MAIN_BACKPACK, icon(Material.CHEST, "gui.button-backpack",
                Map.of("capacity", String.valueOf(maid.inventory().storageCapacity())),
                List.of("gui.button-backpack-lore"), false));
        inv.setItem(GuiLayout.MAIN_EQUIPMENT, icon(Material.ARMOR_STAND, "gui.button-equipment",
                Map.of(), List.of("gui.button-equipment-lore"), false));
        inv.setItem(GuiLayout.MAIN_MODEL, icon(Material.MAP, "gui.button-model",
                Map.of("model", maid.getModelId()), List.of("gui.button-model-lore"), false));
        inv.setItem(GuiLayout.MAIN_SIT, sitIcon(maid));

        player.openInventory(inv);
    }

    public void openBackpack(Player player, Maid maid) {
        MaidMenuHolder holder = new MaidMenuHolder(MaidMenuHolder.MenuType.BACKPACK, maid, 0);
        Inventory inv = Bukkit.createInventory(holder, GuiLayout.BACKPACK_SIZE,
                Messages.get("gui.backpack-title", Map.of("name", maid.getName())));
        holder.setInventory(inv);

        for (int i = 0; i < GuiLayout.BACKPACK_EQUIP_SLOTS; i++) {
            ItemStack equipped = maid.inventory().get(i);
            inv.setItem(GuiLayout.BACKPACK_EQUIP_BASE + i,
                    equipped != null ? equipped.clone() : equipHint(i));
        }
        for (int i = GuiLayout.BACKPACK_EQUIP_FILLER; i < GuiLayout.BACKPACK_STORAGE_BASE; i++) {
            inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE, null, null));
        }

        int capacity = maid.inventory().storageCapacity();
        for (int i = 0; i < GuiLayout.BACKPACK_STORAGE_SLOTS; i++) {
            int guiSlot = GuiLayout.BACKPACK_STORAGE_BASE + i;
            if (i < capacity) {
                inv.setItem(guiSlot, copy(maid.inventory().get(MaidInventory.EQUIPMENT_SLOTS + i)));
            } else {
                inv.setItem(guiSlot, lockedPane());
            }
        }
        for (int i = GuiLayout.BACKPACK_TOOLBAR; i < GuiLayout.BACKPACK_SIZE; i++) {
            inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE, null, null));
        }
        inv.setItem(GuiLayout.BACKPACK_HELP, helpItem(maid));
        inv.setItem(GuiLayout.BACKPACK_BACK, icon(Material.ARROW, "gui.button-back", Map.of(), null, false));

        player.openInventory(inv);
    }

    public void openEquipment(Player player, Maid maid) {
        MaidMenuHolder holder = new MaidMenuHolder(MaidMenuHolder.MenuType.EQUIPMENT, maid, 0);
        Inventory inv = Bukkit.createInventory(holder, GuiLayout.EQUIPMENT_SIZE,
                Messages.get("gui.equipment-title", Map.of("name", maid.getName())));
        holder.setInventory(inv);

        for (int i = 0; i < GuiLayout.EQUIPMENT_SLOTS; i++) {
            ItemStack equipped = maid.inventory().get(i);
            inv.setItem(GuiLayout.EQUIPMENT_BASE + i, equipped != null ? equipped.clone() : equipHint(i));
        }
        inv.setItem(GuiLayout.EQUIPMENT_BACK, icon(Material.ARROW, "gui.button-back", Map.of(), null, false));

        player.openInventory(inv);
    }

    public void openModeSelect(Player player, Maid maid) {
        MaidMenuHolder holder = new MaidMenuHolder(MaidMenuHolder.MenuType.MODE, maid, 0);
        Inventory inv = Bukkit.createInventory(holder, GuiLayout.MODE_SIZE,
                Messages.get("gui.mode-title", Map.of("name", maid.getName())));
        holder.setInventory(inv);

        renderModeIcons(inv, maid);
        inv.setItem(GuiLayout.MODE_BACK, icon(Material.ARROW, "gui.button-back", Map.of(), null, false));

        player.openInventory(inv);
    }

    public void openModelSelect(Player player, Maid maid, int page) {
        List<String> all = models.all();
        int pages = pageCount(all.size());
        int clamped = Math.max(0, Math.min(page, pages - 1));

        MaidMenuHolder holder = new MaidMenuHolder(MaidMenuHolder.MenuType.MODEL, maid, clamped);
        Inventory inv = Bukkit.createInventory(holder, GuiLayout.MODEL_SIZE,
                Messages.get("gui.model-title", Map.of("name", maid.getName(),
                        "page", String.valueOf(clamped + 1), "pages", String.valueOf(pages))));
        holder.setInventory(inv);

        renderModelPage(inv, maid, clamped);
        player.openInventory(inv);
    }

    // ---- 渲染 ----

    private void renderModeIcons(Inventory inv, Maid maid) {
        for (int i = 0; i < MODE_ORDER.length; i++) {
            Maid.Mode mode = MODE_ORDER[i];
            boolean current = maid.getMode() == mode;
            inv.setItem(GuiLayout.MODE_BASE + i, icon(MODE_ICONS[i],
                    current ? "gui.mode-selected" : "gui.mode-button",
                    Map.of("mode", modeName(mode)),
                    current ? List.of("gui.mode-current-lore") : null, current));
        }
    }

    private void renderModelPage(Inventory inv, Maid maid, int page) {
        for (int i = 0; i < GuiLayout.MODEL_SIZE; i++) {
            inv.setItem(i, null);
        }
        List<String> all = models.all();
        int from = page * GuiLayout.MODEL_PAGE_SIZE;
        for (int i = 0; i < GuiLayout.MODEL_PAGE_SIZE; i++) {
            int index = from + i;
            if (index >= all.size()) {
                break;
            }
            String modelId = all.get(index);
            boolean selected = modelId.equals(maid.getModelId());
            inv.setItem(GuiLayout.modelSlot(i), icon(Material.PLAYER_HEAD,
                    selected ? "gui.model-selected" : "gui.model-entry",
                    Map.of("model", modelId), null, selected));
        }
        inv.setItem(GuiLayout.MODEL_PREV, icon(Material.SPECTRAL_ARROW, "gui.button-prev", Map.of(), null, false));
        inv.setItem(GuiLayout.MODEL_BACK, icon(Material.ARROW, "gui.button-back", Map.of(), null, false));
        inv.setItem(GuiLayout.MODEL_NEXT, icon(Material.SPECTRAL_ARROW, "gui.button-next", Map.of(), null, false));
    }

    private ItemStack infoBook(Maid maid) {
        int level = MaidInventory.levelForAffection(maid.getAffection());
        Map<String, String> ph = new HashMap<>();
        ph.put("name", maid.getName());
        ph.put("owner", ownerName(maid));
        ph.put("title", maid.getOwnerTitle());
        ph.put("affection", String.format(Locale.ROOT, "%.1f", maid.getAffection()));
        ph.put("level", Messages.plain("gui.level-" + level));
        ph.put("mode", modeName(maid.getMode()));
        ph.put("used", String.valueOf(maid.inventory().usedStorageSlots()));
        ph.put("capacity", String.valueOf(maid.inventory().storageCapacity()));
        ph.put("kills", String.valueOf(maid.stats().kills()));
        ph.put("harvests", String.valueOf(maid.stats().harvests()));
        ph.put("breeds", String.valueOf(maid.stats().breeds()));
        ph.put("shears", String.valueOf(maid.stats().shears()));
        ph.put("milks", String.valueOf(maid.stats().milks()));

        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(Messages.get("gui.info-title", ph)));

        List<Component> lore = new ArrayList<>();
        Messages.getList("gui.info-lore-top", ph).forEach(line -> lore.add(noItalic(line)));
        lore.add(equipmentLine(maid));
        Messages.getList("gui.info-lore-bottom", ph).forEach(line -> lore.add(noItalic(line)));
        meta.lore(lore);

        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * 信息书里的装备行。女仆模型会盖住原版装备层，界面上看不到穿了什么，
     * 所以在书里列出来，避免"放进去了但不知道有没有生效"。
     */
    private Component equipmentLine(Maid maid) {
        // adventure 5.x 已移除 MutableComponent，Component.text() 直接返回 TextComponent.Builder
        TextComponent.Builder line = Component.text()
                .decoration(TextDecoration.ITALIC, false)
                .append(Messages.get("gui.info-equipment-label"));
        boolean any = false;
        for (int i = 0; i < MaidInventory.EQUIPMENT_SLOTS; i++) {
            ItemStack item = maid.inventory().get(i);
            if (item == null) {
                continue;
            }
            if (any) {
                line.append(Component.text("  "));
            }
            line.append(Messages.get(EQUIP_HINT_KEYS[i]).decoration(TextDecoration.ITALIC, false));
            line.append(Component.text(" "));
            line.append(itemName(item).decoration(TextDecoration.ITALIC, false));
            any = true;
        }
        if (!any) {
            line.append(Messages.get("gui.info-equipment-empty"));
        }
        return line.build();
    }

    /** 优先用玩家自定义名，否则交给客户端按语言翻译材质名（服务端拿不到中文名）。 */
    private Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component custom = meta.displayName();
            if (custom != null) {
                return custom;
            }
        }
        return Component.translatable(item.getType().translationKey());
    }

    private ItemStack sitIcon(Maid maid) {
        boolean sitting = maid.isSitting();
        return icon(sitting ? Material.LIME_DYE : Material.GRAY_DYE,
                sitting ? "gui.button-sit-on" : "gui.button-sit-off",
                Map.of(), null, false);
    }

    private ItemStack lockedPane() {
        return pane(Material.GRAY_STAINED_GLASS_PANE, "gui.locked-slot", "gui.locked-slot-lore");
    }

    /** 空装备位的提示（铁头盔这类轮廓图标 + 名字），一眼能看出这格放什么。 */
    private ItemStack equipHint(int index) {
        ItemStack stack = new ItemStack(EQUIP_HINT_MATERIALS[index]);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(Messages.get(EQUIP_HINT_KEYS[index])));
        meta.lore(Messages.getList("gui.slot-hint-lore").stream().map(GuiManager::noItalic).toList());
        meta.getPersistentDataContainer().set(HINT_KEY, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack helpItem(Maid maid) {
        ItemStack stack = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(Messages.get("gui.help-item")));
        meta.lore(Messages.getList("gui.help-lore",
                        Map.of("capacity", String.valueOf(maid.inventory().storageCapacity())))
                .stream().map(GuiManager::noItalic).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean isHint(ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(HINT_KEY, PersistentDataType.BYTE);
    }

    /**
     * 提示物是件真物品，不拦就会被玩家拿走、也会被关闭回写塞进女仆背包。
     * 光标上真拿着东西时撤掉提示，让这次点击按"点空格"交给原版；其余情况一律取消。
     *
     * @return true 表示后续按正常流程继续处理
     */
    private boolean allowHintClick(InventoryClickEvent event, Inventory top, int slot) {
        if (!isHint(top.getItem(slot))) {
            return true;
        }
        ItemStack cursor = event.getCursor();
        boolean placing = !event.getClick().isShiftClick()
                && cursor != null && !cursor.getType().isAir();
        if (!placing) {
            event.setCancelled(true);
            return false;
        }
        top.setItem(slot, null);
        return true;
    }

    private ItemStack pane(Material material, String nameKey, String loreKey) {
        ItemStack stack = new ItemStack(material);
        if (nameKey == null) {
            return stack;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(Messages.get(nameKey)));
        if (loreKey != null) {
            meta.lore(Messages.getList(loreKey).stream().map(GuiManager::noItalic).toList());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack icon(Material material, String nameKey, Map<String, String> placeholders,
                           List<String> loreKeys, boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(noItalic(Messages.get(nameKey, placeholders)));
        if (loreKeys != null && !loreKeys.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String key : loreKeys) {
                lore.add(noItalic(Messages.get(key, placeholders)));
            }
            meta.lore(lore);
        }
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- 点击分发 ----

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MaidMenuHolder holder)) {
            return;
        }
        // 双击收集会把顶部填充玻璃也吸进光标，直接禁掉（ClickType 里没有这个值，在 InventoryAction 里）
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        // 顶部/底部用 rawSlot 判断，不依赖 getClickedInventory() 的实例比较
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0) {
            return;
        }
        if (rawSlot < top.getSize()) {
            handleTopClick(event, holder, top, rawSlot);
        } else {
            handleBottomClick(event, holder, top);
        }
    }
    private void handleTopClick(InventoryClickEvent event, MaidMenuHolder holder, Inventory top, int slot) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        Maid maid = holder.maid();
        if (!allowHintClick(event, top, slot)) {
            return;
        }
        switch (holder.type()) {
            case MAIN -> {
                event.setCancelled(true);
                clickMain(player, maid, top, slot);
            }
            case MODE -> {
                event.setCancelled(true);
                clickMode(player, maid, top, slot);
            }
            case MODEL -> {
                event.setCancelled(true);
                clickModel(player, holder, top, slot);
            }
            case BACKPACK -> {
                if (slot == GuiLayout.BACKPACK_BACK) {
                    event.setCancelled(true);
                    sync(holder, top);
                    openMain(player, maid);
                } else if (!editable(holder, slot)) {
                    event.setCancelled(true);
                } else if (event.getClick().isShiftClick()) {
                    event.setCancelled(true);
                    shiftOut(player, event);
                }
                // 其余情况交给原版处理，关闭时统一回写
            }
            case EQUIPMENT -> {
                if (slot == GuiLayout.EQUIPMENT_BACK) {
                    event.setCancelled(true);
                    sync(holder, top);
                    openMain(player, maid);
                } else if (!editable(holder, slot)) {
                    event.setCancelled(true);
                } else if (event.getClick().isShiftClick()) {
                    event.setCancelled(true);
                    shiftOut(player, event);
                }
            }
        }
    }

    private void handleBottomClick(InventoryClickEvent event, MaidMenuHolder holder, Inventory top) {
        if (!event.getClick().isShiftClick()) {
            return; // 自己背包里的普通拿放不干涉
        }
        // 原版 shift-click 会往顶部任意空槽塞（含锁定槽、按钮位），必须自己搬
        event.setCancelled(true);
        if (holder.type() != MaidMenuHolder.MenuType.BACKPACK
                && holder.type() != MaidMenuHolder.MenuType.EQUIPMENT) {
            return;
        }
        shiftInto(event, holder, top);
    }

    private void clickMain(Player player, Maid maid, Inventory top, int slot) {
        switch (slot) {
            case GuiLayout.MAIN_MODE -> openModeSelect(player, maid);
            case GuiLayout.MAIN_BACKPACK -> openBackpack(player, maid);
            case GuiLayout.MAIN_EQUIPMENT -> openEquipment(player, maid);
            case GuiLayout.MAIN_MODEL -> openModelSelect(player, maid, 0);
            case GuiLayout.MAIN_SIT -> {
                maid.setSitting(!maid.isSitting());
                maidManager.saveAsync(maid);
                top.setItem(GuiLayout.MAIN_SIT, sitIcon(maid));
                Messages.send(player, maid.isSitting() ? "gui.sit-on" : "gui.sit-off",
                        Map.of("name", maid.getName()));
            }
            default -> {
                // 信息书与空白格：无操作
            }
        }
    }

    private void clickMode(Player player, Maid maid, Inventory top, int slot) {
        if (slot == GuiLayout.MODE_BACK) {
            openMain(player, maid);
            return;
        }
        int index = slot - GuiLayout.MODE_BASE;
        if (index < 0 || index >= MODE_ORDER.length) {
            return;
        }
        Maid.Mode target = MODE_ORDER[index];
        if (maid.getMode() == target) {
            return;
        }
        maid.setMode(target);
        maidManager.saveAsync(maid);
        // 模式台词（P4）：{@code on_mode_change.<MODE>}。放在这里而不是 Maid#setMode 里，
        // 是因为领域对象不该反向依赖台词服务；P8 的命令行切模式要记得同步补一句。
        dialogue.send(maid, "on_mode_change", target.name());
        renderModeIcons(top, maid);
        Messages.send(player, "gui.mode-changed",
                Map.of("name", maid.getName(), "mode", modeName(target)));
    }

    private void clickModel(Player player, MaidMenuHolder holder, Inventory top, int slot) {
        Maid maid = holder.maid();
        if (slot == GuiLayout.MODEL_PREV) {
            openModelSelect(player, maid, holder.page() - 1);
            return;
        }
        if (slot == GuiLayout.MODEL_NEXT) {
            openModelSelect(player, maid, holder.page() + 1);
            return;
        }
        if (slot == GuiLayout.MODEL_BACK) {
            openMain(player, maid);
            return;
        }
        int indexInPage = GuiLayout.modelIndexAt(slot);
        if (indexInPage < 0) {
            return;
        }
        int absolute = holder.page() * GuiLayout.MODEL_PAGE_SIZE + indexInPage;
        List<String> all = models.all();
        if (absolute >= all.size()) {
            return;
        }
        String modelId = all.get(absolute);
        if (modelId.equals(maid.getModelId())) {
            return;
        }
        maid.setModelId(modelId);
        NPC npc = maid.npc();
        if (npc != null) {
            modelApplier.apply(npc, modelId);
        }
        maidManager.saveAsync(maid);
        // 原地重绘，不重开界面：重开会闪一下，且当前页会丢
        renderModelPage(top, maid, holder.page());
        Messages.send(player, "gui.model-changed", Map.of("name", maid.getName(), "model", modelId));
    }

    // ---- 搬运 ----

    /** 顶部槽位 shift-click 之后，把物品塞回玩家背包；放不下的原样留在原槽。 */
    private void shiftOut(Player player, InventoryClickEvent event) {
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.getType().isAir()) {
            return;
        }
        ItemStack candidate = moving.clone();
        event.setCurrentItem(null);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(candidate);
        if (!leftover.isEmpty()) {
            event.setCurrentItem(leftover.values().iterator().next());
        }
    }

    /** 玩家背包 shift-click 之后，手动搬进顶部合法槽位。 */
    private void shiftInto(InventoryClickEvent event, MaidMenuHolder holder, Inventory top) {
        ItemStack moving = event.getCurrentItem();
        if (moving == null || moving.getType().isAir()) {
            return;
        }
        if (holder.type() == MaidMenuHolder.MenuType.EQUIPMENT) {
            int target = equipmentSlotFor(moving);
            // 装备界面只往空的目标位塞，避免 shift 一下就把身上的甲换掉；提示物算空位
            if (target < 0) {
                return;
            }
            ItemStack sitting = top.getItem(target);
            if (sitting != null && !isHint(sitting)) {
                return;
            }
            top.setItem(target, moving.clone());
            event.setCurrentItem(null);
            return;
        }

        List<Integer> targets = new ArrayList<>();
        int capacity = holder.maid().inventory().storageCapacity();
        for (int i = 0; i < capacity; i++) {
            targets.add(GuiLayout.BACKPACK_STORAGE_BASE + i);
        }

        int remaining = moving.getAmount();
        for (int guiSlot : targets) {
            if (remaining <= 0) {
                break;
            }
            ItemStack existing = top.getItem(guiSlot);
            if (existing == null || !existing.isSimilar(moving)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) {
                continue;
            }
            int move = Math.min(space, remaining);
            ItemStack merged = existing.clone();
            merged.setAmount(existing.getAmount() + move);
            top.setItem(guiSlot, merged);
            remaining -= move;
        }
        if (remaining > 0) {
            for (int guiSlot : targets) {
                if (top.getItem(guiSlot) == null) {
                    ItemStack placed = moving.clone();
                    placed.setAmount(remaining);
                    top.setItem(guiSlot, placed);
                    remaining = 0;
                    break;
                }
            }
        }
        if (remaining == moving.getAmount()) {
            return; // 一个都放不进去，原样不动
        }
        if (remaining <= 0) {
            event.setCurrentItem(null);
        } else {
            ItemStack rest = moving.clone();
            rest.setAmount(remaining);
            event.setCurrentItem(rest);
        }
    }

    private int equipmentSlotFor(ItemStack item) {
        EquipmentSlot slot = item.getType().getEquipmentSlot();
        return switch (slot) {
            case HEAD -> GuiLayout.EQUIPMENT_BASE + 2;
            case CHEST -> GuiLayout.EQUIPMENT_BASE + 3;
            case LEGS -> GuiLayout.EQUIPMENT_BASE + 4;
            case FEET -> GuiLayout.EQUIPMENT_BASE + 5;
            case OFF_HAND -> GuiLayout.EQUIPMENT_BASE + 1;
            default -> -1;
        };
    }

    // ---- 拖拽 / 关闭 ----

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MaidMenuHolder holder)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= top.getSize()) {
                continue;
            }
            if (!editable(holder, rawSlot)) {
                event.setCancelled(true);
                return;
            }
            if (isHint(top.getItem(rawSlot))) {
                // 拖拽落点是空装备位：先把提示图标撤掉，否则拖过去的物品会跟它挤在一格
                top.setItem(rawSlot, null);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof MaidMenuHolder holder) {
            sync(holder, top);
        }
    }

    /** 把 GUI 里的内容全量回写到 MaidInventory，并让 NPC 外观跟上。 */
    private void sync(MaidMenuHolder holder, Inventory top) {
        Maid maid = holder.maid();
        switch (holder.type()) {
            case BACKPACK -> {
                for (int i = 0; i < GuiLayout.BACKPACK_EQUIP_SLOTS; i++) {
                    maid.inventory().set(i, copyReal(top.getItem(GuiLayout.BACKPACK_EQUIP_BASE + i)));
                }
                // 只回写已解锁的格：锁定格在 GUI 里是玻璃板，写回去会把原本存着的物品抹掉
                int capacity = maid.inventory().storageCapacity();
                for (int i = 0; i < capacity; i++) {
                    maid.inventory().set(MaidInventory.EQUIPMENT_SLOTS + i,
                            copyReal(top.getItem(GuiLayout.BACKPACK_STORAGE_BASE + i)));
                }
                persist(maid);
            }
            case EQUIPMENT -> {
                for (int i = 0; i < GuiLayout.EQUIPMENT_SLOTS; i++) {
                    maid.inventory().set(i, copyReal(top.getItem(GuiLayout.EQUIPMENT_BASE + i)));
                }
                persist(maid);
            }
            default -> {
                // 主菜单/模式/模型没有可写的物品数据
            }
        }
    }

    private void persist(Maid maid) {
        maid.applyEquipment();
        maidManager.saveAsync(maid);
    }

    // ---- 小工具 ----

    private boolean editable(MaidMenuHolder holder, int slot) {
        if (holder.type() == MaidMenuHolder.MenuType.EQUIPMENT) {
            return slot >= GuiLayout.EQUIPMENT_BASE && slot < GuiLayout.EQUIPMENT_BASE + GuiLayout.EQUIPMENT_SLOTS;
        }
        if (holder.type() != MaidMenuHolder.MenuType.BACKPACK) {
            return false;
        }
        if (slot < GuiLayout.BACKPACK_EQUIP_SLOTS) {
            return true; // 0..5 装备位
        }
        if (slot < GuiLayout.BACKPACK_STORAGE_BASE) {
            return false; // 6..8 填充
        }
        int index = slot - GuiLayout.BACKPACK_STORAGE_BASE;
        return index < GuiLayout.BACKPACK_STORAGE_SLOTS
                && index < holder.maid().inventory().storageCapacity();
    }

    private static int pageCount(int total) {
        return Math.max(1, (total + GuiLayout.MODEL_PAGE_SIZE - 1) / GuiLayout.MODEL_PAGE_SIZE);
    }

    private String modeName(Maid.Mode mode) {
        return Messages.plain("gui.mode-" + mode.name().toLowerCase(Locale.ROOT));
    }

    private String ownerName(Maid maid) {
        if (maid.getOwnerUuid() == null) {
            return "?";
        }
        String name = Bukkit.getOfflinePlayer(maid.getOwnerUuid()).getName();
        return name == null ? maid.getOwnerUuid().toString().substring(0, 8) : name;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    /** 回写用：空位的提示图标要当 null，否则铁头盔会被当成真装备存进女仆背包。 */
    private ItemStack copyReal(ItemStack item) {
        return item == null || isHint(item) ? null : item.clone();
    }

    /** 物品名默认斜体，统一关掉，颜色由 MiniMessage 决定。 */
    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
