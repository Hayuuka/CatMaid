# 更新日志（Changelog）

本项目所有显著变更都记录在本文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循[语义化版本（SemVer）](https://semver.org/lang/zh-CN/)。

## [1.1.0] - 2026-11-14

### Added

- **`/catmaid hide` — 玩家级猫娘可见性开关**（Phase 11）：不想看猫娘的玩家一键对所有猫娘女仆的渲染实体隐身——骨骼模型、头顶名牌、NPC 底层实体全部隐藏，**包括自己的女仆**；女仆照常工作，其他玩家零影响。再输一次恢复。
  - 基于 Paper 原生 `Player#hideEntity`，无新依赖、无 NMS、不碰资源包链路。
  - 偏好持久化到数据库 `player_prefs` 表，relog 后保持；换模型、区块重载 respawn、名牌重建时自动重新施加，另有低频对账 sweep 兜底。
  - 受 `commands.admin-only.hide`（默认 `false`，即默认对全员开放）闸门约束；不要求 `catmaid.use`。
- **身份书实时血量显示**：GUI 信息书新增血量行（`gui.info-health`），收纳态无实体时自动省略。
- **可配置回血机制**：缓慢自动回血（`combat.regen-amount` / `combat.regen-seconds`，支持 `/catmaid admin reload` 实时调整）与投喂回血（`interaction.feed-heal`）。
- config.yml 新键：`commands.admin-only.hide`、`combat.regen-amount`、`combat.regen-seconds`、`interaction.feed-heal`；messages.yml 新键：`hide-on` / `hide-off` / `gui.info-health`（老配置文件启动时自动补齐）。

### Fixed

- 修复 relog 后猫娘隐藏失效：hideEntity 状态随连接销毁，但内存对账集跨 relog 存活导致重进后跳过重新施加；现已改为退出时清空该玩家对账记录。

## [1.0.0] - 2026-11-13

首个正式版本。

### Added

- **女仆核心**：召唤 / 收纳 / 放出（魂符三态，对齐车万女仆）、好感度养成、改名改称呼、台词系统、独立虚拟背包（随好感扩容）与装备 GUI。
- **五种工作模式**：跟随 / 待机 / 战斗（近战 + 弓弩）/ 农业（收割补种）/ 畜牧（配对·剪毛·挤奶）。
- **死亡与复活**：倒下变铃铛，凭材料唤醒；铃铛丢失可补发。
- **多女仆**：每玩家可养多只，命令按名字定向；`/catmaid admin` 管理命令集。
- **模型系统**：27 个 BetterModel 模型（改编自车万女仆美术资源，CC BY-NC-SA 4.0），骨骼动画、坐姿贴地钳制、手持物品渲染、头顶名牌随好感变色。
- **资源包下发**：CraftEngine 路线（`dependency.provider=craftengine`）单包下发含修复，或原版 `server.properties` 静态托管。
- **分层授权**：代码 MIT；模型素材 CC BY-NC-SA 4.0（见 LICENSE-MODELS.md）。

[1.1.0]: https://github.com/Hayuuka/CatMaid/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/Hayuuka/CatMaid/releases/tag/v1.0.0
