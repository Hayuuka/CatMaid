package dev.catmaid.command;

import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.ModelRegistry;
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
 * /catmaid 命令树。Phase 1 实现了 admin 三个子命令，Phase 4 加了 talisman（魂符发放），
 * 其余（gui/mode/info/rename/sethome/admin reload 等）在 Phase 8 补齐。
 */
public final class CatMaidCommand implements CommandExecutor, TabCompleter {

    private final MaidManager maidManager;
    private final ModelRegistry models;
    private final Plugin plugin;

    public CatMaidCommand(MaidManager maidManager, ModelRegistry models, Plugin plugin) {
        this.maidManager = maidManager;
        this.models = models;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "talisman" -> talisman(sender, args);
            case "admin" -> admin(sender, args);
            default -> sendHelp(sender);
        }
        return true;
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
            // 普通玩家只有 talisman，不要因为没 admin 权限就把补全整个清空
            List<String> subs = new ArrayList<>();
            if (sender.hasPermission("catmaid.use")) {
                subs.add("talisman");
            }
            if (sender.hasPermission("catmaid.admin")) {
                subs.add("admin");
            }
            return filter(subs, args[0]);
        }
        if (!sender.hasPermission("catmaid.admin")) {
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
