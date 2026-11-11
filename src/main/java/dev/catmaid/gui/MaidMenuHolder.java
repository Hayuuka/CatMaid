package dev.catmaid.gui;

import dev.catmaid.maid.Maid;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * GUI 身份标识：在 {@code InventoryClickEvent} 里靠 holder 类型分派，不靠标题字符串判断（api-facts 15.5-4）。
 *
 * <p>Paper 的 {@code Bukkit.createInventory(holder, ...)} 不会回填 holder 自己的 inventory 字段，
 * 所以创建后必须调一次 {@link #setInventory(Inventory)}。
 */
public final class MaidMenuHolder implements InventoryHolder {

    public enum MenuType {
        MAIN, BACKPACK, EQUIPMENT, MODE, MODEL
    }

    private final MenuType type;
    private final Maid maid;
    private int page;
    private Inventory inventory;

    public MaidMenuHolder(MenuType type, Maid maid, int page) {
        this.type = type;
        this.maid = maid;
        this.page = page;
    }

    public MenuType type() {
        return type;
    }

    public Maid maid() {
        return maid;
    }

    public int page() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
