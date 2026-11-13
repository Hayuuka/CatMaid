package dev.catmaid.listener;

import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.AffectionReason;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.gui.GuiManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.util.MaidItems;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 右键女仆：手持食物时喂食，否则打开主菜单。
 *
 * <p>玩家型 NPC 的交互走 Citizens 的 {@link NPCRightClickEvent}，不是
 * {@code PlayerInteractEntityEvent}（api-facts 3.2 / 15.5-1），方法名是 {@code getClicker()}。
 *
 * <p>开界面延迟 1 tick：Citizens 在交互包的处理过程中同步开容器，客户端偶尔会立刻关掉。
 */
public final class MaidInteractListener implements Listener {

    private final MaidManager maidManager;
    private final GuiManager guiManager;
    private final Plugin plugin;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    private final MaidItems items;

    /** 喂食冷却：per-maid，不是 per-player。两个主人账号轮着喂同一只也该被拦。 */
    private final Map<UUID, Long> fedAt = new HashMap<>();

    public MaidInteractListener(MaidManager maidManager, GuiManager guiManager, Plugin plugin,
                                AffectionManager affection, DialogueManager dialogue, MaidItems items) {
        this.maidManager = maidManager;
        this.guiManager = guiManager;
        this.plugin = plugin;
        this.affection = affection;
        this.dialogue = dialogue;
        this.items = items;
    }

    @EventHandler
    public void onNpcRightClick(NPCRightClickEvent event) {
        // 先按 NPC id 反查：没有任何 Maid 认领的 NPC（Citizens 自建、别的插件建的）直接放行
        Maid maid = maidManager.byNpcId(event.getNPC().getId()).orElse(null);
        if (maid == null || !maid.isAlive()) {
            return;
        }
        Player player = event.getClicker();
        if (!player.getUniqueId().equals(maid.getOwnerUuid())) {
            Messages.send(player, "not-your-maid");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (items.isStoredTalisman(hand)) {
            // 手里已经有一张"装着她"的魂符时不该再收一次（否则会凭空多出一张凭证）
            Messages.send(player, "talisman-occupied", Map.of("name", maid.getName()));
            return;
        }
        if (items.isInitTalisman(hand)) {
            // 未唤醒的魂符还没装人，对着女仆没有收纳语义（TLM 的 INIT 对着女仆同样不响应）
            Messages.send(player, "init-talisman-on-maid");
            return;
        }
        if (items.isTalisman(hand)) {
            store(player, maid, hand);
            return;
        }
        if (isFeedable(hand)) {
            // 手上有吃的 → 喂她，不开菜单（蓝图 P4「喂食接线」）
            feed(player, maid, hand);
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                guiManager.openMain(player, maid);
            }
        });
    }

    /**
     * 空魂符右键自己的女仆 = 把她收进魂符（蓝图 P9-9.2，语义对齐 TLM 的 Soul Spell）。
     *
     * <p>票证语义：手上的空魂符**原地变成**"装着她的魂符"（数量不变、不掉地），
     * 与 TLM 的 {@code player.setItemInHand(...)} 完全一致；放出时再变回空的。
     * 召唤不是这里的职责——未唤醒态的魂符对着地面右键才会召唤（api-facts 25.2）。
     */
    private void store(Player player, Maid maid, ItemStack talisman) {
        if (!maid.isAlive()) {
            Messages.send(player, "store-dead", Map.of("name", maid.getName()));
            return;
        }
        if (maid.isStored()) {
            Messages.send(player, "store-already", Map.of("name", maid.getName()));
            return;
        }
        if (!maidManager.store(maid)) {
            Messages.send(player, "store-failed", Map.of("name", maid.getName()));
            return;
        }
        playStoreEffect(player);
        // 票证语义（TLM 的 SlabClickEvent）：手里那张原地变成"装着她"，不是消耗 + 新给一张
        items.replaceHeld(player, talisman, items.storedTalisman(maid));
        player.setCooldown(items.talismanMaterial(), 20);
        dialogue.send(maid, "on_store");
        Messages.send(player, "store-success", Map.of("name", maid.getName()));
    }

    /** 收纳特效：原地留一团烟，让她"钻进魂符"这件事有个交代（agent.md 第 10 节）。 */
    private void playStoreEffect(Player player) {
        Location loc = player.getLocation().add(0, 1, 0);
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.CLOUD, loc, 18, 0.25, 0.5, 0.25, 0.02);
        world.spawnParticle(Particle.ENCHANT, loc, 20, 0.3, 0.5, 0.3, 0.4);
        world.playSound(loc, Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.6f);
    }

    private boolean isFeedable(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        List<String> allowed = plugin.getConfig().getStringList("interaction.feedable");
        String type = item.getType().name();
        for (String name : allowed) {
            if (type.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void feed(Player player, Maid maid, ItemStack hand) {
        long now = System.currentTimeMillis();
        long cooldownMs = plugin.getConfig().getLong("interaction.feed-cooldown-seconds", 60) * 1000L;
        Long last = fedAt.get(maid.getId());
        if (last != null && now - last < cooldownMs) {
            Messages.send(player, "feed-cooldown", Map.of("name", maid.getName()));
            return;
        }
        fedAt.put(maid.getId(), now);

        // 服务端拿不到物品的中文名（api-facts 16.8），台词里的 {item} 用材质名代替
        String itemName = hand.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        items.consumeOne(player, hand);
        playEatEffect(maid);
        heal(maid, plugin.getConfig().getDouble("interaction.feed-heal", 6.0));
        affection.apply(maid, affection.gain(AffectionReason.FEED), AffectionReason.FEED);
        dialogue.send(maid, "on_feed", Map.of("item", itemName));
    }

    /** 喂食回血：夹紧到她的上限；没实体（理论到不了，喂食前提是有实体）或 amount<=0 时不动。 */
    private void heal(Maid maid, double amount) {
        if (amount <= 0) {
            return;
        }
        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        entity.setHealth(Math.min(maid.getMaxHealth(), entity.getHealth() + amount));
    }

    private void playEatEffect(Maid maid) {
        if (maid.entity() == null) {
            return;
        }
        Location loc = maid.entity().getLocation().add(0, 1.2, 0);
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.HEART, loc, 8, 0.3, 0.3, 0.3, 0);
        world.playSound(loc, Sound.ENTITY_GENERIC_EAT, 0.8f, 1.3f);
    }
}
