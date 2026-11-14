# CatMaid API 事实清单（Phase 0 产出）

> 本文件是 Phase 0「依赖打通验证」的交付物。后续阶段写代码前必须先读本文件。
> 标记约定：**[JAR]** = 从依赖 jar 用 `javap` 反编译确认；**[实测]** = 在本地 Paper 26.2 测试服实跑确认；**[待实测]** = 需要真实客户端人工验证。
> 最后更新：2026-11-13（P10：第 26 节含 sqlite relocate 冲突、命令修复验收与部署注意；第 4.0 节资源包走原版裁决）

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

## 4. 资源包方案（Phase 0 定案：双包分离；P10-10.3 修订：只走原版单包；4.0b 二次修订：分发跟 provider 对齐）

### 4.0 P10 修订：资源包分发走原版，CE 移除（2026-11-13，服主裁决）

P0 的双包分离解决了"合并渲染"问题，但留下了"进服两次下载"（原版槽位发 BM 包 + CE 再发自己的包）。
P10-10.3 按蓝图给出两条路线，**服主裁决走原版（provider=vanilla）**：

- BM 包继续走原版 `server.properties` 槽位强制下发（`require-resource-pack=true`），这是唯一下发源；
- CE 若仅为 CatMaid 而装则整体移除——测试服 CE 无任何 catmaid 物品定义（见 24.3-6），
  已于 2026-11-13 把 `CraftEngine.jar` 与 `CraftEngine/` 从 `testserver/plugins/` 移到
  `testserver/plugins-disabled/`（可逆备份），进服只弹一次下载；
- 若生产服另有 CE 内容：CE 仍会发自己的包，两次下载依旧——取舍写在这里，不强行合并；
- CE 合并路线（蓝图 10.3 的另一分支）**不再采用**：4.3 记录过 CE `send_pack` 在实测中未生效、
  方案 A（merge-external-zip-files）也无通过记录，没有理由为一个已裁掉的功能保留双包复杂度；
- 配置侧 `dependency.provider` 保持 `vanilla`（默认值），插件零改动——本节属部署事项（agent.md 1.5）。

测试服现状核对（2026-11-13）：`packhost/catmaid-pack.zip` sha1 = `6f0f8d7183203b881d44798f5088e3358aee5b62`
与 `server.properties` 一致；单次下载待客户端进服复核（蓝图 10.3 验收项）。

### 4.0b 二次修订：物品与分发跟 provider 整体对齐（2026-11-13 服主二次裁决，覆盖 4.0 的路线取舍）

服主于同日二次裁决：**撤销 4.0 的"CE 合并路线不再采用"**，改为 `dependency.provider` 同时决定
凭证物品与资源包分发，两条路线各自自洽、禁止混搭：

- vanilla（**现状，已部署**）：原版凭证物品 + BM 包独占原版槽位；
- craftengine：CE 凭证物品（`catmaid:bell` / `catmaid:talisman`）+ BM 包并入 CE
  `merge-external-zip-files`、由 CE send_pack 统一下发；server.properties 的资源包三键清空；
  启用前置（send_pack 修复）**已于 2026-11-13 完成并实测通过（见 4.3）**。剩余前置：
  CE 侧需先定义 `catmaid:bell` / `catmaid:talisman` 物品（测试服 CE 仍无定义，插件会回落
  原版材质并告警），且 provider=craftengine 全链路（召唤/喂食/死亡/复活/收纳）未实测（24.3-6）。

4.0 的部署事实（CE 移至 `plugins-disabled/`、BM 包独占原版槽位）仍有效，作为 vanilla 现状保留；
本节只改"路线取舍"口径。

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

### 4.3 CE send_pack 未生效——根因已定位并修复（2026-11-13，覆盖 11-10 原记录）

**原故障**（2026-11-10 19:50 会话）：CE 合并 BM 单包测试时，客户端不弹包、服务端无下发日志、`data.db` 无玩家包记录。

**根因**（反编译 CE 26.9.2 + 日志对照得出）：CE join 下发（1.20.2+ 客户端走 configuration 阶段，拦截 vanilla FinishConfigurationPacket 后发包）在 `SelfHostHttpServer.generateOneTimeUrl` 返回 null 时**静默跳过**。该方法有三条全部无日志的失败路径：
1. self-host 未启用（`enabled=false`）；
2. **内存 `packs` 表中无该 pack id 的托管包**（本次故障命中项）；
3. 玩家 UUID 为空。

任何一条命中都是三症状齐合：无下发日志、无 data.db 记录、客户端无下载请求。11-10 故障会话重启后工作流从未跑过（console.log 无"生成/上传资源包"行），配置处于实验态，self-host 内存中无可用托管包 → 静默跳过。

**排除项（均有实测证据，勿再怀疑）**：
- 原版 `server.properties` 三键**与 CE 下发不冲突**：11-10 19:37/19:43 两次进服在原版键设着的情况下 CE 均成功下发（客户端下载缓存 log.json 为证）；
- 离线模式无影响；
- `port: "auto"` 时 CE 不开独立端口，HTTP 挂靠 MC 主端口（25566）——netstat 看不到额外端口是**正常设计**；
- 2026-11-13 16:59 会话的"弹包成功"是**误判**：该会话插件列表里根本没有 CraftEngine，弹的是原版包。

**修复动作**（2026-11-13）：CE 移回 `plugins/`；`merge-external-zip-files` 恢复 `BetterModel/build.zip`；`server.properties` 三键清空（4.0b craftengine 路线）；`/ce reload pack` 重建托管包（250KB→9.15MB，含 18167 条 bettermodel 条目）。

**验收结果**（全部通过，2026-11-13 18:08–18:51 实测）：
1. ✅ 进服自动收到包：客户端下载缓存三次 join（18:08:52 / 18:48:40 / 18:50:44）均下载 9150489 字节 = 托管包精确大小；玩家确认收到包、CE 物品正常；
2. ✅ 服务端日志：工作流"生成→验证→压缩→上传成功"（join 时下发按 CE 设计不打日志；一次性 token 使每次 join 必下新包，以客户端下载记录为准）；`data.db`：`/pack disable`+`/pack enable` 后 `ce_pack_preferences` 落库——普通 accept 不写库是 CE 设计，仅显式 `/pack` 偏好落库；
3. ✅ 模型 NPC 正常渲染无紫黑（玩家实机确认；玩家移走旧 BT 包后模型仍正常 → CE 单包内容齐全）；
4. ✅ 单包：每次 join 仅 1 个下载记录；客户端列表残留的"bt"条目为历史下载缓存，可手动删除；
5. ✅ reload→重进拿新包：18:50 reload 后 18:50:44 重进即下载新上传包。

**运维要点**：重启或改配置后若"不弹包"，先跑 `/ce reload pack` 确保 self-host 内存中有托管包——静默跳过不会给任何日志提示。

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
| 2026-11-13 | **4.3 send_pack 故障收口**：根因定位（self-host 内存 `packs` 表无托管包时 join 下发静默跳过，反编译 CE 26.9.2 确认，三条无日志失败路径）→ 修复（CE 移回 `plugins/`、恢复 `merge-external-zip-files`、清空 server.properties 三键、`/ce reload pack` 重建托管包 250KB→9.15MB）→ 实机验收 5 项全过（单次下载、渲染无紫黑、reload 即拿新包）。**craftengine 路线启用前置解除**；排障任务书存档 `docs/send-pack-debug-prompt.md`、DB 侧写脚本 `tools/check_db.py`。 |

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
- **`animals.setLoveMode(600)` 不存在了。** 26.2 的 `Animals` 提供的是
  `boolean isLoveMode()` / `int getLoveModeTicks()` / `void setLoveModeTicks(int)`，求偶入口只有
  `setLoveModeTicks(600)`。（**维护窗口修订 2026-11-12**：原建议的 `Breedable#setBreed(boolean)`
  已删除——它改的是年龄，true→`setAge(0)`、false→`setAge(6000)` 变幼崽，与求偶无关，禁用。）
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
**客户端已验收通过**（2026-11-12 20:35–20:41，服主实测，见 20.8）：四条链路 + 缺饲料台词 + 坐下停手
全部通过，本轮"待客户端"的项已全部关闭。P6 收口。

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
| 玩家刚丢的物品 10 tick 内不抢 | **✅ 通过（客户端）** | 代码按 `getTicksLived()<=10` 不碰、带 owner 的再宽限到 100 tick（20.7-3）；服主实测通过 |
| 饲料耗尽时不再尝试繁殖（有台词提示一次） | **✅ 通过（客户端）** | 服务端侧：清空小麦后 2 头成年牛 30 秒内 `InLove` 恒为 0、`breeds` 不涨、挤奶照常；台词由服主客户端确认 |

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

P6 收口状态：新增 1 个类 / 改 3 个文件；蓝图验收 6 项全部通过（4 项服务端 + 2 项客户端）。提交记录：`bb4714f`（捡拾）→ `98dc3cb`（剪毛）→ `6fa1638`（挤奶）→ `d35029a`（繁殖）
→ `4655a8c`（缺料提示）→ 修复求偶 API → 本次文档提交。

### 20.8 P6 客户端验收记录（2026-11-12 20:35–20:41）

服主用 `Hayuuka`（女仆 owner）进服，在测试牧场按 20.5 的场景逐项过了一遍，**回报 P6 通过**。
本轮因此关闭了此前两处"待客户端"：

| 项 | 结论 |
|---|---|
| `on_no_food` 台词（饲料耗尽只念一次） | ✅ 通过 |
| `on_work_done` 台词（干完一件活 10% 概率） | ✅ 通过 |
| 玩家刚丢的物品不抢 | ✅ 通过 |
| 剪毛/挤奶/繁殖/捡拾的观感（走动姿态、红心、羊变裸） | ✅ 通过 |

**现场搭建方式已固化，可随时重建**（P7 及以后回归也用这套）：

```bash
# 石台 13x13 + 围栏 + 4 盏海晶灯（避开农田，别踩 P5 的耕地）
python tools/rcon.py "fill 28 70 8 40 70 20 minecraft:stone" \
  "fill 28 71 8 40 74 20 minecraft:air" \
  "fill 28 71 8 40 71 8 minecraft:stone_bricks" \
  "fill 28 71 20 40 71 20 minecraft:stone_bricks" \
  "fill 28 71 9 28 71 19 minecraft:stone_bricks" \
  "fill 40 71 9 40 71 19 minecraft:stone_bricks" \
  "setblock 34 71 8 minecraft:air" "setblock 35 71 8 minecraft:air" \
  "setworldspawn 34 71 10" "gamerule doMobSpawning false"
# 动物 + 补给箱（箱子里 64 小麦/3 桶/剪刀/16 蛋）
python tools/rcon.py "summon cow 31 71 12" "summon cow 36 71 16" \
  'summon sheep 32 71 17 {Color:14}' "setblock 29 71 19 minecraft:chest" \
  "item replace block 29 71 19 container.0 with minecraft:wheat 64"
```

**演示要点（下次给别人演示照做）**：先把她设成 `sitting=1` 再开服——否则玩家进服前的几十秒里
她就会把两头牛喂进 5 分钟冷却，繁殖演示直接看不到。玩家进服后点「坐/站」放开她，再自己把小麦
shift-click 进背包，时序才由人掌控。

### 20.9 给蓝图修正窗口的报告（P6 收口时提，2026-11-12）

> 下面每条都标了"建议改法"。**P7 章节本身没有动过**——按 agent.md 第 9 节，蓝图正文由维护窗口改，
> 这里只交事实与建议。优先级 A = 照抄会写出错代码，B = 口径不清会引发返工，C = 可读性。

**A1｜Phase 6 `animals.setLoveMode(600)` 必须整句替换**
- 现状：该 API 在 26.2 不存在（P0 已记）；19.9-2 给的备选 `Breedable#setBreed(true)` **同样是错的**
  ——字节码显示它是 `setAge(0)` / `setAge(6000)`。
- 建议：改成 `animal.setLoveModeTicks(600)`（`Animals#setLoveModeTicks(int)`），并在伪代码旁注
  "本方法在 26.2 存在，但 `setBreed` 会改年龄、`setBreed(false)` 会把成年动物变幼崽，禁用"。

**A2｜Phase 6 繁殖伪代码缺少"已经在求偶的不要重复喂"**
- 现状：`canBreed()` 只判 `getAge()==0`，不判求偶。只按它筛选时同一对会被反复投喂
  （实测 2 分钟空扣 27 份小麦）。
- 建议：筛选条件补 `&& !animal.isLoveMode()`，并在蓝图里写明原因。

**A3｜Phase 6 捡拾判定的字段名要改，并补 owner 规则**
- 现状：写的是 `age > 10 tick`。真实字段是 `Entity#getTicksLived()`；另外 `Item` 还有
  `getPickupDelay()` / `canMobPickup()` / `getOwner()`。
- 建议："`getPickupDelay() <= 0 && getTicksLived() > 10 && canMobPickup()`；带 owner 的（玩家亲手丢的）
  再宽限 100 tick（5 秒），否则等于永远不捡主人扔给她的东西"。

**A4｜Phase 7 `entity.attack(target)` 需要补"攻击强度"这一层**
- 现状：`LivingEntity#attack(Entity)` 在 26.2 存在（[JAR] 已核），但女仆是**玩家型 NPC**，
  走的是 `ServerPlayer.attack`，伤害受攻击冷却（attackStrengthTicker）影响；`Player` 上只有
  `getCooledAttackStrength(float)` / `getCooldownPeriod()` / `resetCooldown()`，**没有能直接灌满的 setter**
  （`resetCooldown()` 是清零，反而让下一刀更弱）。
- 建议：伪代码的攻击分支加一句 `if (maidEntity.getCooledAttackStrength(0.5f) < 0.9f) 跳过本轮（等下一 tick）`，
  并注明"剑满蓄约 12.5 tick、斧 20–25 tick，`attack-interval-ticks: 20` 对斧略欠"。

**A5｜Phase 7 女仆血量两处坑**
- 现状：只写了 `getAttribute(MAX_HEALTH).setBaseValue(40)`。① 只改上限不改当前血量，会出现"20/40"；
  ② Citizens 每次 spawn 都会重建实体，只在 onEnable 设一次会在 despawn/重生成后失效。
- 建议：改成"每次实体 ready（attach/spawn 回调）后 `setBaseValue(max)` + `setHealth(max)`；
  常量名是 `Attribute.MAX_HEALTH`（不是 `GENERIC_MAX_HEALTH`）"。

**B1｜Phase 6 验收项"饲料耗尽有台词提示一次"要标注可测边界**
- 建议：补一句"主人不在线时台词静默丢弃，服务端只能验行为（牛不进求偶、`breeds` 不涨）；台词需真人"。

**B2｜Phase 6「玩家刚丢的物品 10 tick 内不抢」的口径**
- 10 tick 在服务端几乎测不出来（一个决策周期就 30 tick）。建议直接按 A3 的"10 tick + owner 宽限 5 秒"表述，
  并把验收项改写为"玩家亲手丢出的物品，落地 5 秒后才会被捡"。

**B3｜Phase 5/6 伪代码要显式标注"伪代码，方法名不做保证"**
- 连续两个阶段各踩一处（`breakNaturally` 返回值、`setLoveMode`/`setBreed`）。建议在蓝图开头
  第 1 节加一句总则："所有代码块是流程示意，落地前必须 javap 核对"。

**B4｜Phase 7 伪代码里 `if (sitting) return` 与 P3 的全局拦截重复**
- 建议删掉，改注"坐姿由 BehaviorManager 统一硬拦截（P3），各 Mode 不用自己判"——P5/P6 都是这么做的。

**B5｜Phase 7 缺"索敌排除项"与"女仆被打是否扣好感"**
- 索敌要排除 Citizens NPC 与其他女仆：建议写进伪代码注释。
- 更要紧的产品问题：`MaidDamageListener.onHurt` 对**所有**伤害扣好感（5 秒冷却）。P7 一上线，
  带她打怪 = 每次挨打 -0.5 好感，与 `WORK_KILL +0.2` 互相抵消。**这条需要蓝图窗口裁决**：
  建议"+战斗模式受击不扣好感（或减半），非战斗模式照旧"，见 20.10-5.3。

**C1｜Phase 6 配置段注释**："`breed-food` 只覆盖特例，没写的动物由 `Animals#isBreedItem` 判定"——
已按此实现（实际只配了 4 种），建议蓝图补这句，省得以后有人把 7 种动物全配一遍。

**已确认无需改**：Phase 6 的五条验收项、`ranch` 配置键名、动作冷却 2 秒、8 秒卡死 + 30 秒拉黑、
"染色羊毛按 `DyeColor` 取材质"——实现与蓝图一致，实测通过。

### 20.10 P7（战斗模式）开工清单

**必读**：20.7-4（状态机四件套）、20.9 的 A4/A5/B5（P7 三条硬坑）、19.9-6（RCON 验收套路）。
基线：P6 收口提交（见本节末）。

#### 1. 落点
新建 `behavior/mode/CombatMode implements BehaviorMode`，`BehaviorManager` 构造里
`modes.put(Maid.Mode.COMBAT, new CombatMode(config, affection, dialogue, follow))`；
接上后 `BehaviorManager.tick` 里的降级 fallback（`mode == null → FOLLOW`）可以整段删掉，
五个模式全齐。`pulse()` 里战斗模式**不要**加 `tickIdle`（打架时碎碎念很吵，同 FARM/RANCH）。

#### 2. API 核对结果（[JAR] paper-api 26.2.build.135，已核）
| 蓝图用法 | 结论 |
|---|---|
| `LivingEntity#attack(Entity)` | ✅ 存在（void）；受攻击冷却影响，见 A4 |
| `LivingEntity#swingMainHand()` | ✅ 存在（客户端可见性另说，见 5.3） |
| `LivingEntity#hasLineOfSight(Entity)` | ✅ 存在 |
| `World#spawnArrow(Location, Vector, float, float)` | ✅ 存在，返回 `Arrow` |
| `Arrow#setDamage/setShooter` | ✅ 在 `AbstractArrow` 上（`getDamage/setDamage`）+ `Projectile#setShooter` |
| `AbstractArrow#setPickupStatus(PickupStatus)` | ✅ 存在——**建议设 `CREATIVE_ONLY`**，否则玩家能捡走她的箭 |
| `Attribute.MAX_HEALTH` / `ATTACK_DAMAGE` / `ATTACK_SPEED` | ✅ 存在（**没有** `GENERIC_` 前缀了） |
| `Monster` | ✅ 存在（`Creature` + `Enemy`） |
| `EntityDamageByEntityEvent#getDamager()` | ✅ 存在 |
| `Player#getCooledAttackStrength(float)` / `resetCooldown()` | ✅ 存在；**没有** `getAttackCooldown()`，也没有灌满强度的 setter |
| `npc.setProtected(false)` | ✅ `MaidManager` 第 194/289 行已经在用，女仆可受击（P4 死亡链路已验） |

#### 3. 现成能力（别重造）
- `Movement.navigate/stop/isNavigating/arrived`；姿态由 BehaviorManager 自动给 WALK/STAND；
- **`FollowMode` 实例**已由 BehaviorManager 持有：脱战时"缓步跟随主人"建议直接复用它
  （`friend.tick(maid, tick)`），别复制一份距离带逻辑；它内部还带跨世界 3 秒延迟；
- `affection.damageBonus(level)`：蓝图验收项"好感 60+ 打得痛 +15%"直接用它，数值在 `affection.levels[].damage-bonus`；
- `maid.stats().addKill()` + `affection.apply(maid, affection.gain(AffectionReason.WORK_KILL), WORK_KILL)` + `dialogue.send(maid, "on_work_done")`；
- `MaidDamageListener` 已有受击/死亡链路——**P7 只需在同一事件里"锁定反击目标"，不要另建监听器**；
- `maid.inventory().getMainHand()` 就是她手上的武器（`applyEquipment` 已同步到实体）；
- `State` 四件套（目标 + 8 秒卡死 + 30 秒拉黑 + 动作冷却）直接照抄 `RanchMode`。

#### 4. 需要新写的
- `CombatMode`：索敌（`getNearbyEntities(..., Monster.class)`，排除 `CitizensAPI.getNPCRegistry().isNPC(e)`、
  排除其他女仆实体、排除已死/隐身）、近战追击、远程放箭、脱战回主人；
- `OwnerProtectionListener`：主人被打 → 该玩家名下**战斗模式**的女仆锁定 attacker；
  女仆被打 → 战斗模式下锁定 attacker。只做"写 target"这一件事，业务在 CombatMode 里；
- 伤害加成：`EntityDamageByEntityEvent` 里 damager 是女仆实体时 `setDamage(damage * (1 + bonus))`；
  同一事件里顺手判 `kill`（或用 `EntityDeathEvent#getKiller`）；
- `config.yml` 新增 `combat` 段（radius 12 / lose-dist 20 / attack-interval-ticks 20 / ranged-enabled /
  ranged-range 15 / arrow-damage 5.0 / maid-max-health 40 / protect-owner）；
- 女仆血量：给 `MaidManager` 的 attach/spawn 回调加"设 MAX_HEALTH + setHealth"（见 A5），别只在 onEnable。

#### 5. 踩坑预告
1. **攻强衰减**（A4）：不判 `getCooledAttackStrength` 的话，20 tick 一刀在斧头上只有 ~80% 伤害，
   而且"好感 0 vs 60"的 15% 差异会被这层噪声淹掉，验收项会时过时不过。
2. **血量上限与当前血量要一起设**，且每次实体重建都要重设（A5）。否则表现为"她只有 20 血"或"血条 20/40"。
3. **带她打架会掉好感**（B5，需先裁决）：`MaidDamageListener.onHurt` 目前无差别扣 0.5（5 秒冷却）。
   建议 P7 动这个之前先定口径，别让"战斗模式"变成"惩罚模式"。
4. **玩家型 NPC 的 `attack()` 会消耗主手耐久**（走真实玩家逻辑）：铁剑打十几只僵尸就报销。
   这是设计取舍，但要在 P8 文档里告诉玩家（或给她配"不毁坏"的武器）。
5. **挥剑动作大概率看不见**：BetterModel 盖住原版实体渲染，`swingMainHand` 只有原版手臂动画。
   P3 的 `tools/bbmodel_anim.py` 能往模型里注动画——P7 验收时先确认有没有 `attack` 动画可挂。
6. **弓箭的初速**：蓝图给 speed 2.0（原版满蓄力约 3.0），15 格外下坠明显；`spread` 是角度（度），
   6f 已经比较散，调试时先调这两项，别怀疑命中判定。
7. **怪物会主动打女仆**（她是 ServerPlayer，仇恨正常），所以"她挨打 → 反击"这条天然成立，
   但要防止"她被两只僵尸夹在中间反复反击到死"——`lose-dist` 与血量上限就是为这个设的。
8. **无玩家在线时 Citizens 会 despawn**（18.3-4）：自动化验收一律"`/npc spawn` 后立刻测"。

#### 6. 验收准备（服务端可自动化）
```bash
# 场地：沿用 20.8 的石台（28..40 / 8..20 / y71）
python tools/rcon.py "summon zombie 34 71 12"
python tools/rcon.py "npc spawn 23"
# 她的主手武器：DB 里 slot 0 放钻石剑（blob 做法见 19.3-3），或 GUI 装备页 shift-click
# 看怪掉血：连续两刀之间的 Health 差
python tools/rcon.py 'data get entity @e[type=zombie,x=34,y=71,z=14,distance=..12,limit=1] Health'
# 好感加成对照：先 0 好感打一刀记伤害，再把 maids.affection 改成 60 重启打一刀对比
```
蓝图 P7 六项验收对照：①主动迎敌击杀 ②弓保持距离且有伤害归属 ③好感加成可测
④打主人/打女仆都会被反击 ⑤脱战回主人 ⑥HP 归零走铃铛流程。

#### 7. 基线
P6 收口提交见本节末（`git log --oneline -1`）。开发前先 `git pull`——P6 期间蓝图与 api-facts 都改过。
唯一强约束：**别照抄蓝图 P5/P6/P7 伪代码里的方法名**，三次里已经错了三次。

---

## 21. Phase 7 交接（战斗模式，2026-11-12）

### 21.1 阶段结论

**代码落地，服务端六项验收全部有实测依据**：主动索敌并近战击杀（僵尸 100 → 死）、远程放箭
（箭 `pickup=2b` 不可回收、击杀计入统计）、好感伤害加成（0 好感 6.888/刀 → 65 好感 7.9212/刀，
精确 ×1.15）、索敌半径外不追（32 格刷怪不动）、目标超出脱战距离后停手、真实战斗打到 0 血走
Phase 4 铃铛流程（DB `alive=0`、地上出现带 lore 的 bell、重启收起幽灵身体）。
**客户端已验收通过**（2026-11-12 21:47–22:00，服主用 `Hayuuka` 进服实测，见 21.8）：近战迎击、
远程放箭、脱战回主人、主人被打 → 群起反击、被打反击、死亡铃铛全部通过。P7 收口。
挥剑/拉弓的**骨骼动作**仍受"模型没有 `attack`/`bow` 动画"限制（21.7-2），属 P8 的打磨项，不是阻塞项。

### 21.2 新增 / 改动文件

| 文件 | 职责 |
|---|---|
| `behavior/mode/CombatMode`（新） | 索敌 / 近战 / 远程 / 击杀记账 / 脱战交回跟随；per-maid 状态（目标 + 8 秒卡死 + 30 秒拉黑 + 出手冷却） |
| `listener/OwnerProtectionListener`（新） | 主人被打 → 其战斗模式女仆锁定攻击者；女仆被打 → 就地反击。只写 target，业务在 Mode 里 |
| `listener/MaidDamageListener` | 新增"女仆打出去的伤害按好感加成"（含箭矢归属）；战斗模式受击不再扣好感 |
| `maid/Maid` | 新增 `maxHealth` 字段与 `applyVitals()`（装备写回 + 血上限一起做） |
| `maid/MaidManager` | 载入/召唤时灌血量上限；新增 `onEntityReady(maid)`，四条生成路径统一走它 |
| `maid/MaidTrait` | `onSpawn()` 改调 `applyVitals()`（Citizens 重建实体后补血量上限） |
| `listener/PlayerConnectionListener`、`listener/TalismanListener` | 生成实体后改调 `maidManager.onEntityReady()` |
| `behavior/BehaviorManager` | 注册 `COMBAT`；新增 `lockCombatTarget()` 转发；降级 fallback 保留为"配置写错时退回跟随" |
| `resources/config.yml` | 新增 `combat` 段（radius / lose-dist / attack-interval-ticks / ranged-* / arrow-damage / maid-max-health / protect-owner） |

### 21.3 实测新增的 API / 行为事实

**(1) 攻强闸门是"好感加成可测"的前提。** [实测] 同一把钻石剑、同一只 1000 血僵尸：
好感 0（等级 0，+0%）→ 每刀 **6.888**；好感 65（等级 3，+15%）→ 每刀 **7.9212**，比值精确 1.15。
6.888 而不是 7 说明 20 tick 间隔下攻强仍在 0.984 左右——不判 `getCooledAttackStrength(0.5f) >= 0.9f`
的话这 1.6% 抖动会随暴击（×1.5）放大成"时过时不过"。暴击同样存在（观测到 11.8818 = 7.9212×1.5）。

**(2) 箭的实际伤害 = `setDamage` × 速度折算。** [实测] `arrow-damage: 5.0` + 初速 2.0 时，
每箭掉血约 **9.8–10.8**（不是 5.0）。要精调伤害必须同时看 `arrow-damage` 与 `ARROW_SPEED`，
只改前者会得到一半的预期效果。

**(3) `Arrow#setPickupStatus(CREATIVE_ONLY)` 落地正确。** [实测] `data get entity @s pickup` 返回 `2b`
（0=不可拾取，1=可，2=仅创造）。她的箭不会给生存玩家白捡。

**(4) 玩家型 NPC 依然无法用命令选中。** [实测] `/execute as 小铃`、`/attribute 小铃 ...` 全部
`Invalid name or UUID`；`scoreboard objectives add hp health` 对她也不产生分数（她不在玩家列表）。
**验收手段**：用她头顶的 `@e[type=text_display]` 定位（P6 已固化），血量只能靠临时探针
（本轮在 `Maid.applyMaxHealth` 打过一行 `血量 40.0/40.0`，验收后已删）。

**(5) 给女仆塞武器不必开客户端 GUI。** [实测] `ItemStack.serializeAsBytes` 的落库格式就是
**gzip 后的 NBT 根 compound**（无版本前缀）：
```
0A 0000                       compound, name ""
  03 000B "DataVersion" int 4903
  08 0002 "id"          string "minecraft:diamond_sword"
  03 0005 "count"       int 1
00                            end
```
用 Python 造这 84 字节（`gzip.compress(..., mtime=0)`）写进 `maid_items(slot=0)`，重启即"主手=DIAMOND_SWORD"。
P7 的近战/远程验收全靠这条，省掉了真人往 GUI 里拖装备的步骤。

**(6) 战斗死亡链路在 40 血下照常工作。** [实测] 被多只僵尸围殴到 0 血 → 掉出带 lore 的 bell、
`maid_stats` 不再增长、DB `alive=0`、重启时 `已收起铃铛态女仆 … 的残留实体（NPC 23）`。
与 P4 的唯一差别是"能撑更久"，没有新增分支。

### 21.4 与蓝图的有意差异

1. **脱战直接复用 `FollowMode` 实例**（蓝图建议如此）：实际站距沿用 `follow.stop-dist`（2.5），
   不是蓝图写的 standby 4。理由：复制一份距离带就会出现两套阈值，改配置时必然漏一处。
2. **远程不做"拉开"**：只做"接近到射程"。目标贴脸时后撤会让僵尸追着她跑，观感像逃跑。
3. **好感伤害加成放在 `MaidDamageListener`**（蓝图只说"监听 `EntityDamageByEntityEvent`"）：
   那个类已经有可靠的"实体 → 女仆"反查（含 despawn 兜底），不必再写一份。箭顺 `getShooter()` 找回她。
4. **击杀记账在主循环的"目标已死"分支**（同蓝图伪代码）：主人补最后一刀也算她的。
   反过来"她打了但目标被别人打死"不会重复记。
5. **`lockTarget` 加了距离上限（`lose-dist`）与排除项**：超过 20 格不锁（下一轮就会被丢），
   其他 Citizens NPC 与主人本人不锁（否则"喂食点错"会变成家暴）。
6. **战斗模式不掷待机台词**（`pulse()` 里只有 IDLE/FOLLOW），与 FARM/RANCH 一致。

### 21.5 验收结果（服务端实测 2026-11-12 21:12–21:27）

| 蓝图验收项 | 结果 | 依据 |
|---|---|---|
| 附近刷僵尸 → 主动迎敌、挥剑击杀、播放挥臂 | **✅ 服务端通过**（"挥臂"待客户端） | 100 血僵尸被追到 9.6 血后击杀；`maid_stats.kills` 连增；日志无 ERROR |
| 主手给弓 → 保持距离射箭，箭有伤害归属 | **✅ 服务端通过** | 10 格外放箭，僵尸 1000 → 865（每箭 ≈10）；`pickup=2b`；箭击杀后 kills +2、好感 +0.4 |
| 好感 60+ 的女仆明显比 0 好感打得痛（+15%） | **✅ 服务端通过** | 6.888/刀 vs 7.9212/刀（×1.15，见 21.3-1） |
| 打主人 → 群起反击；打女仆 → 反击 | **✅ 通过** | 女仆侧：13 格外的骷髅（在索敌半径 12 之外）射中她后，她主动还击并把骷髅打到 20→10→死；主人侧：服主实测（21.8） |
| 索敌半径外/目标死亡正确脱战并回主人 | **✅ 服务端通过** | 32 格外的僵尸 8 秒内不引发移动、无箭；目标被传到 30 格外后立刻停手（箭数 0、血量不变）；无主人在线时回 home 附近待机 |
| 女仆 HP 归零走 Phase 4 死亡流程（铃铛） | **✅ 服务端通过** | 见 21.3-6 |

血量：`applyMaxHealth` 探针显示两只女仆均为 `40.0/40.0`，四条生成路径（召唤/恢复/上线补挂/铃铛复活）
都走 `onEntityReady`。

编译与启动：`gradlew shadowJar` 通过；paper-26.2-62 启动 `ERROR|Exception` 计数为 0。

### 21.6 冲突处理记录（agent.md 第 9 节格式）

本轮无新增 `[CONFLICT]`：蓝图 P7 章节已由修订窗口按 20.9 的 A4/A5/B5 改过，实现与之一致。
唯一一条"事实修正"是 21.3-2（箭伤害按速度折算），它不改变蓝图结构，只影响调参预期。

### 21.7 已知限制 / P8 接手要点

1. **`attack-interval-ticks: 20` 对斧略欠**：斧攻速 0.8（25 tick 满蓄），20 tick 间隔会被攻强闸门
   跳过一轮，实际约 25 tick 一刀。要"斧也满伤"请把间隔调到 25。
2. **挥剑/拉弓看不见**：`swingMainHand()` 只驱动原版手臂，模型盖住实体后没有动作。要让观感成立，
   得先用 `tools/bbmodel_anim.py` 往 bbmodel 注入 `attack` / `bow` 动画（与 P3 的 idle/walk/sit 同理）。
3. **玩家型 NPC 的 `attack()` 消耗主手耐久**：走真实玩家逻辑，铁剑打十几只僵尸就报废。
   P8 的玩家文档要写明，或给她配"不毁坏"的武器（`unbreakable` 组件）。
4. **战斗模式受击不扣好感**已按窗口裁决落地；非战斗模式照旧 -0.5（5 秒冷却）。改这个口径只需动
   `MaidDamageListener#onHurt` 里的那一个 `if`。
5. **贴脸不后撤**：弓手被她自己贴在脸上也照射，不会拉开。观感问题留给 P8 的打磨项。
6. **多只女仆围攻同一目标**：各自独立结算，目标死亡时"正在打她的那只"各记一次击杀（可能多记）。
   不影响数值平衡，暂不处理。
7. **`/catmaid mode` 仍未实现**（P8）：本轮验收靠"停服改 `maids.mode` 再启动"，
   真人切模式走 GUI。**注意：运行期直接改库会被每 5 分钟的 saveAll 覆盖，必须先停服。**

### 21.8 P7 客户端验收记录（2026-11-12 21:47–22:00）

服主用 `Hayuuka`（owner）进服，在石台（28..40 / 8..31 / y71）按 21.5 的场景逐项过了一遍，
**回报 P7 通过**（逐项口述确认，未逐条留截图）：

| 项 | 结论 |
|---|---|
| 资源包与模型（build8.zip 下发、贴图正常） | ✅ 通过 |
| 近战：刷怪 → 主动迎敌、击杀 | ✅ 通过 |
| 脱战：目标跑出 lose-dist → 收手 | ✅ 通过 |
| 主人被打 → 群起反击 | ✅ 通过 |
| 她被打 → 就地反击 | ✅ 通过 |
| 远程：主手弓 → 放箭、箭不可拾取 | ✅ 通过 |
| HP 归零 → 铃铛流程 | ✅ 通过 |

挥剑/拉弓的骨骼动作仍受 21.7-2 限制，本轮按"已知限制"接受，转 P8 打磨（给 bbmodel 补 `attack`/`bow`）。

**验收环境固化（P8 回归照用）**：

```bash
# 游戏服
127.0.0.1:25566          # server.properties: server-port=25566，require-resource-pack=true
# 资源包服（必须与 server.properties 的 resource-pack-sha1 对齐，否则客户端不重下）
cd testserver/plugins/BetterModel && python -m http.server 8123 --bind 0.0.0.0 --directory .
#   resource-pack=http://127.0.0.1:8123/build8.zip
#   sha1 = 6f0f8d7183203b881d44798f5088e3358aee5b62 （1217743 字节）
# 场边补给箱 (31,71,19)：1 弓 + 32 箭，给远程验收用
```

**本轮新增的验收技巧（P8 复用）**：
- 给女仆塞武器不必开客户端 GUI：见 21.3-5 的 gzip NBT blob，直接写 `maid_items.slot=0`；
- "主人被打"用 `/summon zombie ~ ~ ~2` 就能造，不需要第二个玩家；
- 女仆定位一律 `@e[type=text_display]`（`@e[type=player]` 查不到她），血量靠临时探针；
- 改 `maids.mode` / `affection` **必须先停服**，否则被 5 分钟一次的 `saveAll` 覆盖。

### 21.9 P7 收口状态

新增 2 个类（`CombatMode`、`OwnerProtectionListener`）、改 6 个 Java 文件 + `config.yml`；
蓝图 P7 六项验收全部通过（4 项服务端 + 2 项客户端），另有"好感加成"的数值精确对照（6.888 → 7.9212）。
提交记录：`0a9b14b`（基线）→ `3b0f021`（血量）→ `551c320`（索敌近战）→ `a995213`（击杀统计）
→ `3633e84`（远程）→ `cdef033`（伤害加成/受击口径）→ `75c21ae`（反击锁定）→ `241063f`（收口文档）
→ 本次客户端验收记录。下一阶段：P8（命令补齐 `/catmaid mode|info|sethome|admin reload`、玩家文档与整包交付）。

---

## 22. P8（配置化 / 命令 / 收尾）开工清单

**必读**：21.7（P7 已知限制）、蓝图 Phase 8、`agent.md` 第 4 节。
基线：P7 收口提交（`git log --oneline -1`）。

### 1. 现状盘点（别重造）

- `CatMaidCommand` 现有：`/catmaid`（帮助）、`talisman [数量]`、`admin summon|remove|list`。
  **缺**：`gui / mode / sethome / info / rename / title / admin setaffection / admin reload`。
- `GuiManager` 已有打开主菜单/模式页/装备页的入口（P2），`/catmaid gui` 只是"找对人再调它"。
- `DialogueManager.reload()` 已存在（重读 `dialogue.yml`）；`Messages.load(plugin)` 同（P1）。
- `AffectionTable.load(config, logger)` 是等级表来源，**reload 时必须重灌**，否则 `Maid#setAffection`
  换算出的背包容量还是旧值。
- 名牌在 `NameTagManager`（TextDisplay），改颜色只动文本，不动结构。

### 2. 需要新写的

- 8 个命令分支 + tab 补全（模式名、在线玩家名、数值范围）。
- `messages.yml` 补键：`command-help.*`、`mode-changed`、`home-set`、`info-entry`、`rename-*`、
  `affection-set`、`reload-done`。**代码里不许出现中文字面量**（agent.md 5.5）。
- `config.yml` 补 `performance` 段（`global-maid-cap: 60`、`behavior-tick-stagger: true`）——
  代码里 `global-maid-cap` 读的就是这个路径、默认 60，补配置只是把它显式化，别改默认值。
- 名牌颜色随好感等级（灰→白→黄→粉→红）：`AffectionTable.level(...)` → 颜色映射写在一处。

### 3. 踩坑预告

1. **`admin setaffection` 不要直接改字段**：容量同步、事件、升级台词都在
   `AffectionManager.apply(maid, delta, reason)` 里。按 `delta = 目标 - 当前` 调它最省事。
   若想跳过升级台词，得给 `AffectionReason` 加枚举——但**必须同步 `configKey()` 的 switch**，
   否则 `gain()` 会走进默认分支（0 值静默通过，最难查）。
2. **`admin reload` 的顺序**：`AffectionTable.load()` → `Messages.load()` → `DialogueManager.reload()`。
   反过来会出现"好感变了但背包容量没变"（容量只在 `setAffection` 时刷新，reload 不补历史女仆）。
3. **`/catmaid gui` 的"最近一只"**：先找 8 格内自己名下的，找不到再退"自己唯一一只"；
   两种都不满足时给 `no-maid-nearby`，**不要随便打开别人的**。
4. **`rename` 要过滤富文本**：`ChatColor.stripColor` 拦不住 MiniMessage 的 `<red>`/`<click:...>`，
   直接拒绝 `<`、`&`、`§` 三个字符比"过滤"更稳。
5. **60 只女仆压测卡在边界**：`summonFor` 的判断是 `maids.size() >= cap`，cap=60 时第 60 只被拒。
   压测要么把 cap 调到 61，要么只召 59 只；连发召唤要分 tick（每 2 tick 一只），否则一次主线程
   60 次 spawn 会把 TPS 打成个位数，测出来的是自己不是插件。
6. **`admin summon` 的离线玩家**：`resolvePlayer` 已有 `hasPlayedBefore()` 检查，别绕过——
   离线模式服会凭空造出幽灵档案。

### 4. 验收准备

- 命令逐条：每条验"无权限 / 正常 / 缺参数"三种路径；玩家可见文案全部来自 `messages.yml`。
- 全链路回归（蓝图 P8 第 1 条）：新玩家领魂符 → 召唤 → 喂食 → 农田/牧场/战斗各跑一次 → 死亡 → 复活。
- 重启恢复（第 2 条）：先记 位置/模式/背包/好感/坐下/存活，重启后逐项对比。
- reload（第 4 条）：改 `affection.levels[].capacity` 与 `dialogue.yml` 一句台词，
  `/catmaid admin reload` 后立刻验证容量与台词都变了。
- 60 只 TPS（第 3 条）：`spark tps` 或 `/tps`，目标 ≥19.5；结论写"待服务器实测"也算合格（agent.md 8.4）。

### 5. P7 交接要点（P8 开工前必看）

1. 挥剑/拉弓没有骨骼动作 → 想补要先给 bbmodel 注入 `attack`/`bow`（21.7-2），**改模型不必重发资源包**
   （附录 A 已记：动画不在包内）。
2. 玩家型 NPC 的 `attack()` **消耗主手耐久**，铁剑打十几只僵尸就报废 —— P8 的玩家文档必须写明，
   或给她配 `unbreakable` 武器（21.7-3）。
3. `attack-interval-ticks: 20` 对斧略欠，要满伤得调到 25（21.7-1）。
4. 战斗模式不掷待机台词，别在 P8 里"顺手补上"——那会让打架时碎碎念。
5. 验收环境已固化（21.8）：游戏服 25566 + 资源包服 8123 + 场边补给箱；资源包 sha1 必须对齐。

---

## 23. Phase 8 交接（配置化 / 命令 / 收尾打磨，2026-11-12）

> **收口状态（2026-11-12 22:42）：代码完成、服务端验收通过；客户端项未验证。**
> 服主决定先收口（当时另发现若干冲突待处理），因此蓝图 P8 的四条最终验收里
> **「新玩家全链路」「60 只女仆 TPS」两项保持未验证**，不得计入通过。测试服已停机。

### 23.1 阶段结论

命令树、文案外置、配置补齐、名牌颜色四项全部落地并**服务端**实测；蓝图 P8 的最终验收
**四项里两项已过（重启恢复、reload 生效），另两项（新玩家全链路、60 只 TPS）客户端未验证**——
无玩家时 Citizens 会把全部女仆 despawn（原因见 23.3-4），服务端测不出行为负载。

构建与启动：`gradlew shadowJar` 通过；paper-26.2-62 启动 `ERROR|Exception` 计数 0
（唯一一条 `TerminalConsole 拒绝访问` 是本轮用 fifo 起服的环境问题，与服务端/插件无关，见 23.7-6）。

### 23.2 新增 / 改动文件

| 文件 | 改动 |
|---|---|
| `command/CatMaidCommand.java` | 补齐 gui/mode/sethome/info/rename/title + admin setaffection/reload；`resolveMaid` 最近女仆解析；talisman 冷却与开关；tab 补全按权限分层 |
| `maid/AffectionTable.java` | 新增 `threshold(int level)`（按档位取阈值，供 rename/title 门槛与提示数值） |
| `maid/NameTagManager.java` | 名字按好感等级上色；记录 renderedLevels，过档才重设文本；refresh/remove 同步清理 |
| `maid/MaidManager.java` | 召唤时 `general.default-title` 作为默认称呼 |
| `dialogue/AffectionReason.java` | 新增 `ADMIN`（setaffection 专用，配置里无数值键） |
| `dialogue/AffectionManager.java` | `configKey()` 的穷尽 switch 补 `ADMIN -> null` |
| `util/Messages.java` | 新增 `rawList(key)`（取未解析的模板列表，给名牌拼色用） |
| `behavior/BehaviorManager.java` | 读到 `performance.behavior-tick-stagger: false` 时明确告警（不静默吞掉） |
| `config.yml` | `general.default-title`、`interaction.talisman-enabled/-cooldown-hours`、`performance.*` |
| `messages.yml` | help 段重写 + 21 个新键（no-maid / mode-* / home-set / info-* / rename-* / title-* / talisman-* / affection-* / reload-done / remove-all-success）+ `nametag.name-colors` |
| `CatMaidPlugin.java` | 命令构造注入 gui/affection/dialogue/nameTags |

### 23.3 实测新增的 API / 行为事实

1. **[源码已核] `JavaPlugin#reloadConfig()` 会换对象**：Paper 的实现是
   `newConfig = YamlConfiguration.loadConfiguration(configFile)` —— 它**不**原地改旧对象。
   而 `MaidManager` / `AffectionManager` / 五个 `BehaviorMode` 都在构造时抓住了 `getConfig()` 的引用，
   直接调 `reloadConfig()` 的结果是"提示重载成功、行为一点没变"。
   **采用方案**：`CatMaidCommand#reloadConfigInPlace()` 把文件里的键逐个 `set` 进当前 FileConfiguration
   （先清空再覆盖），所有持有者立刻看到新值。改不到的部分：`models` 名单、`nametag.scale/height`
   （构造期快照），已写进命令提示。
2. **[已实测] messages.yml 的合并只补缺键、不改已有键**：P7 的 `messages.yml` 里 `help` 段是旧的，
   起来后 `/catmaid` 仍只列 3 条旧命令。升级姿势：删掉 `plugins/CatMaid/messages.yml` 让插件按 jar 模板重建，
   或手动替换 `help:` 段。**这是升级必踩的一次性动作**。
3. **[已实测] 控制台/RCON 的 sender 不是 Player**：`/catmaid gui|mode|talisman` 从 RCON 发会得到
   "该命令只能由玩家执行"。玩家侧命令的服务端验证因此只能验拦截分支，happy path 必须真人客户端。
4. **[已实测] 无玩家在线时 60 只女仆压测无效**：`admin summon` 分 tick 召了 58 只（cap 61 / max-per-player 60），
   TPS 20.0 / MSPT 2.5ms 看着很漂亮，但 Citizens 早已把她们全部 despawn（`maid.entity()` 全为 null，
   行为 tick 全空转）——测出来的是"58 条数据库记录"，不是 58 个实体。
   **60 只 TPS 只能在真人客户端在线时测**（流程：客户端进服 → cap 调 61、max-per-player 调 60 → reload →
   每 2 tick 召一只 → 站她身边看 `/tps` 与 `/mspt`）。
5. **[JAR 已核] `NPC#setName(String)` 存在**（citizens-main）：`rename` 改名时同步 NPC 名，
   避免 `/npc list` 与名牌两处名字不一致。
6. **[已实测] `summonFor` 的两道上限都会挡住压测**：`maids.size() >= global-maid-cap` 与
   `byOwner().size() >= max-per-player`；召 60 只要同时把它们调到 61 / 60。
7. **[已实测] reload 的容量刷新是真生效的**：把 `affection.levels` 里第 1 档容量 30→31→32，
   `/catmaid admin reload` 后日志逐只打印 `小铃 背包容量 31 → 32`；没改数值时 reload 不打印任何行（幂等）。

### 23.4 与蓝图的有意差异

1. **`performance.behavior-tick-stagger` 目前是"形状键"**：错峰写在五个 Mode 与 NameTagManager 内部
   （`tick % N == npcId % N`），关掉它需要逐 Mode 改造，本轮不做；读到 `false` 会打一条告警，
   不静默吞掉。蓝图 P8 只要求"补这个段"，故按字面满足，行为保持恒开。
2. **`/catmaid talisman` 的冷却记在内存**：重启即清零。它不是经济系统，为它加一张表不划算；
   真正的服用方式是 `interaction.talisman-cooldown-hours: 0` 或关掉自助领取。
3. **rename/title 的门槛按"档位"而非写死 40**：`RENAME_MIN_LEVEL = 2`（伙伴档），
   提示里的数值由 `AffectionTable.threshold(2)` 取——服主改了阈值，解锁点与提示一起跟着走。
4. **`/catmaid mode` 认 messages.yml 里的中文模式名**：`gui.mode-<枚举名>` 就是输入别名，
   服主把「农业」改成「种田」，命令输入、GUI 按钮、提示文案一起变；代码里没有中文别名表。
5. **`general.default-title` 是 P8 顺手加的配置**：`Maid` 的字段默认值仍是"主人"，
   但新召唤的女仆按配置写称呼（agent.md 5.5 的"玩家可见文案不硬编码"）。

### 23.5 验收结果（服务端实测 2026-11-12 22:22–22:44）

| 蓝图 P8 项 | 结论 | 证据 |
|---|---|---|
| `/catmaid` 帮助列出全部新命令 | ✅ | 12 行输出，含 gui/mode/sethome/info/rename/title/setaffection/reload |
| 玩家侧命令的玩家/权限闸门 | ✅（拦截侧） | RCON 发 `gui`/`mode 农业`/`talisman` 均回"该命令只能由玩家执行"；happy path 待客户端 |
| `admin list` | ✅ | 3 只女仆，名字/主人/模型/好感/存活齐全 |
| `admin setaffection` 正常 | ✅ | 20.4 → 60.0，日志"升到等级 3（背包 36 格）"，DB 落库 60.0 |
| `admin setaffection` 异常三路 | ✅ | 120 → "要在 0.0 到 100.0 之间"；缺参 → 用法；非 UUID → "找不到这个女仆" |
| `admin reload` 生效 | ✅ | 容量 30→31→32 逐次生效，日志"小铃 背包容量 31 → 32"；未改数值时无输出（幂等） |
| `admin remove` 批量清理 | ✅ | 58 只一次清完，提示改为"已送走 loadtest 名下的 58 只女仆"；DB 回到 3 只 |
| 重启后状态恢复 | ✅ | 好感 60.0 / 模式 COMBAT / 铃铛态汐音 / 座位/装备均按库恢复，启动 0 ERROR |
| 新玩家全链路（领魂符→召唤→喂食→农/牧/战→死亡→复活） | ⚠ 客户端未验证 | 各环节在 P4–P7 已分别客户端验过，P8 只补命令入口；整链一次跑通**没人跑过** |
| 60 只女仆 TPS ≥ 19.5 | ⚠ 客户端未验证 | 见 23.3-4：无玩家时实体全被 despawn，测不出负载；测试服已停机 |

> 上表"✅"仅代表**服务端**（控制台/RCON/数据库/日志）证据；标 ⚠ 的两项不得视为通过。
> 玩家侧命令（gui/mode/sethome/info/rename/title/talisman）的 **happy path 也属于未验证**，
> 本轮只验到"非玩家 sender 被拦住"这一侧。

### 23.5.1 恢复验证时的最小清单（测试服重开后照此跑）

1. 起服：`testserver` 下 `java -jar paper-26.2-62.jar nogui`；
   资源包服：`testserver/plugins/BetterModel` 下 `python -m http.server 8123 --bind 0.0.0.0 --directory .`
   （`server.properties` 的 `resource-pack` 是 `127.0.0.1`，客户端不在本机时要换成内网 IP）。
2. 进服后依次：`/catmaid info` → `/catmaid mode 农业` → `/catmaid sethome` → `/catmaid gui` →
   `/catmaid rename 新名字` → `/catmaid title 主人` → `/catmaid talisman` 连发两次（第二次应被冷却拦住）。
3. 名牌颜色：`/catmaid admin setaffection b2aada7b-f57a-4e79-85af-d23a53898d94 85` → 名字应变红（羁绊档）。
4. 全链路：领魂符 → 召唤 → 喂食 → 种田/打怪 → 打死 → 铃铛复活。
5. 60 只：`max-per-player` 调 60、`global-maid-cap` 调 61 → `/catmaid admin reload` → 分 tick 召唤 →
   站她身边看 `/tps` `/mspt`；测完 `admin remove <玩家>` 清理。

### 23.6 冲突处理记录（agent.md 第 9 节格式）

```
[CONFLICT] 蓝图位置: Phase 8 打磨项「config.yml 补 performance 段：behavior-tick-stagger: true」
现实情况: 错峰逻辑分布在各 Mode 与 NameTagManager 内部（tick % N == npcId % N），没有一个统一的开关点；
         关掉错峰需要改 6 个文件，且没有玩家会想关（关了 60 只女仆同 tick 扫方块）
采用方案: 保留键位并在读到 false 时告警，行为恒开  理由: 满足配置形状，且不让"看起来能关"变成静默谎言
```

```
[CONFLICT] 蓝图位置: Phase 8「/catmaid admin reload → 重载 config + dialogue」
现实情况: Paper 的 reloadConfig() 换对象，构造期抓住旧引用的 5 个组件看不到新值
采用方案: 原地覆盖 config 的键  理由: 不改 6 个类的构造签名，reload 立刻对所有持有者生效
```

### 23.7 已知限制 / 部署注意

1. **升级到 P8 必做一次**：删 `plugins/CatMaid/messages.yml`（或手动替换 `help:` 段），
   否则 `/catmaid` 的帮助还是旧命令表（23.3-2）。
2. **config.yml 同理不会自动长出新键**：`general.default-title`、`interaction.talisman-*`、
   `performance.*` 三处若沿用旧文件，代码走内置默认值（24 小时冷却 / 上限 60 / 称呼"主人"），
   想配就对照仓库 `src/main/resources/config.yml` 手动补。
3. **`models` 名单、`nametag.scale/height` 需要重启**才生效（构造期快照），reload 提示里已写明。
4. **改名/改称呼的富文本闸门**：直接拒收 `<`、`&`、`§`（MiniMessage / 旧颜色码 / 段落符入口），
   长度按码点算（2-12 / 1-12）。名牌文字用玩家输入拼 MiniMessage，不拒会让人把组件注入名牌。
5. **魂符冷却在内存**，重启清零（23.4-2）。
6. **本轮控制台起服方式**：`exec 3<>cmd.fifo && java ... <&3` 在 Windows/cygwin 下会让
   `TerminalConsole` 启动时报一次 `IOException: 拒绝访问`，控制台输入不可用但 **RCON 正常**；
   不是插件问题，历史 P7 日志里没有这条是因为当时用的是有 TTY 的启动方式。

### 23.8 玩家须知（部署时贴到服务器公告）

- 手上拿**食物**右键女仆 = 喂食（涨好感）；拿**魂符**右键 = 召唤；空手右键 = 打开菜单。
- 战斗模式下她替你打架，**用的就是你塞给她的武器**——玩家型 NPC 走真实玩家逻辑，**武器会掉耐久**，
  养她请给「无法破坏」词条的装备，或别把家传宝剑给她。
- 农业/畜牧要她自己有工具和种子/饲料：锄头给主手，种子、小麦、空桶放背包存储格。
- 好感度到「伙伴」档（默认 40）才能 `/catmaid rename` 与 `/catmaid title`。
- 她倒下会变成铃铛进你背包，右键铃铛 + 材料（默认 1 颗钻石）就能唤醒；好感度会掉一截。
- 60 只女仆同服时的 TPS 属服务器实测项，见 23.3-4 的测试流程。

### 23.9 遗留（服主反馈，待补细节）

服主在 P8 收口时反馈"发现一些冲突"，但为不阻塞进度先收口，细节尚未整理进本文档。
下一位接手请先向服主确认冲突清单，再据实修订 23.3/23.4 或蓝图附录 A。

---

> **2026-11-13 追加**：本节的 9.1「主副手物品显示」已整段删除（服主验收：观感不可接受），
> `maid/HandItemDisplays.java`、`hand-item.*` 配置一并移除；24.3 的骨骼/CE 事实仍然有效，留作后续参考。
> 魂符部分已按车万女仆的语义重构，见第 25 节。

## 24. Phase 9 交接（手部物品 / 魂符收纳 / 依赖模式，2026-11-12）

> **收口状态：代码完成；服务端验收 3/5，客户端项未验证。**
> 测试环境：`paper-26.2-62.jar` + Citizens 2.0.44 build 4269 + BetterModel 3.7.0 + CraftEngine 26.9.2。
> 启动 `ERROR|SEVERE|Exception` 计数 **0**。无玩家在线时 Citizens 会把女仆全部 despawn（同 23.3-4），
> 因此"右键收纳/释放""手上物品看得见"这类交互只能等真人客户端。

### 24.1 阶段结论

三件事都落地了，但**其中一件的关键前提被实测推翻**：

1. **手部物品显示（9.1）**：BetterModel 的模型骨骼**不能**直接挂物品（原因见 24.3-1、24.3-2），
   最终走"独立 ItemDisplay + 几何偏移"；服务端已验证展示实体被创建、物品内容正确、跟着女仆移动。
2. **魂符收纳（9.2）**：`stored` 列 + 幂等迁移、收纳/释放、收纳态不生成实体不参与行为全部实测通过
   （用临时探针命令跑完 store → 重启 → release，探针已删）。
3. **依赖模式（9.3）**：`MaidItems` 重构成唯一工厂/识别器，两种 provider 都能启动；
   **craftengine 模式只验到启动**——测试服 CE 里没有 `catmaid:bell` / `catmaid:talisman` 物品定义，
   而 agent.md 6.1 禁止改 `plugins/CraftEngine/`，所以构造/识别路径仍是"JAR 已核、未实测"。

### 24.2 新增 / 改动文件

| 文件 | 改动 |
|---|---|
| `maid/HandItemDisplays.java` | 新增。主副手物品显示：先试模型手持物品骨，不行退独立 `ItemDisplay`；每 2 tick 错峰同步，空手/死亡/收纳/换模型自动刷新 |
| `util/MaidItems.java` | 重构成实例类（唯一工厂/识别器）：`bell` / `talisman` / `storedTalisman` / `isBell` / `isTalisman` / `isStoredTalisman` / `maidIdOf` / `consumeOne`，内部按 provider 分派 |
| `maid/Maid.java` | 新增 `stored` 字段与读写 |
| `data/Database.java` | `maids.stored` 列 + `migrateStoredColumn()`（PRAGMA table_info 判断后 ALTER，幂等） |
| `data/MaidRepository.java` | `stored` 读写（含 Snapshot record 增字段） |
| `maid/MaidManager.java` | 新增 `store` / `release` / `placeAt`；`despawnDeadNpcs` → `despawnHiddenNpcs`（铃铛态 + 收纳态）；载入时跳过收纳态 |
| `behavior/BehaviorManager.java` | 新增 `reset(maid)`：停导航 + 清当前模式状态 + 丢动画记忆（不动好感/台词计时） |
| `listener/MaidInteractListener.java` | 手持空魂符右键自己的女仆 = 收纳；手持"装着她"的魂符再右键 = 拒绝提示 |
| `listener/TalismanListener.java` | 装有女仆的魂符右键 = 释放（放回玩家身后、魂符变回空的）；铃铛复活改走 `placeAt` |
| `listener/MaidDamageListener.java`、`command/CatMaidCommand.java` | 改用 `MaidItems` 实例；`state` 三态（存活/铃铛/魂符中） |
| `gui/GuiManager.java` | 信息书在收纳态多一行「魂符中」 |
| `listener/PlayerConnectionListener.java` | 上线补挂跳过收纳态 |
| `CatMaidPlugin.java` | 创建 `MaidItems` 与 `HandItemDisplays`，启动打一行 provider 说明 |
| `config.yml` | 新增 `dependency.*`（provider / CE 物品 ID）与 `hand-item.*`（模式、骨名、独立显示几何参数） |
| `messages.yml` | 新增 `state-stored`、`gui.info-stored`、收纳/释放 9 个键 |
| `dialogue.yml` | 新增 `on_store` / `on_release` |

### 24.3 实测 / JAR 核对出来的 API 事实

1. **[已实测] BetterModel 的定位骨没有渲染载体**：`tracker.bone("RightHandLocator")` 能拿到
   `righthandlocator`（骨名全小写），但 `RenderedBone#getDisplay()` == **null**。
   往它上面 `itemStack(...)` 会"成功"但屏幕上什么都没有——和 17.9 的"缺动画静默失败"同一类坑。
   foxmaid 共 **110** 根骨，`righthand` / `lefthand` 有 display，但那是手部的**几何体**，
   用物品替换等于把手换成剑（松手还原不回来），不能当挂点。
   **对后续影响**：模型要在 Blockbench 里加一根"专门放手部物品"的骨（BM 里能拿到 display 的空骨），
   代码这边改 `hand-item.main-bone` 即可切换；在那之前一律走独立展示实体。
2. **[已实测] `RenderedBone#worldPosition()` / `worldRotation()` 不是世界坐标**：
   foxmaid 上四根手臂骨都返回 `(0, -0.01875, 0)`，与实体实际坐标（15.98, 72, 0.02）无关——
   是模型局部空间的静止值。想"用骨骼位置驱动外部显示实体"这条路不成立。
3. **[JAR 已核] 骨骼挂物品的完整调用链**（留给以后有手持物品骨的模型）：
   `BetterModel.registryOrNull(UUID)` → `EntityTrackerRegistry#first()` → `Tracker#bone(String)` →
   `RenderedBone#itemStack(Predicate<RenderedBone>, TransformedItemStack)`；
   物品包装用 `TransformedItemStack.of(new BukkitItemStack(ItemStack))`，清空用 `TransformedItemStack.empty()`。
   `Tracker#bones()` 可枚举全部 110 根骨（排查骨名靠它）。
4. **[实测] 独立 `ItemDisplay` 的表现**：`setItemDisplayTransform(THIRDPERSON_RIGHTHAND/LEFTHAND)`
   + `setRotation(实体 yaw/pitch)`，位置 = 实体脚下 + 1.2 格高、右偏 0.32、前偏 0.22。
   服务端核对到：展示实体带正确物品（`item` 数据是 `minecraft:bow`）、Y 恒为 `实体Y+1.2`、
   连续两次查询位置变化（女仆走动时它跟着走）。
5. **[JAR 已核] CraftEngine 物品 API**（CE 26.9.2）：
   `CraftEngineItems.byId(String)` → `BukkitItemDefinition#buildBukkitItem()` 得 `ItemStack`；
   `CraftEngineItems.getCustomItemId(ItemStack)` 得 `Key`（`toString()` = `命名空间:值`，可直接和配置串比较）。
   CE 同时带 `plugin.yml` 与 `paper-plugin.yml`，运行时与 Bukkit 插件 classloader 隔离 → **必须反射**
   （与 BetterModel 同一套理由）。
6. **[未实测] CE 物品写入 Bukkit PDC 后是否还保留 CE 身份**：测试服 CE 里没有任何自定义物品可用，
   无法构造最小样本。代码里写了自检 `warnIfLostCeIdentity`：一旦 CE 认不出来就打一条告警
   （功能不受影响——识别只靠 `catmaid:maid_id` / `catmaid:soul_talisman`）。**待有 CE 物品的环境实测**。
7. **[已实测] SQLite 加列**：`CREATE TABLE IF NOT EXISTS` 对老库不生效，必须 `PRAGMA table_info` 查完再
   `ALTER TABLE ... ADD COLUMN`；实测老库（P8 建的表）启动后列出现在末尾、值全 0，第二次启动不再改结构。
8. **[已实测] 收纳态重启后确实不生成实体**：`restoreNpcs` 跳过收纳态；Citizens 会按存档把她的身体
   恢复出来一次，`despawnHiddenNpcs` 立刻收起，日志 `已收起收纳态女仆 小铃 的残留实体（NPC 25）`。
   释放后 `placeAt` 重新 spawn + 重挂模型 + 补装备血量，日志出现 `装备同步 主手=BOW`。
   （注：释放时 `placeAt` 与 `MaidTrait#onSpawn` 各刷一次装备，日志会看到两行同样的"装备同步"，属正常。）

### 24.4 与蓝图的有意差异

1. **9.1 的 ① 骨骼方案实际未启用**：蓝图要求"优先 BM 骨骼、降级几何推导"，实测 ① 在当前模型上
   物理不可行（24.3-1/2）。代码保留了 `hand-item.mode: auto|bone|display` 与骨骼调用链，
   `auto` 每次都先试、失败了打一行原因再退独立显示；**现在实际生效的永远是 display**。
2. **刷新靠轮询而非 `Maid#applyEquipment()` 回调**：装备改动有 5 条来源（GUI 回写、召唤、恢复、
   死亡、换模型），逐个埋点必漏；改成每 2 tick 错峰比对（≤100ms 跟上，满足蓝图"换装 ≤1s"）。
3. **手部几何参数做成配置**（`hand-item.display-side/forward/height/scale`）：这条只能在客户端调，
   写死在代码里等于每调一次都要重新构建发版。
4. **`placeAt` 抽成 MaidManager 的公共方法**：铃铛复活与魂符释放的生成逻辑一模一样，
   各写一遍必然漏 `applyVitals`（20.9-A5 的"她只有 20 血"）。复活路径因此少了一段代码。
5. **`despawnDeadNpcs` 改名为 `despawnHiddenNpcs`**：铃铛态与收纳态都需要"别把身体恢复出来"，
   两件事合成一个兜底，日志按状态分别说明。

### 24.5 验收结果（服务端 2026-11-12 23:38–23:59）

| 蓝图 P9 验收项 | 结论 | 证据 |
|---|---|---|
| 主手剑 / 副手食物在模型手上可见（客户端） | ⚠ 客户端未验证 | 服务端确认展示实体被创建、`item` 数据正确、跟随女仆移动（24.3-4）；观感（位置/朝向/大小）必须真人看 |
| 换装 ≤1s 刷新、空手无显示 | ⚠ 部分 | 轮询周期 2 tick；空手分支代码为"移除展示实体"，未在客户端核过观感 |
| 收纳 → 重启 → 释放；位置/好感/背包/模式保留 | ✅（服务端） | 探针：`store` 后 `spawned=true→false`、DB `stored=1`；重启日志"已收起收纳态…（NPC 25）"且不生成实体；`release` 后 `spawned=true`、`装备同步 主手=BOW`；DB 回 `stored=0`，好感 34.4 / 模式 IDLE 未变 |
| stored 女仆不参与行为 | ✅（服务端） | 收纳态 `maid.entity()==null`，`BehaviorManager` 首道守卫直接返回 |
| 死亡铃铛态收纳被拒并有提示 | ⚠ 客户端未验证 | `store-dead` 分支已写；铃铛态没有实体，正常操作路径根本点不到——只能靠客户端构造极端场景 |
| `on_store` / `on_release` 台词出现 | ⚠ 客户端未验证 | 台词键已加，`DialogueManager` 只发给在线主人，服务端无玩家时静默丢弃 |
| provider=vanilla 全链路 | ✅（部分） | 启动 `provider=vanilla（铃铛 BELL，魂符 AMETHYST_SHARD）`；收纳/释放链路如上一行。喂食/死亡/复活仍沿用 P4 已验逻辑，本轮只换了物品工厂 |
| provider=craftengine 全链路 | ⚠ 只验启动 | 启动 `provider=craftengine（…CE 缺失时回落 BELL / AMETHYST_SHARD）`，0 异常；**构造/识别未实测**（CE 无物品定义，见 24.3-6） |
| 切换 provider 后旧铃铛 / 魂符仍可识别 | ⚠ 未实测 | 识别只看 `catmaid:maid_id` / `catmaid:soul_talisman` 两个 PDC 键，与材质/CE ID 无关；逻辑成立但没跑过真物品 |

### 24.6 冲突处理记录（agent.md 第 9 节格式）

```
[CONFLICT] 蓝图位置: Phase 9-9.1「① BetterModel 手部骨骼 [待验证]；② 降级：按实体朝向几何推导」
现实情况: 模型里能拿到的两根 hand 骨，定位骨(RightHandLocator)没有渲染载体、挂物品不可见；
         手骨(RightHand)有渲染载体但那是手的几何体，替换等于把手换成物品且无法还原；
         RenderedBone#worldPosition() 返回模型局部坐标，也做不了外部挂点
采用方案: 一律走 ②（独立 ItemDisplay + 几何偏移），① 保留为配置开关等模型补手持物品骨  理由: ①在当前资产上不可实现，硬做会把手部模型弄坏
```

```
[CONFLICT] 蓝图位置: Phase 9-9.3「craftengine 模式物品构造走 CE 公开 API [待验证]」
现实情况: API 已从 CE 26.9.2 jar 核对（byId + buildBukkitItem + getCustomItemId），但测试服 CE 里
         没有任何 catmaid 物品定义，而 agent.md 6.1 禁止改 plugins/CraftEngine/ → 无法构造样本实测
采用方案: 按核对过的签名实现，运行时自检并在失败时回落原版材质（带告警）  理由: 不乱改 CE 配置的前提下这是唯一可做的验证边界
```

### 24.7 已知限制 / 部署注意

1. **升级到 P9 必做一次**：删 `plugins/CatMaid/messages.yml`（或手动补键），否则「魂符中」「收纳/释放」
   这些新文案不会进旧文件（`mergeMissingKeys` 只补缺失键——这次新增的都是新键，所以**其实会自动补上**；
   但 `help` 段仍是旧的，P10-10.2 会重排命令）。
2. **`config.yml` 不会自动长出新键**：`dependency.*` 与 `hand-item.*` 若沿用旧文件，代码走内置默认值
   （vanilla / auto / RightHandLocator / 0.32·0.22·1.20·0.55）。想调手感就对照仓库模板手抄这几段。
3. **手部物品的观感只能客户端调**：位置不对就改 `hand-item.display-side/forward/height`，
   改完 `/catmaid admin reload` 不生效——这三个值在 `HandItemDisplays` 构造时读取，**要重启**。
4. **收纳态的凭证只有一张**：魂符丢了她仍在库里（`stored=1`），补发渠道是 P10-10.4 的
   `/catmaid bell` 那一套思路，本轮没做。
5. **收纳态仍占 `max-per-player` 名额**（蓝图明确要求"她依旧是你的女仆"），主人满额时只能先释放。
6. **craftengine 模式的物品 ID 要在 CE 侧先定义**：`catmaid:bell` / `catmaid:talisman` 两个 ID
   没定义时插件会自动回落原版材质（日志一条 WARNING），功能不受影响、只是外观不是 CE 的。
7. **切换 provider 不用重发资源包**：铃铛/魂符的身份是 PDC，换模式只是"新建的物品长什么样"变了。

### 24.8 玩家须知补充（贴公告时追加）

- 手上拿**空魂符**右键自己的女仆 = 把她收进魂符（随身带着走）；拿**装有她的魂符**右键 = 放出来。
- 收进魂符的女仆不会自己干活、也不占世界里的位置，但**仍然算你的女仆名额**。
- 她倒下变成铃铛时不能收纳——先把铃铛唤醒。

---

## 25. 魂符：按车万女仆的 ItemSmartSlab 做成三态（2026-11-13）

> **收口状态：完成（2026-11-13）。服务端启动 0 异常，客户端链路由服主实测通过。**
> 提交：`ad1dec9`（三态重构）、`41efb50`（文档）、`dbf1475`（删手部物品显示）。

### 25.1 起因

第一版让空魂符同时承担"召唤凭证"和"收纳容器"，靠右键对象分流；第二版拆成"魂符 + 女仆契约"两件物品。
两版都不对——**车万女仆的魂符本来就是一件物品的三种状态**，召唤用的那一态用掉后会变回空魂符。
拆成两件物品的后果：召唤完手里没有符，玩家还得另外领一张才能收纳，且与 TLM 玩家的直觉冲突。

### 25.2 TLM 源码事实（1.20 分支，`TartaricAcid/TouhouLittleMaid`）

**一个类、三个 Type、三个物品 ID，但共用一个显示名**（`init/InitItems.java`、`item/ItemSmartSlab.java`）：

```java
public enum Type { INIT, EMPTY, HAS_MAID }
// smart_slab_init / smart_slab_empty / smart_slab_has_maid
@Override public String getDescriptionId() { return "item.touhou_little_maid.smart_slab"; }
```

**三条路径全部是"原地替换手里物品"，不是消耗**：

| 路径 | 源码 | 关键一行 |
|---|---|---|
| 未唤醒召唤 | `ItemSmartSlab#spawnNewMaid` | `player.setItemInHand(hand, SMART_SLAB_EMPTY.getDefaultInstance())` |
| 装着女仆释放 | `ItemSmartSlab#spawnFromStore` | `player.setItemInHand(hand, SMART_SLAB_EMPTY.getDefaultInstance())` |
| 空符收纳女仆 | `event/maid/SlabClickEvent#onInteract` | `player.setItemInHand(MAIN_HAND, maidSmartSlab.getDefaultInstance())` |

配套细节（都照抄了）：

1. **只有 EMPTY 能收纳**：`SlabClickEvent` 里写死 `stack.getItem() == emptySmartSlab`，
   INIT / HAS_MAID 对着女仆不响应——所以不存在"靠右键对象猜意图"的歧义。
2. **20 tick 冷却**：三条路径替换后都 `player.getCooldowns().addCooldown(..., 20)`。
   我们用原版 `Player#setCooldown(Material, 20)`，客户端物品上有灰条。
3. **名额校验**：`cap.canAdd() || creative`，满了提示 `can_not_add`，**不消耗、不变空**。
4. **主人锁定**：INIT 可带 `InitMaidOwner`（开局赠送时写入），非本人右键 `InteractionResult.FAIL` + 提示。
   我们用 PDC `soul_init_owner`。
5. **落点校验**：`util/PlaceHelper#notSuitableForPlaceMaid` 只看点击方块上方**两格**的碰撞体积
   （`getCollisionShape().isEmpty()`），不查脚下支撑、不查液体（水没有碰撞体积，所以能放水面）。
   且 `useOn` 里要求 `clickedFace == Direction.UP`——对着侧面点不生效。
6. **发光**：`isFoil()` 返回 `type != EMPTY`，非空态自带附魔光。我们用 UNBREAKING I + `HIDE_ENCHANTS`。
7. **HAS_MAID 不能塞进容器**（`canFitInsideContainerItems()` 返回 false）——Bukkit 没有对应钩子，未实现。

### 25.3 我们的实现（一物三态）

材质统一 `AMETHYST_SHARD`（可配），状态靠 PDC：

| 状态 | PDC | 名字 | 右键女仆 | 右键方块顶面 | 右键空气 |
|---|---|---|---|---|---|
| 未唤醒 | `soul_talisman` + `soul_init`(+`soul_init_owner`) | 未唤醒的魂符（发光） | 提示先唤醒 | 召唤 → **变空魂符** | 落在脚下召唤 → 变空魂符 |
| 空 | `soul_talisman` | 猫娘魂符 | 收纳 → **变"装着她"** | 只提示 | 提示「对着女仆用」 |
| 装着 | `soul_talisman` + `maid_id` | 装有 X 的魂符（发光） | 提示已装着她 | 释放 → **变空魂符** | 落在脚下释放 → 变空魂符 |

**状态迁移全靠 `MaidItems#replaceHeld`**（对应 TLM 的 `setItemInHand`）：数量守恒、不掉地、背包满也不会丢符。
手里是旧版堆叠（>1）时只扣一张、把新状态塞背包。

**获取**：

- `/catmaid talisman [数量]` → 空魂符（容器）
- `/catmaid soul [数量]` → 未唤醒魂符（召唤），写入领取者 UUID 做锁定
- 首次进服自动发 1 张未唤醒魂符（PDC `init_talisman_given` 去重；
  配置 `interaction.grant-init-talisman-on-first-join`）——对应 TLM 的开局赠送

**与 TLM 的有意差异**：

1. **额外支持右键空气**：TLM 只认 `useOn`（必须对着方块顶面）。我们允许右键空气把女仆放在自己脚下，
   否则玩家站在平地上往天上点会完全没反应。落点校验规则不变。
2. **冷却按材质记**：TLM 按物品（INIT/EMPTY/HAS_MAID 各一份），我们三态同材质，共享 1 秒冷却。
   实际体验差异只在"刚召唤完立刻收纳"会多等 1 秒。
3. **没有实现"装着女仆的魂符不能进容器"**：Bukkit 没有 `canFitInsideContainerItems` 对应的钩子。

### 25.4 改动文件

| 文件 | 改动 |
|---|---|
| `util/MaidItems.java` | 删 contract 三件套；新增 `initTalisman(owner)` / `isInitTalisman` / `initOwnerOf` / `replaceHeld` / `glow` / `talismanMaterial()`；`storedTalisman` 加发光 |
| `listener/TalismanListener.java` | 分支改为 铃铛→装着→未唤醒→空（提示）；召唤/释放都走"替换成空魂符"；落点改为 TLM 规则（两格碰撞 + 只认顶面）；冷却改原版 `setCooldown` |
| `listener/MaidInteractListener.java` | 收纳改为 `replaceHeld`（不再 consume + addItem）；未唤醒态对着女仆给提示 |
| `listener/PlayerConnectionListener.java` | 开局赠送改发未唤醒魂符（带 owner 锁定），PDC 键 `init_talisman_given` |
| `command/CatMaidCommand.java` | 新增 `/catmaid soul [数量]`（独立冷却 `soulReadyAt`），删 `/catmaid contract` |
| `config.yml` | `interaction.soul-enabled/soul-cooldown-hours/grant-init-talisman-on-first-join`；删 `contract-*` 与 `dependency.craftengine.contract-id` |
| `messages.yml` | 删 `contract-*`；新增 `init-talisman-*`（6 个）、`soul-*`（3 个）、`summon-blocked`、`place-only-on-top` |

### 25.5 冲突处理记录（agent.md 第 9 节格式）

```
[CONFLICT] 蓝图位置: Phase 9-9.2「空魂符右键（不对女仆）→ 维持 P4 原语义（召唤新女仆）」
现实情况: ①同一物品同一按键承载消耗品与可复用容器两种互斥语义，靠右键对象分流会误触；
         ②拆成"魂符 + 契约"两件物品后，召唤完手里没有魂符，与 TLM 玩家直觉冲突；
         ③查 TLM 源码（ItemSmartSlab / SlabClickEvent）确认其做法是"一物三态 + 原地替换"，
         召唤用掉的那一态会变成空魂符，整条链数量守恒。
采用方案: 魂符做成三态（未唤醒 / 空 / 装着），三态之间用 replaceHeld 原地替换；/catmaid soul 发放未唤醒态
         理由: 与 TLM 行为完全一致；一件物品一件事变成"一个状态一件事"，玩家不需要多领一张符
```

### 25.6 验收结果

| 项 | 结论 | 证据 |
|---|---|---|
| 启动无异常 | ✅ | `凭证物品 provider=vanilla（铃铛 BELL，魂符 AMETHYST_SHARD）`；`ERROR/SEVERE/Exception` 0 |
| 命令注册 | ✅ | 控制台 `catmaid soul` → 「该命令只能由玩家执行」；`catmaid contract` 已无输出（子命令已删） |
| 文案合并 | ✅ | 删测试服 `messages.yml` 后重启，`init-talisman-name` / `soul-received` / `place-only-on-top` 全部自动补入 |
| 未唤醒魂符召唤 | ✅ 客户端 | 服主实测：`/catmaid soul` 领符 → 对地右键召出女仆，手里变回空魂符 |
| 收纳（空 → 装着） | ✅ 客户端 | `/catmaid admin list` 出现「魂符中」状态；DB `stored=1` |
| 三态串联（召唤→收纳→放出） | ✅ 客户端 | 服主 2026-11-13 12:18 确认通过；同一张符数量不变 |
| 落点校验 / 冷却 / 台词 | ✅ 客户端 | 服主确认通过（未逐项留证） |
| provider=craftengine 全链路 | ⚠ 未测 | CE 无 catmaid 物品定义，仍是"JAR 已核、未实测"（24.3-6） |
| 死亡铃铛态收纳被拒 | ⚠ 未测 | 铃铛态无实体，正常路径点不到（24.5 同款限制） |

### 25.7 部署注意

1. **上一轮发出去的「女仆契约」会变成普通命名牌**：`maid_contract` 识别已删除（插件未分发，不做兼容）。
2. **旧魂符照常可用**：状态判定是"有无 `soul_init` / `maid_id`"，旧空魂符 = 空态、旧收纳符 = 装着态。
3. **`config.yml` 不会自动长出新键**：`soul-*` 与 `grant-init-talisman-on-first-join` 沿用旧文件时走默认值
   （开启 / 24 小时 / 首次赠送开启）。
4. **落点比之前宽松**：现在允许水面、允许脚下没有支撑（TLM 同款）。想收紧就改
   `TalismanListener#canPlaceMaidAt`，但别再引入"必须站在实心方块上"的限制——会把台阶边也挡掉。

## 26. Phase 10 交接（修复轮：命令前缀 / 多女仆定向 / 原版资源包 / 铃铛绑定，2026-11-13）

### 26.1 阶段结论

四个修复项全部落码（10.1 `cabc44b`、10.2 `5039601`、10.3 `299fbe6`、10.4 `1421a9c`），
外加一个启动期关键修复（sqlite relocate，`795ffc5`，见 26.3）。服务端启动验证 0 ERROR，
RCON 实测命令注册与别名移除；其余验收项（客户端观感、10.5 回归）**待真人客户端**。

### 26.2 新增 / 改动文件

| 提交 | 文件 | 内容 |
|---|---|---|
| cabc44b | `plugin.yml` | 删除 aliases，唯一入口 `/catmaid` |
| 5039601 | `CatMaidCommand.java`、`messages.yml` | `resolveMaid(Player, String)` 名字/就近双策略、`optionalArg`、rename 撞名拒绝、tab 补全女仆名、`info-target-hint` |
| 299fbe6 | `docs/api-facts.md`、testserver | 资源包走原版单包裁决：CE 从 `testserver/plugins/` 移至 `plugins-disabled/`；4.0 记录部署事实 |
| 1421a9c | `CatMaidCommand`、`CatMaidPlugin`、`Database`、`MaidRepository`、`Maid`、`MaidDamageListener`、`config.yml`、`messages.yml` | `/catmaid bell [名字]`（收 revive-cost 同额材料；`death.bell-expire-days` 过期免费）、`/catmaid release [名字]`（聊天栏 30s 内再输一次名字二次确认）；`death_at` 列幂等迁移 |
| 795ffc5 | `build.gradle.kts`、`Database.java` | **去掉 sqlite-jdbc relocate**（见 26.3），驱动类回到 `org.sqlite.JDBC` |
| （26.1 后增补） | `CatMaidCommand`、`config.yml`、`messages.yml` | **命令级管理员开关**（服主 2026-11-13 13:04 需求）：`commands.admin-only.<命令>` 每条玩家侧命令单独设 true/false，true 时只认 `catmaid.admin`，普通玩家收到 `admin-only-command` 拒绝提示；tab 补全同步隐藏；config 现场读、`admin reload` 即时生效。admin 子树本身已挂 catmaid.admin，不进这张表 |

### 26.3 [CONFLICT] 重大冲突：sqlite-jdbc 的 shade relocate 会破坏原生库绑定

```
[CONFLICT] 蓝图位置: agent.md 2.1 / blueprint 0.4（"sqlite-jdbc 须 relocate 到 dev.catmaid.libs.sqlite"）
现实情况: sqlite-jdbc 3.50.3.0 的原生库（Windows/Linux/macOS 的 sqlitejdbc 动态库）在加载时按
          硬编码类名 "org/sqlite/core/NativeDB" 动态注册 natives，且导出表只有 Java_org_sqlite_*
          符号（DLL 内 Java_dev_catmaid_* 计数为 0，已用二进制检查确认）。relocate 后：
          纯 JVM 探针 = NoClassDefFoundError: org/sqlite/core/NativeDB；
          测试服启动 = dev.catmaid.libs.sqlite.core.NativeDB._open_utf8 UnsatisfiedLinkError，
          插件自禁用（2026-11-13 实测）。此前 P1–P9 能跑是 classloader 全局共享让别的插件的
          org.sqlite 类"顶包"成功的巧合，今天环境一变即失效。
采用方案: 去掉 relocate（build.gradle.kts 注释 + Database.java 驱动类同步改回 org.sqlite.JDBC）。
理由: 最小改动保守实现——Paper 的插件类加载器本身就把各插件的类隔离了，不 relocate 不会与
      Citizens 自带的 sqlite 相互污染；relocate 在这个依赖上是原理性不可行，没有任何
      "保留 relocate + 补丁"的低成本做法（改 DLL 导出表/类名串都需重建 PE 结构）。
```

**教训**：对带原生库的依赖（sqlite-jdbc、SQLite natives、任何 JNI 绑定）做 shade relocate
前必须先用最小探针验证 native 加载，不能只看 Java 编译/启动。

### 26.4 与蓝图的有意差异

1. **`death.bell-expire-days` 的"免材料补发一次"口径**：实现为"倒下超过 N 天后，补发不收材料"
   （每次过期补发都免费）。蓝图原文"免费补发一次"若按"只有第一次免费"理解需要额外的"已免过"标记
   位，为默认关闭的可选增强不值得加状态——按保守口径实现，想收紧再改 `CatMaidCommand#bellExpired`。
2. **`/catmaid release` 与 P9 魂符释放的文案键**：魂符释放已占用 `release-*` 键（TalismanListener），
   命令"送走"用 `sendaway-*` 前缀，避免共用一个键改文案时互相牵连。
3. **release 的目标解析**：不带名字时沿用"就近/唯一"策略（任何状态都可被选中送走），带名字走 10.2 的
   按名定向；蓝图写 `<名字>`，实现为 `[名字]`（单人时少打一个参数）。
4. **bell 的默认目标**：不带名字时只在铃铛态女仆里解析（就近策略对没有实体的她无意义），多于一只
   必须指定名字。

### 26.5 验收结果（服务端实测 2026-11-13 12:55）

- [x] 测试服启动 0 ERROR：CatMaid 正常 enable、载入 2 只女仆、收纳态残留实体正常收起
      （修复 relocate 后，日志 p10-boot.log）
- [x] `death_at` 列幂等迁移落库：`PRAGMA table_info(maids)` 含 `death_at`，存量 2 只数据完好
- [x] `/maid` 从控制台执行 = Unknown command（10.1）；`/catmaid admin list` 正常
- [x] messages.yml 删除重建后 `bell` / `release` 帮助行出现（23.3-2 的升级姿势照跑了一遍）
- [x] `gradlew shadowJar` 通过，产物部署 `testserver/plugins/CatMaid-0.1.0.jar`
- [ ] **待客户端**：`/catmaid:xxx` 前缀入口消失、双女仆按名定向 + tab 补全、rename 撞名拒绝、
      进服只弹一次资源包下载、全灭丢铃铛补发闭环、release 聊天确认流、P8 挂起项（10.5）

### 26.6 部署注意

0. **`config.yml` 的 `commands.admin-only` 段不会自动长进旧文件**（25.7-3 同款）：升级时手动把该段
   拷进生产服 config（缺段 = 全部 false = 行为与旧版一致，不拷也能跑）。测试服已手动补齐。
   实测：控制台（有 catmaid.admin）能过闸门并落到"该命令只能由玩家执行"；普通玩家被拒的画面
   需要真人客户端（admin reload 后把一条设 true 再敲即可验）。

1. **生产服升级到 P10 前先删一次 `plugins/CatMaid/messages.yml`**（23.3-2）：否则帮助文案停留在
   旧命令表，且 10.4 的 bell/release 提示键不会生效（新键会补、`help` 列表键不会更新）。
2. **`dependency.provider` 保持 `vanilla`**；资源包链路只有原版 `server.properties` 一个下发源，
   若服务器另有 CE 内容且要保留 CE，两次下载依旧（api-facts 4.0 的取舍）。
3. **`testserver/plugins-disabled/` 是 CE 的可逆备份**，不要当成垃圾清掉；要回 CE 路线直接移回 plugins。
4. sqlite 不再 relocate 后，**其他插件若也 shade 了 org.sqlite 且共享类加载空间，可能出现同名类**——
   Paper 的隔离机制下不会发生；若将来引入把库装进全局 classpath 的加载器（如各类 libby 动态装载），
   需重新评估（26.3 的探针手法直接可用）。

## 27. 27 个手工转换模型批量启用（2026-11-13）

### 27.1 控制台/RCON 不能挂模型（重要，别再试）

1. **RCON 下 `npc create` 必 NPE**：Citizens 创建后要在"发送者位置"生成实体（`NPCCommands.lambda$create$1`），
   RCON 发送者无位置 → `Cannot invoke "Location.getChunk()" because "loc" is null`，NPC 创建即作废
   （日志 6 条 Task 异常）。带 `--at` 也只创建了空壳（未生成、无 location/model trait）。
2. **RCON 下 `npc select` + `npc model` 组合不可用**：选中状态按 CommandSender 对象存储，
   Paper 给每条 RCON 命令新建 sender 对象，同一连接内跨命令即丢 → `/npc model` 必报"必须选择一个NPC"。
3. 结论：**挂模型的唯一可靠途径是游戏内操作**（或先核实 `bm spawn` 的位置参数格式——cloud LocationParser，
   本次未核实完，试错式传参全被拒）。
4. Phase 0 的测试 NPC（测试/模型测试/骑士）当时是在游戏内创建与 select 的，文档此前未记录这个前提。

### 27.2 BetterModel 3.6.0 的资源包产物是裸名 build.zip

启动时重写裸名 `build.zip`（3.7.0 才是编号递增 build<N>.zip，目录里 build2~8 是历史遗留）。
`tools/pack_host.py` 的 `latest_build()` 原来只认编号、会错拿旧 build8，已补丁：
裸名比最大编号新时取裸名。sync 后 sha1 写回 server.properties，**仍需重启服务端才对客户端生效**
（server.properties 不热重载，8.3 的规矩）。

### 27.3 本轮部署与验证记录

1. `model_src/` 27 个 bbmodel（含注入动画）已复制到 `plugins/BetterModel/models/`；
   winefox_blue 目录内的文件名为 winefox.bbmodel，部署时已改名 `winefox_blue.bbmodel` 防重名。
2. config.yml `models` 名单扩为 27 个（GUI 模型选择分页读取此名单，重启生效）；
   `max-per-player` 2 → 20（服主要求）。
3. 启动验证：两次启动日志均无 `Unable to load this model`，27/27 模型解析通过；
   新 catmaid-pack.zip 8.17MB/18157 项，sha1 `16d3662b...`，HTTP 8123 可下载。
4. **待客户端实测**：贴图正确性、挂实体、动画观感（重点：四肢摆动方向，注入报告 §7-A 的轴向假设）。
5. 清理：误创建的空壳 NPC 87（mt_foxmaid）已 `npc remove 87` 永久删除；原有女仆（85 小铃/86 汐音）未动。

### 27.4 首轮客户端实测反馈与修复（2026-11-13 15:57–16:07）

1. **[实测] 三个症状**：坐姿扭成一团、走路单腿平移、切换模型后多模型叠加。
2. **轴向翻转已启用**（14.5 预判命中）：`inject_tlm_anims.py` 新增 `convert_axis()`——
   rotation x/y 取反、position x 取反、scale 不变（TACZ 规则）。27/27 重注入，静态校验 0 悬空。
   静态姿势（idle/sit）同样受此规则约束，观感待复测。
3. **[字节码] ModelTrait.setRenderer 切换泄漏**：新渲染器非空分支直接 `ModelRenderer.create()`
   而**不调用** `registryOrNull(uuid).close()`（close 只在渲染器为 null 分支）；
   `ModelRenderer.create()` 内部也不清理 → A→B 切换残留 A 的全部部件（客户端看到模型叠加）。
   **CatMaid 修复**（ModelApplier.apply）：挂新模型前先 `setRenderer(null)` 走 close 分支。
   首次挂载时 registry 不存在，该调用为空操作，无害。
4. **[事实] 动画不进资源包**：BetterModel 服务端驱动骨骼变换（显示实体 metadata），
   bbmodel 动画改动不改变 build.zip 内容与 sha1——改动画只需重启服务端，客户端无需重下包。
5. 修复后重启验证：日志干净，CatMaid 0.1.0 正常加载。

### 27.5 动画"冻结在第 0 帧"的真正根因（2026-11-13 16:15）

27.4 的轴向翻转方向是对的（伸右腿变伸左腿），但动画仍在第 0 帧。复查注入器发现结构性 bug：
`inject_tlm_anims.py` 把一个通道的**全部时间点塞进同一个 keyframe**（keyframe 层 `"time": 0` 写死），
而 BetterModel 的 `ModelDatapoint` record **没有 time 字段**（数据点内的 time 被忽略）→
整条动画坍缩成 t=0 单帧。完美解释全部现象：翻转前 t=0 右腿在前、翻转后左腿在前；
foxmaid 的 sit/idle 是纯静态单帧不受影响；winefox_blue 的 sit 是多帧动画坍缩成乱值（"缩在一起"）。
修复：每个时间点独立 keyframe、time 写 keyframe 层（与 bbmodel_anim.py 实测可播的结构一致）。
校验：27/27 重注入、0 悬空、foxmaid walk LeftLeg 25 帧铺满 0→1s。

### 27.6 sit 沉降基线钳制（2026-11-13 16:45）

1. **[实测] wedding/winefox 坐姿陷入地里**：其 sit（公共 maid.animation.json）链沉降
   Root(-14)+AllBody(-10.5) ≈ -24.5px，比实测正常的 foxmaid（-15.5px）深 9px。
2. **离线包围盒路线作废**：多轴旋转 + 静止旋转（如 wedding 的 legBand* 实为袖带、挂在
   手臂链上）使几何正算不可靠——foxmaid 的木屐被算到 -26px（实际 ≈0）。教训同 14.4b：
   自检数学必须先拿已知正确样本（foxmaid）校准，校准不过就别用。
3. **采用方案**（agent.md 9 优先级 3，最小改动）：inject_tlm_anims.py 新增
   clamp_sit_ground——取腿骨链 position.y 总沉降，超过基线 SIT_DROP_BASELINE=-16px
   （foxmaid 实测值 15.5 + 0.5 容差）就把根侧 position 骨骼（通常 Root）抬高差值。
   核心骨骼名匹配 leg/foot/clog/shoe 且排除 band/arm/sleeve/hand（wedding 的
   legBand* 是袖带）。foxmaid 沉降 -15.5 ≥ -16，不受影响。
4. 27 个模型重注入：19 个被钳制（+2.5 ~ +13.5px 不等），foxmaid 未动。数据面无解的
   模型（链上无合格 position 骨骼）会打印"跳过"并在日志可见。
5. 复测重点：wedding/winefox 家族坐姿是否贴地不悬浮；若仍差，调 SIT_DROP_BASELINE。

---

## 28. Phase 11 交接（玩家级猫娘可见性开关 /catmaid hide，2026-11-14）

### 28.1 骨骼枚举反射链 [JAR 已核，2026-11-14]

对 `testserver/plugins/BetterModel.jar`（BetterModel 3.6.0，dev build 541）javap 复核，
`MaidVisibilityManager` 的反射句柄全部按以下真实签名建立：

```java
kr.toxicity.model.api.BetterModel
  static EntityTrackerRegistry registryOrNull(java.util.UUID);      // UUID / PlatformEntity / BaseEntity 三个重载仍在
kr.toxicity.model.api.tracker.EntityTrackerRegistry
  EntityTracker first();                                            // 返回 EntityTracker，不是 Tracker
kr.toxicity.model.api.tracker.Tracker                               // EntityTracker extends Tracker
  java.util.Collection<kr.toxicity.model.api.bone.RenderedBone> bones();
kr.toxicity.model.api.bone.RenderedBone
  kr.toxicity.model.api.nms.ModelDisplay getDisplay();              // 部分骨（定位骨）返回 null，要跳过
kr.toxicity.model.api.nms.ModelDisplay extends kr.toxicity.model.api.nms.Identifiable
  int id();
  java.util.UUID uuid();                                            // 骨骼 display 实体的服务端 UUID
```

**关键事实**：`ModelDisplay` 继承 `Identifiable`，`uuid()` 直接给出骨骼 display 实体
（服务端真实存在的展示实体，blueprint 11.0-1）的 UUID → `Bukkit.getEntity(uuid)` 可拿到
`org.bukkit.entity.Entity` → 直接 `Player#hideEntity(Plugin, entity)`。无需解析 BM 内部类。

### 28.2 实现要点（P11 落码）

1. `Player#hideEntity(Plugin, Entity)` / `showEntity` 是 Paper API，逐玩家逐实体、幂等；
   **hide 状态不跨 relog**，重新施加的四个挂点（blueprint 11.2 表）全部落码：
   - `PlayerConnectionListener.onJoin` 尾部（opt-out 则 applyAllFor）；
   - `MaidManager.onEntityReady` 尾部（恢复/召唤/上线补挂/复活四条路径）；
   - `MaidTrait#onSpawn` **改为委托 `MaidManager.onEntityReady`**——区块重载 respawn 只触发
     onSpawn、不走那四条路径，原先只在这里 applyVitals，现在委托后可见性也覆盖（旧行为在
     插件未就绪时保留为 fallback）；
   - `NameTagManager` 名牌（重）建成功处回调 `setOnSpawnCallback`（CatMaidPlugin 接线）；
   - 换模型：`GuiManager` 模型页 `modelApplier.apply` 返回 true 后补调 `maidManager.onEntityReady`。
2. 内存对账集 `maidId → playerUuid → 已 hide 实体 UUID 集`：只对集合里没有的 UUID 调
   hideEntity，防重复调用；sweep（20 tick 一轮、按 npcId % 5 错峰，每只每 100 tick 一次）
   只做"对账补漏"，不新增每 tick 任务。
3. DB：`player_prefs(uuid TEXT PRIMARY KEY, hide_models INTEGER NOT NULL DEFAULT 0)`；
   `loadHidePrefs()` 启动同步读（executor 无任务，同 loadAll 前提）、`setHideModels()`
   走既有单线程 executor 异步 upsert。
4. 命令 `hide`：无 catmaid.use 门槛（不想看猫娘的玩家未必养女仆），仅受
   `commands.admin-only.hide`（默认 false）闸门；文案键 `hide-on` / `hide-off`。
   messages.yml 升级注意：新键会自动补齐，但 **help 段不会被更新**（23.3-2 既有行为），
   服主要手动把 `/catmaid hide` 那行加进 help。

### 28.3 待实测（需真人双客户端，A=opt-out / B=对照）

- A `/catmaid hide` 后骨骼 display + 名牌整体消失、B 不受影响；再输一次恢复。
- 换模型 / 走动 / 区块重载 respawn 期间 A 始终看不见（不漏 spawn 新骨实体）。
- **11.0-5 实测项**：opt-out 玩家是否看到 NPC 底层实体（裸玩家皮肤）——代码已把底层实体
  一并 hideEntity（幂等无害），预期看不到；若实测仍可见则说明 hideEntity 对 BM 的
  packet 通路无效，需要回填事实。
- relog 后偏好保持（player_prefs 落库、进服即看不见）。
- 30+ 女仆同屏 opt-out 玩家端性能（hide 后 BM 不再对其下发逐帧包）。
