# CatMaid 配置详解

> 适用版本：P10（2026-11-13）。对应文件：`plugins/CatMaid/config.yml`。
> 阅读顺序建议：先看[总览](#0-总览)，再按需要查各节。
> 文案/台词不在本文件里——见文末[配套文件](#9-配套文件messagesyml-与-dialogueyml)。

## 0. 总览

| 生效方式 | 配置节 |
|---|---|
| `/catmaid admin reload` 即时生效 | general、commands、performance、follow、idle、farm、ranch、interaction、affection、dialogue、death、combat、dependency |
| **必须重启服务器** | `models`（启动快照）、`nametag` 的 scale/height（构造时读取） |
| 旧文件不会自动长出新键 | config.yml 只在文件不存在时从 jar 模板整体生成；升级后手动拷新段（不拷 = 走默认值，行为不变） |

---

## 1. general — 基础

| 键 | 默认 | 说明 |
|---|---|---|
| `max-per-player` | `2` | 每个玩家最多拥有的女仆数（含铃铛态与收纳态，都占名额）。达到上限后召唤被拒绝并提示。 |
| `initial-affection` | `10` | 新女仆的初始好感度。 |
| `default-title` | `主人` | 新女仆对主人的默认称呼，台词里的 `{owner}` 就是它。玩家可用 `/catmaid title` 修改（有好感门槛）。 |

## 2. commands.admin-only — 命令权限（P10 新增）

每条 `/catmaid` 玩家侧命令一个布尔开关：

| 键 | 对应命令 |
|---|---|
| `gui` | `/catmaid gui [名字]` — 打开女仆主菜单 |
| `mode` | `/catmaid mode <模式> [名字]` — 切换工作模式 |
| `sethome` | `/catmaid sethome [名字]` — 设家 |
| `info` | `/catmaid info` — 女仆状态列表 |
| `rename` | `/catmaid rename <名字> [原名字]` — 改名 |
| `title` | `/catmaid title <称呼> [名字]` — 改称呼 |
| `talisman` | `/catmaid talisman [数量]` — 领空魂符 |
| `soul` | `/catmaid soul [数量]` — 领未唤醒的魂符 |
| `bell` | `/catmaid bell [名字]` — 补发铃铛 |
| `release` | `/catmaid release [名字]` — 送走女仆 |

- `false`（默认）= 有 `catmaid.use`（默认全员）就能用；`true` = 只认 `catmaid.admin`（默认 op），普通玩家收到拒绝提示。
- 设 `true` 后该命令在普通玩家的 tab 补全里也会消失。
- 改完 `/catmaid admin reload` 即时生效。
- `admin` 子树不在此配置（本身就挂 `catmaid.admin`）。
- 键名必须与命令名一字不差；写错不报错，只是不生效。
- 注意 `soul`/`talisman` 设为 true 会关掉玩家自助领取，但**魂符三态的右键使用不受影响**——玩家仍可以用已有的魂符召唤/收纳/放出。

## 3. models / names — 模型池与名字池

```yaml
models:
  - foxmaid
names:
  - 小铃
  - 汐音
```

- `models`：召唤时未指定模型则随机取一个。名字必须与 `plugins/BetterModel/models/` 内文件名（去扩展名）一致。**改动需重启**。目前只启用 `foxmaid`（唯一客户端实测贴图正确的模型，其余 TLM 转换模型有贴图问题，见 api-facts 第 14 节）。模型素材 CC BY-NC-SA 4.0，仅非商用、须署名 TartaricAcid 与车万女仆项目。
- `names`：召唤时给女仆随机取的名字池，可自由增删。

## 4. performance — 性能

| 键 | 默认 | 说明 |
|---|---|---|
| `global-maid-cap` | `60` | 全服女仆总数上限（含铃铛态/收纳态）。判定是 `>=`，第 60 只就会召唤失败（api-facts 22.3-5）。 |
| `behavior-tick-stagger` | `true` | 行为 tick 错峰开关。目前实现是无条件错峰（写在五个 Mode 与名牌跟随内部），设 `false` 只会打一条告警，键位保留以满足蓝图配置形状。 |

## 5. nametag — 头顶名牌

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | TextDisplay 独立名牌总开关。 |
| `scale` | `0.8` | 文字缩放。**改动需重启**。 |
| `height` | `0.35` | 相对头顶的额外抬升。**改动需重启**。 |

名字颜色随好感档位变化，模板在 `messages.yml` 的 `nametag.format` 与 `nametag.name-colors`。

## 6. follow / idle — 跟随与待机

### follow（跟随模式）

| 键 | 默认 | 说明 |
|---|---|---|
| `start-dist` | `6` | 距离超过它开始追。 |
| `stop-dist` | `2.5` | 进入这个距离就停下面向主人。 |
| `teleport-dist` | `40` | 超过直接传送到主人身后（防止跟丢）。 |
| `speed` | `1.15` | Citizens baseSpeed；1.0 约等于步行，1.15 才跟得上玩家走路。 |

### idle（待机模式）

| 键 | 默认 | 说明 |
|---|---|---|
| `wander-radius` | `5` | 在家（home）周围闲逛的半径。 |

## 7. farm / ranch — 农业与畜牧

### farm（农业模式）

| 键 | 默认 | 说明 |
|---|---|---|
| `radius` | `8` | 以中心向外扫几格（x/z），y 只扫 -1..+1。每 20 tick 扫一次（按 npcId 错峰）。 |
| `center` | `home` | `home` = 在家附近干活；`self` = 跟着自己脚下走（适合带出去开荒）。 |
| `replant` | `true` | 收割后自动补种，从女仆背包扣 1 个对应种子；没种子就空着。 |
| `crops` | 小麦/胡萝卜/土豆/甜菜/地狱疣/可可 | 允许收割的作物（必须是原版 Ageable，成熟判定看 age 到顶）。 |

### ranch（畜牧模式）

| 键 | 默认 | 说明 |
|---|---|---|
| `radius` | `8` | 找动物/地面产物的半径（以女仆为中心）。捡拾每 10 tick、决策每 30 tick，均错峰。 |
| `pickup` | `true` | 是否顺手捡地面产物（鸡蛋、羊毛、掉落物）。 |
| `action-priority` | `[BREED, SHEAR, MILK]` | 干活顺序，排前面的先干。 |
| `breed-food` | 牛/羊小麦、猪胡萝卜、鸡种子 | 只覆盖特例；没写的动物由原版判定（`isBreedItem`）。繁殖一次要 2 份（一只一份）。 |

## 8. interaction — 交互与凭证物品

### 魂符三态（对齐车万女仆，api-facts 25）

```yaml
talisman-material: AMETHYST_SHARD
```

魂符是同一件物品的三个状态，全部"替换"不"消耗"：
**未唤醒**（对地右键召唤，原地变空）→ **空**（对女仆右键收纳）→ **装着**（对空地右键放出，又变空）。材质可换（默认紫水晶碎片）。

### 自助领取

| 键 | 默认 | 说明 |
|---|---|---|
| `talisman-enabled` / `talisman-cooldown-hours` | `true` / `24` | `/catmaid talisman` 空魂符领取开关与冷却（0 = 不限）。冷却记内存，重启清零。 |
| `soul-enabled` / `soul-cooldown-hours` | `true` / `24` | `/catmaid soul` 未唤醒魂符（召唤凭证）同上。 |
| `grant-init-talisman-on-first-join` | `true` | 首次进服自动发一张未唤醒的魂符（PDC 去重，一生一次）。 |

### 喂食

| 键 | 默认 | 说明 |
|---|---|---|
| `feed-cooldown-seconds` | `60` | 同一只女仆两次喂食的最小间隔。 |
| `feedable` | 面包、烤鳕鱼/鲑鱼、烤土豆、烤牛/猪排、曲奇、蛋糕、甜浆果、发光浆果 | 手持其中之一右键女仆 = 喂食（否则是开菜单）。 |

## 9. affection — 好感度养成

### 区间与等级表

```yaml
affection:
  min: 0
  max: 100
  levels:
    - {min: 0,  capacity: 27, damage-bonus: 0.0}
    - {min: 20, capacity: 30, damage-bonus: 0.05}
    - {min: 40, capacity: 33, damage-bonus: 0.10}
    - {min: 60, capacity: 36, damage-bonus: 0.15}
    - {min: 80, capacity: 36, damage-bonus: 0.20}
```

- `min` = 进入该档所需好感度（第一档必须 0 且递增）；`capacity` = 该档背包格数（最大 36）；`damage-bonus` = 战斗伤害加成（P7）。
- **档数可增删**：等级名在 `messages.yml` 的 `gui.level-<序号>`（0 起：陌生人→熟识→伙伴→挚爱→羁绊），超出的档显示 `Lv.N`。
- 背包容量只在好感变动时换算；`admin reload` 会按新表把存量女仆刷一遍。

### 其他键

| 键 | 默认 | 说明 |
|---|---|---|
| `death-halve-from-level` | `4` | 从第几档（0 起）开始，死亡扣好感减半（默认最后一档"羁绊"）。 |
| `hurt-cooldown-seconds` | `5` | 受击扣好感的最小间隔（火焰每 10 tick 跳一次，不节流会放干好感）。 |
| `gain.feed / work-harvest / work-breed / work-kill / follow-tick` | `1.0 / 0.1 / 0.1 / 0.2 / 0.5` | 各行为加的好感。 |
| `loss.hurt / loss.death` | `0.5 / 20.0` | 受击 / 死亡扣的好感。 |
| `follow-tick-interval-seconds` | `300` | 跟随多久结算一次 follow-tick（蓝图要求 5 分钟）。 |

## 10. dialogue — 台词

| 键 | 默认 | 说明 |
|---|---|---|
| `channel` | `chat` | 台词出口：`chat` 聊天栏 / `actionbar` 快捷栏上方。 |
| `hurt-cooldown-seconds` | `30` | 受伤台词冷却（与好感扣减冷却分开）。 |
| `idle.check-interval-seconds / chance / cooldown-seconds` | `180 / 0.05 / 300` | 随机待机台词：每 180s 掷骰，5% 说一句，同一只两次至少隔 300s。 |

台词本体在 `dialogue.yml`（触发点、键名见第 13 节配套文件）。

## 11. death — 死亡与铃铛

| 键 | 默认 | 说明 |
|---|---|---|
| `revive-cost` | `DIAMOND:1` | 右键铃铛唤醒的代价，格式 `材质:数量`（数量可省略）。**`/catmaid bell` 补发铃铛也按这个价收**。材质写错回落 DIAMOND 并打告警。 |
| `bell-material` | `BELL` | 铃铛的物品材质。 |
| `bell-glowing` | `true` | 掉落在地的铃铛是否发光（穿墙可见）。 |
| `bell-expire-days` | `0` | 铃铛态超过 N 天后 `/catmaid bell` 免材料补发。`0` = 关闭（补发一律收费）。默认关。 |

死亡不掉落任何物品（一切都在虚拟背包与数据库里）；铃铛本体不可摧毁、不消失（api-facts 18.3-3）。

## 12. combat — 战斗模式

| 键 | 默认 | 说明 |
|---|---|---|
| `radius` | `12` | 索敌半径（主动找范围内的怪近战）。每 10 tick 决策一次，错峰。 |
| `lose-dist` | `20` | 脱战距离。 |
| `attack-interval-ticks` | `20` | 两次出手的最小间隔（剑满蓄约 12.5 tick，20 对斧略欠）。 |
| `ranged-enabled` | `true` | 主手是弓/弩时保持距离射箭。 |
| `ranged-range` | `15` | 射程（超出就走近；15 格外箭下坠明显）。 |
| `arrow-damage` | `5.0` | 箭基础伤害（不含好感加成）。 |
| `protect-owner` | `true` | 主人被打时，战斗模式的女仆立刻锁定攻击者。 |
| `maid-max-health` | `40` | 女仆血量上限。玩家型 NPC 默认 20 血，不改打不过两只僵尸；每次实体生成都会重设并回满。 |

## 13. dependency — 依赖模式（P9）

```yaml
dependency:
  provider: vanilla        # vanilla | craftengine
  craftengine:
    bell-id: catmaid:bell
    talisman-id: catmaid:talisman
```

- `vanilla`（当前采用）：凭证物品（铃铛/魂符）用原版材质，资源包只有原版槽位一个下发源。
- `craftengine`：凭证物品改用 CE 自定义物品 ID。CE 侧没定义这些 ID 时自动回落原版材质并打告警。
- 两种模式的物品互相认（身份认 CatMaid 自己的 PDC），切换后旧物品不作废。
- 资源包分发的部署裁决见 api-facts 第 4.0 节（P10 起只走原版单包）。

---

## 14. 配套文件：messages.yml 与 dialogue.yml

### messages.yml（玩家可见文案，MiniMessage 格式）

- 所有提示/菜单/名牌/物品名都从这里读，代码不硬编码中文（agent.md 5.5）。
- 占位符用 `{name}` 风格；名牌模板 `nametag.format`、名字颜色档 `nametag.name-colors`、等级名 `gui.level-*`、模式显示名 `gui.mode-*` 都在这里。
- **升级时旧文件的 `help`（帮助列表）不会自动更新**——删除一次 `plugins/CatMaid/messages.yml` 让插件从 jar 重建（新键会自动补，已有键/列表不会变），这是已知升级姿势（api-facts 23.3-2）。

### dialogue.yml（台词）

按触发点组织：`on_summon` / `on_revive` / `on_death` / `on_mode_change` / `on_hurt` / `on_store` / `on_release` / 待机随机台词等。改台词不用动代码，`admin reload` 即时生效。

### config.yml 升级速查

| 情况 | 做法 |
|---|---|
| 全新部署 | 什么都不用做，首次启动自动生成带注释的完整 config |
| 升级后想用新键 | 从 jar 模板（`config.yml` 源文件）手动拷对应段 |
| 只改了已有键的值 | `/catmaid admin reload` 即可（models、nametag 尺寸除外，需重启） |
