# 待验证 Bug / 可疑项清单（2026-11-15 只读排查产出）

> 本清单收录**无法仅凭静态代码确认、需要实机验证**的可疑项。
> 已经代码确认的确定缺陷不走本清单，见 GitHub Issues #1–#9。
> 排查方式：通读 blueprint.md / agent.md / docs/api-facts.md 与全部 Java 源码（只读，未改动任何代码）。
> 验证后请把结论回填到对应条目，并同步 api-facts.md 修订记录。

---

## 1. 「名牌残留」的根因归属（已知 bug，三个候选根因待分辨）

**现象**：女仆不在那个位置，她的名牌却留在那里。

代码侧结论：NameTagManager 的脚本化清理路径本身是通的（死亡 → `removeOrphans` 下一 tick；
收纳/实体消失 → `update()` 的 entity==null 分支 ≤5 tick 清；管理移除 → `removeOrphans`）。
所以**长期残留**一定来自下述候选之一，按可能性排序：

### 候选 A：启动竞态幽灵身体（代码已证实成立 → Issue #1，本条只验实机表现）

收纳态女仆的幽灵身体（alive=true）会被行为 tick 推动并被 NameTagManager 挂名牌，
表现就是「名牌（和身体）留在收纳原地」。修复跟踪在 Issue #1。

**验证步骤**：
1. 把所有女仆收进魂符或打成铃铛态，`citizens save`；
2. 重启服务器，等 10 秒（Citizens 异步载入完成后）；
3. `citizens save` 后 grep saves.yml，看该 NPC 是否被重新写成 spawned / 位置条目复活；
4. 进服观察收纳原地是否有身体+名牌。

### 候选 B：历史孤儿 TextDisplay（不在 displays 表里，removeOrphans 永远扫不到）

api-facts.md:1294 已记录过这个已知边界。任何历史版本/崩溃会话落盘的 persistent 名牌
都会永久留在原地，而对应女仆可能早已不在——与现象完全一致。

**验证步骤**：控制台执行 `/kill @e[type=text_display]`，观察残留名牌是否消失、之后是否还复现。
若清掉后不再复现 → 坐实候选 B（只需一次性清理 + 以后启动加一轮世界清扫即可）。

### 候选 C：短暂观感（不算缺陷，确认可接受即可）

BM 骨骼实体在 NPC despawn 瞬间消失，名牌要 ≤5 tick（250ms）才被清，且名牌带
`setTeleportDuration(3)` 插值（NameTagManager.java:142）——每次收纳/死亡都有一瞬
「模型没了、名牌还挂着」。若服主反馈的是这种一闪而过的情况，属预期，可调小
STAGGER 或在死亡/收纳路径即时调 `nameTagManager.remove(maid)` 消除。

**验证步骤**：收纳一只女仆，录屏数帧，确认名牌是「闪一下就没了」还是「永久挂着」。

---

## 2. 慢启动重影 NPC（recreate 竞态的反面）

`scheduleRestore` 30 秒后才 `allowRecreate`（CatMaidPlugin.java:239），api-facts 10.1
明确警告「查不到就新建会造重影」。正常启动 Citizens 约 5 秒载入完成，安全余量充足；
但**若 Citizens 载入超过 30 秒**（大存档/慢盘），`MaidManager.recreate`（MaidManager.java:385-391）
会新建 NPC 并把 `maid.npcId` 覆盖成新 id——随后 Citizens 载入完成，旧 NPC 带完整模型出现：
不被任何 Maid 引用（无名牌、行为不 tick），却永久留在 Citizens 存档里。

**验证步骤**：难以直接复现；可临时把 `attempts >= 30` 改小观察（验证后还原），
或在生产服出现「多了一只站着不动、没有名牌的女仆」时先比对 saves.yml 里
同名 NPC 的数量与 catmaid trait 的 maidId 是否指向同一只。

---

## 3. 玩家型 NPC 的死亡事件是否派发（P4 遗留未实测）

`MaidDamageListener` 的类注释（MaidDamageListener.java:40-44）写明：死亡事件同时挂在
`EntityDeathEvent` 与 `PlayerDeathEvent` 上，**玩家型 NPC 到底走哪条未实测**，靠
`onDamage` 的 `health - finalDamage <= 0` 兜底（MaidDamageListener.java:81-84）兜住。
现状功能可用（兜底路径已实测），但「死亡事件从不派发」的话 `getDrops().clear()`、
`setDroppedExp(0)` 两条防掉落只在兜底路径生效，时序上兜底发生在伤害事件里、
早于原版掉落结算——**需要实测确认原版掉落物/经验不会在兜底路径下漏出来**。

**验证步骤**：用高伤害武器一击打死女仆，检查死亡点是否出现任何掉落物/经验球
（预期：只有铃铛，无其他掉落）。

---

## 4. P11 遗留复测项（api-facts 28.4-3 / 28.5-2 已登记）

- 区块重载 respawn（只触发 `MaidTrait#onSpawn` 委托路径）下，opt-out 玩家是否始终看不见
  重生的骨骼实体与名牌；
- 30+ 女仆同屏时 opt-out 玩家端性能（`renderEntityUuids` 每次全量反射枚举骨骼，
  NameTagManager 100 tick 一次 sweep + 每次 hide 回调，30 只以上可能有可测开销）。

**验证步骤**：按 api-facts 28.3 的双客户端方案补测。

---

## 5. craftengine provider 全链路（既有登记项，随本清单归档）

api-facts 24.3-6 已登记：CE 无 catmaid 物品定义、provider=craftengine 的
召唤/喂食/死亡/复活/收纳全链路未实测（构造路径「JAR 已核、未实测」）。
启用该路线前必须补测，见 api-facts 4.0b 的前置清单。

---

## 6. 非缺陷备忘（设计取舍，防止日后误报）

- **铃铛/魂符无防复制机制**：识别全靠 PDC，任何能复制 NBT 的途径（某些备份/同步插件）都会
  克隆出有效凭证。当前单机自用可接受，开放服需评估。
- **FarmMode 无归属/时运判断**：`getDrops(tool)` 只用主手工具，女仆能收任何玩家种的小麦；
  时运/精准只在主手真拿工具时生效。蓝图未要求归属，属设计内。
- **CombatMode 箭无限**：女仆背包无箭也无限射（`spawnArrow`），蓝图未要求弹药经济。
- **切模式即时生效不播 on_mode_change 之外的特效**：GUI 切模式只有台词，无粒子——与
  「模式切换是低仪式感操作」的口径一致。

---

## 关联索引

| 确定缺陷（已立 issue） | 编号 |
|---|---|
| 启动恢复轮询提前收工 → 幽灵身体无人回收（名牌残留主根因） | #1 |
| 多世界未加载时 home 被静默抹掉（数据丢失） | #2 |
| 切模式绕过 BehaviorManager 生命周期，状态残留 | #3 |
| RanchMode.breed 不校验 mate 存活 | #4 |
| 女仆移除后 per-maid 缓存不清（内存泄漏） | #5 |
| NameTagManager 负数取模洞（npcId=-1 潜伏） | #6 |
| /catmaid bell 可无限补发 | #7 |
| summonFor 异常窗口留下无人认领 NPC | #8 |
| 名牌 {owner} 不随主人改名刷新 | #9 |
