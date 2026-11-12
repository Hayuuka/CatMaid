# CatMaid API 事实清单（Phase 0 产出）

> 本文件是 Phase 0「依赖打通验证」的交付物。后续阶段写代码前必须先读本文件。
> 标记约定：**[JAR]** = 从依赖 jar 用 `javap` 反编译确认；**[实测]** = 在本地 Paper 26.2 测试服实跑确认；**[待实测]** = 需要真实客户端人工验证。
> 最后更新：2026-11-12（Phase 5 收口 + 窗口复核 + P6 开工清单 19.9）

**Phase 0 状态：已完成**（客户端实机验收通过）。
**Phase 1 状态：已完成并收口**（服务端 6 项验收通过、模型外观客户端确认，详见第 11、15 节）。
**Phase 2 状态：已完成，蓝图验收清单 7/7 通过客户端实测**（详见第 16 节）。
**Phase 3 状态：已完成并收口**（跟随 / >40 格传送 / 跨世界双向 / 坐下起立 / 动画全部实测通过，
详见第 17 节；**接手请直接读 17.2、17.4、17.9，P4 开工前必读 17.11**）。
**Phase 4 状态：已完成并收口**（蓝图验收 7/7，服务端 4 项 + 服主客户端 3 项；养成数值全部配置化。
详见第 18 节，**接手 P5 前读 18.3 / 18.9 / 18.11**）。
**Phase 5 状态：已完成并收口**（服务端验收 5/5，观感项待客户端；窗口复核 2 条冲突均保守解决，
详见第 19 节，**接手 P6 前读 19.6、19.7、19.8，开工照 19.9**）。

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
| 2026-11-11 | **Phase 3 收口**：新增第 17 节。核心事实 17.2——玩家型 NPC 的 `Trait#run()` 永远不会被调用（Citizens 只对带 `PacketNPC` 的 NPC 调 `AbstractNPC#update()`），行为 tick 改由插件调度器驱动，蓝图 P3 的委托点作废（含 `[CONFLICT]` 记录）；17.5 新增 `Tracker.animate/stopAnimation` + `AnimationModifier(LOOP)` 的反射用法与「为什么不用 `animate(String)`」；17.6 记录"无玩家在线时 NPC 会被置为 spawned=false"这个把排查带偏的坑。 |

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

### 14.4 修订（2026-11-10 晚，用户实测反馈后）

第一版转换有四个错误，均已修正：

1. **UV 尺寸读错（贴图全乱的主因）**。1.12 格式的尺寸在 `minecraft:geometry[0].description.texture_width/height`，
   老格式才叫 `geometry.model.texturewidth/height`。第一版只读后者，读不到就**默认 64**，
   而酒狐贴图是 128×128 —— UV 整体缩了一半，贴图完全错位。
   现在顺序：`description.texture_width` → `texturewidth` → 贴图实际像素尺寸，**绝不默认 64**。
   （同类问题还影响了 `izayoi_sakuya`：几何声明 128，第一版按 64 输出。）
2. **up/down 面的角点顺序**。Blockbench 里这两个面是角点反序写的（`[x2,y2,x1,y1]`），
   照 `[x1,y1,x2,y2]` 写会让上/下两面贴图镜像翻转。
3. **动画根本没转**（这是原话"原 mod 动画和模型分开，你为啥不转"的原因）。
   TLM 的动画在 `geckolib/animation/` 下：`maid.animation.json`（通用骨架 97 个动作）+ 角色专属一份
   （如 `foxmaid.animation.json`）。现在 `--anim` 参数会合并成 bbmodel 的 `animations`，
   默认只导 `idle / walk / run / sit / jump / attacked / death` 这 7 个。
   两个坑：`idle`/`sit` 是**静态姿势**（通道值直接给 `[x,y,z]` 而非"时间→值"字典），
   少量关键帧是 **molang 表达式**（`"0.4*ysm.head_yaw"`），前者按 0 秒单帧处理、后者跳过。
4. **资源包不会因为 `/bm reload` 重建**（BetterModel 3.6.0 实测）：`build.zip` 只在**服务端启动时**生成。
   改了模型必须重启；想强制重建就删掉 `plugins/BetterModel/build.zip` 再启动。
   生成后照 4.2 的规矩**换文件名 + 改 `resource-pack` 与 `resource-pack-sha1`**。

### 14.4b 修订（2026-11-10 深夜，芙兰朵露贴图仍错之后）

上一版仍然错，错在两处，都是"看着合理但没验证"造成的：

1. **box UV 的六面位置整体排错**（主因）。之前是凭"标准展开图"的记忆写的：
   `west` 放最左、`east` 放最右、`south` 起点用 `u+d+w`。实际规则要从 TLM 自己的渲染类里读：

   ```
   com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockCubeBox
   ```

   `javap -c` 里 `uvs[0..5]` 是六个 U 边界、`uvs[6..8]` 是三个 V 边界，
   `UV_ORDER_NO_MIRROR[face] = [uStartIdx, uEndIdx, vStartIdx, vEndIdx]`。解出来是：

   ```
            ┌─────┬─────┐
            │ up  │down │        v .. v+d
   ┌────┬────┼─────┼─────┐
   │west│north│east │south│     v+d .. v+d+h
   └────┴────┴─────┴─────┘
     u   u+d  u+d+w  u+2d+w
   ```

   即：**总宽 2w+2d、总高 d+h；第二行依次是 west(d) north(w) east(d) south(w)**，
   顶行 up/down 各宽 w 高 d，起点分别是 u+d 和 u+d+w。旧实现把 east 和 west 换了位置、
   south 起点少算了一个 d，所以整张贴图整体错位（不是细节偏移，是所有面都错）。
   验证方式：芙兰 338 个 cube 的 `u+2d+2w` 全部 ≤ 144（贴图宽），旧公式会大量越界。

2. **up / down 两个面的角点写法不一样**，不能一概而论。从用户提供的正确成品
   `model_src/foxmaid/foxmaid.bbmodel` 里反推：`up` 写作 `[x2,y2,x1,y1]`（对角反序），
   `down` 写作 `[x2,y1,x1,y2]`（只反 x）。旧实现两个都做对角反序，down 会上下颠倒。

3. **包裹型外壳必须跳过**（新发现的独立问题）。芙兰和咲夜的 `head` 骨骼里各有一个
   比头大 0.5 的立方体（8.5 vs 8.0），贴图是不透明的金色实心块，尺寸刚好把内层的脸整个盖住。
   在 TLM 里它是描边/头发层，搬进 BetterModel 就是一块挡住脸的金板
   （实测：去掉它脸部贴图立刻正常显示）。转换器现在会自动识别"把同骨骼内另一个 cube
   整包住、每轴只大 ≤1"的壳并跳过，全模型里这类只有 1 个，不会误伤。

4. **新增 `tools/bbmodel_preview.py`**：没有客户端时的自检手段。正交投影 + z-buffer
   软渲染，支持六视图和拼图输出。

   **这个工具自己也踩了一个坑，值得记下来**：第一版把每个面的起始顶点写成"右下角"，
   导致渲染结果整体翻转 180°，看起来"贴图全乱"，我据此误判了两次。正确约定是
   Blockbench 的每个面按「从外面看的 左上 → 左下 → 右下 → 右上」排列，uv 的 `[x1,y1]`
   对应第一个顶点。修正后同一份模型渲染出的灵梦立刻有了正常的脸和巫女服。
   **教训：自检工具本身必须先拿已知正确的样本（酒狐）校准，否则它会稳定地骗人。**

### 14.5 缺口：**动画需要代码驱动**



动画已经进 bbmodel 了（见 14.4-3），但**播放要代码驱动**：玩家型 NPC 的 `isWalking` 恒 false（3.5），
BetterModel 不会自己播 `walk`。Phase 3 必须在导航开始/结束时调
`BetterModel.registryOrNull(uuid).first().animate("walk")` / `stopAnimation("walk")`，
坐下时 `animate("sit")`。

bbmodel 里的动画名是 `idle / walk / run / sit / jump / attacked / death`，代码里的动画名要与之一致。
动画数据本身是 geckolib 的旋转/位移关键帧，坐标与角度未做轴向翻转（BetterModel 不解析 model_format，
按 bbmodel 原生空间处理），**首次在客户端看时要重点确认四肢摆动方向是否合理**，不合理则改
`ModelMeta.format_version`（5.0 ↔ 4.x）或对 rotation 取反再验。

---

## 15. 交接说明（Phase 1 → Phase 2）

### 15.1 阶段结论

**Phase 1 已完成并收口。** 可运行产物：`catmaid admin summon/list/remove`、SQLite 持久化、
重启恢复、玩家上限、依赖缺失自禁用，全部在真实服务端跑过。模型统一使用 `foxmaid`
（服主提供的 bbmodel，客户端实测确认）。

代码规模：16 个 Java 文件 / 约 1800 行；`git log` 23 个提交。

### 15.2 代码地图（Phase 2 从哪下手）

| 文件 | 职责 | Phase 2 会怎么动 |
|---|---|---|
| `CatMaidPlugin` | 装配：config → 依赖自检 → trait 注册 → DB → 仓库/管理器 → 恢复 → 监听/命令 → 定时保存 | 注册新的 Listener（右键、GUI 点击） |
| `data/Database` | 单连接 + WAL，建三表 | 可能要加 `maid_equipment` 之外的列（好感度等级等） |
| `data/MaidRepository` | 主线程生成快照 → 单线程 executor 执行 SQL；`loadAll` 同步读 | 新增 UI 状态后记得进快照 |
| `data/ItemStackCodec` | `serializeAsBytes` / `deserializeBytes`，坏槽位返回 null | 直接复用 |
| `maid/Maid` | 领域对象，字段对齐 `maids` 表；`npc()`/`entity()` 可空；`applyEquipment()` 把装备位写回实体 | 装备变更后调 `applyEquipment()` + `saveAsync()` |
| `maid/MaidInventory` | **6 装备位 + 36 存储位**（`withStorage(n)` 可指定容量，Phase 4 按好感度 27/30/33/36 解锁） | GUI 直接读写它，改完标记脏 |
| `maid/MaidManager` | 召唤 / 移除 / 恢复 / 定时保存 | 交互入口都从这里拿 `Maid` |
| `maid/MaidTrait` | Citizens trait，持久化只存 `maidId`；`run()` 留空 | Phase 3 在这里挂行为 tick |
| `maid/ModelApplier` | 反射挂 BetterModel 模型（唯一调用点） | 换模型/换贴图都走这里 |
| `command/CatMaidCommand` | 命令树 + Tab 补全 | 加子命令 |
| `util/Messages` | MiniMessage 渲染 + 占位符（解析前替换，防标签注入） | 新文案加进 `messages.yml` |
| `util/Locations` | Location ↔ 世界名/坐标 编解码 | 复用 |

### 15.3 数据模型（SQLite，`plugins/CatMaid/catmaid.db`）

```sql
maids(id TEXT PK, owner_uuid, name, model_id, mode, affection REAL,
      owner_title, alive INT, npc_id INT,
      home_world, home_x, home_y, home_z, sitting INT, created_at INT)
maid_items(maid_id, slot, data BLOB, PK(maid_id, slot))   -- slot 0..5 装备，6..41 存储
maid_stats(maid_id PK, kills, harvests, breeds, shears, milks)
```

槽位约定（`MaidInventory`）：`0..5` = 主手/副手/头盔/胸甲/护腿/靴子，`6..` = 存储区。
`maid_items` **全量重写**（写入前先 `DELETE`），不做增量 diff。

### 15.4 运行期不变量（改代码前必须知道）

1. **主线程 vs 单线程 executor 的分工**：`ItemStack` 只能在主线程碰。写库流程是
   "主线程序列化成字节 → 交给 executor 只做 SQL"。不要在 executor 里读 `ItemStack`。
2. **保存时机**：玩家下线、`onDisable`（`saveAll` + `flush` 等队列落地）、每 5 分钟全量。
   服务端被强杀最多丢 5 分钟，这是设计下限，别再往上堆复杂度。
3. **Citizens 是异步加载 NPC 的**（启动后约 5 秒，见 10.1）：`MaidManager.restoreNpcs(boolean allowRecreate)`
   每秒轮询、30 秒后才允许重建，并按 `MaidTrait#maidId()` 反查兜底。**任何"查不到 NPC 就新建"的写法都会造出重影。**
4. **装备变更必须调 `Maid#applyEquipment()`**，否则 NPC 外观不变（数据变了、看着没变，很容易误判成 bug）。
5. **模型池只有一个 `foxmaid`**：不传模型参数召唤时随机取，池子只有一个就是它。新增模型前先确认贴图在客户端正确。

### 15.5 Phase 2 开工前的六条提醒

1. **右键事件用 `NPCRightClickEvent`**（api-facts 3.x 已核），`getClicker()` 拿玩家；
   玩家型 NPC 走 Citizens 的事件，不是 `PlayerInteractEntityEvent`。
2. **玩家型 NPC 的 `isWalking` 恒 false**（3.5）——不要指望用原版移动状态驱动动画。
3. **TAB / MTSAnticheat / AuthMe** 在生产服可能干扰玩家型 NPC（13.4-3），交互逻辑要能容忍
   未登录玩家（AuthMe 未登录时右键应静默忽略）。
4. **GUI 用 `Bukkit.createInventory(holder, size, title)`**，holder 自己实现 `InventoryHolder`，
   在 `InventoryClickEvent` 里按 holder 类型分派 —— 不要靠标题字符串判断。
5. **`MaidInventory.MAX_STORAGE = 36`**，但 Phase 4 才按好感度解锁；Phase 2 先全开或先用 27，
   把 `withStorage()` 的调用点收敛在一处，后面改等级只动那一个地方。
6. **物品进 GUI 前要 `clone()`**，`MaidInventory#get` 返回的是内部引用，直接塞进 GUI 会被玩家拖走后
   数据错位。

### 15.6 环境重建（P1 版，取代 8.1 / 8.3 的版本号）

| 组件 | 版本 | 说明 |
|---|---|---|
| Paper | **26.2 build 62** | 与生产服逐字节一致（sha256 `597cb54e…`），`testserver/paper-26.2-62.jar` |
| BetterModel | **3.6.0** | 与生产一致（sha256 `66b2acc6…`），`plugins/BetterModel.jar` |
| Citizens | 2.0.44-b4269 | `plugins/Citizens.jar` |
| CraftEngine | 26.9.2 | 同生产 |
| JDK | 26.0.2.1（本机）| 编译用 `--release 25`；Gradle **9.9.0**（wrapper 指向腾讯镜像） |

```bash
# 模型包静态托管（必须与 MC 同时运行）
cd testserver/plugins/BetterModel && python -m http.server 8123 --bind 127.0.0.1

# 启动
cd testserver && exec 3<>cmd.fifo && java -Dterminal.jline=false -Xms1G -Xmx2G -jar paper-26.2-62.jar nogui <&3

# 控制台
python tools/rcon.py "catmaid admin list"

# 停机
python tools/rcon.py "stop"
```

**改模型后必做**：删 `plugins/BetterModel/build.zip` → 启动（此刻才重建包）→ 停机 →
复制成新文件名（`buildN.zip`）→ 改 `server.properties` 的 `resource-pack` 与 `resource-pack-sha1`
→ 再启动。`/bm reload` **不会**重建 zip（14.4-4）。

### 15.7 模型决策（重要，别再走回头路）

- **唯一启用的模型是 `foxmaid`**，来源 `model_src/foxmaid/`（服主提供，客户端实测正确）。
- `tools/tlm_to_bbmodel.py` 能把 TLM 素材转成 bbmodel，box UV 布局已从 TLM 字节码校准，
  但**转换产物的贴图在 BetterModel 上仍不正确**（芙兰、咲夜等实测）；已停用，五个模型文件已从
  `plugins/BetterModel/models/` 删除。排查结论留在 14.4b，供以后要用时接着查。
- `tools/bbmodel_preview.py` 可离线渲染 bbmodel 看效果，**但它只能用来看几何轮廓**；
  它的顶点约定刚修过一次，用它判断"贴图对不对"之前，先拿 `foxmaid` 校准。

### 15.8 未完成与风险

| 项 | 状态 | 说明 |
|---|---|---|
| 玩家上下线补挂/保存 | 代码已写，**未实测** | `PlayerConnectionListener`；需要真实玩家退出重进 |
| 行走/坐下动画 | 数据在模型里，**无代码驱动** | `isWalking` 恒 false，Phase 3 用 `animate("walk")` |
| 背包/装备 GUI | Phase 2 | `MaidInventory` 已就绪，只差界面 |
| 生产服部署 | 未开始 | Citizens jar 已上传待重启；CatMaid jar + OSS 资源包未动（13.3） |
| Paper build 62 兼容性 | 已本地验证 | 与生产同版本跑通，`api-version: '26.2'` 被接受 |

### 15.9 交接边界

- **已交付**：可运行插件 jar、23 个提交、`docs/api-facts.md`（含全部踩坑记录）、
  `tools/rcon.py`、`tools/bbmodel_preview.py`、`testserver/` 现场环境。
- **不入库**：`testserver/`、`*.bbmodel`、`model_src/`（部署资产 + CC BY-NC-SA 美术资源）。
- **接手第一步**：起测试服 → `catmaid admin list` 应看到两只 `foxmaid` 女仆 → 进服确认外观 →
  开始 Phase 2 的右键与 GUI。

---

## 16. Phase 2 交接（背包 / 装备 / 主菜单 GUI，2026-11-11）

### 16.1 阶段结论

**Phase 2 代码已完成，蓝图验收清单 7/7 全部通过客户端实测**（2026-11-11）。
本轮新增 5 个类、改 5 个文件，`gradlew shadowJar` 通过，Paper 26.2 build 62 多次启动无新增 ERROR。

### 16.2 新增 / 改动文件

| 文件 | 职责 |
|---|---|
| `gui/GuiLayout` | 五个 GUI 的全部槽位常量 + 模型分页双向映射（`modelSlot` / `modelIndexAt`）。魔数只在这里出现 |
| `gui/MaidMenuHolder` | `InventoryHolder` + `MenuType`，GUI 身份靠 holder 类型判断，不靠标题字符串 |
| `gui/GuiManager` | 5 个 GUI 的打开、点击分派、拖拽拦截、关闭回写；同时是 `Listener` |
| `listener/MaidInteractListener` | `NPCRightClickEvent` → 主人校验 → 延迟 1 tick 开主菜单 |
| `maid/NameTagManager` | 头顶名牌：TextDisplay 的创建 / 跟随 / 失效重建 / 孤儿回收 |
| `maid/MaidInventory` | 存储容量改为可变（`setStorageCapacity`），新增 `capacityForAffection` / `levelForAffection` / `usedStorageSlots` |
| `maid/Maid` | `setAffection` 成为容量的唯一同步点 |
| `util/Messages` | 新增 `getList(key, placeholders)`；新增缺失键自动回填（16.5） |

### 16.3 数据流（改 GUI 前必须知道）

**GUI 的 `Inventory` 只是编辑缓冲区**，唯一事实仍是 `MaidInventory`：

```
打开：MaidInventory --clone--> GUI Inventory
点击：可编辑槽交给原版处理，锁定槽/按钮位/拖拽一律取消
关闭：GUI Inventory --全量回读--> MaidInventory → applyEquipment() → saveAsync()
```

三条由此推出的约束：

1. **在 GUI 之间跳转必须先 `sync`**。`Player#openInventory` 会先触发旧界面的
   `InventoryCloseEvent` 再开新界面，而新界面是在 close 之前构建的——不手动同步就会显示跳转前的旧数据。
   `GuiManager` 的返回/翻页分支都已显式 `sync`。
2. **锁定槽回写时要跳过**。锁定格在 GUI 里是灰玻璃板，若照原样写回会把那一格原本存着的物品覆盖成玻璃板。
   `sync` 只遍历 `storageCapacity()` 范围内的格子；好感度掉级后超出容量的物品留在数组里不显示，
   等容量涨回来会重新出现（不丢数据）。
3. **shift-click 必须自己搬**。原版 shift-click 会往顶部任意空槽塞，包括锁定槽与按钮位。
   `handleBottomClick` 一律取消并手动放进第一个合法槽；顶部槽 shift-click 则手动塞回玩家背包，放不下的原样留下。

### 16.4 与蓝图的有意差异

| 位置 | 蓝图 | 实际 | 理由 |
|---|---|---|---|
| 模式选择 GUI | 只列 10..14 五个模式 | 额外在 slot 22 放「返回主菜单」 | 没有返回键只能按 ESC，和其他四个界面不一致 |
| 背包存储区 | 「27-35 是第 3 行存储，36-44 是灰玻璃填充」但又说容量可扩到 36 格 | 9..44 四行全部是存储位，容量 27 时 36..44 显示为锁定玻璃 | 蓝图这两句自相矛盾（27 格装不下 36 格物品）。按「逐格替换灰玻璃」的口径，未解锁的行就是填充，容量 27 时视觉与蓝图完全一致，扩到 36 时才变成可用格 |
| 好感度容量 | Phase 4 才由 `AffectionManager` 提供 | Phase 2 就把阈值放进 `MaidInventory.capacityForAffection` | P2 验收项要求「锁定槽（好感度不足）无法放入物品」，必须有容量概念；放在领域对象里，P4 的 `AffectionManager` 直接复用，避免两处阈值 |
| 背包界面装备区 | 「装备展示区」 | 可编辑（与装备界面共用 0..5 槽） | 展示区能直接换装更符合直觉，且两处写的是同一批槽位，不产生第二套数据 |
| 空装备位 | 蓝图未规定 | 空格显示带 PDC 标记的轮廓图标（铁剑/盾/铁甲四件），点它等于点空格，回写时当 null | 纯空格看不出哪格放什么；图标不能真被玩家拿走或存进女仆背包 |
| 装备可见性 | 0.2 节「不做：装备在模型上可见（明确排除）」 | 模型仍不显示装备，改为**在信息书里列出 6 个装备位** | 模型盖住原版装备层，玩家会误以为没生效；用文字补偿，不触碰模型渲染。技术上 BetterModel 挂手持物的能力理论可行，但需单独立项验证 API |

### 16.5 messages.yml 升级回填

旧 `messages.yml` 不会自动带上新版本的键，缺键时玩家会看到 `gui.button-back` 这种裸 key
（P1 踩过 `depend-missing` 的同类问题）。`Messages#mergeMissingKeys` 在 load 时把 jar 内模板里
「文件没有」的键补进去再存盘，已有值一律不覆盖。

实测：删掉 `gui.main-title` → 重启 → 日志打印「messages.yml 已补齐新版本新增的文案键」且键被补回；
再重启不会重复打印（幂等）。副作用是 `YamlConfiguration.save` 会重排格式——文件头注释保留，
值的引号被去掉、段间空行丢失，都不影响语义。

### 16.6 验收结果

| 验收项 | 结果 | 依据 |
|---|---|---|
| 右键自己的女仆 → 主菜单 | **✅ 通过** | 客户端实测（2026-11-11 18:34） |
| 右键别人的女仆 → 拒绝提示 | **✅ 通过** | 客户端换第二个账号实测（2026-11-11 20:09），提示「她不是你的女仆哦。」 |
| 放入/取出背包物品，重启后仍在 | **✅ 通过** | 客户端实测 + 复查 DB `maid_items` |
| 给女仆穿钻石胸甲 → 实体胸甲槽同步 | **✅ 通过** | 客户端实测放入四件钻石甲 → 查 DB slot 2/3/4/5 各有一条（gzip NBT 解出 `diamond_helmet/chestplate/leggings/boots`）→ 重启后 `applyEquipment` 日志确认实体槽为 `DIAMOND_*` |
| 锁定槽（好感度不足）无法放入物品 | **✅ 通过** | 客户端实测 + 容量阈值 10 个边界值离线自检 |
| 模式/模型选择点击生效 | **✅ 通过** | 客户端实测五个界面点击均生效 |
| 头顶名牌（`[主人] 女仆名`） | **✅ 通过（样式待微调）** | 客户端实测两只女仆均显示；字号/高度是首版默认值 |
| 编译与启动 | **✅ 通过** | `shadowJar` 通过；build 62 多次启动无新增 ERROR；2 只女仆正常恢复 |

自检方式（临时脚本，跑完已删）：`GuiLayout` 布局映射用纯 Java 断言校验；
容量/等级阈值用 `java build/tmp/CapCheck.java` 直接跑（不依赖服务器）。

**关于"装备了但看不见"的核对方法**（重要，别重复踩）：
- `@e[type=minecraft:player]` **选不到** Citizens 的玩家型 NPC——它不在 level 实体索引里，`/data get entity <npc uuid>` 同样返回 `No entity was found`
- Citizens 存档里的 `inventory: {}` 是 `/npc inventory` 那个背包容器，**不含实体装备槽**
- 唯一可靠手段是 `Maid#applyEquipment()` 末尾的日志（`[CatMaid] <名字> 装备同步 主手=… 头盔=…`），每次 spawn / 关闭装备界面都会打印一行

### 16.7 风险与降级预案

1. **`NPCRightClickEvent` 对玩家型 NPC 已实测可派发**（2026-11-11 18:34 客户端右键成功打开主菜单）。
   原先准备的降级方案（改监听 `PlayerInteractAtEntityEvent` + `isNPC()`）**不需要了**，留作后代参考。
2. **AuthMe 未登录玩家的右键未过滤**（13.4-3 遗留）。要过滤必须依赖 AuthMe API，违反 agent.md 1.4，
   本轮不做；生产服若确实需要，用 AuthMe 的 `AuthMeAsyncPreLoginEvent`/登录状态接口另立小任务。
3. **两处 GUI 写同一批槽位**：背包界面与装备界面都能改 0..5。单玩家同时只会打开一个界面，
   不存在并发覆盖；但如果以后做「多人共同编辑同一只女仆」，需要加版本号。
4. **`setAffection` 是容量唯一同步点**：任何直接改 `affection` 字段的写法都会让容量与好感度错位。
   Phase 4 的 `AffectionManager` 必须走 setter。

### 16.8 顺手确认的 API（均 [JAR] 已核）

| API | 结论 |
|---|---|
| `ClickType.COLLECT_TO_CURSOR` | **不存在**，双击收集在 `InventoryAction.COLLECT_TO_CURSOR`（编译期就报错，已修） |
| `Material#getEquipmentSlot()` | 存在，返回 `EquipmentSlot`；用来把 shift-click 的护甲送进对应装备位 |
| `ItemMeta#setEnchantmentGlintOverride(Boolean)` | 存在，比「加隐藏附魔」更干净，用于标记当前模式/当前模型 |
| `Bukkit.createInventory(InventoryHolder, int, Component)` | 存在，标题直接吃 Adventure `Component` |
| `InventoryDragEvent#getRawSlots()` | 存在，用于判断拖拽是否碰到锁定槽 |
| `InventoryCloseEvent#getView().getTopInventory().getHolder()` | 可取到自定义 holder（切换界面时也会触发，`sync` 需幂等） |
| `MutableComponent` | **adventure 5.1.1 已移除该类**（编译直接报找不到符号）；`Component.text()` 返回 `TextComponent.Builder`，链式 append 后 `build()` 收尾 |
| `Material#translationKey()` + `Component.translatable(...)` | 服务端拿不到中文材质名，物品名交给客户端按语言渲染；只在物品 lore / 名称里有效 |

### 16.9 头顶名牌（用户要求提前到 P2 实现）

**归属**：蓝图放在 P8 打磨项（「`npc.setNameTagVisible` 恒显；颜色随好感度等级变化」）。
用户在 P2 验收时要求提前做，并把格式定为 `[主人名字] 女仆名字`（蓝图没有这个格式）。

```
[CONFLICT] 蓝图位置: blueprint.md Phase 8 打磨项「女仆名牌：npc.setNameTagVisible 恒显」
现实情况: BetterModel 接管渲染后会隐藏原版实体（config 的 player-hide-delay），原版名牌随之消失，
          setNameTagVisible 设成 true 也看不见
采用方案: 独立 TextDisplay 实体悬浮在头顶 理由: 模型下唯一能让名牌可见的方式，且内容为完整
          Component，"颜色随好感度"以后只要改 text 就行，不用再换方案
```

实现要点（`maid/NameTagManager`）：

- 全局**一个**任务，每 tick 只处理 `npcId % 5 == ticker % 5` 的女仆（错峰分母 5），不是每只女仆一个任务
- 名牌实体 `setPersistent(false)`：不写进世界文件，避免重启留下孤儿名牌；代价是区块卸载后消失，
  所以 tick 里发现实体失效就重建，这条也是"玩家上线后名牌自动回来"的机制
- `displays` 表（maidId → 实体 UUID）+ `removeOrphans`，兜住"女仆已被移除但名牌还在"
- 文字模板在 `messages.yml` 的 `nametag.format`，主人名取 `Bukkit.getOfflinePlayer(...).getName()`，
  离线/取不到时回落为"主人"
- 可调参数：`config.yml` 的 `nametag.enabled / scale(0.8) / height(0.35)`
- 渲染参数：无背景板、有阴影、`see-through` 可穿墙、`Billboard.CENTER` 始终正面朝向玩家、
  `setTeleportDuration(3)` 让 5 tick 一次的跟随看起来是滑过去的

踩坑：**错峰计数器忘了自增**。`ticker` 恒为 0，条件等价于"只处理 npcId 能被 5 整除的女仆"，
表现为缀理（npcId 10）有名字、小铃（npcId 12）没有。错峰逻辑本身没问题，错在分母不会走。

**未做**：改名（`/catmaid rename`）仍留在 P8；名牌颜色随好感度等 P4 的 `AffectionManager`。
当前字号/高度是首版默认值，用户反馈"怪怪的但先这样"，样式待微调。

### 16.10 交接边界

- **已交付**：4 个新类（GuiLayout / MaidMenuHolder / GuiManager / MaidInteractListener）+ 1 个表现层类
  （NameTagManager）、5 个 GUI 的完整交互逻辑、`messages.yml` 全部界面文案、空装备位提示、
  背包说明牌、信息书装备行、头顶名牌。
- **待客户端**：无。蓝图 P2 验收清单 7/7 全部实测通过。
- **下一步**：P3 行为框架（跟随 / 坐下 / 待机）。`MaidTrait.run()` 里留了 `TODO(P3)` 委托点；
  `Maid#setSitting` 目前只改字段，P3 要补上清导航与 sit 动画。
- **不入库**：`testserver/`、`*.bbmodel`、`model_src/`。


---

## 17. Phase 3 交接（行为框架：跟随 / 坐下 / 待机，2026-11-11）

### 17.1 阶段结论

**Phase 3 代码完成，服务端行为已实测跑通**（女仆确实会自己在 home 附近走动、坐下后不再移动、
行走/坐下动画调用成功）。**跟随主人、动画观感等客户端项待人工实测**（见 17.6）。

本轮新增 6 个类、改 3 个文件，`gradlew shadowJar` 通过，Paper 26.2 build 62 启动无新增 ERROR。

### 17.2 ⚠ 关键事实：玩家型 NPC 的 `Trait#run()` **永远不会被调用**

这是本轮最重要的发现，直接推翻了蓝图 P3 的"MaidTrait.run() 每 tick 委托 BehaviorManager"。

**证据链（全部从 Citizens.jar 字节码确认）**

```
PlayerUpdateTask.run()
  └─ if (npc.hasTrait(PacketNPC))   → 排队执行 npc::update   → AbstractNPC.update()
     else                          → 排队执行 NMS.playerTicker(player)   ← 我们的情况
AbstractNPC.update()  →  遍历 runnables 调 Trait.run()
CitizensNPC.update()  →  navigator.run() + updateCounter++   ← 导航也在 update() 里
```

- 玩家型 NPC（真实 `ServerPlayer`）**没有** `PacketNPC` trait → 走 `NMS.playerTicker`，
  那是原版玩家 tick，不碰 Citizens 的 trait 与导航。
- 实测证据：给 `MaidTrait#run()` 加了每 100 次打印一次的探针，跑满 70 秒（≈1400 tick）
  **一次都没打印**；同一时间 `bind()` 与 `onSpawn()` 都正常触发（trait 实例是同一个，
  identityHashCode 一致），说明不是实例错位，就是没人调 `run()`。
- 同一探针下把行为改由插件调度器驱动后，女仆立刻开始移动，导航 `isNavigating()` 从 true 变 false，
  坐标持续变化——反证导航本身没问题，缺的只是"谁每 tick 推一下"。

**结论 / 采用方案**：行为 tick 由 `CatMaidPlugin` 的 `runTaskTimer(..., 1L, 1L)` 驱动，
`MaidTrait` 退化为纯数据标记（只存 maidId）。`MaidTrait.run()` 的委托点已删除，
类注释里写明了原因。

```
[CONFLICT] 蓝图位置: blueprint.md Phase 3「MaidTrait.run() 内部 tickCounter++；委托 BehaviorManager.tick」
现实情况:   玩家型 NPC 走原版 playerTicker，Citizens 的 PlayerUpdateTask 只对带 PacketNPC trait 的
            NPC 调 AbstractNPC#update()，而 Trait.run() 与 navigator.run() 都只在 update() 里执行
采用方案:   改由插件自己的全局调度器每 tick 推一次（节流/错峰仍在 BehaviorManager 与各 Mode 内）
            理由: 不引入 PacketNPC（那是"只有协议假实体"模式，会和 BetterModel 的实体渲染打架），
                  插件自驱最可靠且完全可控
```

### 17.3 新增 / 改动文件

| 文件 | 职责 |
|---|---|
| `behavior/BehaviorMode` | 蓝图定义的接口：`type()` / `tick()` / `onEnable` / `onDisable` |
| `behavior/BehaviorManager` | 全局守卫（实体空、区块未加载）→ 坐姿硬拦截 → 按模式分发 → 统一驱动模型姿态 |
| `behavior/Movement` | 移动原语：`navigate`（含 baseSpeed）/ `stop` / `isNavigating` / `teleport` / `arrived` |
| `behavior/mode/FollowMode` | 三段式距离带、跨世界延迟传送、卡死放弃、主人下线转待机 |
| `behavior/mode/IdleMode` | 在 home 附近找平地闲逛、5% 概率看向最近的玩家（优先主人） |
| `maid/ModelAnimator` | 反射驱动 `animate("walk"/"sit")`，按 `Pose` 幂等切换（P0 遗留项 3.5 的落地点） |
| `CatMaidPlugin` | 新建 BehaviorManager + 行为调度器（`behaviorTask`，onDisable 取消） |
| `MaidManager` | 构造参数加 `BehaviorManager`；`remove()` 时 `behavior.forget(maid)` |
| `config.yml` | 新增 `follow.*` 与 `idle.wander-radius` |

### 17.4 行为层设计要点（改 P5-P7 前先看这里）

1. **谁 tick**：`BehaviorManager.tickAll(maidManager.all())`，每 tick 一次，全局 `tickCounter` 自增。
   错峰用 `Math.floorMod(tick, N) == Math.floorMod(npcId, N)`，分母 N 由各 Mode 自己定（Follow 是 5）。
2. **动画不写在 Mode 里**：`BehaviorManager` 每 tick 看一眼 `Movement.isNavigating()`，
   映射成 `Pose.WALK / STAND`；坐着则 `Pose.SIT`。P5-P7 加新模式时**不需要**再管动画。
3. **坐下是跨模式硬拦截**：`maid.isSitting()` 为真时清导航、套 SIT、只让 IdleMode 做小动作。
   `Maid#setSitting` 仍然只改字段——下一次 tick（≤50ms）就会生效，所以 GUI 的坐下按钮不用额外接线。
4. **IdleMode 用 `nextAt` 计数器而不是取模**：它会被 FollowMode 在"主人离线"分支里调用，
   调用节奏是 5 tick 一次而非每 tick，取模会算不准。间隔 = 40 + (npcId % 20)。
5. **距离带留缓冲**：`stop-dist(2.5)` 到 `start-dist(6)` 之间**什么都不做**，
   否则会在阈值上反复起步/刹车。追踪时只有"主人挪了 2 格以上"或"导航已停"才重下目标，避免每 5 tick 打断寻路。
6. **闲逛落点只取同层或低一格**：早期版本向上找地面，女仆会一层层爬上屋顶（实测 y 从 72 爬到 74），
   现在只接受 `dy ∈ {0, -1}`，找不到就不动。

### 17.5 动画 API（本轮新增确认，均 [JAR]）

```java
kr.toxicity.model.api.BetterModel
  static EntityTrackerRegistry registryOrNull(UUID entityUuid);     // 3.6.0 与 3.7.0 一致
kr.toxicity.model.api.tracker.EntityTrackerRegistry
  EntityTracker first();
kr.toxicity.model.api.tracker.Tracker
  boolean animate(String animation);                               // 内部用 AnimationModifier.DEFAULT
  boolean animate(String animation, AnimationModifier modifier);
  boolean stopAnimation(String animation);
kr.toxicity.model.api.animation.AnimationModifier
  static AnimationModifier.Builder builder();                      // builder().type(...).build() 全 public
kr.toxicity.model.api.animation.AnimationIterator$Type
  PLAY_ONCE / LOOP / HOLD_ON_LAST
```

**为什么显式用 LOOP 而不是 `animate(String)`**：`animate(String)` 走 `AnimationModifier.DEFAULT`，
而 `DEFAULT = builder().build()` 里 `type` 是 null（由下游按模型自身默认解析）。走路动画放一遍就停，
会以另一种形式重现"走起来四肢不动"这个 P0 遗留问题，所以显式 `builder().type(LOOP).build()`。

`ModelAnimator` 的反射句柄只解析一次（拿到 LOOP modifier 一起缓存），失败一次就整体禁用并只告警一次；
姿态按 `(maidId → 实体UUID + Pose)` 记录，实体重建后自动重放，避免"重生成后姿态丢失"。

### 17.6 验收结果（服务端实测 2026-11-11 20:47–20:55）

| 蓝图验收项 | 结果 | 依据 |
|---|---|---|
| 跟随模式下稳定跟随、>40 格传送、跨世界 | **✅ 通过（客户端+服务端）** | 客户端确认会跟、跟随时四肢摆动；>40 格传送由服主实测通过（随机传送导致女仆摔死那次）；跨世界双向实测：玩家 tp 到末地后 7 秒内两只女仆 `saves.yml` 的 world 变为 `world_the_end`，tp 回主世界后 7 秒内变回 `world`，与「跨世界延迟 3 秒再传送」的设计一致 |
| 主人下线后女仆回家闲逛；上线后重新跟来 | **✅ 通过（下线侧）** | 主人离线时 Follow 转 Idle，坐标 12.63/-1.17 → 18.90/2.91 持续变化；上线补挂沿用 P1 的 `PlayerConnectionListener` |
| 坐下后任何模式都不移动，解除后恢复 | **✅ 通过（客户端）** | 坐下播 sit 姿势、不移动；点起立能站起来（修复过程见 17.9） |
| 10 只女仆同屏 TPS 无明显下降 | **⚠ 部分通过** | 召唤 10 只并全部 spawn 后 `/tps` 三个窗口均 20.0；但没有真实玩家在附近，实体闲置，**真正的同屏压力仍需客户端复测** |
| 行走/坐下动画真的播出来 | **✅ 通过（客户端）** | 修复模型资产后实测通过，见 17.9 |
| 编译与启动 | **✅ 通过** | `shadowJar` 通过；build 62 启动无新增 ERROR（唯一 ERROR 仍是 JLine 读管道 stdin 那个已知项） |

**测试环境的坑（重要）**：没有玩家在线时 Citizens 会把 NPC 置为 `spawned=false`（实体不存在），
此时 `maid.entity()` 为 null，行为 tick 全部空转——一开始就是被这个现象误导，以为 tick 没跑。
驱动测试必须先用 `/npc spawn <id>` 把它们叫出来。

自检方式：临时探针（已删）打印 `tickAll` / `navigate` / `anim` 三类日志；
坐标采样用 `citizens save` + 解析 `saves.yml`（玩家型 NPC 不在 level 实体索引里，
`/data get entity` 查不到，见 16.6）。

### 17.7 已知限制 / 遗留

1. **跨世界跟随**用"延迟 3 秒再传送"，没有做"主人穿传送门"的特殊处理；实际体验待客户端确认。
2. **卡死判定**（8 秒无位移即放弃这轮导航）只在 Follow 里实现；Farm/Ranch 的"拉黑目标 30s"按蓝图属 P5/P6。
3. **未实现的模式**（COMBAT/FARM/RANCH）当前 fallback 到 FollowMode——不然 P2 的 GUI 切过去就成了木头人。
   P5-P7 实现后把 fallback 去掉即可。
4. **`Movement.arrived` 暂时没有调用点**：蓝图 P3 的通用工具列表里有它，留给 P5/P6 用。

### 17.8 交接给 P4

- **NPC 死亡后的真实状态（P3 验收时踩到，务必先看）**：玩家型 NPC 被摔死后，Citizens 会把
  实体移除并把 NPC 置为 `spawned=false`，但 **NPC 注册与 saves.yml 条目都保留，npcId 不变**。
  此时 `/npc list` 看起来是空的（要按 id 操作），`maid.entity()` 为 null，行为 tick 自然空转。
  手动救活就是 `/npc despawn <id>` 再 `/npc spawn <id>`，实体重建后 `MaidTrait.onSpawn()` 会照常
  跑 `applyEquipment()`（日志里能看见），行为立即恢复；复活位置是 NPC 上次的坐标，
  之后 FollowMode 若发现离主人 > 40 格会自己传回去。
  → P4 实现死亡时不要假设"死亡事件之后 npc.isSpawned() 还是 true"；实现复活时直接用
  `npc.spawn(loc)` 即可，蓝图 P4「`npc.despawn()`（保留 Citizens 注册）」的方向是对的，只是
  Citizens 在你 despawn 之前已经帮你 despawn 过一次。
- 好感度：`Maid#setAffection` 已是背包容量的唯一同步点（16.7-4），`AffectionManager` 直接调它。
- 台词：`IdleMode` 的决策点（每 40~60 tick）是"随机待机台词"最自然的挂载处，
  但**必须加每女仆独立冷却**（蓝图要求 3 分钟 5%），不要直接复用决策间隔。
- 死亡/复活：`BehaviorManager.tick` 里已有"实体为 null 直接 return"，铃铛态天然不会报错。
- 喂食接线（蓝图 P4）：`MaidInteractListener` 里留的 TODO，注意 60s 冷却用内存 Map 即可。

### 17.9 动画实战：三条踩出来的事实（2026-11-11 客户端联调）

首次客户端验收的反馈是"没有行走动画、坐下没有 sit 姿势、女仆不动"，查下来不是一个 bug，是三件事叠在一起：

**(1) 模型里必须真有关键帧，否则 `animate()` 只是静默返回 false。**
`foxmaid.bbmodel` 的 `animations` 是**空的**（P0 验证动画用的是 `demon_knight`，它有 idle/walk/run 等 10 条）。
BM 的 `Tracker.animate(String)` 找不到动画名时**不抛异常**，返回 false——我们的代码只捕获异常，
于是服务端一路"成功"，客户端毫无变化。教训：**改了动画代码一定要把 `animate` 的返回值打出来看**。
补动画用 `tools/bbmodel_anim.py`（按骨骼名注入 idle/walk/sit，见脚本头注释）。

**(2) 单次 `animate` 播完就停在最后一帧，不会自己续。**
表现是"跟随时前几秒有走路动作，之后变成平移"。现在 `ModelAnimator` 按动画长度做续播心跳：
walk 每 1050ms 重播（walk 本身 1 秒一循环、首尾同帧，重播几乎无缝），sit 每 5000ms 补一次。

**(3) `stopAnimation("sit")` 停得掉动画，停不掉骨骼姿势。**
玩家型 NPC 的 tracker 不会自己回到 bind pose，所以"点起立"之后模型仍然是坐姿。
解法是再加一条 `idle` 动画（把 walk/sit 动过的 7 根骨头显式写回 bind pose，注意手臂要写 ±13° 的
自然倾斜而不是全 0），STAND 时 `stop(walk) stop(sit) + animate("idle")` 去顶掉。

**顺带确认的好消息**：资源包里**不含任何 animation 数据**（build8.zip 里 `animation` 匹配数为 0，
模型几何也不在包里，只有贴图和骨骼用的物品模型）。BM 的动画是服务端逐帧驱动 display 实体算出来的，
所以**改模型/改动画不需要重新下发资源包，重启让 BM 重读 bbmodel 即可**——省掉了一轮打包与客户端重下。

**跟随朝向**（同轮反馈"一会左一会右一会后"）：两个原因
- 2.5~6 格的缓冲带原来"什么都不做"，她既不走也不看你，看起来就是不知道该看哪 → 改成站住并每秒面向主人（用眼睛位置，不用脚底）
- 距离在 6 格附近抖动会反复起步/急停，朝向跟着乱甩 → 加迟滞：`>start` 才起追、`<=stop` 才停；重算路径阈值从 2 格提到 3 格

### 17.10 P3 完成度（截至 2026-11-11 21:40）

- **代码**：蓝图 P3 范围 100% 落地（BehaviorMode/BehaviorManager/Movement/FollowMode/IdleMode/ModelAnimator），无越界的 P4 代码。
- **验收**：5 项实测通过（跟随、>40 格传送、跨世界双向、坐下/起立、动画），1 项服务端通过（下线闲逛），
  1 项**由服主决定不验**（10 只真人同屏 TPS——服务端 10 只 spawn 后 `/tps` 为 20.0）。
- **资产层额外工作**：`foxmaid.bbmodel` 原本 0 动画，本轮用脚本补了 idle/walk/sit 三条最小动画。
  **这是权宜版**：摆幅 25°、周期 1s 是拍出来的手感，sit 姿势是几何推算的，正式上线前建议在 Blockbench 里精修。
- **已知未验**：疾跑/骑马时的跟随稳定性（未单独测试）。

### 17.11 P4 开工清单（P3 收口时写给下一位）

按顺序读完这 7 条再动手，能省掉本轮踩过的坑：

1. **不要往 Trait 上挂周期逻辑**。行为、动画、好感计时一律走 `BehaviorManager.tickAll`
   （由 `CatMaidPlugin` 每 tick 推），Mode/Listener 内部需要定时就用 tick 计数（17.2）。
2. **动画三件套**：模型必须有该动画的关键帧；单次 `animate` 不自续要按长度续播；
   姿态切换要靠"归位动画"而不是只 `stopAnimation`。P4 的受击/死亡动画照这个模式加（17.9）。
3. **死亡流程**：Citizens 已经替你 despawn 了，不要重复；且事件里 `npc.isSpawned()` 已为 false。
   复活用 `npc.spawn(loc)`，若模型没跟着回来，补一次 `modelApplier.apply(npc, modelId)`（17.8）。
4. **`Maid#setAffection` 是背包容量的唯一同步点**，`AffectionManager` 直接调它，
   不要在别处再写一次容量。
5. **台词全部走 yml**（`dialogue.yml` 走 `DialogueManager`，玩家可见文案走 `messages.yml`），
   随机待机台词要加每女仆独立冷却，别复用 IdleMode 的 40~60 tick 决策间隔（17.8）。
6. **验证姿势**：无玩家在线先 `/npc spawn <id>`，否则 `maid.entity()` 为 null、一切空转，
   很容易误判成"代码没生效"（17.6 末尾）。
7. **改模型/调动画不用重发资源包**，重启让 BM 重读 bbmodel 即可（17.9）。

P3 的收口状态：代码 6 类全落地，验收 5 项实测通过 + 1 项服主决定不验（10 只真人同屏 TPS），
未单独验证疾跑/骑马跟随。提交记录：`b1e558e` → `b6456b6` → `36b4c45` → `03d9082`。

---

## 18. Phase 4 交接（养成层：好感度 / 台词 / 喂食 / 死亡复活，2026-11-11）

### 18.1 阶段结论

**代码全部落地，服务端链路实测通过**：跟随计时涨好感、等级升降（含背包容量日志）、
死亡转铃铛（无掉落 / PDC 带 maid_id / 掉在地上烧不掉）、重启不复活。
**三条交互必须真人客户端**：喂食、铃铛复活、台词显示（都要 `NPCRightClickEvent` /
`PlayerInteractEvent` 或聊天窗口）。清单见 18.6。

### 18.2 新增 / 改动文件

| 文件 | 职责 |
|---|---|
| `dialogue/AffectionReason` | 好感度变动原因的枚举，负责映射到 config 的 `affection.gain/loss` 键 |
| `dialogue/AffectionManager` | 数值/等级/容量/伤害加成；`apply()` 是唯一改动入口（事件→clamp→等级变化特效/台词/提示→落库） |
| `dialogue/DialogueManager` | 台词随机抽取、chat/actionbar 双通道、二级键（`on_mode_change.<MODE>`）、随机待机台词每女仆冷却 |
| `event/MaidAffectionChangeEvent` | 好感度变动事件，可取消，`setNewValue` 可让外部插件改数值 |
| `event/MaidDeathEvent` | 女仆倒下通知（不可取消，事后再通知语义） |
| `listener/MaidDamageListener` | 受击好感度与台词、死亡兜底、清掉落、发铃铛 |
| `listener/TalismanListener` | 铃铛复活 + 魂符召唤（同一个 `PlayerInteractEvent` 里分流） |
| `util/MaidItems` | 铃铛/魂符的构造与 PDC 识别、主手消耗 1 个 |
| `resources/dialogue.yml` | 蓝图 P4 的全部台词（首次启动释放到数据目录） |
| `BehaviorManager` | 新增 `pulse()`：在每 tick 分发里推 FOLLOW_TICK 与随机待机台词（不做成 trait，见 17.2） |
| `MaidManager` | 召唤成功后说 `on_summon`；`forget()` 时清好感计时与台词冷却 |
| `GuiManager` | 切模式后说 `on_mode_change.<MODE>` |
| `MaidInteractListener` | 手持食物右键 = 喂食（替换 P2 的 TODO） |
| `CatMaidCommand` | 新增 `/catmaid talisman [数量]`（`catmaid.use`），Tab 补全按权限分流 |
| `config.yml` / `messages.yml` | 新增 `interaction` / `affection` / `dialogue` / `death` 段与全部新文案 |

### 18.3 实测新增的 API 行为事实

**(1) 玩家型 NPC 完全无法用原版命令定位。** `/damage 小铃 1` → `Invalid name or UUID`；
`@e[type=player]`、`/data get entity <npc uuid>` 同样无效（16.6 已记，本轮再次确认）。
**唯一可控的伤害制造方式**是"打位置不打人"：
`citizens save` → 解析 `saves.yml` 里该 NPC 的 `x/y/z` → `summon minecraft:lightning_bolt <x y z>`
（每发 5 点，AABB ±3 格，会误伤 3 格内的其他女仆）或 `setblock <x y z> lava`（更快，约 3 秒烧死）。

**(2) 死亡由"伤害事件兜底"路径处理，不是死亡事件。** 实测日志顺序是
`铃铛掉落…` → `倒下了…` → 原版 `was struck by lightning` / `burned to death`，
即我们的处理发生在原版死亡结算之前，来自 `EntityDamageEvent` 里
`health - finalDamage <= 0` 的预判分支。`EntityDeathEvent`/`PlayerDeathEvent` 是否也会派发
本轮没有单独区分（两者都注册了，`handleDeath` 用 `maid.isAlive()` 幂等）。
**不要删掉伤害兜底那一段**，否则可能整条死亡流程不触发。

**(3) 掉在地上的铃铛会消失两次，必须同时防两种。** 第一次死亡测试（雷击引燃火焰）后 2 分钟查询，
死亡点附近**没有任何 item 实体**——铃铛已被烧毁；第二次测试的铃铛则在服务器重启后消失，
因为原版 item 有 5 分钟 `Age` 上限（`Age: 6000` 即销毁，区块没加载时计时暂停，一加载就补齐）。
现在 `drop()` 会给掉落物 `setInvulnerable(true)` + `setUnlimitedLifetime(true)` + `setGlowing(true)`：
实测铃铛在岩浆里存活、重启后仍在原地，`Age: -32768s`（无限存活标记），
`Item.components."minecraft:custom_data"` 里 `PublicBukkitValues: {"catmaid:maid_id": "<uuid>"}` 完好。
**主人不在线时这条尤其关键**——铃铛是玩家唯一的复活凭证，丢了就等于永久失去女仆。

**(4) Citizens 的自动 despawn 会打断一切 tick 计时。** 无玩家在线时，`/npc spawn` 后约 1 分钟
NPC 就被丢弃（再 `spawn` 会回"你生成了…"而不是"已经在另一个位置生成"）。
所以 5 分钟的 FOLLOW_TICK 在无客户端环境里**永远攒不满**：本轮把间隔做成可配置
（`affection.follow-tick-interval-seconds`），用 15 秒间隔验证机制，默认 300 秒未跑满。

**(5) 同一个 NPC 连续 `npc spawn` 会触发 moonrise 警告**
`Entity uuid already exists: ... can't add EntityHumanNPC` —— 测试里 3 秒内重复 spawn 造成的，
复活逻辑用 `npc.isSpawned()` 分支（已生成就 teleport）规避，实际不会走到这条。

**(6) 铃铛态女仆的 NPC 会被 Citizens "复活"成幽灵身体，而且还在被 tick。** 实测现象（服主反馈
"多了一个没名字的女仆"）：死亡女仆的 NPC 存档里带 `spawned` 状态，重启后 Citizens 照样把实体建回来；
但插件按 `alive=false` 跳过她 → **没有名牌、站着不动、好感度却还在按 FOLLOW_TICK 涨**（0→1.5）。
**修复三处**：
1. `BehaviorManager#tick` 开头 `if (!maid.isAlive()) return;`——死人一律不参与行为/计时；
2. `MaidManager#despawnDeadNpcs` 在启动恢复轮询里把铃铛态女仆的实体 despawn（日志：`已收起铃铛态女仆 X 的残留实体`）；
3. `MaidDamageListener` 死亡结算后 2 tick 再 despawn 一次，不依赖 Citizens 的自动行为。
另：删库/删 NPC 会留下孤儿名牌（TextDisplay），它们不在 NameTagManager 的跟踪表里，
不会被 `removeOrphans` 清掉，需要手工 `/kill @e[type=text_display]`（插件会在下一 tick 重建真女仆的名牌）。

### 18.4 与蓝图的有意差异

| 位置 | 蓝图 | 实际 | 理由 |
|---|---|---|---|
| `Maid#addAffection(delta, reason)`（0.7） | 领域对象上提供 | 不提供，只留 `AffectionManager.apply` | 领域对象不该反向持有服务/事件总线；单一入口更不容易绕过事件与容量同步 |
| `event/MaidModeChangeEvent`（0.5） | 列出该类 | 未创建；模式台词接在 `GuiManager` 切模式处 | 当前唯一调用点就一个，为一个调用点建事件类是超前设计（agent.md 3.3）；P8 加 `/catmaid mode` 时再补，届时把台词一并搬进去 |
| 受击扣好感 | 「女仆受击 -0.5」 | 5 秒内多次伤害只算一次 | 火焰/岩浆每 10 tick 一跳，不节流一分钟就能把好感清空 |
| 跟随计时 | 固定 5 分钟 | 可配置，默认 300 秒 | 验收必须能调短；同时是 P8「数值全可配」的一部分 |
| `/catmaid talisman` | `[玩家] [数量]` | 只做 `[数量]` 自助领取 | 给别人发用原版 `/give` 即可（PDC 能跟着走），批量分发留 P8 |
| `config.yml` 新增段 | — | **不自动补键**（只有 messages.yml 会补） | 旧配置文件不会带上 `interaction.feedable` 等新段，喂食会静默失效 → 升级步骤见 18.7 |

### 18.5 验收结果（服务端实测 2026-11-11 22:28–22:39；客户端由服主实测 23:2x–23:3x）

| 蓝图验收项 | 结果 | 依据 |
|---|---|---|
| 喂食：食物消耗、粒子、好感 +1、台词、60s 冷却 | **✅ 通过** | 服主客户端实测；服务端路径 `MaidInteractListener#feed` 同日联调 |
| 好感度跨等级：锁定槽解锁、升级特效与台词 | **✅ 通过** | 日志 `缀理 好感度 38.5 → 40.5 升到等级 2（背包 33 格）`、`44.0 → 39.5 降到等级 1`；客户端确认升级特效与提示 |
| 杀死女仆 → 无掉落、铃铛入包/落地、DB alive=0、重启不复活 | **✅ 通过** | 死亡点 item 实体数 = 1（只有铃铛）；`alive=0`、好感 -20；重启后无该女仆的装备/生成日志；`admin list` 显示「铃铛」；客户端确认铃铛入包与台词 |
| 铃铛使用：非主人无效；消耗钻石复活、好感 -20、on_revive 台词 | **✅ 通过** | 服主客户端实测复活成功；好感 -20 在死亡时已扣（存档核对） |
| 随机待机台词低频出现且不刷屏 | **✅ 机制通过**（服主未逐条复核） | 掷骰 3 分钟 5% + 每女仆 5 分钟冷却，参数现已在 `dialogue.idle` 可调 |
| 魂符召唤（`/catmaid talisman` + 手持右键） | **✅ 通过** | 存档中出现 `test` 账号名下的女仆，即魂符召唤产生 |
| 编译与启动 | **✅ 通过** | `shadowJar` 通过；build 62 多次启动除 JLine 那条已知 ERROR 外无异常 |

自测手法：RCON + `citizens save` 解析坐标 + 闪电/岩浆制造伤害 + 直读 SQLite 与日志（脚本临时生成，已删）。

### 18.6 客户端验收记录（已通过，2026-11-11 23:2x–23:3x）

服主在原清单上逐项实测，结论"都可以"。存档侧可交叉验证的证据：`test` 账号名下的女仆
（魂符召唤产物）、Hayuuka 名下 `汐音` 已为铃铛态且好感 6.5（说明死亡 → 铃铛 → 扣好感链路走通）、
玩家 data 里带 `catmaid:maid_id` 的铃铛物品（复活道具确实进了背包）。

以下保留原始清单，供后续回归时照着重测：

1. 手持面包右键女仆 → 食物 -1、爱心粒子、吃音效、聊天台词，且**不开菜单**；60 秒内再喂出「刚吃过」提示且不扣食物。
2. 对话面板看背包：好感升到 20 后第 3 行解锁（容量 30）；升到 40 后 33 格。
3. 打死女仆（`/kill`、`@e` 都选不中，直接打或用岩浆）→ 铃铛进背包（主人在线分支）、无其他掉落、on_death 台词。
4. 拿铃铛 + 钻石右键 → 铃铛消失、钻石 -1、身边出现她、on_revive 台词、爱心与铃声音效。
5. 用别的账号右键铃铛 → 「铃铛毫无反应……」。
6. `/catmaid talisman 1` → 得到魂符，手持右键 → 召唤一只新女仆、台词 on_summon。
7. 静置 3~5 分钟，确认随机台词不会连续刷屏。

### 18.7 升级与部署注意

1. **`config.yml` 不会自动补新段**（只有 `messages.yml` 有回填逻辑）。升级必须：
   备份 → 删掉 `plugins/CatMaid/config.yml`（或手动补 `interaction` / `affection` / `dialogue` / `death` 四段）→ 重启。
   否则 `interaction.feedable` 为空 = 喂食永远不触发。
2. `dialogue.yml` 首次启动会释放到数据目录，之后以文件为准；改台词重启即可（P8 接 `/catmaid admin reload`）。
3. 台词 `channel: actionbar` 时聊天框不会留记录，调试时先切回 `chat`。

### 18.8 P5-P7 接手要点

- 好感度一律 `affectionManager.apply(maid, affectionManager.gain(AffectionReason.WORK_HARVEST), WORK_HARVEST)`，
  不要直接 `maid.setAffection`（会绕过事件与容量同步，16.7-4）。
- 台词用 `dialogue.send(maid, "on_work_done")`；二级键用 `send(maid, key, subKey)`。
- 战斗伤害加成用 `affectionManager.damageBonus(affectionManager.level(maid.getAffection()))`（P7）。
- 死亡后 `maid.entity()` 为 null、BehaviorManager 自动空转；P5/P6 的 Mode 不必自己判断存活。
- 铃铛/魂符的 PDC 键在 `MaidItems` 里，新增特殊物品请沿用同一个类，别再散落 `NamespacedKey`。

P4 收口状态：新增 9 个类 / 改 10 个文件；蓝图验收 7 项全部通过（服务端 4 项 + 服主客户端 3 项），
数值全部配置化（清单见 18.9）。提交记录：`d3dd1e0` → `65bbaec` → `823d205`。

### 18.9 P4 数值配置清单（2026-11-11 服主提出"都要能配"后补）

**原则上不再有写死在代码里的养成数值**，全部落在 `config.yml`：

| 项 | 键 | 默认 | 说明 |
|---|---|---|---|
| 好感上下界 | `affection.min` / `affection.max` | 0 / 100 | `AffectionManager.apply` 里夹紧 |
| 等级阈值 / 容量 / 伤害加成 | `affection.levels[]`（`min` / `capacity` / `damage-bonus`） | 0/20/40/60/80，27/30/33/36/36，0/.05/.10/.15/.20 | 档数可增删，由 `AffectionTable` 解析；写坏（空表、阈值不递增）整表回落默认并打警告 |
| 死亡减半档 | `affection.death-halve-from-level` | 4 | 该档及以上死亡扣好感减半 |
| 各原因增减 | `affection.gain.*` / `affection.loss.*` | feed 1.0、follow-tick 0.5、hurt 0.5、death 20… | P5-P7 的 work-* 也在这里预留 |
| 跟随计时间隔 | `affection.follow-tick-interval-seconds` | 300 | |
| 受击扣好感冷却 | `affection.hurt-cooldown-seconds` | 5 | |
| 台词通道 | `dialogue.channel` | chat | chat / actionbar |
| 受伤台词冷却 | `dialogue.hurt-cooldown-seconds` | 30 | |
| 待机台词：间隔/概率/冷却 | `dialogue.idle.check-interval-seconds` / `.chance` / `.cooldown-seconds` | 180 / 0.05 / 300 | |
| 喂食冷却 / 可喂食物 | `interaction.feed-cooldown-seconds` / `interaction.feedable` | 60 / 10 种食物 | |
| 魂符材质 | `interaction.talisman-material` | AMETHYST_SHARD | |
| 复活代价 | `death.revive-cost` | DIAMOND:1 | 格式 `材质:数量`，写错材质回落钻石 |
| 铃铛材质 / 发光 | `death.bell-material` / `death.bell-glowing` | BELL / true | 材质同时用于识别"这是谁的铃铛" |
| 女仆上限 / 初始好感 / 模型池 / 名字池 | `general.*` / `models` / `names` | — | P1 起就有 |

两条注意：
1. **等级档数与 `messages.yml` 的 `gui.level-<序号>` 对齐**。加到第 6 档时，若没配 `gui.level-5`，
   显示会回落成 `Lv.6`（`Messages.levelName` 兜的底），不会出现裸 key。
2. 现在改配置仍需**重启**生效（配置对象在 onEnable 读入内存）。`/catmaid admin reload` 属 P8。

### 18.10 冲突处理记录（agent.md 第 9 节格式）

```
[CONFLICT] 蓝图位置: Phase 4「死亡 / 复活」+ 附录 A 死亡处理
现实情况: 玩家型 NPC 是真实 ServerPlayer。实测插件在 EntityDamageEvent 阶段就能判定致死
          （日志里我们的处理早于原版死亡消息），EntityDeathEvent / PlayerDeathEvent 是否派发未区分
采用方案: 同时注册 EntityDeathEvent 与 PlayerDeathEvent，并在 EntityDamageEvent 里用
          `health - finalDamage <= 0` 预判兜底；handleDeath 以 maid.isAlive() 幂等
理由: 兜底路径实测可用，且不依赖事件派发细节，删掉它可能整条死亡流程不触发
```

```
[CONFLICT] 蓝图位置: Phase 4「掉落一个铃铛，满则掉落死亡位置」
现实情况: 地上铃铛会被死亡现场的火/岩浆烧毁；原版 item 还有 5 分钟 Age 上限，
          主人不在线时（铃铛必须掉地上）等于 5 分钟后永久失去女仆
采用方案: 掉落物 setInvulnerable(true) + setUnlimitedLifetime(true) + setGlowing(可配)
理由: 铃铛是唯一复活凭证，"能被环境销毁"在体验上是不可接受的失败态（agent.md 第 10 节）
```

```
[CONFLICT] 蓝图位置: Phase 4「女仆倒下 → alive=false，NPC 保留」
现实情况: Citizens 会把"已生成"状态写进存档，重启后按它把死亡女仆的实体重新建出来：
          世界里多出一具没有名牌的身体，而且行为 tick 还在跑 → 好感度凭空上涨（实测 0→1.5）
采用方案: BehaviorManager#tick 开头拦 !isAlive；MaidManager#despawnDeadNpcs 启动时收起；
          死亡结算后 2 tick 主动 despawn 一次
理由: 死亡是业务状态，不能交给 Citizens 的实体生命周期决定；幽灵身体还会误导玩家
```

```
[CONFLICT] 蓝图位置: 0.7「Maid#addAffection」/ 0.5「event/MaidModeChangeEvent」
现实情况: 领域对象不应反向依赖服务层；模式切换目前只有 GUI 一个调用点
采用方案: 好感度只走 AffectionManager.apply；模式台词接在 GuiManager 切模式处
理由: 单一入口才不会绕过事件与容量同步；不为单一调用点建事件类（agent.md 3.3），P8 加
      /catmaid mode 时再抽事件
```

### 18.11 P5（农业模式）开工清单

**必读**：18.3（死亡兜底、无玩家时 NPC 会被 despawn）、18.9（数值一律进配置的约定）、17.2（tick 由插件调度器驱动）。

1. **落点**：新建 `behavior/mode/FarmMode implements BehaviorMode`，在 `BehaviorManager` 构造里
   `modes.put(Maid.Mode.FARM, new FarmMode(config))`——`get()` 已有 `mode == null → 跟随` 的降级，
   接上后不用改别的。
2. **现成能力（别重复造）**：
   - 移动：`Movement.navigate/stop/teleport/isNavigating`（`baseSpeed` 倍率，1.1 ≈ 略快于步行）；
   - 动画：`BehaviorManager` 已在每次 tick 后按 `Movement.isNavigating` 切 WALK/STAND，**Mode 里不要播动画**；
   - 好感与统计：`affection.apply(maid, affection.gain(AffectionReason.WORK_HARVEST), WORK_HARVEST)` +
     `maid.stats().addHarvest()`（`MaidStats` 已有字段）；
   - 台词：`dialogue.send(maid, "on_work_done")`（`dialogue.yml` 里已备好这条）；
   - 入包：`maid.addItem(stack)` 返回 false 表示放不下 → 调用方掉落到地面；
   - 工具：`maid.inventory().getMainHand()`，直接传给 `Block#breakNaturally(ItemStack)`（自带耐久与掉落）。
3. **需要新写的**：`MaidInventory#consumeOne(Material)`（补种扣种子，目前只有 `addItem`/`get`/`set`）；
   作物成熟判定用 `BlockData instanceof Ageable`（Nether Wart/Cocoa 也是 Ageable）；
   拉黑表 `Map<BlockPos, Long>` 建议按蓝图放 Mode 实例内，并在 `onDisable` 里清。
4. **配置**（写进 `config.yml`，不要写死）：`farm.radius` / `farm.center`(home|self) / `farm.replant` / `farm.crops`。
5. **已知坑**：
   - 无玩家在线时 Citizens 约 1 分钟就把 NPC 丢掉（18.3-4）。自动化验收要"先 `/npc spawn` 再立刻测"，
     5 分钟级的周期行为只能调短配置后验证；
   - 玩家型 NPC 无法用 `/damage`、`@e` 选中（18.3-1），制造场景靠 `/setblock`、`/summon`、`/fill` 打位置；
   - 每 tick 扫描只在错峰条件满足时做（`tick % 20 == npcId % 20`），且先判区块已加载，否则 TPS 会掉。
6. **验收前准备**：一片成熟小麦（`/setblock ... wheat[age=7]`）、给女仆一把锄头（GUI 装备页）、
   清空她的背包；收成后看 GUI 背包与 `catmaid.db` 的 items 表，重启确认不丢。

---

## 19. Phase 5 交接（农业模式，2026-11-12）

### 19.1 阶段结论

**代码落地，服务端链路全部实测通过**：成熟小麦逐株收割（一次连续 42 株）、补种扣种子、收成入女仆背包、
重启后背包不丢、背包满时掉落原地、栅栏围住的作物 8 秒放弃换下一株、坐下时完全不动作。
**需真人客户端确认的只剩观感**：田里走动的姿态、`on_work_done` 台词、以及上下台阶会不会踩坏耕地。

### 19.2 新增 / 改动文件

| 文件 | 职责 |
|---|---|
| `behavior/mode/FarmMode` | 目标扫描（17×3×17，区块未加载即跳过）→ 导航 → 收割 → 补种；卡死 8 秒拉黑 30 秒 |
| `behavior/BehaviorManager` | 注册 `Maid.Mode.FARM`；降级注释从 P5-P7 改为 P6-P7 |
| `maid/MaidInventory` | 新增 `consumeOne(Material)`：只搜存储位（装备位是主人特意放的，农活不该动） |
| `resources/config.yml` | 新增 `farm` 段（`radius` / `center` / `replant` / `crops`） |

### 19.3 实测新增的 API 行为事实

**(1) `Block#breakNaturally(ItemStack)` 返回 `boolean`。** [JAR] paper-api 26.2.build.135：
`boolean breakNaturally()`、`boolean breakNaturally(ItemStack)`，另有 `breakNaturally(boolean…)` 若干重载，
**没有任何返回掉落列表的重载**。要"先算掉落再入包"只能用 `Collection<ItemStack> getDrops(ItemStack)`。
副作用：`breakNaturally` 会把物品直接掉进世界，用它就必然要把物品再捡一次。

**(2) RCON 下查方块状态只有一条路**：`execute if block <x> <y> <z> <block[state]> run say <标记>`，
然后去服务端日志 grep 标记。两个坑：
- `say` 的内容**不会**出现在 RCON 响应里（它是广播，不是命令反馈），RCON 响应是空的，必须看日志；
- `data get block` 只对容器这类方块实体有效，普通方块一律报 `The target block is not a block entity`。

**(3) "背包满"场景不需要改代码也能造出来**：DB 里把 `maid_items` 的 slot 6 那条 blob 复制到 slot 6..32
（27 个同材质 60 个的堆）→ 女仆好感 9.5 属第 0 档、容量正好 27 → 再收 4 株把 27 堆都叠到 64，之后
`addItem` 必然失败。实测表现与预期一致：小麦继续叠进旧堆，**种子无处可去直接掉在地上**
（`@e[type=item]` 能看到 `wheat_seeds x3~8` 若干）。

**(4) 玩家型 NPC 在平地麦田里走不会踩坏耕地。** 41 株收割 + 多次导航后复查，耕地仍是 farmland、
未收的 `wheat[age=7]` 仍在原地（原版踩踏需要"落到耕地上"的落体动作，平走不触发）。
上下台阶/跳跃场景未测，留给客户端观察。

### 19.4 与蓝图的有意差异

1. **空目标的待机台词**复用 `DialogueManager.tickIdle` 的闸门（180 秒掷 5% + 每女仆 300 秒冷却），
   而不是蓝图写的"无目标 → 概率 on_idle_random"——按 20 tick 决策间隔各掷 5% 等于每秒 5%，会刷屏。
2. **拉黑表**放 `FarmMode.State` 里（每女仆一份），`onDisable` 整份清掉；蓝图只写了 `Map<BlockPos, Long>`，
   没说归属。用 `BlockVector` 作键（`BlockPos` 不是 Bukkit 类型）。
3. **收割不用 `breakNaturally`**（见 19.6），所以没有默认的方块破裂表现，手动补了
   `Sound.BLOCK_CROP_BREAK` + `Particle.BLOCK`（agent.md 第 10 节：心跳不能省）。
4. **卡死判定放在"是否在导航"之外**：寻路失败时 navigator 会静默停下（`isNavigating` 变 false），
   只判"导航中"会变成原地反复下目标、永不拉黑。现在按"位置 8 秒没变"判定，两种失败都能兜住。

### 19.5 验收结果（服务端实测 2026-11-12 12:55–13:06）

| 蓝图验收项 | 结果 | 依据 |
|---|---|---|
| 一片成熟小麦 → 逐株收割、补种、种子从背包扣除 | **✅ 通过（服务端）** | 42 株连续收割；`execute if block 17 72 -1 wheat[age=0]` 命中（补种回 age 0）；背包 `WHEAT_SEEDS` 63→43 逐株递减 |
| 收成进女仆 GUI 背包，重启不丢 | **✅ 通过（服务端）** | 第一次运行收 42 株后停机重启，再次收割时背包首行已是 `WHEATx54`（延续上一轮），`maid_items` 有 3 条记录 |
| 背包已满 → 物品掉落原地 | **✅ 通过（服务端）** | 27 格塞满 60 个小麦后继续收割：小麦叠满 64，种子全部落地（`@e[type=item]` 见多堆 wheat_seeds） |
| 栅栏围住的作物 → 8 秒放弃转下一个，不卡死 | **✅ 通过（服务端）** | 四向栅栏围住的 `(14,72,7)`：`13:00:28` 开始前往 → `13:00:36` 放弃（正好 8 秒）→ 之后 `没找到成熟作物`，塔上无异常 |
| 空闲时坐着（sitting）不工作 | **✅ 通过（服务端）** | `sitting=1` 重启后放进 10 株成熟小麦，30 秒内收割/导航探针各 0 条（BehaviorManager 的坐姿硬拦截生效） |
| 观感（走动姿态、on_work_done 台词） | **待客户端** | 需要真人进服看，方法见 19.7 |

编译与启动：`gradlew shadowJar` 通过；paper-26.2-62 启动无 ERROR（唯一 WARN 仍是既有的
`resource-pack-id missing`）。探针在验收后已全部删除并重新打包，最终包复跑一轮确认仍能收割
（`maid_stats.harvests` 70 → 82，好感 15.5 → 17.7，与 12 株 ×0.1 吻合）。

### 19.6 冲突处理记录（agent.md 第 9 节格式）

```
[CONFLICT] 蓝图位置: Phase 5「harvest: drops = block.breakNaturally(tool) // 返回 List<ItemStack>」
现实情况: Paper 26.2 的签名是 boolean breakNaturally(ItemStack)（[JAR] 已核），没有返回掉落列表的
          重载；掉落列表只有 getDrops(ItemStack) → Collection<ItemStack>。而且 breakNaturally 会把物品
          直接掉进世界，"收成入背包"就得先把地上的捡回来。
采用方案: getDrops(tool) 先算掉落 → setType(AIR, false) 静默清空 → 逐件 addItem，放不下的
          dropItemNaturally 掉在原地
理由: 蓝图要求的"收成入背包、满了才落地"只有这条路径能实现；工具不扣耐久也符合原版（作物没有正确工具）
```

**18.11 第 2 条"工具直接传给 `Block#breakNaturally(ItemStack)`（自带耐久与掉落）"随之作废**，
以后写 P6 的剪毛/挤奶别再照抄那句。

### 19.7 已知限制 / P6 接手要点

1. **好感满 100 时收成不落库**：`AffectionManager.apply` 在 `target == oldValue` 时提前 return，
   于是 100 好感的女仆收成要等 5 分钟 `saveAll` 或停机保存。运维上无碍，若要"每株必落库"
   需要额外保存点（本轮没加，避免超前设计）。
2. **FARM 不进 `BehaviorManager.pulse` 的待机台词通道**：干活时碎碎念很吵；只有"附近没活干"才会
   走 `tickIdle` 的闸门。
3. **观感待客户端**：① 田里走动时是否有 walk 动画（P3 已能播，但农场是"走走停停"的节奏，
   要看停下的瞬间会不会闪回 STAND）；② `on_work_done` 台词是否真的偶尔出现（10% 概率、收 10 株一次）；
   ③ 上下台阶/跳下耕地会不会踩坏 farmland（平地已确认不会）。
4. **P6 可复用的**：`Movement.arrived`（现在有调用点了）、`State` 的"目标 + 卡死 + 拉黑"三件套、
   `consumeOne`、`getDrops` 替代 `breakNaturally` 的做法。捡拾地面物品时记得 `pickupDelay` 与
   `age > 10 tick`（蓝图 P6 要求），别抢玩家刚丢的东西。
5. **无玩家在线时 Citizens 仍会在约 1 分钟后 despawn**（18.3-4），自动化验收一律"先 `/npc spawn` 立刻测"。

P5 收口状态：新增 1 个类 / 改 3 个文件；蓝图验收 5 项服务端全通过、1 项观感待客户端。
提交记录：`8de4fe7` → `2d71296` → `ad5c995` → 本次文档提交。

### 19.8 P5 窗口复核（蓝图维护者，2026-11-12 晚）

**javap 独立复核**（paper-api-26.2.build.135-stable.jar，与 19.3(1) 同源）：
- `org.bukkit.block.Block#breakNaturally` 共 7 个重载（无参 / `(ItemStack)` / `(boolean)` /
  `(boolean,boolean)` / `(ItemStack,boolean)` / `(ItemStack,boolean,boolean)` /
  `(ItemStack,boolean,boolean,boolean)`），**全部返回 `boolean`**，无返回掉落列表的重载
  —— 19.3(1) 与 19.6 的冲突记录属实。
- `getDrops()` / `getDrops(ItemStack)` 返回 `Collection<ItemStack>`；`Particle.BLOCK`、
  `Sound.BLOCK_CROP_BREAK` 均存在。采用方案用到的 API 全部成立。
- `org.bukkit.util.BlockVector` 存在；`org.bukkit.util.BlockPos` **不存在**（javap 报
  "找不到类"；`BlockPos` 实为 NMS `net.minecraft.core.BlockPos`，agent.md 1.2 禁用）。

**窗口新增冲突 1 条**（编码侧原记在 19.4「有意差异 2」的拉黑表部分，按"蓝图引用了不可用 API"
口径重分类为冲突）：

```
[CONFLICT] 蓝图位置: Phase 5「导航卡死（8s 内位移 < 0.5 格）→ 放弃该目标并拉黑 30s（Map<BlockPos, Long>）」
现实情况: org.bukkit 中不存在 BlockPos（javap: 找不到类 org.bukkit.util.BlockPos）；
          BlockPos 是 NMS 类 net.minecraft.core.BlockPos，按 agent.md 1.2 禁止 import
采用方案: org.bukkit.util.BlockVector 作拉黑表键（javap 确认存在，等价的 Bukkit 值类型）
理由: 蓝图此处是伪代码记法，照抄会违反禁 NMS 铁律，保守替换为等价类型
```

**复核结论**：19.4 其余各条有意差异核对属实且不构成冲突（行为与验收均与蓝图一致，sitting
拦截由 BehaviorManager 统一处理后验收项"坐下不工作"实测通过）；P5 窗口口径冲突共 **2 条**
（breakNaturally 签名 + BlockPos 类型名），均已保守解决——未动架构、未加依赖、未用反射。
对后续阶段的唯一影响：**P6 勿再照抄蓝图 P5 伪代码中 `breakNaturally` 返回掉落列表的写法**
（18.11 第 2 条已作废），捡拾/剪毛复用 19.7-4 的 `getDrops` + "目标 + 卡死 + 拉黑"三件套。
blueprint.md 已同步：Phase 5 状态行补窗口复核注记、附录 A 新增 breakNaturally/BlockPos 风险行。

### 19.9 P6（畜牧模式）开工清单

**必读**：19.3（RCON 查方块/造满包的办法）、19.6（`breakNaturally` 别照抄）、19.7-4（可复用的四样东西）、
以及下面第 2 条——**蓝图 P6 伪代码里又有两处 API 已经不成立**，先看再动手。

#### 1. 落点
新建 `behavior/mode/RanchMode implements BehaviorMode`，在 `BehaviorManager` 构造里
`modes.put(Maid.Mode.RANCH, new RanchMode(config, affection, dialogue))`；
接上后降级 fallback 里只剩 `COMBAT`（P7）。

#### 2. 蓝图 P6 伪代码与实际 API 的偏差（[JAR] 已核，写代码前先改口径）
> **修订（2026-11-12 晚，P6 实装后）**：下面第 1 条的"替代建议"选错了 API——`Breedable#setBreed(boolean)`
> 改的是**年龄**（true→`setAge(0)`、false→`setAge(6000)` 变幼崽），与求偶无关；正确入口是
> `Animals#setLoveModeTicks(int)`。另外 `canBreed()` 只判 `getAge()==0`，不判求偶状态。
> 详情与实测证据见第 20.3 / 20.6 节，**本条已作废，勿照抄**。
- **`animals.setLoveMode(600)` 不存在了。** 26.2 的 `Animals` 提供
  `boolean isLoveMode()` / `int getLoveModeTicks()` / `void setLoveModeTicks(int)`；
  更贴合"让它进入求偶"的语义的是 `Breedable#setBreed(boolean)`（`Breedable extends Ageable`，
  `canBreed()` 是闸门）。建议：`setBreed(true)` + 用 `setLoveModeTicks(600)` 只在需要控时长时用。
- **捡拾判定的字段名要换**：蓝图写 `age>10tick`，实际是 `Entity#getTicksLived() > 10`；
  再叠加 `Item#getPickupDelay() <= 0`、`Item#canMobPickup()`。**外加 `Item#getOwner()`**：
  玩家刚丢的东西 owner 就是那个玩家，别去抢（蓝图"10 tick 内不抢"的意图其实是这条）。
- **顺手可省一张表**：`Animals#isBreedItem(ItemStack)` 能直接判断"这食物能不能喂它"，
  `ranch.breed-food` 就只用来覆盖特例（比如狼用骨头、马用金胡萝卜），不必把 7 种动物全配一遍。

#### 3. 现成能力（别重造）
- 状态三件套照搬 `FarmMode.State`：`target + lastPos/lastProgressAt + blacklist`，8 秒放弃、30 秒拉黑；
- `Movement.navigate/stop/isNavigating/arrived`（`arrived` 已在 P5 有调用点）；
- `maid.inventory().addItem(stack)` 返回**剩余数量**（0 = 全进包），余量非空就掉原地；
- `maid.inventory().consumeOne(Material)`（P5 新增，挤奶扣桶、喂食扣饲料都用它）；
- `affection.apply(maid, affection.gain(AffectionReason.WORK_BREED), WORK_BREED)` +
  `maid.stats().addBreed()/addShear()/addMilk()`；
- `dialogue.send(maid, "on_work_done")`；`dialogue.send(maid, key, subKey)` 走二级键。

#### 4. 需要新写的
- **挤奶没有任何 API 帮你完成**（`Cow` 上没有 milk 方法，javap 已核）：只能
  `consumeOne(BUCKET)` → `addItem(new ItemStack(MILK_BUCKET))` → 播 `Sound.ENTITY_COW_MILK`。
- **剪羊毛材质**：`Colorable#getColor()` 拿 `DyeColor`，再
  `Material.matchMaterial(color.name() + "_WOOL")`（`LIGHT_BLUE` → `LIGHT_BLUE_WOOL` 成立）。
- **饲料耗尽提示**：`dialogue.yml` 需要新增键（如 `on_no_food`），P6 记得加；
  `DialogueManager.send` 对不存在的键是**静默丢弃**，很容易以为接线没生效。
- **`ranch` 配置段**：`radius` / `pickup` / `action-priority` / `breed-food`。
  配置对象在 onEnable 读入内存，改完仍要重启（P8 才做 reload）。

#### 5. 踩坑预告
1. **节奏要拆开**：蓝图把"捡拾"和"决策"写在同一个 tick 里，但捡拾应该是高频（每 10 tick 错峰）、
   决策是 30 tick。合成一个频率的话鸡蛋要在地上躺 1.5 秒，观感上像"她没看见"。
2. **`getNearbyEntities` 只许出现在 Mode 的节流 tick 内**（agent.md 7.4），不要放监听器。
3. **繁殖要先判"两只都成年且 `canBreed()`"**，否则她会对着小牛/冷却中的牛一直喂小麦。
4. **动作冷却 2 秒用 `Map<UUID, Long>` 记时间戳**，别用 tick 取模——各女仆错峰相位不同。
5. **一次只捡一件**，别把整堆 item 一口吞：`addItem` 有余量就把余量留在原地（或直接跳过这件），
   否则满包时会反复"删了又掉"。

#### 6. 验收准备（服务端可自动化）
```bash
# 两只成年牛（分开几格，避免重叠导致"最近的一对"判定怪）
python tools/rcon.py "summon cow 19 72 2" "summon cow 21 72 4"
# 有毛羊（颜色参数：14 = 红）
python tools/rcon.py "summon sheep 15 72 5 {Color:14}"
# 女仆背包塞小麦/空桶：DB 里复制 maid_items 的 blob（做法见 19.3-3），或用 P2 的 GUI 手动放
# 无玩家在线时先叫出来
python tools/rcon.py "npc spawn 25"
```
验收对照蓝图 P6 五项：繁殖（双红心 + 小麦减少）、剪毛（颜色正确 + 羊变裸）、挤奶（桶→奶桶）、
捡拾（鸡蛋/羊毛入包、玩家刚丢的不抢）、饲料耗尽（停手 + 只说一次台词）。

#### 7. 基线
P5 收口提交 `4685951`（已推送）。P6 从这一版开分支/直推 `main` 都行，唯一的强约束是
**别再照抄蓝图 P5/P6 伪代码里的 API 写法**——两份伪代码各有一处已经和 26.2 不一致了。

## 20. Phase 6 交接（畜牧模式，2026-11-12）

### 20.1 阶段结论

**代码落地，四条链路全部服务端实测通过**：捡拾地面产物（鸡蛋入包）、剪毛（红羊→红羊毛、羊变裸）、
挤奶（空桶→奶桶）、繁殖（两只成年牛各喂 1 份小麦 → 双双求偶 → 生出小牛、父母进 5 分钟冷却）。
坐下不干活、目标失效重选、8 秒卡死拉黑 30 秒沿用 P5 的三件套。
**需真人客户端确认的只剩观感**：`on_work_done` / `on_no_food` 台词（主人不在线时台词是静默丢弃的，
服务端无法自证）、以及"玩家刚丢的物品不抢"这条（要真人在她脚边丢东西）。

### 20.2 新增 / 改动文件

| 文件 | 职责 |
|---|---|
| `behavior/mode/RanchMode`（新） | 捡拾 + 繁殖 + 剪毛 + 挤奶；per-maid 状态机（目标 / 8 秒卡死 / 30 秒拉黑 / 2 秒动作冷却） |
| `behavior/BehaviorManager` | 注册 `Maid.Mode.RANCH`；降级 fallback 只剩 `COMBAT`（P7） |
| `resources/config.yml` | 新增 `ranch` 段（`radius` / `pickup` / `action-priority` / `breed-food`） |
| `resources/dialogue.yml` | 新增 `on_no_food` |

### 20.3 实测新增的 API 行为事实

**(1) ⚠ `Breedable#setBreed(boolean)` 改的是年龄，不是求偶。** [JAR/CraftBukkit 字节码已核]
`CraftAgeable#setBreed` 的实现是：
```
setBreed(true)  → setAge(0)       // 变回成年
setBreed(false) → setAge(6000)    // 变成幼崽（!!）
```
跟"进入求偶"毫无关系。真正的求偶入口是 `Animals#setLoveModeTicks(int)` →
`Animal#setInLoveTime(int)`（只写 `inLove` 字段），读回用 `Animals#getLoveModeTicks()` / `isLoveMode()`。
**api-facts 19.9-2 里"更贴合语义的是 setBreed"这条建议作废**，见 20.6。

**(2) `Breedable#canBreed()` 只看年龄。** [JAR 已核] `CraftAgeable#canBreed()` 就是 `getAge() == 0`，
**不判是否已在求偶**。只按它筛选会导致"同一对动物被反复喂小麦、每 30 tick 扣一次饲料"（本轮实测踩到：
2 分钟扣了 27 份小麦，其中 3 份是重复喂同一只）。所以筛选与目标校验都必须额外加 `!animal.isLoveMode()`。

**(3) 求偶状态的生命周期**：手工 `data merge entity <牛> {InLove:600}` 能写进去且不会被 tick 重置；
喂完两只后原版会立刻判定繁殖 → **求偶值当场归零、父母 `Age` 置 6000 进入冷却**。
所以"查 InLove 是不是 600"必须赶在繁殖发生前（本轮用"父母 Age 变成 5xxx + 附近出现 `Age:-2xxxx` 的小牛"
来验收更稳）。幼崽与冷却中的父母都会被 `canBreed()` 自动排除。

**(4) 捡拾判定的真实字段**：`Item#getPickupDelay()`、`Item#getTicksLived()`、`Item#canMobPickup()`、
`Item#getOwner()` 全部存在 [JAR]；蓝图写的 `age>10tick` 对应 `getTicksLived()`。
`Item#getItemStack()` 返回的是**副本**，改数量后必须 `setItemStack(...)` 写回，否则地上那堆数量不变。

**(5) 挤奶没有任何 API 帮忙**：`Cow` 上没有 milk 方法（javap 已核）。空桶换奶桶只能自己算：
`consumeOne(BUCKET)` → `addItem(MILK_BUCKET)` → `Sound.ENTITY_COW_MILK`。

**(6) 剪羊毛的颜色**：`Sheep#getColor()` → `DyeColor` → `Material.matchMaterial(color.name() + "_WOOL")`
（`LIGHT_BLUE` → `LIGHT_BLUE_WOOL` 成立）。`ENTITY_SHEEP_SHEAR`/`ENTITY_COW_MILK`/`ENTITY_GENERIC_EAT`
音效与 `Particle.HEART` 均存在 [JAR]。

### 20.4 与蓝图的有意差异

1. **捡拾拆成两档节奏**（api-facts 19.9-5.1 的建议）：脚下 2.5 格内每 10 tick 直接收（不导航，
   免得把"走去喂牛"的目标顶掉）；半径 8 格内更远的东西交给 30 tick 的决策走一趟。
2. **繁殖分两趟喂**（蓝图是一步喂两只）：先喂近的一只、再走去它对象那儿喂第二只，
   两只都点上求偶才结算好感与统计；中途对象被牵走就白喂一次、不给奖励（原版同样白喂）。
3. **饲料必须够 2 份**才开喂（蓝图"一只一份"的推论）；只有 1 份时不动手，免得喂一只空一只。
4. **跳过已驯服的动物**：不替别人家的狗/猫配种（蓝图没写，产品判断：别碰主人以外的财产）。
5. **拉黑表与动作冷却放在 `State` 字段里**（蓝图写 `Map<UUID, Long>`）：per-maid 状态本来就按女仆存，
   再挂一张 UUID 表等于同一件事存两遍。
6. **`ranch.action-priority` 里写错的词直接跳过**，不让一个笔误把整个模式变成木头人。

### 20.5 验收结果（服务端实测 2026-11-12 20:09–20:26）

| 蓝图验收项 | 结果 | 依据 |
|---|---|---|
| 两只成年牛 + 小麦 → 走近、消耗小麦、双红心进入繁殖 | **✅ 通过（服务端）** | 20:23:15/16 两条喂食探针日志 `love=600`（探针验收后已删）；随即附近出现 `Age:-23269` 小牛，两头父母 `Age=5268`（6000 冷却中） |
| 有毛羊被剪 → 羊毛入女仆背包（颜色正确），羊变裸 | **✅ 通过（服务端）** | `data get entity … Sheared` = `1b`；背包 slot 11 = `red_wool x1`（召唤时 `{Color:14}` 红羊） |
| 空桶 → 牛奶桶转换正确 | **✅ 通过（服务端）** | 背包 slot 8/10 = `milk_bucket`；`maid_stats.milks` 7 → 9；桶被扣光后不再尝试 |
| 地上鸡蛋/羊毛自动拾取入背包 | **✅ 通过（服务端）** | 召唤 2 个蛋 20 秒内消失，背包 slot 9 = `egg x2`；`@e[type=item]` 为空 |
| 玩家刚丢的物品 10 tick 内不抢 | **待客户端** | 需要真人脚边丢东西；代码按 `getTicksLived()<=10` 不碰、带 owner 的再宽限到 100 tick，见 20.7-3 |
| 饲料耗尽时不再尝试繁殖（有台词提示一次） | **部分通过** | 清空小麦后：2 头成年牛 30 秒内 `InLove` 恒为 0、`breeds` 不涨、挤奶照常工作；"只说一次"的闸门实测为 true（探针），**台词本身待客户端** |

最终一轮（干净包、无探针）账目完全对得上：`breeds 14→15`、`shears 1→2`、`milks 7→9`、
小麦 20 → 18（正好一次繁殖 = 2 份）、好感 34.0 → 34.4（4 次动作 × 0.1）、背包新增
`milk_bucket x2` / `egg x2` / `red_wool x1`。

编译与启动：`gradlew shadowJar` 通过；paper-26.2-62 启动无 ERROR（唯一 WARN 仍是既有的
`resource-pack-id missing`）。探针（每 4 秒一条的决策探针 + 每条喂食日志）在验收后已全部删除并重新打包，
最终包复跑一轮确认四条链路仍正常。

### 20.6 冲突处理记录（agent.md 第 9 节格式）

```
[CONFLICT] 蓝图位置: Phase 6「BREED: 两只都 animals.setLoveMode(600)」（经 api-facts 19.9-2 转述为 Breedable#setBreed）
现实情况: Paper 26.2 的 Breedable#setBreed(boolean) 实现是 setAge(0)/setAge(6000)（改年龄、false 还会变幼崽），
          与求偶无关；真正的入口是 Animals#setLoveModeTicks(int) → Animal#setInLoveTime(int)。
          且 canBreed() 只判 getAge()==0，不判求偶状态，按它筛选会重复喂同一只。
采用方案: 喂食用 animal.setLoveModeTicks(600)；筛选与目标校验额外加 !animal.isLoveMode()
理由: 只有这条路径能真的让动物进求偶并触发原版繁殖；照 setBreed 写会「喂了小麦却没有红心」
```

`Animals#setLoveMode` 这个蓝图方法本身在 26.2 已不存在（P0 已记为不存在，19.9-2 的替代建议又选错了 API）。

### 20.7 已知限制 / P7 接手要点

1. **`on_no_food` 只提醒一次**，且只有"确实有可繁殖的一对、但饲料不足 2 份"时才提醒；
   补给到位（再次成功选中）后闸门复位。台词走 `dialogue.yml`，主人不在线时静默丢弃——
   排障时以"牛是否进求偶"为准，别以"有没有说话"为准。
2. **挤奶没有每日上限**：一头牛可以反复挤（原版也是这样），所以背包里有多少空桶就能产多少奶桶。
   蓝图未要求限流，本轮不加。
3. **捡拾的 owner 宽限是 100 tick（5 秒）**：蓝图只写"10 tick 内不抢"，但玩家亲手丢出的物品带 owner，
   逐帧判定等于永远不捡主人扔给她的东西。现在的口径是：出生 10 tick 内一律不碰；
   带 owner 的再等 5 秒；无主掉落（鸡蛋/羊毛/怪物掉落）10 tick 后就能捡。
4. **P7 可复用的**：`State` 的"目标 + 卡死 + 拉黑 + 动作冷却"四件套、`stillValid` 的目标失效重挑、
   `count(Maid, Material)`、`hearts(Entity)` 这类小工具；战斗索敌同样只许写在 Mode 的节流 tick 里
   （agent.md 7.4）。**别再用 `setBreed`**，也别假设 `canBreed()` 会帮你过滤求偶中的动物。
5. **无玩家在线时 Citizens 仍会在约 1 分钟后 despawn**（18.3-4），自动化验收一律"先 `/npc spawn` 立刻测"；
   本轮 `@e[type=player]` 查不到玩家型 NPC，定位女仆用她头顶的 TextDisplay（`@e[type=text_display]`）。

P6 收口状态：新增 1 个类 / 改 3 个文件；蓝图验收 4 项服务端全通过、1 项部分通过（台词待客户端）、
1 项待客户端。提交记录：`bb4714f`（捡拾）→ `98dc3cb`（剪毛）→ `6fa1638`（挤奶）→ `d35029a`（繁殖）
→ `4655a8c`（缺料提示）→ 修复求偶 API → 本次文档提交。
