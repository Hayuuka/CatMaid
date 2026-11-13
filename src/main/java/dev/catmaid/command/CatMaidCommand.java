package dev.catmaid.command;

import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.AffectionReason;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.gui.GuiManager;
import dev.catmaid.maid.AffectionTable;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.ModelRegistry;
import dev.catmaid.maid.NameTagManager;
import dev.catmaid.util.MaidItems;
import dev.catmaid.util.Messages;
import net.citizensnpcs.api.npc.NPC;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /catmaid 命令树。
 *
 * <p>Phase 1 实现了 admin 三个子命令，Phase 4 加了 talisman，Phase 8 补齐玩家侧
 * （gui/mode/sethome/info/rename/title）与 admin 的 setaffection/reload。
 *
 * <p>玩家侧命令都要先"找到说的是哪只女仆"：{@link #resolveMaid(Player, String)} 有不带名字的
 * 就近策略（先找 8 格内的，实在找不到才接受"你只有一只活着"的退让）和不给名字时的按名定向策略。
 * 两条路都只在自己名下的女仆里找——绝不跨越到别人的女仆（api-facts 22.3-3）。
 *
 * <p>P10-10.2 起所有玩家侧命令都接受一个可选的"女仆名"参数（放在最后），否则
 * {@code max-per-player} 大于 1 时第二只根本够不着：命令只会认最近那一只。
 *
 * <p>P10-10.4 起本类还是 {@link Listener}：{@code /catmaid release} 的二次确认要求
 * "在聊天栏再输入一次名字"（蓝图原文），聊天确认挂在 {@link #onAsyncChat(AsyncChatEvent)}。
 */
public final class CatMaidCommand implements CommandExecutor, TabCompleter, Listener {

    /** 玩家侧命令认领女仆的半径（蓝图 P8：/catmaid gui 打开"最近一只（或 8 格内）"）。 */
    private static final int NEARBY_RADIUS = 8;

    /** /catmaid release 聊天栏二次确认的等待窗口。 */
    private static final long CONFIRM_TIMEOUT_MS = 30_000L;

    /**
     * 改名 / 改称呼解锁的档位（蓝图 P4：伙伴档起，默认即好感 40）。
     *
     * <p>这里按"档位"而不是写死 40：阈值能配，服主把伙伴档挪到 35 或 45 时，
     * 解锁点应当跟着走（数值由 {@link AffectionTable#threshold(int)} 提供）。
     */
    private static final int RENAME_MIN_LEVEL = 2;

    private static final int NAME_MIN_LENGTH = 2;
    private static final int NAME_MAX_LENGTH = 12;

    private final MaidManager maidManager;
    private final ModelRegistry models;
    private final Plugin plugin;
    private final GuiManager gui;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    private final NameTagManager nameTags;
    private final MaidItems items;

    /** 魂符自助领取的冷却：玩家 UUID → 可以再领的时间戳（毫秒）。 */
    private final Map<UUID, Long> talismanReadyAt = new HashMap<>();
    /** 未唤醒魂符（召唤用）的领取冷却，与空魂符分开记——两者用途不同 */
    private final Map<UUID, Long> soulReadyAt = new HashMap<>();
    /** /catmaid release 的待确认：玩家 UUID → 她要送走的那只（聊天栏再输入一次名字才生效）。 */
    private final Map<UUID, PendingRelease> pendingRelease = new HashMap<>();

    public CatMaidCommand(MaidManager maidManager, ModelRegistry models, Plugin plugin,
                          GuiManager gui, AffectionManager affection, DialogueManager dialogue,
                          NameTagManager nameTags, MaidItems items) {
        this.maidManager = maidManager;
        this.models = models;
        this.plugin = plugin;
        this.gui = gui;
        this.affection = affection;
        this.dialogue = dialogue;
        this.nameTags = nameTags;
        this.items = items;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "gui" -> gui(sender, args);
            case "mode" -> mode(sender, args);
            case "sethome" -> sethome(sender, args);
            case "info" -> info(sender);
            case "rename" -> rename(sender, args);
            case "title" -> title(sender, args);
            case "talisman" -> talisman(sender, args);
            case "soul" -> soul(sender, args);
            case "bell" -> bell(sender, args);
            case "release" -> release(sender, args);
            case "admin" -> admin(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * 打开一只女仆的主菜单。命令只是"找对人再交给 GUI"，界面逻辑一律留在 GuiManager。
     *
     * @param args 第二个参数可以是女仆名（不给就按就近策略）
     */
    private void gui(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        Maid maid = resolveMaid(player, optionalArg(args, 1));
        if (maid == null) {
            return; // resolveMaid 已经给过提示
        }
        gui.openMain(player, maid);
    }

    /**
     * 切换一只女仆的模式。与 GUI 里的模式页是同一条链路：改字段 → 落库 → 模式台词 → 提示。
     *
     * <p>台词这句不能省：蓝图 P4 规定模式切换要有反应，GUI 那条路已经接了，
     * 命令行不补就会出现"点界面她说话、打命令她不理"（P2 的代码里留了这条提醒）。
     *
     * <p>用法：{@code /catmaid mode <模式> [女仆名]}。
     */
    private void mode(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        if (args.length < 2) {
            Messages.send(player, "mode-usage");
            return;
        }
        Maid.Mode target = parseMode(args[1]);
        if (target == null) {
            Messages.send(player, "mode-invalid", Map.of("mode", args[1]));
            return;
        }
        Maid maid = resolveMaid(player, optionalArg(args, 2));
        if (maid == null) {
            return;
        }
        String display = modeName(target);
        if (maid.getMode() == target) {
            Messages.send(player, "mode-already", Map.of("name", maid.getName(), "mode", display));
            return;
        }
        maid.setMode(target);
        maidManager.saveAsync(maid);
        dialogue.send(maid, "on_mode_change", target.name());
        Messages.send(player, "mode-changed", Map.of("name", maid.getName(), "mode", display));
    }

    /** 把脚下位置设成一只女仆的家（农业模式 center=home、回家闲逛都看这个点）。 */
    private void sethome(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        Maid maid = resolveMaid(player, optionalArg(args, 1));
        if (maid == null) {
            return;
        }
        Location home = player.getLocation().clone();
        maid.setHome(home);
        maidManager.saveAsync(maid);
        Messages.send(player, "home-set", Map.of(
                "name", maid.getName(),
                "x", String.valueOf(home.getBlockX()),
                "y", String.valueOf(home.getBlockY()),
                "z", String.valueOf(home.getBlockZ())));
    }

    /**
     * 列出自己全部女仆：名字 / 模式 / 好感度 / 存活。
     *
     * <p>与 admin list 的区别是视角：这里只给自己的，且不带 UUID（玩家不需要）。
     */
    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        List<Maid> owned = maidManager.byOwner(player.getUniqueId());
        if (owned.isEmpty()) {
            Messages.send(player, "no-maid");
            return;
        }
        owned.sort(Comparator.comparingDouble(Maid::getAffection).reversed());
        Messages.send(player, "info-header", Map.of("count", String.valueOf(owned.size())));
        for (Maid maid : owned) {
            Messages.send(player, "info-entry", Map.of(
                    "name", maid.getName(),
                    "mode", modeName(maid.getMode()),
                    "affection", String.format(Locale.ROOT, "%.1f", maid.getAffection()),
                    "state", Messages.plain(stateKey(maid))));
        }
        // 有了多女仆定向（P10-10.2）之后，列表不只是"看看"，还是选目标的入口
        Messages.send(player, "info-target-hint");
    }

    /**
     * 改名。好感不够（蓝图：伙伴档起）时拒绝，名字要 2-12 字且不能含富文本记号。
     *
     * <p>为什么直接拒绝 {@code <} {@code &} {@code §} 而不是"过滤"：这三个字符分别是
     * MiniMessage、旧颜色码、段落符的入口，玩家把 {@code <click:run_command:...>} 塞进名字
     * 后名牌会把整段当组件渲染（api-facts 22.3-4）。名字同时是 NPC 名与名牌文字，改完要一起刷。
     *
     * <p>用法：{@code /catmaid rename <新名字> [原名字]}。查重是必须的——名字是
     * P10-10.2 定向操作的唯一把手，两只同名等于定向永久失效。
     */
    private void rename(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        if (args.length < 2) {
            Messages.send(player, "rename-usage");
            return;
        }
        Maid maid = resolveMaid(player, optionalArg(args, 2));
        if (maid == null) {
            return;
        }
        if (!canRename(player, maid, "rename")) {
            return;
        }
        String newName = sanitize(args[1], NAME_MIN_LENGTH, NAME_MAX_LENGTH);
        if (newName == null) {
            Messages.send(player, "rename-invalid");
            return;
        }
        String oldName = maid.getName();
        if (newName.equals(oldName)) {
            Messages.send(player, "rename-same");
            return;
        }
        // 撞自己名下另一只的名字 → 拒绝：定向操作靠名字找她，重名之后两只都叫不动
        for (Maid other : maidManager.byOwner(player.getUniqueId())) {
            if (!other.getId().equals(maid.getId()) && other.getName().equalsIgnoreCase(newName)) {
                Messages.send(player, "rename-conflict", Map.of("name", newName));
                return;
            }
        }
        maid.setName(newName);
        NPC npc = maid.npc();
        if (npc != null) {
            npc.setName(newName); // 与 /npc list、Citizens 存档保持一致，避免两处名字不一样
        }
        nameTags.refresh(maid);
        maidManager.saveAsync(maid);
        Messages.send(player, "rename-success", Map.of("old", oldName, "name", newName));
    }

    /** 改女仆对自己的称呼（台词里的 {owner}）。门槛与改名一致。 */
    private void title(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        if (args.length < 2) {
            Messages.send(player, "title-usage");
            return;
        }
        Maid maid = resolveMaid(player, optionalArg(args, 2));
        if (maid == null) {
            return;
        }
        if (!canRename(player, maid, "title")) {
            return;
        }
        String newTitle = sanitize(args[1], 1, NAME_MAX_LENGTH);
        if (newTitle == null) {
            Messages.send(player, "title-invalid");
            return;
        }
        maid.setOwnerTitle(newTitle);
        maidManager.saveAsync(maid);
        Messages.send(player, "title-success", Map.of("name", maid.getName(), "title", newTitle));
    }

    /** rename/title 共用的好感闸门；不够时按前缀发对应的提示（rename-* / title-*）。 */
    private boolean canRename(Player player, Maid maid, String prefix) {
        int level = affection.level(maid.getAffection());
        if (level >= RENAME_MIN_LEVEL) {
            return true;
        }
        Messages.send(player, prefix + "-need-affection", Map.of(
                "name", maid.getName(),
                "level", Messages.levelName(RENAME_MIN_LEVEL),
                "min", String.valueOf(AffectionTable.threshold(RENAME_MIN_LEVEL))));
        return false;
    }

    /**
     * 名字/称呼的合法化：长度按码点算（中文一字一码点，emoji 不会被当成两个字），
     * 命中禁用字符或长度越界返回 null。
     */
    private String sanitize(String raw, int min, int max) {
        String trimmed = raw.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < min || length > max) {
            return null;
        }
        if (trimmed.indexOf('<') >= 0 || trimmed.indexOf('&') >= 0 || trimmed.indexOf('§') >= 0) {
            return null;
        }
        return trimmed;
    }

    /**
     * 模式名的解析：先认枚举名（idle/follow/...），再认 {@code messages.yml} 里的中文显示名。
     *
     * <p>为什么要读 messages 而不是在代码里写死中文别名：服主把「农业」改成「种田」后，
     * 命令输入、GUI 按钮、提示文案应当一起变；写死别名就会出现"界面叫种田、命令只认农业"。
     */
    private Maid.Mode parseMode(String raw) {
        for (Maid.Mode mode : Maid.Mode.values()) {
            if (mode.name().equalsIgnoreCase(raw)) {
                return mode;
            }
        }
        for (Maid.Mode mode : Maid.Mode.values()) {
            if (modeName(mode).equalsIgnoreCase(raw)) {
                return mode;
            }
        }
        return null;
    }

    /** 模式的玩家可见名（messages.yml 的 {@code gui.mode-<枚举名小写>}）。 */
    private String modeName(Maid.Mode mode) {
        return Messages.plain("gui.mode-" + mode.name().toLowerCase(Locale.ROOT));
    }

    /**
     * 玩家侧命令的公共目标解析：先找 8 格内自己名下、实体在线的女仆（取最近），
     * 再退"自己只有一只活着的女仆"（她坐家里/在别处也允许用命令）。
     *
     * <p>找不到时直接把提示发给玩家并返回 null——调用方只需要判空，不要各自再拼文案。
     */
    private Maid resolveMaid(Player player) {
        List<Maid> owned = maidManager.byOwner(player.getUniqueId());
        if (owned.isEmpty()) {
            Messages.send(player, "no-maid");
            return null;
        }
        Maid nearest = null;
        double bestSq = (double) NEARBY_RADIUS * NEARBY_RADIUS;
        for (Maid maid : owned) {
            if (!maid.isAlive()) {
                continue;
            }
            LivingEntity entity = maid.entity();
            if (entity == null || !entity.getWorld().equals(player.getWorld())) {
                continue;
            }
            double distSq = entity.getLocation().distanceSquared(player.getLocation());
            if (distSq <= bestSq) {
                bestSq = distSq;
                nearest = maid;
            }
        }
        if (nearest != null) {
            return nearest;
        }
        List<Maid> alive = new ArrayList<>();
        for (Maid maid : owned) {
            if (maid.isAlive()) {
                alive.add(maid);
            }
        }
        if (alive.size() == 1) {
            return alive.getFirst();
        }
        Messages.send(player, "no-maid-nearby", Map.of("radius", String.valueOf(NEARBY_RADIUS)));
        return null;
    }

    /**
     * 按名字定向的解析（P10-10.2）：多女仆时命令只认"最近一只"，第二只永远够不着，
     * 所以各玩家侧命令都接受一个可选的名字参数，给了名字就在自己名下按名找。
     *
     * <p>名字查找不限状态（铃铛态/收纳态也认）：{@code bell} 与 {@code release} 本来就要
     * 对这两种状态操作；gui/mode 这类对实体有意义的命令拿到离线女仆也只是行为空转，
     * 不会出错，没必要为它们各写一套状态过滤。
     */
    private Maid resolveMaid(Player player, String name) {
        if (name == null || name.isBlank()) {
            return resolveMaid(player);
        }
        List<Maid> owned = maidManager.byOwner(player.getUniqueId());
        if (owned.isEmpty()) {
            Messages.send(player, "no-maid");
            return null;
        }
        for (Maid maid : owned) {
            if (maid.getName().equalsIgnoreCase(name.trim())) {
                return maid;
            }
        }
        Messages.send(player, "maid-name-not-found", Map.of("name", name));
        return null;
    }

    /** 取第 {@code index} 个参数，越界返回 null——各命令用它读"可选的女仆名"。 */
    private static String optionalArg(String[] args, int index) {
        return args.length > index ? args[index] : null;
    }

    private void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("catmaid.admin")) {
            Messages.send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "summon" -> summon(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender, args);
            case "setaffection" -> setAffection(sender, args);
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
    }

    /**
     * 重载 config 与台词（蓝图 P8：{@code /catmaid admin reload} 后配置与台词即时生效）。
     *
     * <p>三个动作的顺序是硬性的（api-facts 22.3-2）：等级表 → 文案 → 台词。
     * 反过来会出现"好感变了但背包容量没变"——容量只在 {@code Maid#setAffection} 时同步，
     * reload 不补历史女仆，所以最后还要把每只女仆的容量按新表刷一遍。
     *
     * <p>改不动的部分（需要重启）：{@code models} 名单（ModelRegistry 是启动快照）、
     * {@code nametag.*} 的缩放与高度（NameTagManager 构造时读取）。
     */
    private void reload(CommandSender sender) {
        reloadConfigInPlace();
        AffectionTable.load(plugin.getConfig(), plugin.getLogger());
        // Messages 存着插件的 JavaPlugin 引用，reload() 会连"补齐缺失键"一起重做
        Messages.reload();
        dialogue.reload();
        for (Maid maid : maidManager.all()) {
            // 同一数值再 set 一次：setAffection 是容量的唯一同步点，reload 后靠它把新容量灌进去
            int before = maid.inventory().storageCapacity();
            maid.setAffection(maid.getAffection());
            int after = maid.inventory().storageCapacity();
            if (before != after) {
                plugin.getLogger().info(maid.getName() + " 背包容量 "
                        + before + " → " + after + "（reload 后按新等级表刷新）");
            }
        }
        Messages.send(sender, "reload-done");
        plugin.getLogger().info("配置与台词已重载，已按新的等级表刷新 "
                + maidManager.all().size() + " 只女仆的背包容量");
    }

    /**
     * 把 config.yml 的值原地覆盖进插件的 FileConfiguration。
     *
     * <p>为什么不用 {@code plugin.reloadConfig()}：Paper 的实现是
     * {@code newConfig = YamlConfiguration.loadConfiguration(...)}——它换的是对象，
     * 而 MaidManager / AffectionManager / 五个 BehaviorMode 在构造时就抓住了旧引用，
     * 换对象等于"提示重载成功，但行为一点没变"。原地覆盖则所有持有者立刻看到新值。
     */
    private void reloadConfigInPlace() {
        FileConfiguration live = plugin.getConfig();
        YamlConfiguration fresh = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "config.yml"));
        for (String key : List.copyOf(live.getKeys(true))) {
            live.set(key, null);
        }
        for (String key : fresh.getKeys(true)) {
            if (!fresh.isConfigurationSection(key)) {
                live.set(key, fresh.get(key));
            }
        }
    }

    /**
     * 设置指定女仆的好感度。走 {@link AffectionManager#apply} 而不是直接改字段：
     * 背包容量换算、可取消事件、升级特效与台词都挂在那一条链路上，
     * 绕过它的下场是"面板显示 60，背包还是 27 格"（api-facts 22.3-1）。
     *
     * <p>数值上限按 config 的 {@code affection.min/max} 校验，不写死 0..100。
     */
    private void setAffection(CommandSender sender, String[] args) {
        if (args.length < 4) {
            Messages.send(sender, "setaffection-usage");
            return;
        }
        UUID maidId = parseUuid(args[2]);
        if (maidId == null || maidManager.byId(maidId).isEmpty()) {
            Messages.send(sender, "maid-not-found", Map.of("name", args[2]));
            return;
        }
        Maid maid = maidManager.byId(maidId).get();
        double min = plugin.getConfig().getDouble("affection.min", 0);
        double max = plugin.getConfig().getDouble("affection.max", 100);
        double target;
        try {
            target = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            Messages.send(sender, "affection-invalid",
                    Map.of("min", fmt(min), "max", fmt(max)));
            return;
        }
        if (target < min || target > max) {
            Messages.send(sender, "affection-invalid",
                    Map.of("min", fmt(min), "max", fmt(max)));
            return;
        }
        // delta 传差值：apply 只用 delta，reason 决定事件里的语义（ADMIN 在配置里没有数值键）
        affection.apply(maid, target - maid.getAffection(), AffectionReason.ADMIN);
        Messages.send(sender, "affection-set", Map.of(
                "name", maid.getName(), "value", fmt(maid.getAffection())));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * 发魂符给自己。蓝图 P4 的写法是 {@code [玩家] [数量]}，这里只做自助领取：
     * 给别人发用原版 /give 即可（物品带 PDC，/give 也能带）。
     *
     * <p>P8 加了两个闸门（蓝图 P8 命令树：catmaid.use，冷却 24h，config 可关）：
     * {@code interaction.talisman-enabled} 一键关掉，{@code interaction.talisman-cooldown-hours}
     * 控制冷却（0 = 不限）。冷却记在内存里，重启就清零——故意的：它不是经济系统，
     * 记录落库要多一张表，收益不值。
     */
    private void talisman(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        if (!plugin.getConfig().getBoolean("interaction.talisman-enabled", true)) {
            Messages.send(player, "talisman-disabled");
            return;
        }
        long cooldownMs = Math.max(0,
                plugin.getConfig().getLong("interaction.talisman-cooldown-hours", 24)) * 3600_000L;
        long now = System.currentTimeMillis();
        long readyAt = talismanReadyAt.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long minutes = Math.max(1, (readyAt - now) / 60_000L);
            Messages.send(player, "talisman-cooldown", Map.of("minutes", String.valueOf(minutes)));
            return;
        }
        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                sendHelp(player);
                return;
            }
        }
        player.getInventory().addItem(items.talisman(amount)).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        talismanReadyAt.put(player.getUniqueId(), now + cooldownMs);
        Messages.send(player, "talisman-received", Map.of("amount", String.valueOf(amount)));
    }

    /**
     * 发放「未唤醒的魂符」（召唤凭证，TLM 的 {@code smart_slab_init}）。
     *
     * <p>与 {@link #talisman} 是同一件物品的不同状态：这张用掉后原地变回空魂符，
     * 所以玩家领一张就能走完"唤醒 → 收人 → 放出"整条链（api-facts 25.2）。
     * 发出的符写了领取者 UUID，转手给别人放不出来。
     */
    private void soul(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        if (!plugin.getConfig().getBoolean("interaction.soul-enabled", true)) {
            Messages.send(player, "soul-disabled");
            return;
        }
        long cooldownMs = Math.max(0,
                plugin.getConfig().getLong("interaction.soul-cooldown-hours", 24)) * 3600_000L;
        long now = System.currentTimeMillis();
        long readyAt = soulReadyAt.getOrDefault(player.getUniqueId(), 0L);
        if (now < readyAt) {
            long minutes = Math.max(1, (readyAt - now) / 60_000L);
            Messages.send(player, "soul-cooldown", Map.of("minutes", String.valueOf(minutes)));
            return;
        }
        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Math.max(1, Math.min(16, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                sendHelp(player);
                return;
            }
        }
        for (int i = 0; i < amount; i++) {
            ItemStack soul = items.initTalisman(player.getUniqueId());
            player.getInventory().addItem(soul).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        }
        soulReadyAt.put(player.getUniqueId(), now + cooldownMs);
        Messages.send(player, "soul-received", Map.of("amount", String.valueOf(amount)));
    }

    // ---- P10-10.4：铃铛补发与自助送走 ----

    /**
     * 补发铃铛（{@code /catmaid bell [名字]}）。
     *
     * <p>绑定关系的修复思路（蓝图 P10-10.4）：DB 是绑定的事实来源，铃铛只是凭证，
     * 凭证丢了可以再补——否则"女仆全灭 + 铃铛丢失"会让玩家既不能复活又腾不出名额，永久锁死。
     * 补发收 {@code death.revive-cost} 同额材料，堵住"丢铃铛白嫖一次复活传送"的漏洞；
     * {@code death.bell-expire-days} 打开时（默认关），倒下超过 N 天的免费补发。
     *
     * <p>不带名字时只在铃铛态女仆里解析：只有一只就默认她，多于一只必须指定——
     * 普通命令的"就近"策略对倒下的女仆没有意义（她没有实体，谈不上远近）。
     */
    private void bell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        String name = optionalArg(args, 1);
        Maid maid;
        if (name == null || name.isBlank()) {
            List<Maid> fallen = maidManager.byOwner(player.getUniqueId()).stream()
                    .filter(m -> !m.isAlive()).toList();
            if (fallen.isEmpty()) {
                Messages.send(player, "bell-none");
                return;
            }
            if (fallen.size() > 1) {
                Messages.send(player, "bell-specify",
                        Map.of("count", String.valueOf(fallen.size())));
                return;
            }
            maid = fallen.getFirst();
        } else {
            maid = resolveMaid(player, name);
            if (maid == null) {
                return;
            }
        }
        if (maid.isAlive()) {
            Messages.send(player, "bell-already-alive", Map.of("name", maid.getName()));
            return;
        }
        Cost cost = reviveCost();
        boolean free = bellExpired(maid);
        if (!free) {
            if (!player.getInventory().containsAtLeast(new ItemStack(cost.material()), cost.amount())) {
                Messages.send(player, "revive-no-item", Map.of(
                        "item", cost.material().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                        "amount", String.valueOf(cost.amount())));
                return;
            }
        }
        ItemStack bell = items.bell(maid);
        player.getInventory().addItem(bell).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        if (!free) {
            player.getInventory().removeItem(new ItemStack(cost.material(), cost.amount()));
            Messages.send(player, "bell-issued", Map.of(
                    "name", maid.getName(),
                    "item", cost.material().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "amount", String.valueOf(cost.amount())));
        } else {
            Messages.send(player, "bell-issued-free", Map.of(
                    "name", maid.getName(),
                    "days", String.valueOf(plugin.getConfig().getLong("death.bell-expire-days", 0))));
        }
        plugin.getLogger().info(player.getName() + " 补发了铃铛态女仆 " + maid.getName()
                + (free ? "（过期免费）" : "（收费 " + cost.material() + " × " + cost.amount() + "）"));
    }

    /** 倒下超过 {@code death.bell-expire-days} 天（0 = 功能关）。 */
    private boolean bellExpired(Maid maid) {
        long days = plugin.getConfig().getLong("death.bell-expire-days", 0);
        if (days <= 0 || maid.getDeathAt() <= 0) {
            return false;
        }
        return System.currentTimeMillis() - maid.getDeathAt() >= days * 86_400_000L;
    }

    /**
     * 主人自助送走（{@code /catmaid release [名字]}）：删 DB 记录 + 销毁 NPC，任何状态都能送。
     *
     * <p>送走是不可逆的（她的好感、背包、统计随记录一起消失），所以二次确认用
     * "聊天栏再输入一次她的名字"（蓝图原文）——比再敲一遍命令更醒目，也不容易顺手点掉。
     * 名字输入在 AsyncChatEvent 里比对，命中就吞掉聊天消息并在主线程执行删除；
     * 输错或超时则静默过期，不打断玩家正常聊天。
     */
    private void release(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        Maid maid = resolveMaid(player, optionalArg(args, 1));
        if (maid == null) {
            return;
        }
        pendingRelease.put(player.getUniqueId(),
                new PendingRelease(maid.getId(), maid.getName(),
                        System.currentTimeMillis() + CONFIRM_TIMEOUT_MS));
        Messages.send(player, "sendaway-confirm", Map.of("name", maid.getName()));
        Messages.send(player, "sendaway-confirm-2", Map.of("name", maid.getName()));
    }

    /** /catmaid release 的聊天栏二次确认（详见 {@link #release}）。 */
    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        PendingRelease pending = pendingRelease.get(event.getPlayer().getUniqueId());
        if (pending == null) {
            return;
        }
        if (System.currentTimeMillis() > pending.expiresAt()) {
            pendingRelease.remove(event.getPlayer().getUniqueId());
            return;
        }
        String typed = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        if (!typed.equalsIgnoreCase(pending.maidName())) {
            return; // 输错了不打断正常聊天，等它自然过期
        }
        event.setCancelled(true);
        pendingRelease.remove(event.getPlayer().getUniqueId());
        UUID maidId = pending.maidId();
        String name = pending.maidName();
        // 聊天是异步事件，删 NPC/DB 必须回主线程
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (maidManager.byId(maidId).isEmpty()) {
                return; // 等待期间她已经被送走/admin remove，无事可做
            }
            if (maidManager.remove(maidId)) {
                Messages.send(event.getPlayer(), "sendaway-success", Map.of("name", name));
            }
        });
    }

    /** /catmaid release 的待确认记录。 */
    private record PendingRelease(UUID maidId, String maidName, long expiresAt) {
    }

    /** config 的 {@code death.revive-cost} 解析（与 TalismanListener 同款；写错回落默认值）。 */
    private record Cost(Material material, int amount) {
    }

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

    private void summon(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendHelp(sender);
            return;
        }
        OfflinePlayer target = resolvePlayer(args[2]);
        if (target == null) {
            Messages.send(sender, "player-not-found", Map.of("player", args[2]));
            return;
        }
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            // 离线模式服没有 usercache 也能确定 UUID，管理员操作给个明确提示而不是静默建幽灵档案
            Messages.send(sender, "summon-offline-note", Map.of("player", args[2]));
        }
        String modelId = args.length >= 4 ? args[3] : null;
        if (modelId != null && !models.contains(modelId)) {
            Messages.send(sender, "model-unknown", Map.of("model", modelId));
        }

        Location loc;
        Player online = target.getPlayer();
        if (online != null) {
            loc = online.getLocation();
        } else {
            if (Bukkit.getWorlds().isEmpty()) {
                Messages.send(sender, "world-unavailable");
                return;
            }
            loc = Bukkit.getWorlds().getFirst().getSpawnLocation();
        }

        Maid maid = maidManager.summonFor(target, modelId, loc);
        if (maid == null) {
            Messages.send(sender, "summon-max", Map.of("max", String.valueOf(maidManager.maxPerPlayer())));
            return;
        }
        Messages.send(sender, "summon-success",
                Map.of("name", maid.getName(), "model", maid.getModelId(), "owner", displayName(target)));
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendHelp(sender);
            return;
        }
        String key = args[2];
        UUID maidId = parseUuid(key);
        int removed = 0;
        String removedName = key;
        if (maidId != null && maidManager.byId(maidId).isPresent()) {
            // 先把名字取出来：remove 之后对象就查不到了，提示里说 UUID 对管理员没意义
            removedName = maidManager.byId(maidId).get().getName();
            removed = maidManager.remove(maidId) ? 1 : 0;
        } else {
            OfflinePlayer target = resolvePlayer(key);
            if (target != null) {
                // 按玩家批量清理时逐只移除：byOwner 返回的是新列表，边遍历边删是安全的
                for (Maid maid : maidManager.byOwner(target.getUniqueId())) {
                    if (maidManager.remove(maid.getId())) {
                        removed++;
                    }
                }
            }
        }
        if (removed == 0) {
            Messages.send(sender, "maid-not-found", Map.of("name", key));
        } else if (removed == 1) {
            Messages.send(sender, "remove-success", Map.of("name", removedName));
        } else {
            // 一次清掉好几只时说"已送走 loadtest"会让人以为只删了一只（P8 压测时踩到）
            Messages.send(sender, "remove-all-success", Map.of(
                    "player", key, "count", String.valueOf(removed)));
        }
    }

    private void list(CommandSender sender, String[] args) {
        List<Maid> maids;
        if (args.length >= 3) {
            OfflinePlayer target = resolvePlayer(args[2]);
            if (target == null) {
                Messages.send(sender, "player-not-found", Map.of("player", args[2]));
                return;
            }
            maids = maidManager.byOwner(target.getUniqueId());
        } else {
            maids = new ArrayList<>(maidManager.all());
        }
        Messages.send(sender, "list-header", Map.of("count", String.valueOf(maids.size())));
        for (Maid maid : maids) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", maid.getName());
            ph.put("owner", displayName(Bukkit.getOfflinePlayer(maid.getOwnerUuid())));
            ph.put("model", maid.getModelId());
            ph.put("affection", String.format(Locale.ROOT, "%.1f", maid.getAffection()));
            ph.put("state", Messages.plain(stateKey(maid)));
            sender.sendMessage(Messages.get("list-entry", ph));
        }
    }

    /**
     * 女仆状态的玩家可见名：铃铛（倒下）/ 魂符中（收纳）/ 存活。
     *
     * <p>三种状态互斥且有优先级：alive=false 才是铃铛态，收纳态一定还活着。
     */
    private String stateKey(Maid maid) {
        if (!maid.isAlive()) {
            return "state-bell";
        }
        return maid.isStored() ? "state-stored" : "state-alive";
    }

    /** 在线 → usercache → （仅离线模式）按名称建档；在线模式没有权威 UUID 时直接拒绝，避免造幽灵档案。 */
    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            return cached;
        }
        return Bukkit.getServer().getOnlineMode() ? null : Bukkit.getOfflinePlayer(name);
    }

    private String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? player.getUniqueId().toString() : name;
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void sendHelp(CommandSender sender) {
        for (net.kyori.adventure.text.Component line : Messages.getList("help")) {
            sender.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            // 普通玩家只有玩家侧命令，不要因为没 admin 权限就把补全整个清空
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("catmaid.use")) {
                subs.add("gui");
                subs.add("mode");
                subs.add("sethome");
                subs.add("info");
                subs.add("rename");
                subs.add("title");
                subs.add("talisman");
                subs.add("soul");
                subs.add("bell");
                subs.add("release");
            }
            if (sender.hasPermission("catmaid.admin")) {
                subs.add("admin");
            }
            return filter(subs, args[0]);
        }
        // 玩家侧命令的参数补全要排在 admin 闸门之前，否则普通玩家敲 /catmaid mode <tab> 什么都看不到
        if (args[0].equalsIgnoreCase("mode") && args.length == 2) {
            List<String> modeNames = new ArrayList<>();
            for (Maid.Mode mode : Maid.Mode.values()) {
                modeNames.add(modeName(mode));
            }
            return filter(modeNames, args[1]);
        }
        // P10-10.2：可选女仆名参数的补全，只补自己名下的名字。
        // mode/rename/title 的名字在第三个参数，gui/sethome 在第二个。
        if (sender instanceof Player player && player.hasPermission("catmaid.use")) {
            List<String> names = maidManager.byOwner(player.getUniqueId()).stream()
                    .map(Maid::getName).toList();
            if (!names.isEmpty()) {
                if (args.length == 2 && (args[0].equalsIgnoreCase("gui")
                        || args[0].equalsIgnoreCase("sethome")
                        || args[0].equalsIgnoreCase("bell")
                        || args[0].equalsIgnoreCase("release"))) {
                    return filter(names, args[1]);
                }
                if (args.length == 3 && (args[0].equalsIgnoreCase("mode")
                        || args[0].equalsIgnoreCase("rename")
                        || args[0].equalsIgnoreCase("title"))) {
                    return filter(names, args[2]);
                }
            }
        }
        if (!args[0].equalsIgnoreCase("admin") || !sender.hasPermission("catmaid.admin")) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(List.of("summon", "remove", "list", "setaffection", "reload"), args[1]);
        }
        if (args.length == 3) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("summon")) {
            return filter(models.all(), args[3]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
