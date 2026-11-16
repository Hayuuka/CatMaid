# 待验证 Bug / 可疑项清单（2026-11-15 只读排查产出）

> 本清单收录**无法仅凭静态代码确认、需要实机验证**的可疑项。
> 已经代码确认的确定缺陷不走本清单，见 GitHub Issues #1–#9。
> 排查方式：通读 blueprint.md / agent.md / docs/api-facts.md 与全部 Java 源码（只读，未改动任何代码）。
> 验证后请把结论回填到对应条目，并同步 api-facts.md 修订记录。
>
> **2026-11-15 状态更新**：首轮 7 个确定缺陷已修复（#1 `fc1d0bd`、#2 `db481a1`、#3 `f352b5e`、
> #4 `28aa508`、#5 `eda28d2`、#6 `dedeb4a`、#8 `2993c55`）。修复均为纯逻辑改动、静态可证，
> 未做实机验证；下文 §1 候选 A 相应转为「修复后实机收口」。
> **2026-11-16 追加**：#9（名牌 {owner} 不随改名刷新）已修复 `5003c0e`（上线 refresh + 10 秒
> 低频比对双路径）。#7（铃铛无限补发）已修复——同一女仆补发前校验背包/末影箱是否已持有
> 该女仆铃铛（`MaidItems#holdsBellFor`），方案参考见 §7/§7.1。**首轮 issue 全部修复完毕**，
> 实机验证统一待做（见 §1）。

---

## 1. 「名牌残留」的根因归属（已知 bug，三个候选根因待分辨）

> 状态：主根因（候选 A）已修复为 Issue #1 并合并，待实机收口；候选 B/C 为备选，
> 仅在修复后残留仍复现时排查。

**现象**：女仆不在那个位置，她的名牌却留在那里。

代码侧结论：NameTagManager 的脚本化清理路径本身是通的（死亡 → `removeOrphans` 下一 tick；
收纳/实体消失 → `update()` 的 entity==null 分支 ≤5 tick 清；管理移除 → `removeOrphans`）。
所以**长期残留**一定来自下述候选之一，按可能性排序：

### 候选 A：启动竞态幽灵身体（根因已证实并修复 → Issue #1，本条为修复后实机收口）

收纳态女仆的幽灵身体（alive=true）会被行为 tick 推动并被 NameTagManager 挂名牌，
表现就是「名牌（和身体）留在收纳原地」。根因修复已合并：fc1d0bd（2026-11-15），
恢复轮询改为常驻 5 秒兜底任务，不再在 Citizens 异步载入前提前收工。

**修复后复测步骤**（原「分辨根因」步骤转为确认修复生效）：
1. 把所有女仆收进魂符或打成铃铛态，`citizens save`；
2. 部署含 fc1d0bd 的新构建并重启服务器，等 10 秒（Citizens 异步载入完成后）；
3. `citizens save` 后 grep saves.yml，确认该 NPC 不再被重新写成 spawned / 位置条目复活；
4. 进服观察收纳原地是否仍有身体+名牌。残留消失 → #1 实机关闭；
   若仍复现 → 转查候选 B/C。

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

> 状态：疑似项（非确定缺陷，未立 issue），静态无法证实，仅在满足触发条件时复现。

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

> 状态：疑似项（未立 issue），功能当前可用（兜底路径已实测），本条只验兜底路径下原版掉落是否漏出。

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

> 状态：既有登记项（与本轮 bug 修复无关），属 hide 功能的补充实测。

- 区块重载 respawn（只触发 `MaidTrait#onSpawn` 委托路径）下，opt-out 玩家是否始终看不见
  重生的骨骼实体与名牌；
- 30+ 女仆同屏时 opt-out 玩家端性能（`renderEntityUuids` 每次全量反射枚举骨骼，
  NameTagManager 100 tick 一次 sweep + 每次 hide 回调，30 只以上可能有可测开销）。

**验证步骤**：按 api-facts 28.3 的双客户端方案补测。

---

## 5. craftengine provider 全链路（既有登记项，随本清单归档）

> 状态：既有登记项（api-facts 24.3-6），与本轮 bug 修复无关，启用 CE 路线前补测。

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

## 7. #7（/catmaid bell 无限补发）修复方案参考：TLM 的绑定/找回设计（2026-11-16 调研）

> 结论经直读 TLM 1.20 分支源码验证，供 #7 的方案设计参考，非验证条目。

**绑定**：TLM 女仆继承 TamableAnimal，主人 UUID 写实体 NBT `Owner`；另有全局台账
`MaidWorldData`（SavedData）按主人记录名下每只女仆/墓碑的 MaidId、维度、区块坐标，
仅供定位，归属本体在实体/物品 NBT。换档时魂符携带完整 NBT，释放校验 Owner 必须匹配。

**找回**：仆人铃（`ItemServantBell`）**不消耗、可重复使用**，但只传送既有实体、从不生成
新女仆——这是无复制风险的关键。狐狸卷轴只读查台账定位；小号召回已加载女仆；魂符
（`ItemSmartSlab`）收放为严格 1:1（收进物品时物品变空魂符，数据移走而非复制）。

**总数守恒三重设计**：
1. 每玩家持久化女仆计数上限（`MaidNumCapability#canAdd`，超限拒绝新增，创造模式豁免）；
2. 凭证 `stacksTo(1)` 不可堆叠 + 唯一 UUID 绑定 + INIT 凭证归属锁（转手放不出）；
3. 载女仆物品防复制（禁入容器、掉落发光无敌、防虚空、使用冷却）。

**对 CatMaid #7 的借鉴**：补发前先查该玩家名下有效铃铛/女仆数（配额校验），名下已有
有效凭证时拒绝补发或改为「重绑」而非发新铃——保证凭证总数守恒；铃铛 PDC 加唯一绑定
UUID 与归属锁，与现有 `soul_init_owner` 思路一致。落地方案待定，修完回填。

### 7.1 TLM 生命周期补充调研（2026-11-16 二轮，直读 1.20 分支源码）

**生成**：INIT 魂符 `item/ItemSmartSlab` 右键 → `spawnNewMaid()`：先
`MaidNumCapability.canAdd()` 校验每玩家上限（`capability/MaidNumCapability.java`），
通过即 `cap.add()` + `maid.tame(player)`，INIT 符变 EMPTY。上限校验在生成口。

**存储**：魂符收放 `SlabClickEvent#storeMaidData` / `spawnFromStore` 用
`saveWithoutId`/`load` 严格 1:1 搬运实体 NBT，全程不动计数；防复制 =
`stacksTo(1)` + 禁入容器 + 掉落物无敌发光 + 双向 UUID 校验。
台账 `world/data/MaidWorldData` 只登记在世女仆最后位置，供仆人铃定位。

**死亡与复活**：死亡留不可破坏的墓碑实体 `entity/item/EntityTombstone`——装备/背包
全部还给主人，本体 NBT 剥成胶片 `item/ItemFilm`；**死亡不释放额度**（cap.min 全库仅
指令调用）。复活唯一通道 = 胶片插神社 `block/BlockShrine` + 玩家血量>一半（扣至 0.25
心），`filmToMaid` 重生且不加额度——免费复活靠「额度永不释放」实现。

**凭证职责边界**：仆人铃 `ItemServantBell` = 在世女仆的定位/召回器（绑 UUID、传送、
查台账写最后坐标），**与死亡-复活完全无关**（死者不进台账）；魂符 = 活体存储凭证；
墓碑 = 死亡现场（还物不复活）；胶片 = 复活凭证（有神社仪式 + 血量代价）。

**CatMaid vs TLM**：CatMaid 把「死亡证明」与「复活权利」合并进铃铛；TLM 拆三段
（墓碑还物 / 胶片存数据 / 神社收仪式成本），且总数守恒由**每玩家额度计数**兜底、
与任何凭证无关——#7 的防无限补发核心手段应是召唤/补发口的额度校验，而非追凭证本身。

---

## 关联索引

| 确定缺陷（已立 issue） | 编号 | 状态 |
|---|---|---|
| 启动恢复轮询提前收工 → 幽灵身体无人回收（名牌残留主根因） | #1 | 已修复 `fc1d0bd`，实机收口见 §1 候选 A |
| 多世界未加载时 home 被静默抹掉（数据丢失） | #2 | 已修复 `db481a1` |
| 切模式绕过 BehaviorManager 生命周期，状态残留 | #3 | 已修复 `f352b5e` |
| RanchMode.breed 不校验 mate 存活 | #4 | 已修复 `28aa508` |
| 女仆移除后 per-maid 缓存不清（内存泄漏） | #5 | 已修复 `eda28d2` |
| NameTagManager 负数取模洞（npcId=-1 潜伏） | #6 | 已修复 `dedeb4a` |
| /catmaid bell 可无限补发 | #7 | 已修复（补发前持有校验，见 §7/§7.1） |
| summonFor 异常窗口留下无人认领 NPC | #8 | 已修复 `2993c55` |
| 名牌 {owner} 不随主人改名刷新 | #9 | 已修复 `5003c0e` |
