package dev.catmaid.data;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接与建表。
 *
 * <p>全插件共用一个 Connection：读写在 MaidRepository 的单线程 executor 上串行执行，
 * 避免多线程写锁竞争（agent.md 5.7）。连接在 onDisable 时由插件关闭。
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    public Database(File file) throws SQLException {
        // sqlite-jdbc 被 shade 并 relocate 到 dev.catmaid.libs.sqlite，
        // 其 META-INF/services 自动注册在重定位后失效，必须显式加载驱动类。
        try {
            Class.forName("dev.catmaid.libs.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("找不到内置 SQLite 驱动，构建产物可能未正确 shade", e);
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建数据目录：" + parent.getAbsolutePath());
        }

        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            // WAL 让"边写边读"不互相阻塞；外键约束当前 schema 未用，保留默认关闭即可
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
        }
        createTables();
    }

    public Connection connection() {
        return connection;
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS maids (
                      id          TEXT PRIMARY KEY,
                      owner_uuid  TEXT NOT NULL,
                      name        TEXT NOT NULL,
                      model_id    TEXT NOT NULL,
                      mode        TEXT NOT NULL DEFAULT 'FOLLOW',
                      affection   REAL NOT NULL DEFAULT 10,
                      owner_title TEXT NOT NULL DEFAULT '主人',
                      alive       INTEGER NOT NULL DEFAULT 1,
                      npc_id      INTEGER,
                      home_world  TEXT, home_x REAL, home_y REAL, home_z REAL,
                      sitting     INTEGER NOT NULL DEFAULT 0,
                      created_at  INTEGER NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS maid_items (
                      maid_id TEXT NOT NULL,
                      slot    INTEGER NOT NULL,
                      data    BLOB NOT NULL,
                      PRIMARY KEY (maid_id, slot)
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS maid_stats (
                      maid_id  TEXT PRIMARY KEY,
                      kills    INTEGER NOT NULL DEFAULT 0,
                      harvests INTEGER NOT NULL DEFAULT 0,
                      breeds   INTEGER NOT NULL DEFAULT 0,
                      shears   INTEGER NOT NULL DEFAULT 0,
                      milks    INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_maids_owner ON maids(owner_uuid)");
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 停机路径上关不掉连接不值得阻断流程
        }
    }
}
