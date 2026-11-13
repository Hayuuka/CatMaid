package dev.catmaid.listener;

import dev.catmaid.data.MaidRepository;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.ModelRegistry;
import dev.catmaid.util.MaidItems;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 凭证物品的右键行为：铃铛复活、未唤醒魂符召唤、装着女仆的魂符释放。
 *
 * <p><b>魂符一物三态</b>（2026-11-13 按车万女仆源码重构，见 api-facts 25.2）：
 * 未唤醒 → 右键地面召唤一只，<b>原地变空魂符</b>；空 → 对着女仆收纳（在 MaidInteractListener）；
 * 装着 → 右键地面放出，<b>又变空魂符</b>。整条链只有"替换"，没有"消耗 + 新给一张"，
 * 所以玩家手里始终是同一张符，数量守恒。
 *
 * <p>落点规则照抄 TLM 的 {@code PlaceHelper.notSuitableForPlaceMaid}：只看目标格与其上一格的
 * 碰撞体积是否为空（水、草、花都算空，允许），不要求脚下有支撑、也不禁止液体。
 */
public final class TalismanListener implements Listener {

    /** TLM 两个 spawn 路径都会加 20 tick 冷却，防止连点刷屏；这里用原版物品冷却，客户端能看到灰条。 */
    private static final int COOLDOWN_TICKS = 20;

    private final Plugin plugin;
    private final MaidManager maidManager;
    private final ModelRegistry models;
    private final DialogueManager dialogue;
    private final MaidRepository repository;
    private final MaidItems items;

    public TalismanListener(Plugin plugin, MaidManager maidManager, ModelRegistry models,
                            DialogueManager dialogue, MaidRepository repository, MaidItems items) {
        this.plugin = plugin;
        this.maidManager = maidManager;
        this.models = models;
        this.dialogue = dialogue;
        this.repository = repository;
        this.items = items;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        if (items.isBell(item)) {
            UUID maidId = items.maidIdOf(item);
            if (maidId != null) {
                revive(event, maidId, item);
            }
            return;
        }
        if (items.isStoredTalisman(item)) {
            release(event, item);
            return;
        }
        if (items.isInitTalisman(item)) {
            summon(event, item);
            return;
        }
        // 空魂符只是容器：对着空气点通常是玩家以为"魂符能召唤"，给一句提示；
        // 对着方块点多半是想开箱子/放东西，静默放行，免得每次右键都刷屏
        if (items.isTalisman(item) && event.getAction() == Action.RIGHT_CLICK_AIR) {
            Messages.send(event.getPlayer(), "talisman-empty-hint");
        }
    }

    // ---- 铃铛：复活 ----

    private void revive(PlayerInteractEvent event, UUID maidId, ItemStack bell) {
        // 铃铛是方块物品，不取消的话右键方块会先试着放置
        event.setCancelled(true);
        Player player = event.getPlayer();
        Maid maid = maidManager.byId(maidId).orElse(null);
        if (maid == null) {
            Messages.send(player, "bell-orphan");
            return;
        }
        if (maid.isAlive()) {
            Messages.send(player, "bell-already-alive", Map.of("name", maid.getName()));
            return;
        }
        if (!player.getUniqueId().equals(maid.getOwnerUuid())) {
            // 蓝图要求"铃铛毫无反应"：别人的铃铛连提示都不给具体的
            Messages.send(player, "bell-not-yours");
            return;
        }
        Cost cost = reviveCost();
        if (!player.getInventory().containsAtLeast(new ItemStack(cost.material()), cost.amount())) {
            Messages.send(player, "revive-no-item", Map.of(
                    "item", cost.material().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "amount", String.valueOf(cost.amount())));
            return;
        }
        NPC npc = CitizensAPI.getNPCRegistry().getById(maid.getNpcId());
        if (npc == null) {
            Messages.send(player, "revive-npc-missing", Map.of("name", maid.getName()));
            return;
        }

        Location target = beside(player);
        maid.setAlive(true);
        maid.setSitting(false);
        // 生成/传送 + 重挂模型 + 绑 trait + 补装备血量都在 placeAt 里：
        // 死亡时 Citizens 只是把 NPC 置为 despawned，模型 tracker 跟着旧实体没了（api-facts 17.11-3），
        // 这段逻辑与魂符释放完全一样，没必要各写一遍
        if (!maidManager.placeAt(maid, target)) {
            maid.setAlive(false);
            Messages.send(player, "revive-failed", Map.of("name", maid.getName()));
            return;
        }
        // REVIVE 的数值是 0（死亡时已经扣过），所以这里不需要动好感度，
        // 只是把状态从"铃铛"翻回"活着"

        player.getInventory().removeItem(new ItemStack(cost.material(), cost.amount()));
        items.consumeOne(player, bell);

        playReviveEffect(target);
        dialogue.send(maid, "on_revive");
        repository.save(maid);
        Messages.send(player, "revive-success", Map.of("name", maid.getName()));
    }

    // ---- 魂符：释放（装着态 → 空） ----

    /**
     * 装着女仆的魂符 → 放在地上的空地（TLM 的 {@code spawnFromStore}）。
     *
     * <p>成功后魂符<b>原地变回空的</b>：不是消耗，也不是新发一张，所以背包不会多出东西、
     * 也不会因为背包满而丢符（TLM 是 {@code setItemInHand}，我们照抄）。
     */
    private void release(PlayerInteractEvent event, ItemStack talisman) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.hasCooldown(items.talismanMaterial())) {
            return;
        }
        UUID maidId = items.maidIdOf(talisman);
        if (maidId == null) {
            // TLM 对应文案：「此魂符缺失小女仆数据」
            Messages.send(player, "talisman-broken");
            return;
        }
        Maid maid = maidManager.byId(maidId).orElse(null);
        if (maid == null) {
            Messages.send(player, "talisman-orphan");
            return;
        }
        if (!player.getUniqueId().equals(maid.getOwnerUuid())) {
            Messages.send(player, "talisman-not-yours");
            return;
        }
        if (!maid.isStored()) {
            Messages.send(player, "release-not-stored", Map.of("name", maid.getName()));
            return;
        }
        Location target = placeLocation(event, player);
        if (target == null) {
            Messages.send(player, "place-only-on-top");
            return;
        }
        if (!canPlaceMaidAt(target)) {
            Messages.send(player, "release-blocked", Map.of("name", maid.getName()));
            return;
        }
        if (!maidManager.release(maid, target)) {
            Messages.send(player, "release-failed", Map.of("name", maid.getName()));
            return;
        }
        items.replaceHeld(player, talisman, items.talisman());
        player.setCooldown(items.talismanMaterial(), COOLDOWN_TICKS);

        playReviveEffect(target);
        dialogue.send(maid, "on_release");
        Messages.send(player, "release-success", Map.of("name", maid.getName()));
    }

    // ---- 魂符：召唤（未唤醒态 → 空） ----

    /**
     * 未唤醒的魂符 → 召唤一只新女仆（TLM 的 {@code spawnNewMaid}）。
     *
     * <p>与 TLM 一致的三条：①召唤后手里变空魂符；②名额满了只提示、不消耗不变空；
     * ③带主人锁定时别人放不出来（防转手）。
     */
    private void summon(PlayerInteractEvent event, ItemStack initTalisman) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.hasCooldown(items.talismanMaterial())) {
            return;
        }
        UUID lock = items.initOwnerOf(initTalisman);
        if (lock != null && !lock.equals(player.getUniqueId())) {
            Messages.send(player, "init-talisman-not-yours");
            return;
        }
        Location target = placeLocation(event, player);
        if (target == null) {
            Messages.send(player, "place-only-on-top");
            return;
        }
        if (!canPlaceMaidAt(target)) {
            Messages.send(player, "summon-blocked");
            return;
        }
        Maid maid = maidManager.summon(player, models.random(), target);
        if (maid == null) {
            Messages.send(player, "summon-max", Map.of("max", String.valueOf(maidManager.maxPerPlayer())));
            return;
        }
        items.replaceHeld(player, initTalisman, items.talisman());
        player.setCooldown(items.talismanMaterial(), COOLDOWN_TICKS);
        Messages.send(player, "init-talisman-used", Map.of("name", maid.getName()));
    }

    /**
     * 落点：对着方块顶面放 = 点击位置的上一格；对着空气放 = 自己脚下。
     *
     * <p>TLM 只在 {@code useOn} 里响应、且要求点击面是 {@code Direction.UP}，所以对着侧面点不会放置。
     * 我们额外支持右键空气（落在脚下），否则玩家站在草地上往天上点会没反应，很反直觉。
     *
     * @return null 表示"这个面不支持放置"
     */
    private Location placeLocation(PlayerInteractEvent event, Player player) {
        Block clicked = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (event.getBlockFace() != BlockFace.UP) {
                return null;
            }
            return clicked.getRelative(BlockFace.UP).getLocation().add(0.5, 0, 0.5);
        }
        return player.getLocation();
    }

    /**
     * 落点能不能放女仆 —— 照抄 TLM 的 {@code PlaceHelper.notSuitableForPlaceMaid}：
     * 目标格与它上一格都没有碰撞体积即可。水、草、花都算空（TLM 允许放在水面）。
     */
    private boolean canPlaceMaidAt(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return false;
        }
        if (loc.getY() < world.getMinHeight() + 1 || loc.getY() >= world.getMaxHeight() - 1) {
            return false;
        }
        Block feet = loc.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        return !blocked(feet) && !blocked(head);
    }

    /** Bukkit 的 VoxelShape 没有 isEmpty —— 用包围盒集合是否为空来判断有没有碰撞体积。 */
    private boolean blocked(Block block) {
        return !block.getCollisionShape().getBoundingBoxes().isEmpty();
    }

    /** config 的 {@code death.revive-cost} 形如 {@code DIAMOND:1}；写错材质就回落到默认值而不是让复活彻底不可用。 */
    private Cost reviveCost() {
        String raw = plugin.getConfig().getString("death.revive-cost", "DIAMOND:1");
        String[] parts = raw.split(":", 2);
        Material material = Material.matchMaterial(parts[0].trim());
        if (material == null) {
            plugin.getLogger().warning("death.revive-cost 的材质 " + parts[0] + " 无法识别，按 DIAMOND 处理");
            material = Material.DIAMOND;
        }
        int amount = 1;
        if (parts.length > 1) {
            try {
                amount = Math.max(1, Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("death.revive-cost 的数量写法不是整数，按 1 处理：" + raw);
            }
        }
        return new Cost(material, amount);
    }

    private record Cost(Material material, int amount) {
    }

    /** 复活点放在玩家身后一格：直接放在脚下会和玩家重叠、互相推挤。 */
    private Location beside(Player player) {
        Location base = player.getLocation().clone();
        double yaw = Math.toRadians(base.getYaw());
        base.add(Math.sin(yaw), 0, -Math.cos(yaw));
        base.setYaw(base.getYaw() + 180f);
        base.setPitch(0f);
        return base;
    }

    private void playReviveEffect(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.HEART, loc.clone().add(0, 1.0, 0), 25, 0.6, 0.8, 0.6, 0);
        world.spawnParticle(Particle.ENCHANT, loc.clone().add(0, 1.0, 0), 30, 0.5, 0.8, 0.5, 0.4);
        world.playSound(loc, Sound.BLOCK_BELL_RESONATE, 1.0f, 1.2f);
        world.playSound(loc, Sound.ENTITY_CAT_PURR, 0.9f, 1.0f);
    }
}
