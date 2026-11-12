package dev.catmaid.command;

import dev.catmaid.dialogue.AffectionManager;
import dev.catmaid.dialogue.DialogueManager;
import dev.catmaid.gui.GuiManager;
import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.ModelRegistry;
import dev.catmaid.maid.NameTagManager;
import dev.catmaid.util.MaidItems;
import dev.catmaid.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
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
 * <p>玩家侧命令都要先"找到说的是哪只女仆"：{@link #resolveMaid(Player)} 先找 8 格内的，
 * 实在找不到才接受"你只有一只活着"的退让——绝不跨越到别人的女仆（api-facts 22.3-3）。
 */
public final class CatMaidCommand implements CommandExecutor, TabCompleter {

    /** 玩家侧命令认领女仆的半径（蓝图 P8：/catmaid gui 打开"最近一只（或 8 格内）"）。 */
    private static final int NEARBY_RADIUS = 8;

    /** 改名 / 改称呼的好感门槛（蓝图 P4 等级表：伙伴档起解锁改名）。 */
    private static final int RENAME_MIN_AFFECTION = 40;

    private static final int NAME_MIN_LENGTH = 2;
    private static final int NAME_MAX_LENGTH = 12;

    private final MaidManager maidManager;
    private final ModelRegistry models;
    private final Plugin plugin;
    private final GuiManager gui;
    private final AffectionManager affection;
    private final DialogueManager dialogue;
    private final NameTagManager nameTags;

    public CatMaidCommand(MaidManager maidManager, ModelRegistry models, Plugin plugin,
                          GuiManager gui, AffectionManager affection, DialogueManager dialogue,
                          NameTagManager nameTags) {
        this.maidManager = maidManager;
        this.models = models;
        this.plugin = plugin;
        this.gui = gui;
        this.affection = affection;
        this.dialogue = dialogue;
        this.nameTags = nameTags;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "gui" -> gui(sender);
            case "mode" -> mode(sender, args);
            case "sethome" -> sethome(sender);
            case "talisman" -> talisman(sender, args);
            case "admin" -> admin(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    /**
     * 打开最近女仆的主菜单。命令只是"找对人再交给 GUI"，界面逻辑一律留在 GuiManager。
     */
    private void gui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        Maid maid = resolveMaid(player);
        if (maid == null) {
            return; // resolveMaid 已经给过提示
        }
        gui.openMain(player, maid);
    }

    /**
     * 切换最近女仆的模式。与 GUI 里的模式页是同一条链路：改字段 → 落库 → 模式台词 → 提示。
     *
     * <p>台词这句不能省：蓝图 P4 规定模式切换要有反应，GUI 那条路已经接了，
     * 命令行不补就会出现"点界面她说话、打命令她不理"（P2 的代码里留了这条提醒）。
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
        Maid maid = resolveMaid(player);
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

    /** 把脚下位置设为最近女仆的家（农业模式 center=home、回家闲逛都看这个点）。 */
    private void sethome(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "player-only");
            return;
        }
        if (!player.hasPermission("catmaid.use")) {
            Messages.send(player, "no-permission");
            return;
        }
        Maid maid = resolveMaid(player);
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
            default -> sendHelp(sender);
        }
    }

    /**
     * 发魂符给自己。蓝图 P4 的写法是 {@code [玩家] [数量]}，这里只做自助领取：
     * 给别人发用原版 /give 即可（物品带 PDC，/give 也能带）
     * —— 管理员的批量分发留到 P8 的完整命令树。
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
        int amount = 1;
        if (args.length >= 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[1])));
            } catch (NumberFormatException e) {
                sendHelp(player);
                return;
            }
        }
        Material material = Material.matchMaterial(
                plugin.getConfig().getString("interaction.talisman-material", "AMETHYST_SHARD"));
        if (material == null) {
            material = Material.AMETHYST_SHARD;
        }
        player.getInventory().addItem(MaidItems.talisman(plugin, material, amount)).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        Messages.send(player, "talisman-received", Map.of("amount", String.valueOf(amount)));
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
        boolean removed = false;
        if (maidId != null && maidManager.byId(maidId).isPresent()) {
            removed = maidManager.remove(maidId);
        } else {
            OfflinePlayer target = resolvePlayer(key);
            if (target != null) {
                for (Maid maid : maidManager.byOwner(target.getUniqueId())) {
                    removed |= maidManager.remove(maid.getId());
                }
            }
        }
        Messages.send(sender, removed ? "remove-success" : "maid-not-found",
                Map.of("name", key));
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
            ph.put("state", Messages.plain(maid.isAlive() ? "state-alive" : "state-bell"));
            sender.sendMessage(Messages.get("list-entry", ph));
        }
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
                subs.add("talisman");
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
        if (!args[0].equalsIgnoreCase("admin") || !sender.hasPermission("catmaid.admin")) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(List.of("summon", "remove", "list"), args[1]);
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
