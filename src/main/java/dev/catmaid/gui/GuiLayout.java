package dev.catmaid.gui;

/**
 * 五个 GUI 的槽位布局（蓝图 Phase 2 的槽位表）。
 *
 * <p>所有魔数只在这里出现：改布局不需要动业务逻辑，也不会出现"布局与点击判定对不上"的偏差。
 */
public final class GuiLayout {

    private GuiLayout() {
    }

    // ---- 主菜单（27 格）----
    public static final int MAIN_SIZE = 27;
    public static final int MAIN_INFO = 4;
    public static final int MAIN_MODE = 10;
    public static final int MAIN_BACKPACK = 12;
    public static final int MAIN_EQUIPMENT = 14;
    public static final int MAIN_MODEL = 16;
    public static final int MAIN_SIT = 22;

    // ---- 背包（54 格）----
    public static final int BACKPACK_SIZE = 54;
    /** GUI 0..5 ↔ 女仆 0..5（主手/副手/头盔/胸甲/护腿/靴子）。 */
    public static final int BACKPACK_EQUIP_BASE = 0;
    public static final int BACKPACK_EQUIP_SLOTS = 6;
    /** 6..8 灰玻璃填充。 */
    public static final int BACKPACK_EQUIP_FILLER = 6;
    /** GUI 9..44 ↔ 女仆 6..41（36 个存储位，按好感度逐格解锁）。 */
    public static final int BACKPACK_STORAGE_BASE = 9;
    public static final int BACKPACK_STORAGE_SLOTS = 36;
    /** 45..53 底部工具栏。 */
    public static final int BACKPACK_TOOLBAR = 45;
    public static final int BACKPACK_HELP = 49;
    public static final int BACKPACK_BACK = 53;

    // ---- 装备（27 格）----
    public static final int EQUIPMENT_SIZE = 27;
    /** 10..15 ↔ 女仆 0..5。 */
    public static final int EQUIPMENT_BASE = 10;
    public static final int EQUIPMENT_SLOTS = 6;
    public static final int EQUIPMENT_BACK = 22;

    // ---- 模式（27 格）----
    public static final int MODE_SIZE = 27;
    /** 10..14，顺序见 GuiManager 的 MODE_ORDER。 */
    public static final int MODE_BASE = 10;
    public static final int MODE_BACK = 22;

    // ---- 模型（54 格，分页 28 个/页）----
    public static final int MODEL_SIZE = 54;
    public static final int MODEL_PAGE_SIZE = 28;
    /** 网格 7 列 × 4 行，起点 10（第 2 行第 2 格），避开左右留白与底部工具栏。 */
    public static final int MODEL_GRID_BASE = 10;
    public static final int MODEL_GRID_COLUMNS = 7;
    public static final int MODEL_PREV = 45;
    public static final int MODEL_BACK = 49;
    public static final int MODEL_NEXT = 53;

    /** 页内第 index 个模型落在哪个 GUI 槽位。 */
    public static int modelSlot(int indexInPage) {
        return MODEL_GRID_BASE + (indexInPage / MODEL_GRID_COLUMNS) * 9 + indexInPage % MODEL_GRID_COLUMNS;
    }

    /** 反向解析：GUI 槽位 → 页内序号，不是模型格返回 -1。 */
    public static int modelIndexAt(int slot) {
        if (slot < MODEL_GRID_BASE) {
            return -1;
        }
        int offset = slot - MODEL_GRID_BASE;
        int column = offset % 9;
        int row = offset / 9;
        if (column >= MODEL_GRID_COLUMNS || row >= 4) {
            return -1;
        }
        return row * MODEL_GRID_COLUMNS + column;
    }
}
