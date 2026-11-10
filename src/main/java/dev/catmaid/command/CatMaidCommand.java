package dev.catmaid.command;

import dev.catmaid.maid.Maid;
import dev.catmaid.maid.MaidManager;
import dev.catmaid.maid.ModelRegistry;
import dev.catmaid.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /catmaid 命令树。Phase 1 只实现 admin 三个子命令，其余在 Phase 8 补齐。
 */
public final class CatMaidCommand implements CommandExecutor, TabCompleter {

    private final MaidManager maidManager;
    private final ModelRegistry models;

    public CatMaidCommand(MaidManager maidManager, ModelRegistry models) {
        this.maidManager = maidManager;
        this.models = models;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("admin")) {
            sendHelp(sender);
            return true;
        }
        if (!sender.hasPermission("catmaid.admin")) {
            Messages.send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "summon" -> summon(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender, args);
            default -> sendHelp(sender);
        }
        return true;
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

    /** 只认 usercache/在线玩家，避免管理员手抖拼错名字造出幽灵 UUID。 */
    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached;
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
        if (!sender.hasPermission("catmaid.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("admin"), args[0]);
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
