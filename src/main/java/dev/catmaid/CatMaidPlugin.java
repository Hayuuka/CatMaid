package dev.catmaid;

import dev.catmaid.behavior.BehaviorManager;
import dev.catmaid.command.CatMaidCommand;
import dev.catmaid.data.Database;
import dev.catmaid.data.MaidRepository;
import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.gui.GuiManager;
import dev.catmaid.listener.MaidDamageListener;
import dev.catmaid.listener.MaidInteractListener;
import dev.catmaid.listener.OwnerProtectionListener;
import dev.catmaid.listener.PlayerConnectionListener;
import dev.catmaid.listener.TalismanListener;
import dev.catmaid.maid.AffectionTable;
import dev.catmaid.maid.HandItemDisplays;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.MaidTrait;
import dev.catmaid.maid.ModelAnimator;
import dev.catmaid.maid.ModelApplier;
import dev.catmaid.maid.ModelRegistry;
import dev.catmaid.maid.NameTagManager;
import dev.catmaid.util.MaidItems;
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
    private NameTagManager nameTagManager;
    private HandItemDisplays handItemDisplays;
    private MaidItems maidItems;
    private BehaviorManager behaviorManager;
    private BukkitTask saveTask;
    private BukkitTask restoreTask;
    private BukkitTask behaviorTask;

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
        // 等级表必须在任何女仆载入之前灌好：Maid.setAffection 要用它换算背包容量
        AffectionTable.load(getConfig(), getLogger());

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
            getLogger().log(Level.SEVERE, "初始化 SQLite 失败，插件停止工作", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        repository = new MaidRepository(database, getLogger());
        // 凭证物品的唯一工厂（P9）：provider=vanilla 用原版物品，craftengine 用 CE 自定义物品
        maidItems = new MaidItems(this, getLogger());
        getLogger().info("凭证物品 " + maidItems.describe());
        modelRegistry = new ModelRegistry(getConfig());
        modelApplier = new ModelApplier(getLogger());
        // 台词与好感度先于行为层创建：BehaviorManager 每 tick 要推这两件事（P4），
        // 而 MaidManager 在召唤/移除时也要用台词与好感度状态。
        DialogueManager dialogueManager = new DialogueManager(this);
        AffectionManager affectionManager = new AffectionManager(getConfig(), dialogueManager, repository);
        behaviorManager = new BehaviorManager(getConfig(), new ModelAnimator(getLogger()),
                affectionManager, dialogueManager);
        maidManager = new MaidManager(repository, modelRegistry, modelApplier, behaviorManager,
                dialogueManager, getConfig(), getLogger());

        maidManager.loadAll();
        // Citizens 异步加载 NPC（启动后数秒才完成），BetterModel 的模型表也在 delayed init 之后才可用。
        // 因此按秒轮询恢复；30 秒后仍找不到 NPC 才认为真的丢了，重建兜底。
        scheduleRestore();

        // 头顶名牌：模型会隐藏原版名牌，这里用 TextDisplay 独立显示，按 npcId 错峰跟随
        nameTagManager = new NameTagManager(this, getConfig());
        nameTagManager.start(maidManager::all);

        // 主副手物品显示（P9）：模型盖住了原版装备层，这里单独把主手/副手物品显示出来
        handItemDisplays = new HandItemDisplays(this, getLogger(), getConfig());
        handItemDisplays.start(maidManager::all);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(maidManager), this);

        // 交互层：右键开菜单/喂食 + 5 个 GUI 的点击/拖拽/关闭回写
        GuiManager guiManager = new GuiManager(maidManager, modelRegistry, modelApplier, dialogueManager);
        getServer().getPluginManager().registerEvents(guiManager, this);
        getServer().getPluginManager().registerEvents(
                new MaidInteractListener(maidManager, guiManager, this, affectionManager, dialogueManager,
                        maidItems), this);

        // 养成层：受击/死亡转铃铛、铃铛复活、魂符召唤（P4）
        getServer().getPluginManager().registerEvents(
                new MaidDamageListener(this, maidManager, affectionManager, dialogueManager, repository,
                        maidItems), this);
        getServer().getPluginManager().registerEvents(
                new TalismanListener(this, maidManager, modelRegistry,
                        dialogueManager, repository, maidItems), this);

        // 战斗层：主人/女仆挨打 → 战斗模式的女仆锁定反击目标（P7）
        getServer().getPluginManager().registerEvents(
                new OwnerProtectionListener(this, maidManager, behaviorManager), this);

        CatMaidCommand command = new CatMaidCommand(maidManager, modelRegistry, this, guiManager,
                affectionManager, dialogueManager, nameTagManager, maidItems);
        PluginCommand pluginCommand = getCommand("catmaid");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        // 每 5 分钟全量保存一次，兜住崩溃/强杀时的数据（正常停机与玩家下线的保存已覆盖大部分场景）
        saveTask = getServer().getScheduler().runTaskTimer(this, () -> maidManager.saveAll(),
                20L * 300, 20L * 300);

        // 行为 tick。为什么不用 Citizens 的 Trait#run：玩家型 NPC 走的是原版 playerTicker，
        // Citizens 的 PlayerUpdateTask 只对带 PacketNPC trait 的 NPC 调 AbstractNPC#update()，
        // 因此 trait 的 run() 实测永不触发（docs/api-facts.md 17.2）。由插件自己驱动最可靠。
        // 节流与错峰在 BehaviorManager 与各 Mode 内完成，这里只管每 tick 推一次。
        behaviorTask = getServer().getScheduler().runTaskTimer(this,
                () -> behaviorManager.tickAll(maidManager.all()), 1L, 1L);

        getLogger().info("已启用，当前女仆 " + maidManager.all().size() + " 只");
    }

    @Override
    public void onDisable() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }
        if (behaviorTask != null) {
            behaviorTask.cancel();
            behaviorTask = null;
        }
        if (nameTagManager != null) {
            nameTagManager.shutdown();
        }
        if (handItemDisplays != null) {
            handItemDisplays.shutdown();
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
        getLogger().info("已停止");
    }

    /** 每秒推进一轮恢复；30 轮（约 30 秒）后仍找不到 NPC 才允许重建。 */
    private void scheduleRestore() {
        int[] attempts = {0};
        restoreTask = getServer().getScheduler().runTaskTimer(this, () -> {
            attempts[0]++;
            boolean done = maidManager.restoreNpcs(attempts[0] >= 30);
            if (done && restoreTask != null) {
                restoreTask.cancel();
                restoreTask = null;
            }
        }, 1L, 20L);
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
            getLogger().log(Level.FINE, "catmaid trait 已注册，跳过", t);
        }
    }
}
