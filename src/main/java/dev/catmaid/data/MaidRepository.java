package dev.catmaid.data;

import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidStats;
import dev.catmaid.util.Locations;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * maids / maid_items / maid_stats 三表的读写。
 *
 * <p>写操作全部投递到单线程 executor：Bukkit 对象（ItemStack）在主线程先序列化成快照，
 * 线程里只做 SQL（agent.md 5.7）。
 */
public final class MaidRepository {

    private final Connection connection;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CatMaid-DB");
        t.setDaemon(true);
        return t;
    });

    public MaidRepository(Database database, Logger logger) {
        this.connection = database.connection();
        this.logger = logger;
    }

    public void insert(Maid maid) {
        Snapshot snapshot = snapshot(maid);
        executor.execute(() -> write(snapshot));
    }

    public void save(Maid maid) {
        Snapshot snapshot = snapshot(maid);
        executor.execute(() -> write(snapshot));
    }

    public void delete(UUID maidId) {
        executor.execute(() -> {
            try (PreparedStatement del1 = connection.prepareStatement("DELETE FROM maids WHERE id = ?");
                 PreparedStatement del2 = connection.prepareStatement("DELETE FROM maid_items WHERE maid_id = ?");
                 PreparedStatement del3 = connection.prepareStatement("DELETE FROM maid_stats WHERE maid_id = ?")) {
                del1.setString(1, maidId.toString());
                del1.executeUpdate();
                del2.setString(1, maidId.toString());
                del2.executeUpdate();
                del3.setString(1, maidId.toString());
                del3.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "删除女仆 " + maidId + " 失败", e);
            }
        });
    }

    /** onEnable 时在主线程同步调用：此时 executor 尚未有任务，直接读连接是安全的。 */
    public List<Maid> loadAll() {
        List<Maid> maids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM maids");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                maids.add(readMaid(rs));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "读取女仆数据失败", e);
        }
        for (Maid maid : maids) {
            loadItems(maid);
            loadStats(maid);
        }
        return maids;
    }

    /** onDisable：等排队中的写全部落地。 */
    public void flush() {
        try {
            executor.submit(() -> {
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.log(Level.WARNING, "等待数据库写入超时，可能有未保存的改动", e);
        }
    }

    public void close() {
        flush();
        executor.shutdown();
    }

    // ---- 内部实现 ----

    private Maid readMaid(ResultSet rs) throws SQLException {
        Maid maid = new Maid(UUID.fromString(rs.getString("id")));
        maid.setOwnerUuid(UUID.fromString(rs.getString("owner_uuid")));
        maid.setName(rs.getString("name"));
        maid.setModelId(rs.getString("model_id"));
        try {
            maid.setMode(Maid.Mode.valueOf(rs.getString("mode")));
        } catch (IllegalArgumentException e) {
            maid.setMode(Maid.Mode.FOLLOW);
        }
        maid.setAffection(rs.getDouble("affection"));
        maid.setOwnerTitle(rs.getString("owner_title"));
        maid.setAlive(rs.getInt("alive") != 0);
        int npcId = rs.getInt("npc_id");
        maid.setNpcId(rs.wasNull() ? -1 : npcId);
        maid.setHome(Locations.toBukkit(rs.getString("home_world"),
                rs.getDouble("home_x"), rs.getDouble("home_y"), rs.getDouble("home_z")));
        maid.setSitting(rs.getInt("sitting") != 0);
        return maid;
    }

    private void loadItems(Maid maid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT slot, data FROM maid_items WHERE maid_id = ?")) {
            ps.setString(1, maid.getId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItemStack item = ItemStackCodec.decode(rs.getBytes("data"));
                    if (item != null) {
                        maid.inventory().set(rs.getInt("slot"), item);
                    }
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "读取女仆 " + maid.getName() + " 的物品失败", e);
        }
    }

    private void loadStats(Maid maid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT kills, harvests, breeds, shears, milks FROM maid_stats WHERE maid_id = ?")) {
            ps.setString(1, maid.getId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    maid.stats().restore(rs.getInt("kills"), rs.getInt("harvests"),
                            rs.getInt("breeds"), rs.getInt("shears"), rs.getInt("milks"));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "读取女仆统计失败", e);
        }
    }

    private Snapshot snapshot(Maid maid) {
        Location home = maid.getHome();
        MaidStats stats = maid.stats();
        return new Snapshot(
                maid.getId(),
                maid.getOwnerUuid(),
                maid.getName(),
                maid.getModelId(),
                maid.getMode().name(),
                maid.getAffection(),
                maid.getOwnerTitle(),
                maid.isAlive(),
                maid.getNpcId(),
                Locations.worldName(home),
                home == null ? 0 : home.getX(),
                home == null ? 0 : home.getY(),
                home == null ? 0 : home.getZ(),
                maid.isSitting(),
                maid.inventory().serialize(),
                stats.kills(), stats.harvests(), stats.breeds(), stats.shears(), stats.milks());
    }

    private void write(Snapshot s) {
        try {
            connection.setAutoCommit(false);
            upsertMaid(s);
            rewriteItems(s);
            upsertStats(s);
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // 回滚失败已无从补救，保留原始异常更有价值
            }
            logger.log(Level.SEVERE, "保存女仆 " + s.name() + " 失败", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // 同上
            }
        }
    }

    private void upsertMaid(Snapshot s) throws SQLException {
        // created_at 只在首次插入时写；冲突分支不更新它
        String sql = "INSERT INTO maids (id, owner_uuid, name, model_id, mode, affection, owner_title,"
                + " alive, npc_id, home_world, home_x, home_y, home_z, sitting, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(id) DO UPDATE SET owner_uuid=excluded.owner_uuid, name=excluded.name,"
                + " model_id=excluded.model_id, mode=excluded.mode, affection=excluded.affection,"
                + " owner_title=excluded.owner_title, alive=excluded.alive, npc_id=excluded.npc_id,"
                + " home_world=excluded.home_world, home_x=excluded.home_x, home_y=excluded.home_y,"
                + " home_z=excluded.home_z, sitting=excluded.sitting";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, s.id().toString());
            ps.setString(i++, s.ownerUuid().toString());
            ps.setString(i++, s.name());
            ps.setString(i++, s.modelId());
            ps.setString(i++, s.mode());
            ps.setDouble(i++, s.affection());
            ps.setString(i++, s.ownerTitle());
            ps.setInt(i++, s.alive() ? 1 : 0);
            if (s.npcId() < 0) {
                ps.setNull(i++, java.sql.Types.INTEGER);
            } else {
                ps.setInt(i++, s.npcId());
            }
            ps.setString(i++, s.homeWorld());
            ps.setDouble(i++, s.homeX());
            ps.setDouble(i++, s.homeY());
            ps.setDouble(i++, s.homeZ());
            ps.setInt(i++, s.sitting() ? 1 : 0);
            ps.setLong(i, s.createdAt());
            ps.executeUpdate();
        }
    }

    private void rewriteItems(Snapshot s) throws SQLException {
        try (PreparedStatement del = connection.prepareStatement("DELETE FROM maid_items WHERE maid_id = ?")) {
            del.setString(1, s.id().toString());
            del.executeUpdate();
        }
        if (s.items().isEmpty()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO maid_items (maid_id, slot, data) VALUES (?,?,?)")) {
            for (Map.Entry<Integer, byte[]> entry : s.items().entrySet()) {
                ps.setString(1, s.id().toString());
                ps.setInt(2, entry.getKey());
                ps.setBytes(3, entry.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void upsertStats(Snapshot s) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO maid_stats (maid_id, kills, harvests, breeds, shears, milks)"
                        + " VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, s.id().toString());
            ps.setInt(2, s.kills());
            ps.setInt(3, s.harvests());
            ps.setInt(4, s.breeds());
            ps.setInt(5, s.shears());
            ps.setInt(6, s.milks());
            ps.executeUpdate();
        }
    }

    /** 主线程生成的数据快照，交给 DB 线程时不再触碰任何 Bukkit 对象。 */
    private record Snapshot(UUID id, UUID ownerUuid, String name, String modelId, String mode,
                            double affection, String ownerTitle, boolean alive, int npcId,
                            String homeWorld, double homeX, double homeY, double homeZ,
                            boolean sitting, Map<Integer, byte[]> items,
                            int kills, int harvests, int breeds, int shears, int milks,
                            long createdAt) {

        Snapshot(UUID id, UUID ownerUuid, String name, String modelId, String mode,
                 double affection, String ownerTitle, boolean alive, int npcId,
                 String homeWorld, double homeX, double homeY, double homeZ,
                 boolean sitting, Map<Integer, byte[]> items,
                 int kills, int harvests, int breeds, int shears, int milks) {
            this(id, ownerUuid, name, modelId, mode, affection, ownerTitle, alive, npcId,
                    homeWorld, homeX, homeY, homeZ, sitting, items,
                    kills, harvests, breeds, shears, milks, System.currentTimeMillis());
        }
    }
}
