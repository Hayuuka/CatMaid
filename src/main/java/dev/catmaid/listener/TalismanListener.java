package dev.catmaid.listener;

import dev.catmaid.data.MaidRepository;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.MaidTrait;
import dev.catmaid.maid.ModelApplier;
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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 魂符召唤与铃铛复活（蓝图 P4）。
 *
 * <p>两者都靠右键触发，所以合在一个监听器里：先认铃铛（有 maid_id），再认魂符（有标记位），
 * 都不是就放行——别的插件的右键行为不受影响。
 *
 * <p>单次调用里的"消耗材料"顺序是先校验后扣：复活要同时有钻石和铃铛，缺一样就什么都不扣。
 */
public final class TalismanListener implements Listener {

    private final Plugin plugin;
    private final MaidManager maidManager;
    private final ModelRegistry models;
    private final ModelApplier modelApplier;
    private final DialogueManager dialogue;
    private final MaidRepository repository;
    private final MaidItems items;

    /** 召唤冷却，防止连点魂符瞬间刷出几个 NPC（上限校验也在，但别让玩家看到"失败"提示堆屏）。 */
    private final Map<UUID, Long> summonAt = new HashMap<>();

    public TalismanListener(Plugin plugin, MaidManager maidManager, ModelRegistry models,
                            ModelApplier modelApplier, DialogueManager dialogue, MaidRepository repository,
                            MaidItems items) {
        this.plugin = plugin;
        this.maidManager = maidManager;
        this.models = models;
        this.modelApplier = modelApplier;
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
        if (items.isTalisman(item)) {
            summon(event, item);
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
        if (npc.isSpawned()) {
            npc.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } else if (!npc.spawn(target)) {
            Messages.send(player, "revive-failed", Map.of("name", maid.getName()));
            return;
        }
        // 死亡时 Citizens 只是把 NPC 置为 despawned，但模型 tracker 跟着实体一起没了，
        // 重新 spawn 后要再挂一次（api-facts 17.11-3）
        modelApplier.apply(npc, maid.getModelId());

        maid.setAlive(true);
        maid.setNpcId(npc.getId());
        maid.setSitting(false);
        // 重新绑一次 trait：如果插件在女仆死亡期间重启过，Citizens 读回的 trait 实例里 maid 是 null
        // （启动时不会为 dead 女仆做恢复），不绑会导致后续 onSpawn 里的装备写回失效
        npc.getOrAddTrait(MaidTrait.class).bind(maid);
        maidManager.onEntityReady(maid);
        // REVIVE 的数值是 0（死亡时已经扣过），所以这里不需要动好感度，
        // 只是把状态从"铃铛"翻回"活着"

        player.getInventory().removeItem(new ItemStack(cost.material(), cost.amount()));
        items.consumeOne(player, bell);

        playReviveEffect(target);
        dialogue.send(maid, "on_revive");
        repository.save(maid);
        Messages.send(player, "revive-success", Map.of("name", maid.getName()));
    }

    // ---- 魂符：召唤 ----

    private void summon(PlayerInteractEvent event, ItemStack talisman) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = summonAt.get(player.getUniqueId());
        if (last != null && now - last < 1000L) {
            return;
        }
        summonAt.put(player.getUniqueId(), now);

        Maid maid = maidManager.summon(player, models.random(), player.getLocation());
        if (maid == null) {
            Messages.send(player, "summon-max", Map.of("max", String.valueOf(maidManager.maxPerPlayer())));
            return;
        }
        event.setCancelled(true);
        items.consumeOne(player, talisman);
        Messages.send(player, "talisman-used", Map.of("name", maid.getName()));
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
