package dev.catmaid.maid;

import dev.catmaid.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 女仆头顶名牌。
 *
 * <p>为什么不用 {@code npc.setNameTagVisible(true)}：BetterModel 接管渲染后会把原版实体隐藏
 * （config 里 {@code player-hide-delay}），原版名牌跟着消失，蓝图 P8 那条方案在模型下不成立。
 * 这里改用独立的 {@link TextDisplay} 悬浮在头顶，位置由全局任务错峰跟随。
 *
 * <p>实体不持久化（{@code setPersistent(false)}）：避免服务器重启后世界文件里留下孤儿名牌，
 * 代价是区块卸载后会丢，所以每轮 tick 发现名牌不存在就重建。
 */
public final class NameTagManager {

    /** 错峰分母：每 tick 只处理 1/N 的女仆，分摊传送包与实体查询开销。 */
    private static final int STAGGER = 5;

    private final Plugin plugin;
    private final boolean enabled;
    private final float scale;
    private final double height;

    /** maidId → TextDisplay 的实体 UUID。 */
    private final Map<UUID, UUID> displays = new HashMap<>();
    private int ticker;

    public NameTagManager(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.enabled = config.getBoolean("nametag.enabled", true);
        this.scale = (float) config.getDouble("nametag.scale", 0.8);
        this.height = config.getDouble("nametag.height", 0.35);
    }

    public boolean enabled() {
        return enabled;
    }

    public void start(Supplier<Collection<Maid>> source) {
        if (!enabled) {
            return;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tick(source.get()), 1L, 1L);
    }

    /** 由全局任务每 tick 调用；内部按 {@code npcId % STAGGER} 错峰。 */
    public void tick(Collection<Maid> maids) {
        if (!enabled) {
            return;
        }
        ticker++;
        Set<UUID> live = new HashSet<>();
        for (Maid maid : maids) {
            if (!maid.isAlive()) {
                continue;
            }
            live.add(maid.getId());
            if (maid.getNpcId() % STAGGER != ticker % STAGGER) {
                continue;
            }
            update(maid);
        }
        removeOrphans(live);
    }

    private void update(Maid maid) {
        LivingEntity entity = maid.entity();
        if (entity == null) {
            remove(maid);
            return;
        }
        TextDisplay display = find(maid);
        if (display == null || !display.isValid()) {
            display = spawn(maid, entity);
            if (display == null) {
                return;
            }
            displays.put(maid.getId(), display.getUniqueId());
        }
        display.teleport(targetLocation(entity));
    }

    private TextDisplay spawn(Maid maid, LivingEntity entity) {
        Location loc = targetLocation(entity);
        if (loc.getWorld() == null) {
            return null;
        }
        return loc.getWorld().spawn(loc, TextDisplay.class, display -> {
            display.setPersistent(false); // 不落盘，避免重启后残留孤儿名牌
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setLineWidth(200);
            display.setTeleportDuration(3); // 让客户端插值，跟随更顺
            display.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0f, 0f, 0f, 1f)));
            display.text(text(maid));
        });
    }

    /** 女仆改名 / 换主人后刷新文字。 */
    public void refresh(Maid maid) {
        TextDisplay display = find(maid);
        if (display != null) {
            display.text(text(maid));
        }
    }

    public void remove(Maid maid) {
        UUID displayId = displays.remove(maid.getId());
        if (displayId == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(displayId);
        if (entity != null) {
            entity.remove();
        }
    }

    public void shutdown() {
        for (UUID displayId : displays.values()) {
            Entity entity = Bukkit.getEntity(displayId);
            if (entity != null) {
                entity.remove();
            }
        }
        displays.clear();
    }

    private void removeOrphans(Set<UUID> live) {
        Iterator<Map.Entry<UUID, UUID>> it = displays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, UUID> entry = it.next();
            if (live.contains(entry.getKey())) {
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.getValue());
            if (entity != null) {
                entity.remove();
            }
            it.remove();
        }
    }

    private TextDisplay find(Maid maid) {
        UUID displayId = displays.get(maid.getId());
        if (displayId == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(displayId);
        if (entity instanceof TextDisplay display && display.isValid()) {
            return display;
        }
        return null;
    }

    private Location targetLocation(LivingEntity entity) {
        return entity.getLocation().add(0, entity.getHeight() + height, 0);
    }

    private Component text(Maid maid) {
        return Messages.get("nametag.format", Map.of(
                "owner", ownerName(maid),
                "name", maid.getName()));
    }

    private String ownerName(Maid maid) {
        if (maid.getOwnerUuid() == null) {
            return "主人";
        }
        String name = Bukkit.getOfflinePlayer(maid.getOwnerUuid()).getName();
        return name == null ? "主人" : name;
    }
}
