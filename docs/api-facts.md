# CatMaid API 事实清单（Phase 0 产出）

> 本文件是 Phase 0「依赖打通验证」的交付物。后续阶段写代码前必须先读本文件。
> 标记约定：**[JAR]** = 从依赖 jar 用 `javap` 反编译确认；**[实测]** = 在本地 Paper 26.2 测试服实跑确认；**[待实测]** = 需要真实客户端人工验证。
> 最后更新：2026-11-10

**Phase 0 状态：已完成**（客户端实机验收通过）。**Phase 1 状态：已完成**（服务端 5 项验收通过，模型外观待客户端实测，详见第 11 节）。

Phase 0 的四条关键结论：
1. 挂模型用 `ModelTrait#setRenderer(BetterModel.modelOrNull(id))`，**没有 `setModel`**（3.1）。
2. 资源包走**双包分离**：BM 包用原版 `resource-pack` 强制下发，CE 只发自己的包（4.1）。
3. 女仆模型必须**自带贴图**；玩家型 bbmodel 会回落彩虹调试皮肤（4.4）。
4. 玩家型 NPC **不会自动播行走动画**，Phase 3 必须代码驱动（3.5）。

---

## 1. 测试环境基线

| 项 | 实际值 | 来源 |
|---|---|---|
| 服务端 | Paper **26.2** build **135**（`26.2-135-ver/26.2@e4d6dfd`，2026-10-25） | [实测] 启动日志 |
| API 版本串 | `26.2.build.135-stable` | [实测] 启动日志 |
| Java 运行时 | Oracle JDK **26.0.2.1**（本机未装 25，26 可正常启动 Paper 26.2） | [实测] |
| Citizens | **2.0.44-SNAPSHOT build 4269**（`Citizens-2.0.44-b4269.jar`） | [实测] `/plugins` |
| BetterModel | **3.7.0-SNAPSHOT-541**（paper-plugin，带 bootstrap loader） | [实测] |
| CraftEngine | **26.9.2** | [实测] |
| 三者共存 | 同服加载无 ERROR；BetterModel 日志 `Plugin hooks Citizens` / CraftEngine 日志 `[兼容性] 已挂钩 BetterModel` | [实测] |

测试服路径：`testserver/`（Paper + 上述三个插件 + `plugins/BetterModel/models/steve.bbmodel`）。
控制台驱动方式：`tools/rcon.py`（RCON，见第 6 节）。

**启动方式（无 TTY 环境）**

```bash
cd testserver
exec 3<>cmd.fifo                      # 保持 stdin 不 EOF；JLine 仍会报一次 stdin 错误，忽略
java -Dterminal.jline=false -Xms1G -Xmx2G -jar paper-26.2-135.jar nogui <&3
python ../tools/rcon.py "版本/命令..."  # 所有控制台操作走 RCON

# 另开一个进程：女仆模型包的静态下发（见第 4 节）
cd testserver/plugins/BetterModel && python -m http.server 8123 --bind 127.0.0.1
```

本次运行日志里唯一的 ERROR 是 `[TerminalConsole] Failed to read console input / java.io.IOException: 拒绝访问`（JLine 读管道 stdin 导致，环境相关，与插件无关）；除它之外全程无 ERROR/Exception。

测试服当前存档状态：**3 个** Citizens NPC —— ID 0 `测试`（无模型）、ID 1 `模型测试`（`model: steve`）、ID 2 `骑士`（`model: demon_knight`）。客户端已验证可渲染（见 5.2）。

---

## 2. 构建依赖坐标（已从仓库确认，替换蓝图 0.4 原值）

| 依赖 | 蓝图原值 | **实际可用坐标** | 状态 |
|---|---|---|---|
| Paper API | `io.papermc.paper:paper-api:26.2-R0.1-SNAPSHOT` | `io.papermc.paper:paper-api:26.2.build.135-stable`（仓库 `repo.papermc.io`，版本串已改为 `X.Y.build.N-stable`，旧 `-R0.1-SNAPSHOT` 不存在） | [JAR] 已下载并反编译 |
| Citizens | `net.citizensnpcs:citizens-api:2.0.35-SNAPSHOT` | `net.citizensnpcs:citizens-main:2.0.44-SNAPSHOT`（仓库 `https://repo.citizensnpcs.co/`） | [JAR] `citizens-api` 制品在该仓库 **404 不存在**；`citizens-main` 内含全部 `net.citizensnpcs.api.*` 类 |
| BetterModel | `io.github.toxicity188:bettermodel:1.15.2` | Maven Central 上确实只有 `1.15.2`（且只含 `kr.toxicity.model.api` 纯 API，**不含** Citizens 集成类 `ModelTrait`） | [JAR] 与运行时 3.7.0 有 API 差异，见 3.1 备注 |
| SQLite JDBC | `org.xerial:sqlite-jdbc:3.50.3.0` | 未在 Phase 0 验证（Phase 1 才引入） | — |

**Phase 1 决策建议（Phase 0 只记录事实，不落地）**：`bettermodel` 的 `compileOnly` 若用 Central 的 1.15.2，**拿不到 `ModelTrait`**，且 `BetterModel.registryOrNull(org.bukkit.entity.Entity)` 在 3.7.0 中已被 `registryOrNull(PlatformEntity)` 取代（调用会 `NoSuchMethodError`）。可行路径二选一：
- (a) `compileOnly(files("libs/BetterModel.jar"))` + `compileOnly(files("libs/Citizens.jar"))`，直接用运行时 jar 编译，签名天然一致；
- (b) 用 1.15.2 API 编译，但把 `ModelTrait` 相关调用集中到 `ModelApplier`（蓝图附录 A 已要求），实现内走反射/命令降级。

---

## 3. API 事实

### 3.1 BetterModel ↔ Citizens 挂载模型

```java
// 类全限定名（在 BetterModel 插件 jar 内，不在 Maven Central 的 API 包里）
kr.toxicity.model.bukkit.compatibility.citizens.trait.ModelTrait
    extends net.citizensnpcs.api.trait.Trait

  public ModelTrait();                              // 构造时 super("model")：Citizens 中 trait 名为 "model"
  public ModelRenderer getRenderer();
  public void setRenderer(ModelRenderer renderer);  // 实体已生成→立即 create tracker；传 null→关闭该实体 tracker
  public void onSpawn();                            // 重新按 _renderer 创建 tracker
  public void onDespawn();
  public void load(DataKey key);                    // key.getString("") → BetterModel.modelOrNull(...)
  public void save(DataKey key);                    // 仅在存在活 tracker 时 setString("", tracker.name())
```
[JAR] `javap -c -p kr.toxicity.model.bukkit.compatibility.citizens.trait.ModelTrait`

**蓝图写的 `setModel(String)` 不存在。** 正确用法（与 BetterModel 自带 `/npc model` 命令的实现完全一致，[JAR] `ModelCommand` 字节码）：

```java
ModelTrait trait = npc.getOrAddTrait(ModelTrait.class);      // trait 名 "model"，Citizens 已注册
trait.setRenderer(BetterModel.modelOrNull(modelId));         // 模型名不存在时返回 null → 即移除模型
```

> **[P1 修订 2026-11-10]** `ModelRenderer` 的实际全限定名是 `kr.toxicity.model.api.data.renderer.ModelRenderer`
> （本文件早先只写类名、未记包路径，实现时误写成 `kr.toxicity.model.api.ModelRenderer` 导致 `ClassNotFoundException`）。
> 现在的 `ModelApplier` 不再写死该类名，改为按名字查找 `setRenderer(单参数)` 方法，渲染器再换包也不会整条链路失效。
> 另：`modelOrNull` 在 3.7.0 仍存在（`javap` 复核），但 `registryOrNull(org.bukkit.entity.Entity)` 已移除，只剩 UUID / PlatformEntity / BaseEntity 三个重载。

模型查询 API：

```java
kr.toxicity.model.api.BetterModel                          // 全静态入口（无实例）
  static Optional<ModelRenderer> model(String name);
  static ModelRenderer modelOrNull(String name);           // 不存在返回 null，不抛异常
  static Set<String> modelKeys();
  static Collection<ModelRenderer> models();
  static EntityTrackerRegistry registryOrNull(UUID entityUuid);   // 3.7.0：仅 UUID / PlatformEntity / BaseEntity 重载
  static Optional<EntityTrackerRegistry> registry(PlatformEntity entity);
```
[JAR] `javap -classpath BetterModel.jar kr.toxicity.model.api.BetterModel`

- 3.7.0 **没有** `registryOrNull(org.bukkit.entity.Entity)` 重载；1.15.2 有。跨版本编译时注意。
- 模型名 = `plugins/BetterModel/models/<文件名去扩展名>`；本次用自带样例 `steve.bbmodel` → 模型名 `steve`。
- 持久化行为：Citizens 自己把模型名写进 `plugins/Citizens/saves.yml` 的 `traits.model`（字符串值），重启后 `load()` → `onSpawn()` 自动恢复，**插件不需要额外处理模型恢复**。[实测] 见第 5 节。

### 3.2 Citizens 事件与 NPC API

```java
net.citizensnpcs.api.event.NPCRightClickEvent extends NPCClickEvent
  public Player getClicker();                 // 右键者
  public NPC getNPC();                        // 继承自 NPCEvent
  public boolean isDelayedCancellation(); public void setDelayedCancellation(boolean);
  // 注意：没有 getPlayer()，蓝图里写的 e.getPlayer() 需改为 e.getClicker()

net.citizensnpcs.api.event.NPCEvent extends CitizensEvent
  public NPC getNPC();

net.citizensnpcs.api.event.NPCClickEvent extends NPCEvent implements Cancellable
  public Player getClicker(); public boolean isCancelled(); public void setCancelled(boolean);
```
[JAR]

```java
net.citizensnpcs.api.npc.NPCRegistry extends Iterable<NPC>
  NPC createNPC(EntityType type, String name);
  NPC createNPC(EntityType type, String name, Location loc);
  NPC getById(int id);
  NPC getNPC(org.bukkit.entity.Entity entity);
  boolean isNPC(org.bukkit.entity.Entity entity);
  void deregister(NPC npc);

net.citizensnpcs.api.npc.NPC
  boolean spawn(Location loc);  boolean despawn();  void destroy();
  void setProtected(boolean);
  <T extends Trait> T getOrAddTrait(Class<T>);  <T extends Trait> T getTraitNullable(Class<T>);
  Navigator getNavigator();  Entity getEntity();  boolean isSpawned();
  void setName(String);  String getName();  UUID getMinecraftUniqueId();  int getId();
  void faceLocation(Location);  void teleport(Location, PlayerTeleportEvent.TeleportCause);
  void setAlwaysUseNameHologram(boolean);

net.citizensnpcs.api.ai.Navigator
  void setTarget(Location loc);  void setTarget(Entity entity, boolean aggressive);
  void cancelNavigation();  boolean isNavigating();  boolean canNavigateTo(Location);
  NavigatorParameters getLocalParameters();  NavigatorParameters getDefaultParameters();

net.citizensnpcs.api.ai.NavigatorParameters
  NavigatorParameters baseSpeed(float);   // ← 蓝图里写的 “speedModifier” 不存在，速度用 baseSpeed(float)
  float speed();  float modifiedSpeed(float);
```
[JAR]

Trait 自助注册（Phase 1 注册 `MaidTrait` 用）：

```java
CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(MaidTrait.class).withName("catmaid"));
net.citizensnpcs.api.trait.Trait
  protected Trait(String name);  void run();  void onSpawn();  void onDespawn();  void onAttach();
  void load(DataKey) throws NPCLoadException;  void save(DataKey);  NPC getNPC();
```
[JAR] `Trait`、`TraitInfo`、`TraitFactory`、`CitizensAPI`；实测证据：`/npc create 模型测试 --trait model` 成功创建，`saves.yml` 的 `traitnames` 出现 `model`。[实测]

### 3.3 Paper 26.2 API 存在性核对

| 蓝图用法 | 结论 | 实际签名 | 状态 |
|---|---|---|---|
| `Entity#attack(Entity)` | ⚠ 不在 `Entity`，在 `LivingEntity` | `org.bukkit.entity.LivingEntity#attack(org.bukkit.entity.Entity)` | [JAR] |
| `Animals#setLoveMode(int)` | ❌ **不存在** | `Animals#setLoveModeTicks(int)`、`isLoveMode()`、`getLoveModeTicks()`、`isBreedItem(ItemStack)` | [JAR] |
| `Sheep#setSheared(boolean)` | ✅ 存在 | `boolean isSheared()` / `void setSheared(boolean)`；颜色走 `Colorable#getColor()/setColor()` | [JAR] |
| `ItemStack#serializeAsBytes()` | ✅ 存在 | `byte[] serializeAsBytes()`；静态 `ItemStack#deserializeBytes(byte[])` | [JAR] |
| `EntityDeathEvent` | ✅ | `getDrops(): List<ItemStack>`、`setDroppedExp(int)`、`getEntity()`、`getDamageSource()` | [JAR] |
| `EntityDamageByEntityEvent#getDamager()` | ✅ | `org.bukkit.entity.Entity getDamager()` | [JAR] |

> Phase 6 受影响：繁殖只能用 `setLoveModeTicks(int)`（原版喂食后为 600）。**不需要**走 bluepring 附录 A 的「退化实现」。

### 3.4 其他本阶段顺手确认的事实
- BetterModel 以 **paper-plugin** 形式加载（`paper-plugin.yml`，`dependencies.server.Citizens.load: BEFORE`），因此 Citizens 的 Trait 注册顺序由 Paper 插件加载器保证，CatMaid 只需 `depend` 两者。[JAR]
- Paper 26.2 启动日志无 `Entity#attack` 类告警；`/plugins` 显示 BetterModel 属 "Paper Plugins"，Citizens/CraftEngine 属 "Bukkit Plugins"。[实测]
- `enable-strict-loading: false` 为默认值：模型含不支持特性时只警告不拒绝加载。[JAR] `config.yml`

### 3.5 ⚠ 玩家型 NPC 不会自动播放行走动画（重要，影响 Phase 3）

**现象**（客户端实测）：`demon_knight` 模型的 NPC 沿路径移动时，模型整体**平移**，四肢不动。

**根因**（[JAR] 3.7.0 NMS 实现字节码确认）：BetterModel 判定「是否在走」走的是
`kr.toxicity.model.bukkit.nms.v26_R2.BaseEntityImpl#onWalk()` → `FunctionsKt.isWalking(Entity)`，
其中玩家分支的逻辑是：

```
isWalking(ServerPlayer p) = xMovement(p) != 0 || zMovement(p) != 0
```

即**只看玩家的移动输入（xxa/zza）**。Citizens 的玩家型 NPC 由服务端强制位移，没有输入，`xxa/zza` 恒为 0
→ `onWalk()` 恒 `false` → BM 永不自动播放 `walk` 动画。非玩家生物走的是另一分支（看 `PathNavigation.isInProgress` / goal），所以普通生物 NPC 不受影响。

**可用 API（[JAR]）**

```java
// 手动播放/停止某个动画（动画名必须在 bbmodel 里存在）
kr.toxicity.model.api.tracker.Tracker
  boolean animate(String animation);
  boolean animate(String animation, AnimationModifier modifier);
  boolean stopAnimation(String animation);

kr.toxicity.model.api.tracker.TrackerBuiltInAnimation
  static TrackerAnimation<Tracker>        IDLE;
  static TrackerAnimation<EntityTracker>  WALK, IDLE_FLY, WALK_FLY, SPAWN;

// 拿到某个实体的 tracker
EntityTrackerRegistry reg = BetterModel.registryOrNull(entity.getUniqueId());
if (reg != null) reg.first().animate("walk");     // first() → EntityTracker
```

BetterModel 还给 Citizens 注册了调试命令（需选中 NPC，**玩家可执行**）：

```
/npc animate <id> <animation> [loop_type] [speed] [player]
```
[JAR] `kr.toxicity.model.bukkit.compatibility.citizens.command.AnimateCommand`（usage 字符串已确认）

**对项目的结论**：女仆是 PLAYER 型 NPC，`FollowMode`/`FarmMode` 等移动时**必须自己驱动** `walk` 动画
（按导航状态 `animate("walk")` / `stopAnimation("walk")`），不能指望 BetterModel 自动判定。这条要在 Phase 3 落地。

---

## 4. 资源包方案（Phase 0 定案：**双包分离**）

### 4.1 定案（已实机通过）

女仆模型包与 CraftEngine 包**分成两个包分别下发**，不做合并（用户决定，2026-11-10）：

| 包 | 内容 | 下发方式 |
|---|---|---|
| 女仆模型包 | `plugins/BetterModel/build.zip`（1.07 MB，`assets/bettermodel`） | 原版 `server.properties`：`resource-pack=http://127.0.0.1:8123/build2.zip` + `resource-pack-sha1` + `require-resource-pack=true`（拒绝即踢出） |
| CraftEngine 包 | `plugins/CraftEngine/generated/resource_pack.zip`（250 KB，`assets/minecraft`/`default`/`internal`） | CE 自托管 HTTP + 工作流 `send_pack` |

配置改动：
1. `plugins/CraftEngine/config.yml` → `resource-pack.merge-external-zip-files` 中**移除** `"BetterModel/build.zip"`（该行本是 CE 默认值），改后 CE 包内不含任何 `assets/bettermodel`。
2. BetterModel 保持 `pack-type: zip`，产物固定 `plugins/BetterModel/build.zip`（`build-folder-location: BetterModel/build`）。
3. 用一个静态 HTTP 服务把该目录挂出来（测试用 `python -m http.server 8123`，生产可放任意对象存储/CDN）。

[实测] 客户端进服后 `选项 → 资源包` 中 **「CraftEngine」与「BetterModel」两个包同时存在**，模型正常渲染。

### 4.2 更新资源包的正确姿势（踩过的坑）

- **无 `resource-pack-sha1` 时客户端不会重新下载**：Paper 启动会警告 `You specified a resource pack without providing a sha1 hash. Pack will be updated on the client only if you change the name of the pack.`
- 正确做法：`/bm reload` 重建 `build.zip` 后，**换一个文件名**（本次 `build.zip` → `build2.zip`）并同步更新 `server.properties` 的 `resource-pack` 与 `resource-pack-sha1`，重启服务端。
- CE 侧的包由工作流自动 upload + send，不需要手工换名。
- 部署顺序：**先 `/bm reload`（重建模型包）→ 再 `/ce reload pack`（重建 CE 包）**。

### 4.3 注意：CE 单独下发在本次测试中未生效（未定位到根因）

改用分离方案**之前**（CE 合并 BM 单包 + `require-resource-pack=true`）时，客户端完全看不到模型，且服务端日志里**没有任何资源包下发记录**，`plugins/CraftEngine/data.db` 里也没有玩家包记录 —— 即 CE 的 `send_pack` 在玩家进服时疑似没有真正执行。改用原版 `resource-pack` 强制下发后立刻可见。

本次未继续深挖 CE 的下发链路（分离方案已满足需求）。**若后续要回到"CE 单包合并"路线，必须先解决这个下发问题**，验证方法：进服时看客户端是否弹包、服务端是否有 `正在上传/发送` 记录、`data.db` 是否记录玩家包状态。

### 4.4 模型贴图来源（重要，影响女仆模型选型）

| 模型类型 | 贴图来源 | 实测结果 |
|---|---|---|
| **自带贴图**（`demon_knight.bbmodel`、`blue_wizard.bbmodel`，以及 TLM 素材的女仆模型） | bbmodel 内置贴图 → 打进资源包 | ✅ 正常上色（`demon_knight` 呈红色牛魔王风格） |
| **玩家型模型**（`steve.bbmodel`，骨骼名为 skin/hat 这类） | 取自所挂**实体的皮肤** | ⚠ NPC 未设皮肤时回落 jar 内 `fallback_skin.png`（一张**彩虹色调试皮肤**，各面颜色都不同）→ 表现为"人形但六面配色怪异"，属预期而非故障 |

结论：**女仆模型必须使用自带贴图的 bbmodel**（TLM 素材即如此），或在 Citizens 侧给 NPC 设置皮肤。不要用纯玩家型 bbmodel，否则会看到 `fallback_skin` 的彩虹配色。

### 4.5 其他观察

- BetterModel `pack.use-obfuscation: true`（默认）会把模型/贴图名混淆成 `a/b/c`，排障时可临时设为 `false` 再 `/bm reload`。
- BetterModel `merge-with-external-resources: true`（默认）表示它也会去合并别的插件包；实测 `bm reload` 后 `build.zip` 只含 `assets/bettermodel`，未见异常。
- 本机踩坑（与插件无关）：Paper/插件库从 `maven-central.storage-download.googleapis.com` 下载时并行连接会被限速到 ~14 KB/s，`fastutil` 一度卡住；用 curl 单连接（~3 MB/s）预置到 `testserver/libraries/` 后秒过。

---

## 5. Phase 0 验收清单结果

| 验收项 | 结果 | 依据 |
|---|---|---|
| 模型 NPC 在 Paper 26.2 上正常渲染 | **✅ 通过**（2026-11-10 客户端实机确认） | `demon_knight` 正常上色渲染；`steve` 渲染成功但用 fallback 皮肤（见 4.4）。资源包列表同时含 CraftEngine 与 BetterModel |
| 模型 NPC 有**行走动画** | **✅ 动画可播放**（`/npc animate 2 walk` 客户端确认四肢摆动，原地播放数秒）；**⚠ 自动触发不可用**，原因见 3.5，需 Phase 3 代码驱动 | 客户端实测 + jar 字节码 |
| 强制包生效时 CE 物品与 BM 模型同时可见（无紫黑棋盘格） | **✅ 通过**（2026-11-10 客户端实机确认） | 双包同时在列；BM 模型正常显示；CE 自定义物品可见且贴图正常 |
| api-facts.md 中每条 API 都有"已从 jar 确认"标记 | **✅ 通过** | 本文件第 3 节 |

### 5.1 服务端链路的实测证据（证明"模型确实被应用到了实体上"）

1. 建 NPC：`npc create 模型测试 --at 2,64,0,world --type player --trait model` → `Created 模型测试 (ID 1) with traits model.`
2. 用 `/npc model steve` 从控制台执行**失败**（见第 6 节限制），改为把模型名写入 Citizens 存档：`plugins/Citizens/saves.yml` 该 NPC 的 `traits:` 下加 `model: steve`。
3. 重启服务器 → `[Citizens] 已载入 3 个NPC.`，无异常；`ModelTrait.load()` 读回模型名，`onSpawn()` 应用。
4. **反向验证（可重复）**：运行中把 `saves.yml` 里所有 `model:` 行删掉 → 执行 `citizens save` → 这些行**被原样写回**。`ModelTrait.save()` 只在 `BetterModel.registryOrNull(entity.getUniqueId()).first().name()` 存在时才写，因此这证明：**实体已生成、BetterModel 已为每个 NPC 实体创建 tracker、tracker 名与存档一致**。本次对 `steve`、`demon_knight` 两个模型均验证通过。

### 5.2 测试服现场（客户端验证用）

| NPC | ID | 坐标 | 模型 | 现象 |
|---|---|---|---|---|
| `测试` | 0 | 0, 63, 0 | 无 | 普通玩家皮（对照组） |
| `模型测试` | 1 | 2, 63, 0 | `steve` | 人形 + 彩虹配色（fallback 皮肤，预期） |
| `骑士` | 2 | 4, 61, 0 | `demon_knight` | 红色牛魔王风格，贴图正常 |

客户端：`127.0.0.1:25566`，离线模式，进服强制接受「CatMaid 女仆模型包」，随后接受 CraftEngine 包。

### 5.3 行走动画：**已确认可播放**

```
/npc select 2
/npc animate 2 walk
```

客户端结果：NPC **原地播放了数秒走路动画，四肢正常摆动**（不移动，因为这是纯动画命令）。

结论：bbmodel 的 `walk` 动画被正确打进资源包、客户端能正常解算 —— 动画链路完整，缺的只是触发者。按 3.5，**Phase 3 由代码在导航开始/结束时驱动** `animate("walk")` / `stopAnimation("walk")`。

---

## 6. 工程限制（Phase 1 必须绕开的坑）

1. **无 TTY 时 Paper 控制台 stdin 不可用**：`JLine` 读 stdin 抛 `java.io.IOException: 拒绝访问`，`-Dterminal.jline=false` 也无效。测试驱动一律走 **RCON**（`enable-rcon=true`、`rcon.port=25575`、密码见 `testserver/server.properties`），客户端脚本 `tools/rcon.py`。
2. **Citizens 控制台 sender 无法执行 `@Requirements(NPC)` 的命令**：`/npc model`、`/npc trait` 等即使先 `/npc select <id>` 也报"你必须选择一个NPC"。RCON 每次连接是新的 sender 实例，选择状态不跨连接；即便同一连接内选择也不生效（Requirements 只认 Player）。
   → 插件侧**不要**依赖 `/npc model` 命令，直接 `npc.getOrAddTrait(ModelTrait.class).setRenderer(BetterModel.modelOrNull(id))`（3.1）。
3. `/ce` 主命令对 `CraftRemoteConsoleCommandSender` 受限，但 **`/ce reload`、`/ce reload pack`、`/ce workflow <name>` 可被 RCON 执行**（其余子命令可能要求 Player）。
4. 本机只有 JDK 26，蓝图要求 Java 25 toolchain：Gradle 首次构建需要自行下载 25 工具链（Phase 1 处理）。

---

## 7. 修订记录

| 日期 | 内容 |
|---|---|
| 2026-11-10 | 初版：Paper 26.2 build 135 + Citizens 2.0.44-b4269 + BetterModel 3.7.0-SNAPSHOT-541 + CraftEngine 26.9.2 打通；模型挂载 API 由 `setModel` 修正为 `ModelTrait#setRenderer(ModelRenderer)`；修正 3 个构建坐标；记录 4 条工程限制。 |
| 2026-11-10 | **资源包方案改为双包分离**（用户决定）：CE 不再合并 `BetterModel/build.zip`，女仆包改由原版 `server.properties` 强制下发（静态 HTTP + sha1）。客户端实机确认双包并存、`demon_knight` 正常渲染；新增 4.2 资源包更新姿势、4.3 CE 下发未生效的遗留观察、4.4 模型贴图来源与 `fallback_skin.png` 彩虹配色事实。 |
| 2026-11-10 | **Phase 0 收尾**：新增 3.5「玩家型 NPC 不会自动播放行走动画」——BM 对 `ServerPlayer` 的 `isWalking` 只看 `xMovement/zMovement`（移动输入），Citizens 玩家型 NPC 恒 false；客户端已确认 `/npc animate 2 walk` 可正常播放动画。验收清单四项中三项通过、行走动画一项定性为"需代码驱动（Phase 3）"。CE 自定义物品可见确认通过。 |

---

## 8. 交接说明（Phase 0 → Phase 1）

### 8.1 环境重建清单（全部为下载即用，无编译）

| 组件 | 版本 | 下载地址 |
|---|---|---|
| Paper | 26.2 build 135 | `https://fill-data.papermc.io/v1/objects/ef3dbc68b013deafdbbee3bebf1ba351671ebcda94a8f136809b7532d5ba2fe1/paper-26.2-135.jar`（sha256 同文件名） |
| Citizens | 2.0.44-SNAPSHOT build 4269 | `https://ci.citizensnpcs.co/job/Citizens2/4269/artifact/dist/target/Citizens-2.0.44-b4269.jar`（Jenkins 编号会变，取 lastSuccessfulBuild 的 artifact 路径） |
| BetterModel | 3.7.0-SNAPSHOT-541 | `https://cdn.modrinth.com/data/4h8rX3rt/versions/FHJv7tap/bettermodel-3.7.0-SNAPSHOT-541-paper.jar` |
| CraftEngine | 26.9.2 | `https://cdn.modrinth.com/data/tRX6FMfQ/versions/kdXqPU2z/craft-engine-bukkit-plugin-26.9.2.jar` |
| JDK | 本机 26.0.2.1 | 蓝图要求 Java 25 toolchain，本机没有；Phase 1 首次 Gradle 构建需联网下载 25 |

依赖库（`libraries/`）由服务端自动下载；本机曾因 Google 镜像并行限速卡在 `fastutil`，可预先用单连接 curl 拉好（路径 `libraries/{group}/{artifact}/{version}/`）。

### 8.2 配置文件改动清单（相对插件默认值）

| 文件 | 改动 |
|---|---|
| `testserver/server.properties` | `online-mode=false`、`server-port=25566`、`enable-rcon=true` + `rcon.port=25575` + `rcon.password=catmaid`、`require-resource-pack=true`、`resource-pack=http://127.0.0.1:8123/build2.zip` + `resource-pack-sha1`（女仆包）、`resource-pack-prompt` |
| `testserver/plugins/CraftEngine/config.yml` | `resource-pack.merge-external-zip-files` **移除** `"BetterModel/build.zip"`（默认值里有）；其余保持默认 |
| `testserver/plugins/BetterModel/config.yml` | **未改动**（`pack-type: zip` 默认即可） |
| `testserver/plugins/BetterModel/models/` | 放入 `steve.bbmodel`/`demon_knight.bbmodel`/`blue_wizard.bbmodel`（取自 BetterModel jar 自带样例，**仅测试用，不入仓库**） |
| `testserver/plugins/Citizens/saves.yml` | 3 个测试 NPC：ID0 `测试`（无模型）、ID1 `模型测试`（`model: steve`）、ID2 `骑士`（`model: demon_knight`） |

### 8.3 启停步骤

```bash
# 1) 女仆模型包的静态下发（必须与 MC 同机同时运行）
cd testserver/plugins/BetterModel && python -m http.server 8123 --bind 127.0.0.1

# 2) 服务端（无 TTY 环境用该写法；有终端直接 java -jar 亦可）
cd testserver && exec 3<>cmd.fifo && java -Dterminal.jline=false -Xms1G -Xmx2G -jar paper-26.2-135.jar nogui <&3

# 3) 控制台操作（无 TTY 时唯一可行方式）
python tools/rcon.py "list" "citizens save"

# 4) 干净停机
python tools/rcon.py "stop"
```

客户端：`127.0.0.1:25566`，离线模式。改了资源包必须重启服务端（`server.properties` 不热重载），且**换文件名 / 换 sha1**，否则客户端不会重新下载。

### 8.4 交付边界

- **已交付**：`docs/api-facts.md`（含验收记录）、`testserver/`（可运行环境）、`tools/rcon.py`、`blueprint.md` 4 处同步修正。
- **未交付（有意留白）**：任何 Java 代码、`build.gradle.kts`、`plugin.yml`、SQLite 表结构 —— 全部属 Phase 1。
- `testserver/`、`plugins/BetterModel/build*.zip`、`models/*.bbmodel` 属**部署资产**，不应进代码仓库（建议 `.gitignore`）。
- 未验证项：`plugin.yml` 的 `api-version: '26.2'` 是否被 Paper 26.2 接受（Phase 1 首次启动时确认，报错则退到 `'26'`）。

### 8.5 遗留问题（按优先级，Phase 1 起需持续关注）

1. ~~**CE 自带 `send_pack` 在本次未生效**（4.3）~~——分离方案已绕开；若回到单包合并路线必须先解决。
2. **行走动画自动触发不可用**（3.5）——Phase 3 用 `animate("walk")` / `stopAnimation("walk")` 驱动。
3. ~~**BetterModel 编译坐标与运行时不一致**（第 2 节）~~——Phase 1 采用方案 (b)+反射：不引入编译依赖，`ModelApplier` 单点隔离，见第 9 节。
4. ~~**JDK 25 toolchain 缺失**~~——改用 `--release 25` 编译，无需 toolchain，见第 9 节。

---

## 9. Phase 1 构建与运行期工程事实（2026-11-10 落地）

第 2 节的两条"决策建议"已定案，后续阶段照此执行：

| 项 | 结论 | 依据 |
|---|---|---|
| Gradle | **9.9.0**（wrapper 的 distributionUrl 指向腾讯云镜像） | Gradle 8.14.3 启动即失败、报错只有 `26.0.2.1`——它不认 Java 26（8.x 官方上限是 Java 24）；本机 `services.gradle.org` 还会被 SSL 拒绝 |
| shadow 插件 | **9.6.1** | 蓝图写的 8.3.6 只兼容 Gradle 8.x |
| Java 版本 | `options.release = 25`，不写 toolchain | 本机只有 JDK 26；toolchain 25 需联网自动下载，不稳定。javac 26 用 `--release 25` 可产出 Java 25 字节码，运行期与蓝图一致 |
| Citizens 依赖 | `citizens-main:2.0.44-SNAPSHOT` + `isTransitive = false` | 它的传递依赖 `net.byteflux:libby-bukkit:1.1.5` 在 mavenCentral / papermc / citizens 三个仓库都不存在；编译只需要 API 类 |
| BetterModel 依赖 | **不引入编译依赖**，唯一调用点 `maid/ModelApplier` 走反射 | Maven Central 无 `ModelTrait`；BetterModel 是 paper-plugin，classloader 隔离，直接 import 运行期会 `NoClassDefFoundError` |
| 反射取类方式 | `Bukkit.getPluginManager().getPlugin("BetterModel").getClass().getClassLoader()` 后 `Class.forName` | 跨 paper-plugin classloader 的唯一可靠途径 |
| api-version | `'26.2'` **被接受** | 启动日志无告警，8.4 的遗留项关闭 |
| 依赖声明 | `softdepend: [Citizens, BetterModel, CraftEngine]`，不用 `depend` | `depend` 缺失时 Paper 以英文报错直接拒绝加载，无法满足"自禁用并打印中文原因"的验收项；softdepend 同样保证加载顺序 |

## 10. Phase 1 实测新增的 API 行为事实

### 10.1 Citizens 是**异步**加载 NPC（重要）

启动时间线（实测，6 个 NPC）：`[Citizens] Enabling` 22:16:01 → CatMaid onEnable 22:16:02 →
`[Citizens] 已载入 6 个NPC` **22:16:07**。

即 `CitizensAPI.getNPCRegistry().getById(id)` 在 onEnable、甚至延后 1 tick 都仍可能返回 null。
Phase 1 最初的"查不到就重建"直接造出了重影 NPC。现行做法：

- 每秒轮询一轮，30 轮后仍找不到才允许重建（`MaidManager#restoreNpcs(boolean allowRecreate)`）；
- 按 id 查不到时，遍历注册表用 `MaidTrait#maidId()` 反查（Citizens 存档重排后 id 会对不上）。

### 10.2 Trait 持久化形状

`plugins/Citizens/saves.yml` 中形如：

```yaml
  traits:
    catmaid:
      maid: d36c5b04-4051-4e0a-9294-1995deede084
  traitnames: scoreboardtrait,spawned,catmaid,skintrait,leashedtrait,location,type,inventory,chunktickettrait
```

`TraitInfo.create(MaidTrait.class).withName("catmaid")` 注册成功后即可被 `getOrAddTrait` 使用；
`DataKey` 的键就是 `MaidTrait#save` 里写的 `maid`。模型挂载成功时同一 NPC 的 `traits` 里还会出现 `model: <模型名>`——
**这是判断"模型真的挂上了"最省事的服务端证据**（无需客户端）。

### 10.3 管理员命令与离线玩家

离线模式服没有权威 usercache，`Bukkit.getOfflinePlayerIfCached` 常为 null。Phase 1 的 `/catmaid admin summon`
按"在线 → usercache → （仅当 `getOnlineMode()==false`）按名称建档"解析，在线模式仍拒绝，避免造出永远匹配不上真人的 UUID。

## 11. Phase 1 验收记录（2026-11-10）

环境：Paper 26.2 build 135 + Citizens 2.0.44-b4269 + BetterModel 3.7.0-SNAPSHOT-541 + CraftEngine 26.9.2，控制台驱动走 RCON。

| 验收项 | 结果 | 依据 |
|---|---|---|
| `/catmaid admin summon <玩家> [模型]` 生成带模型的 NPC、头顶名字 | **✅ 服务端通过 / ⏳ 渲染待客户端实测** | RCON 召唤成功；Citizens 存档出现 `model: demon_knight` / `model: steve` 与 `catmaid: maid: <uuid>`；DB 落库完整（name/model/mode/affection/home/npc_id） |
| `/catmaid admin list` 显示女仆与归属 | **✅ 通过** | 输出 `千夜 \| 主人 Hayuuka \| steve \| 好感 10.0 \| 存活`，共 2 只 |
| 重启服务器 → 女仆仍在原位置、名字/模型正确 | **✅ 通过** | 重启后 `已载入 2 只女仆`，无"NPC 已丢失"告警；npc_id 6/7 与 Citizens 存档一致；坐标 16/72/0 未变 |
| `/catmaid admin remove` 彻底删除（DB + NPC + 重启不复活） | **✅ 通过** | 删除后 DB 行消失、Citizens 存档中该 NPC 消失；再重启仍是 1 只 |
| 卸载 Citizens 启动 → 自禁用并打印中文原因 | **✅ 通过** | 移走 Citizens.jar 后控制台输出 `缺少必需依赖 Citizens，猫娘女仆已停止工作。` 随后 `Disabling CatMaid` |
| 上限（`max-per-player` 默认 2） | **✅ 通过** | 第 3 次召唤输出 `该玩家最多只能拥有 2 只女仆。` |

**未覆盖 / 待人工确认**：客户端侧模型外观与名牌显示（需真实客户端进服）；
`PlayerConnectionListener` 的上线补挂/下线保存分支（需玩家真实上下线，本轮无客户端）。

### 11.1 本轮踩到并已修掉的问题

1. `ModelRenderer` 包路径记错（见 3.1 修订）。
2. Citizens 异步加载导致的重影 NPC（见 10.1）。
3. `messages.yml` 重写时漏掉 `depend-missing`，缺依赖时打印裸 key。
4. 日志里 `[CatMaid]` 前缀重复（插件 logger 已自带）。

## 12. 修订记录（2026-11-10 Phase 1）

| 内容 |
|---|
| 构建链定案：Gradle 9.9.0 + shadow 9.6.1 + `--release 25`；Citizens 关闭传递依赖；BetterModel 改反射（第 9 节） |
| 新增 10.1 Citizens 异步加载 NPC、10.2 Trait 持久化形状与"`model:` 即挂载成功"的证据链、10.3 离线玩家解析策略 |
| 更正 3.1 的 `ModelRenderer` 全限定名，记录 `modelOrNull` 在 3.7.0 仍有效 |
| Phase 1 验收：4 项服务端通过、1 项待客户端；关闭 8.4 的 `api-version` 遗留项 |

---

## 13. 生产环境适配清单（2026-11-10 实地勘察）

生产服：`baiyuanljh.fun`（Windows Server 2019 10.0.17763，`Administrator`），根目录 `E:/Minecraft/catnest_new`。
勘察方式：SSH 只读检查（`dir` / `javap` / `findstr`），未改动任何运行中状态。

### 13.1 基线对照

| 项 | 生产服 | 本地测试服 | 判断 |
|---|---|---|---|
| 服务端 | Paper **26.2 build 62** | Paper 26.2 build 135 | ⚠ 见 13.4-1 |
| Java | **26.0.1** | 26.0.2.1 | ✅ 产物是 Java 25 字节码，26 可运行 |
| 端口 / 模式 | 22024 / **online-mode=true** / max 20 / view-distance 10 | 25566 / 离线 | ⚠ 见 13.4-2 |
| BetterModel | **3.6.0-paper** | 3.7.0-SNAPSHOT-541 | ✅ `javap` 逐个核对：`ModelTrait` 路径、`BetterModel.modelOrNull(String)`、`setRenderer(ModelRenderer)` 签名完全一致，`ModelApplier` 的反射层通用 |
| CraftEngine | 26.9.2 | 26.9.2 | ✅ 同版本 |
| Citizens | **原本没有**（全盘 `dir /s E:/Minecraft/*citizens*` 无结果） | 2.0.44-b4269 | 已补：见 13.3 |
| 启动方式 | `start.bat` = 裸 `java -Xms5G -Xmx5G ... -jar Paper-26.2-build62.jar nogui` + `pause` | 同 | ⚠ **无自动重启循环**，杀进程后必须有人手动起 |
| 模型资产 | blue_wizard / demon_knight / wither_messenger(+png) | steve / demon_knight / blue_wizard | ⚠ 无女仆模型，需另放 |

### 13.2 资源包链路（与测试服完全不同）

`server.properties` 的 `resource-pack` **为空**，生产走 CraftEngine 的 workflow：

```yaml
# plugins/CraftEngine/config.yml
resource-pack:
  workflows:
    default:
      trigger: reload_pack
      steps: [generate, validate, zip -> ./generated/resource_pack.zip, send_pack: {pack: default}]
  packs:
    default:
      default: true
      type: external
      url: "https://catnest.oss-cn-shenzhen.aliyuncs.com/catnestv6.1.zip"
  merge-external-zip-files:
    - "BetterModel/build.zip"
```

即：BM 的 `build.zip`（11/02，1.07MB）被合进 CE 的 `generated/resource_pack.zip`（11/06，9.3MB），
再由 workflow **手动上传到阿里云 OSS**，客户端拿的是那个静态 URL。

**女仆模型上线的正确姿势**（对应 P0 的 4.2 教训）：

1. 女仆 bbmodel 放进 `plugins/BetterModel/models/`（必须自带贴图，见 4.4）；
2. `/bm reload` 重建 `plugins/BetterModel/build.zip`；
3. `/ce workflow default`（或 `/ce reload pack`）重建 `generated/resource_pack.zip`；
4. 把新 zip **换名**上传 OSS（如 `catnestv6.2.zip`），改 `config.yml` 里的 `url`；
5. `/ce reload` + 玩家重新进服。

只做 1-3 而不做 4，客户端永远看不到新模型——它读的是 OSS 上的旧包。

### 13.3 部署步骤（Phase 1 产物）

1. **重启前备份**：`plugins\`（至少 `plugins\CatMaid\`、`plugins\Citizens\`、`plugins\BetterModel\`）；
   生产根目录已有 7 个 `hs_err_pid*.log` 崩溃转储，说明历史上有过崩溃，备份别省。
2. 上传 `CatMaid-0.1.0.jar` 到 `plugins\`。
3. **Citizens 已上传**（2026-11-10）：`plugins\Citizens-2.0.44-b4269.jar`，
   4,393,754 字节，MD5 `bfe80f47887584cc5ce31aa25b9ed495`（与本地发布件一致）。**未重启，尚未加载**。
4. 重启服务端（必须在 RDP 控制台里跑 `start.bat`，见 13.1 的"无自动重启"）。
5. 首次启动看三件事：Citizens 下载依赖库（需外网）、CatMaid 打印「已载入 0 只女仆」、
   `plugin.yml` 的 `api-version: '26.2'` 在 build 62 上是否被接受（本项**未验证**）。
6. 女仆数据落在 `plugins\CatMaid\catmaid.db`（SQLite，WAL），备份时连 `-wal`/`-shm` 一起带。

### 13.4 已知风险（按严重度）

1. **Paper build 62 vs 编译用的 build 135**：`paper-api:26.2.build.62-stable` 在 repo.papermc.io **已 404**
   （旧 build 的制品下架），无法针对性编译。缓解：插件只用了长期稳定 API（`ItemStack#serializeAsBytes`、
   `EntityEquipment`、`Particle`/`Sound`、调度器、`OfflinePlayer` 系列），风险可控但**首次部署应选没人的时段试启动**。
   若出现 `NoSuchMethodError`，按报错换等价旧 API 即可。
2. **online-mode=true**：`/catmaid admin summon` 只认真实进过服的玩家名（见 10.3），线上不能随手写名字。
3. **玩家型 NPC 与其他插件的潜在冲突**（均未实测）：
   - **TAB** 接管玩家列表/名牌，可能覆盖 NPC 的头顶名字；
   - **MTSAnticheat** 可能把玩家型 NPC 的强制位移判为可疑；
   - **AuthMe**：未登录玩家的右键交互需过滤（当前 `MaidInteractListener` 尚未实现，Phase 2 需考虑）；
   - **Multiverse-Core**：多世界下 `home` 的世界名要能正确解析（`Locations.toBukkit` 世界未加载时返回 null，已有兜底）。
4. **`start.bat` 无自动重启**：CatMaid 的保存依赖 `onDisable`（`saveAll` + `flush`）+ 每 5 分钟全量保存。
   若服务端被强杀，最多丢 5 分钟改动；这已是当前设计下限，不额外加复杂度。
5. 生产服已有 `Vault`/`LuckPerms`/`MythicMobs`/`NpcPlugin-Paper`，但 CatMaid **不依赖**它们（agent.md 1.4）。

---

## 14. 女仆模型资产（TLM 素材 → BetterModel）

### 14.1 素材来源

车万女仆（Touhou Little Maid）1.5.3 的 jar **自带整套东方角色模型**：
`assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/assets/touhou_little_maid/models/entity/*.json`
（共 292 个，混有床/椅/桌等家具，角色以角色名命名），贴图在同层 `textures/entity/*.png`；
酒狐等 geckolib 风格模型在 `assets/geckolib/models/entity/`。

获取途径：Modrinth 项目 `touhou-little-maid`（GitHub 在本机不可达，api.github.com 与 raw 均 SSL 000）。
`https://cdn.modrinth.com/data/R0bDWFAW/versions/g1SKoGQJ/touhoulittlemaid-1.5.3-forge+mc1.20.1.jar`

**许可**：美术资源 CC BY-NC-SA 4.0（代码 MIT）。仅非商用服可用，须署名 TartaricAcid 与车万女仆项目，
改编物以相同协议共享；因此**转换产物不入本仓库**（本仓库是 MIT），只放 `plugins/BetterModel/models/`。

### 14.2 格式桥接：`tools/tlm_to_bbmodel.py`

TLM 用基岩几何 JSON（bones + cubes + box UV），BetterModel 只吃 `.bbmodel`。转换是纯格式搬运：

| TLM | bbmodel |
|---|---|
| `geometry.model.bones[].pivot/rotation` | outliner 分组的 `origin` / `rotation` |
| `bones[].cubes[]`（origin + size） | `elements[]`（`from` = origin，`to` = origin + size） |
| cube 的 box UV `[u,v]` | 六个面的逐面 UV（Blockbench 标准展开） |
| `textures/entity/*.png` | 以 `data:image/png;base64,...` 内嵌进 `textures[0].source` |

三个踩过的坑（都验证过，别再踩）：

1. **outliner 里的元素引用必须是裸 uuid 字符串**。写成 `{"uuid": "..."}` 会让 BetterModel 抛
   `Cannot invoke "com.google.gson.JsonArray.asList()" because "children" is null`——它的反序列化把
   "字符串"当元素引用、把"对象"当子分组（`ModelOutliner` 字节码确认）。
2. **必须给逐面 UV**。`ModelFace` 的 north/east/south/west/up/down 六个字段都要 `ModelUV`（`[x1,y1,x2,y2]`），
   `box_uv` 只是给 Blockbench 编辑器看的，BetterModel 不替你展开。
3. **贴图用 data URI 内嵌可行**：`ModelTexture.toBlueprint` 里有 `Base64.getDecoder()`。
   另外 BetterModel **不解析 `model_format`**（全 jar 无此字符串），坐标按 bbmodel 原生空间处理；
   TLM 模型是 y 向上、脚在 0，与 bbmodel 一致，**不需要翻转 Y**。

### 14.3 已转换并在 Paper 26.2 build 62 + BetterModel 3.6.0 上验证

`foxmaid`(酒狐) / `hakurei_reimu` / `kirisame_marisa` / `konpaku_youmu` / `izayoi_sakuya` / `flandre_scarlet`

验证方式（无需客户端）：召唤后用 `citizens save` 看 `plugins/Citizens/saves.yml`，出现
`model: <模型名>` 即说明 `BetterModel.modelOrNull()` 返回了渲染器、模型已被解析并注册；
测试服日志无 `Unable to load this model` 告警。

### 14.4 缺口：**没有动画**

TLM 的走路/坐下动画不在基岩几何里——它由 geckolib animation json + 模组代码按骨骼名驱动，
不是 Bedrock animation 格式，**无法直接转成 bbmodel 的 `animations`**。当前转换产物只有静态几何：

- 模型能正常显示、不影响 Phase 1/2；
- **Phase 3 的行走动画必须自己补**：要么在 Blockbench 里给 `leg/arm/head` 骨骼做 walk/sit 动画再导出，
  要么按 api-facts 3.5 用 `tracker.animate("walk")` 驱动（前提是 bbmodel 里存在名为 walk 的动画）。
  Phase 3 开工前先解决这条，否则女仆移动时会是"平移的雕像"。
