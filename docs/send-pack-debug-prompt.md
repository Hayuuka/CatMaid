# 任务：排查修复 CraftEngine 资源包下发（send_pack）未生效问题

你是一名 Minecraft Paper 服务端运维/排障工程师。你的唯一任务：**定位并修复 CraftEngine 26.9.2 在玩家进服时不自动下发资源包的问题**，修好后验证"玩家进服自动收到 CE 资源包"。这是一个排查任务，不是写插件代码的任务。

## 环境

- Paper 26.2，Java 25，测试服目录见工作区 `testserver/`（启动脚本在其中）。
- CraftEngine **26.9.2**（Bukkit 插件），配置在 `testserver/plugins/CraftEngine/config.yml`。
- BetterModel 3.7.x（paper-plugin，模型包产物 `plugins/BetterModel/build.zip`，1.07 MB，内容 `assets/bettermodel`）。
- Citizens2 共存（与本任务无关，但注意加载顺序日志）。
- 控制台可用 RCON 驱动；客户端测试地址 `127.0.0.1:25566`（离线模式）。
- 测试客户端可用：真人进服观察"选项 → 资源包"列表，或用日志/数据库侧写验证。

## 故障现象（历史实测记录，2026-11-10，见 docs/api-facts.md 4.3）

配置为"CE 合并 BetterModel 包 + CE 单包下发 + `require-resource-pack=true`"时：

1. 客户端进服**完全看不到模型**（资源包没到手）；
2. 服务端日志里**没有任何资源包下发记录**（没有"正在上传/发送 pack"之类日志）；
3. `plugins/CraftEngine/data.db` 里**没有玩家包记录**——即 CE 的 `send_pack` 在玩家进服时疑似根本没执行。

当时改用原版 `server.properties` 强制下发后立刻正常，说明**包本身没问题，问题只在 CE 的下发链路**。

## 已知背景事实（避免重复踩坑）

- CE 包产物：`plugins/CraftEngine/generated/resource_pack.zip`（250 KB，`assets/minecraft` / `default` / `internal`），`/ce reload pack` 可重建。
- CE config 中 `resource-pack.merge-external-zip-files` 默认含 `"BetterModel/build.zip"`（历史上被移除过做双包分离；本任务要恢复合并路线，该行应保留/加回）。
- BetterModel 侧：`pack-type: zip`，`use-obfuscation` 默认 true（排障可临时 false）；`merge-with-external-resources: true`（默认）。
- 已知有效的对照组：原版 `resource-pack` + `resource-pack-sha1` + `require-resource-pack=true` 强制下发 100% 生效。
- 部署顺序惯例：先 `/bm reload` 再 `/ce reload pack`。

## 排查方向（按优先级逐一验证，别跳步）

1. **CE workflow / send_pack 是否真的配置并触发**：读 CE 26.9.2 的 config.yml 完整 `resource-pack` 与 workflow 相关节，确认 `send_pack`/auto-send 的配置形状是否与 CE 26.9.2 实际要求的键名一致（版本间键名可能变化，以 CE jar 内默认 config / wiki 为准）。
2. **触发时机**：确认 send_pack 的触发器（玩家 join 事件钩子）是否被注册——CE 日志开启 debug/verbose 后看玩家进服时有无 pack 相关日志。
3. **托管方式**：CE 自托管 HTTP 是否正常监听（端口、绑定地址、防火墙）；`data.db` 里 workflow 状态表有无错误记录。
4. **离线模式的影响**：测试服是 offline 模式，检查 CE 下发链路是否依赖某些玩家属性（UUID/名字）导致离线模式下静默失败。
5. **require-resource-pack / 提示配置**：CE 侧的强制/提示配置是否正确映射到下发包的 forced 状态。
6. **版本已知 issue**：查 CraftEngine 26.9.x 的 GitHub issue / 更新日志，确认 send_pack 不生效是否为已知 bug、有无修复版本或 workaround（必要时可升级 CE，但升级前先在测试服验证兼容）。
7. **最小复现**：剥掉一切其他变量——空 CE 配置 + 仅一个测试物品 + 默认 send_pack 配置，看包能否发到手。能 → 逐步加回 BetterModel 合并项定位是哪个配置破坏的；不能 → 问题在 CE 下发链路本身。

## 修复成功的验收标准（全部满足才算完成）

1. 玩家进服**自动弹出/自动应用** CE 资源包（无需手动点），客户端"选项 → 资源包"列表中出现该包；
2. 服务端日志出现资源包下发记录；`plugins/CraftEngine/data.db` 记录了玩家包状态；
3. CE 合并了 BetterModel 包（`merge-external-zip-files` 含 `BetterModel/build.zip`）后，**模型 NPC 正常渲染**（用 `foxmaid` 模型验证）且无紫黑棋盘格；
4. 客户端进服**只下载一个包**（原版 server.properties 的 resource-pack / resource-pack-sha1 / require-resource-pack 三键清空后重启验证）；
5. `/ce reload pack` 后更新包，客户端重进服能拿到新包。

## 约束

- **本任务授权修改** `plugins/CraftEngine/` 内的配置与 `server.properties`（这是唯一的例外授权，其他任务里这些目录是禁区）。
- 允许升级 CraftEngine 版本（若确认是版本 bug），但升级后必须重跑全部验收标准。
- **不改** CatMaid 插件代码、不改 BetterModel 与 Citizens 的配置（BetterModel 仅允许临时改 `use-obfuscation` 排障，结束恢复）。
- 不引入 ResourcePackManager 等第三方分发插件作为"绕过方案"——目标是修 CE 原生链路；若最终确认 CE 无解，输出结论与证据即可，不要硬凑。
- 每一步改动与观察结果都记录下来（改了什么键、看到什么日志），最终输出一份结论：根因 / 修复动作 / 验收结果逐条对照。

## 参考文件

- `docs/api-facts.md` 第 4 节（4.0/4.0b/4.1/4.2/4.3：历史裁决、双包配置、更新姿势、故障记录）
- `blueprint.md` Phase 10 的 10.3 节（本任务在整体方案中的位置：craftengine 路线的启用前置）
- `testserver/plugins/CraftEngine/config.yml`、`server.properties`
