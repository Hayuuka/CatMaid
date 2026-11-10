package dev.catmaid;

import dev.catmaid.command.CatMaidCommand;
import dev.catmaid.data.Database;
import dev.catmaid.data.MaidRepository;
import dev.catmaid.listener.PlayerConnectionListener;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.MaidTrait;
import dev.catmaid.maid.ModelApplier;
import dev.catmaid.maid.ModelRegistry;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.TraitInfo;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;

/** 插件生命周期入口。 */
public final class CatMaidPlugin extends JavaPlugin {

    private static CatMaidPlugin instance;

    private Database database;
    private MaidRepository repository;
    private MaidManager maidManager;
    private ModelRegistry modelRegistry;
    private ModelApplier modelApplier;
    private BukkitTask saveTask;

    public static CatMaidPlugin get() {
        return instance;
    }

    public MaidManager maidManager() {
        return maidManager;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        Messages.load(this);

        // 依赖自检放在 enable 里做（plugin.yml 用 softdepend），这样缺依赖时能打印中文原因并自禁用，
        // 而不是被 Paper 以英文报错拒绝加载。
        if (!requirePlugin("Citizens")) {
            return;
        }
        if (!requirePlugin("BetterModel")) {
            return;
        }

        registerTrait();

        try {
            database = new Database(new File(getDataFolder(), "catmaid.db"));
        } catch (SQLException | IllegalStateException e) {
            getLogger().log(Level.SEVERE, "[CatMaid] 初始化 SQLite 失败，插件停止工作", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        repository = new MaidRepository(database, getLogger());
        modelRegistry = new ModelRegistry(getConfig());
        modelApplier = new ModelApplier(getLogger());
        maidManager = new MaidManager(repository, modelRegistry, modelApplier, getConfig(), getLogger());

        maidManager.loadAll();
        maidManager.restoreNpcs();

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(maidManager), this);

        CatMaidCommand command = new CatMaidCommand(maidManager, modelRegistry);
        PluginCommand pluginCommand = getCommand("catmaid");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        // 每 5 分钟全量保存一次，兜住崩溃/强杀时的数据（正常停机与玩家下线的保存已覆盖大部分场景）
        saveTask = getServer().getScheduler().runTaskTimer(this, () -> maidManager.saveAll(),
                20L * 300, 20L * 300);

        getLogger().info("[CatMaid] 已启用，当前女仆 " + maidManager.all().size() + " 只");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (maidManager != null) {
            maidManager.saveAll();
        }
        if (repository != null) {
            repository.close();
        }
        if (database != null) {
            database.close();
        }
        instance = null;
        getLogger().info("[CatMaid] 已停止");
    }

    private boolean requirePlugin(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        if (plugin == null || !plugin.isEnabled()) {
            Bukkit.getConsoleSender().sendMessage(Messages.get("depend-missing", Map.of("name", name)));
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }

    private void registerTrait() {
        try {
            CitizensAPI.getTraitFactory().registerTrait(
                    TraitInfo.create(MaidTrait.class).withName("catmaid"));
        } catch (Throwable t) {
            // /reload 或重复注册时会抛，已有注册即可继续
            getLogger().log(Level.FINE, "[CatMaid] catmaid trait 已注册，跳过", t);
        }
    }
}
