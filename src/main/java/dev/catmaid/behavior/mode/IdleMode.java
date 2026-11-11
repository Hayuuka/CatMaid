package dev.catmaid.behavior.mode;

import dev.catmaid.behavior.BehaviorMode;
import dev.catmaid.behavior.Movement;
import dev.catmaid.maid.Maid;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 待机：在 home 附近闲逛，偶尔抬头看最近的玩家。
 *
 * <p>决策间隔取 40~59 tick（蓝图 40+随机20），间隔按 npcId 固定——不同女仆的间隔不同，
 * 自然不会挤在同一 tick 上做扫描。
 */
public final class IdleMode implements BehaviorMode {

    private static final int WANDER_MIN = 40;
    private static final int WANDER_JITTER = 20;
    private static final float WALK_SPEED = 1.0f;
    /** 蓝图「概率 5%：面向最近的玩家」：每轮决策 5% 的几率改成看一眼而不是闲逛。 */
    private static final double LOOK_CHANCE = 0.05;
    private static final double LOOK_RANGE = 8.0;

    private final FileConfiguration config;
    /** 每只女仆下一次做决策的 tick。用 Map 而不是取模，因为调用节奏由外部（FollowMode）决定。 */
    private final Map<UUID, Integer> nextAt = new HashMap<>();

    public IdleMode(FileConfiguration config) {
        this.config = config;
    }

    @Override
    public Maid.Mode type() {
        return Maid.Mode.IDLE;
    }

    @Override
    public void onDisable(Maid maid) {
        nextAt.remove(maid.getId());
    }

    @Override
    public void tick(Maid maid, int tick) {
        int interval = interval(maid);
        Integer next = nextAt.get(maid.getId());
        if (next == null) {
            // 首次进入按 npcId 错开相位，避免所有女仆在同一 tick 一起开始闲逛
            nextAt.put(maid.getId(), tick + Math.floorMod(maid.getNpcId(), interval));
            return;
        }
        if (tick < next) {
            return;
        }
        nextAt.put(maid.getId(), tick + interval);

        LivingEntity entity = maid.entity();
        if (entity == null) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < LOOK_CHANCE) {
            faceSomeone(maid, entity);
            return;
        }
        if (maid.isSitting()) {
            return; // 坐着只是坐着
        }
        wander(maid, entity);
    }

    private int interval(Maid maid) {
        return WANDER_MIN + Math.floorMod(maid.getNpcId(), WANDER_JITTER);
    }

    /** 在 home 周围随机挑一个能站人的点走过去。 */
    private void wander(Maid maid, LivingEntity entity) {
        Location home = maid.getHome();
        if (home == null || home.getWorld() == null || !home.getWorld().equals(entity.getWorld())) {
            return; // 家不在这个世界（主人把她带出门了），闲逛交给 FollowMode 的距离判断
        }
        Location spot = findSpot(home, config.getDouble("idle.wander-radius", 5));
        if (spot != null) {
            Movement.navigate(maid, spot, WALK_SPEED);
        }
    }

    /**
     * 随机采样若干角度/距离找一块能站人的平地。
     *
     * <p>只接受与 home 同层或低一格的落脚点：往上找会让女仆一层层爬上屋顶/方块堆，
     * 视觉上比"站着不动"更怪。找不到就返回 null，这轮不动。
     */
    private Location findSpot(Location home, double radius) {
        World world = home.getWorld();
        if (world == null) {
            return null;
        }
        int baseY = home.getBlockY();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double distance = 1.0 + ThreadLocalRandom.current().nextDouble() * radius;
            int x = Location.locToBlock(home.getX() + Math.cos(angle) * distance);
            int z = Location.locToBlock(home.getZ() + Math.sin(angle) * distance);
            for (int dy = 0; dy >= -1; dy--) {
                Block ground = world.getBlockAt(x, baseY + dy, z);
                if (!ground.getType().isSolid()) {
                    continue;
                }
                Block feet = ground.getRelative(BlockFace.UP);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
                break; // 这一列的落脚点不行，换一列
            }
        }
        return null;
    }

    /** 优先看主人，主人不在附近才看最近的玩家。 */
    private void faceSomeone(Maid maid, LivingEntity entity) {
        Player target = null;
        Player owner = Bukkit.getPlayer(maid.getOwnerUuid());
        if (owner != null && inRange(entity, owner)) {
            target = owner;
        } else {
            double best = LOOK_RANGE * LOOK_RANGE;
            for (Entity nearby : entity.getWorld()
                    .getNearbyEntities(entity.getLocation(), LOOK_RANGE, 4, LOOK_RANGE)) {
                if (!(nearby instanceof Player player) || !inRange(entity, player)) {
                    continue;
                }
                double distance = player.getLocation().distanceSquared(entity.getLocation());
                if (distance < best) {
                    best = distance;
                    target = player;
                }
            }
        }
        if (target == null) {
            return;
        }
        NPC npc = maid.npc();
        if (npc != null && npc.isSpawned()) {
            // faceLocation 只是一次转头，转过去就会一直保持，正好是「看一会儿」的效果
            npc.faceLocation(target.getLocation());
        }
    }

    private static boolean inRange(LivingEntity maid, Player player) {
        return player.getWorld().equals(maid.getWorld())
                && player.getLocation().distanceSquared(maid.getLocation()) <= LOOK_RANGE * LOOK_RANGE;
    }
}
