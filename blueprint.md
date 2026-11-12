# 猫娘女仆插件（CatMaid）分阶段实施蓝图

> **阅读对象**：负责编码的 AI（DeepSeek）。本文档是唯一事实来源（Single Source of Truth）。
> 你必须先通读本文档和 `agent.md`，再开始写代码。所有类名、方法签名、数据结构以本文档为准；与实际 API 冲突时按 `agent.md` 第 9 节"冲突处理协议"执行。

## 0. 项目总览

### 0.1 目标与环境

| 项 | 值 |
|---|---|
| 插件名 | CatMaid（中文：猫娘女仆） |
| 服务器 | Paper **26.2**，Java **25** |
| 实现方式 | **纯插件**。禁止 mod、禁止要求玩家客户端安装任何东西 |
| 玩家侧 | 强制接受服务器资源包（已有，无需处理下发逻辑） |
| 依赖插件 | Citizens2（硬依赖）、BetterModel（硬依赖，模型渲染）、CraftEngine（软依赖，仅共存不调用其 API） |
| 服务器性质 | 非商用免费服 |
| 体验核心 | **"养了一只属于自己的猫娘女仆"**——主人绑定、好感度养成、台词互动。所有设计决策优先服务这一点 |

### 0.2 需求范围

**做**：女仆实体（东方模型）、战斗模式、农业模式、畜牧模式、背包/装备管理 GUI、好感度养成、台词系统、死亡/复活。
**不做**：装备在模型上可见（明确排除）、AI 聊天、饰品、P 点祭坛、小游戏。

### 0.3 架构图（文字版）

```
CatMaid 插件（自研）
 ├─ 领域层   Maid / MaidManager / MaidTrait(Citizens Trait, 纯数据标记) / ModelAnimator(三姿态动画状态机)
 ├─ 行为层   BehaviorManager → Idle/Follow/Combat/Farm/Ranch 五个 BehaviorMode
 │           （tick 由 CatMaidPlugin 全局调度器每 tick 推行——玩家型 NPC 的 Trait#run()
 │             永不会被 Citizens 调用 [已实测]，见附录 A）
 ├─ 交互层   GuiManager(主菜单/背包/装备/模式/模型 5个GUI) + 台词/喂食/召唤/复活监听
 ├─ 数据层   Database(SQLite) → maids / maid_items / maid_stats 三表
 └─ 接入层   Citizens API(NPC实体/导航/点击事件) + BetterModel API(模型挂载)

外部依赖（只调用，不修改其内部）：
 Citizens2   → 玩家型NPC、Navigator 寻路、NPCRightClickEvent
 BetterModel → ModelTrait 挂 bbmodel 模型 + 动画（双包分离：模型包走原版 resource-pack 强制下发；
             动画由服务端逐帧驱动、不进资源包——改 bbmodel/调动画只需重启，不必重发包，见附录 A）
 CraftEngine → 无代码耦合，仅资源包层面共存（Phase 0 验证）
```

### 0.4 项目构建配置（照抄即可）

`settings.gradle.kts`：
```kotlin
rootProject.name = "CatMaid"
```

`build.gradle.kts`：
```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.catmaid"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.citizensnpcs.co/")
}

dependencies {
    // 以下坐标已于 Phase 0 按仓库实际情况修正，详见 docs/api-facts.md 第 2 节
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")  // 原 26.2-R0.1-SNAPSHOT 已不存在，版本串改为 X.Y.build.N-stable
    compileOnly("net.citizensnpcs:citizens-main:2.0.44-SNAPSHOT")    // 原 citizens-api 制品在该仓库 404；citizens-main 内含全部 api 类
    compileOnly("io.github.toxicity188:bettermodel:1.15.2")          // 仅含纯 API，不含 Citizens 的 ModelTrait；与运行时 3.7.0 有差异，见 api-facts 3.1
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")              // 唯一被 shade 的依赖
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.sqlite", "dev.catmaid.libs.sqlite")
}
```

`src/main/resources/plugin.yml`：
```yaml
name: CatMaid
version: '${version}'
main: dev.catmaid.CatMaidPlugin
api-version: '26.2'        # 若启动时报非法版本，尝试 '26'，并把实际可用值记录进 docs/api-facts.md
author: yourname
depend: [Citizens, BetterModel]
softdepend: [CraftEngine]
commands:
  catmaid:
    description: 猫娘女仆主命令
    aliases: [maid, 猫娘]
permissions:
  catmaid.use:
    description: 普通玩家权限（召唤/管理自己的女仆）
    default: true
  catmaid.admin:
    description: 管理员权限
    default: op
```

### 0.5 包结构（固定，不得擅自增删顶层包）

```
dev.catmaid
 ├─ CatMaidPlugin                 // 主类，生命周期入口
 ├─ maid
 │   ├─ Maid                      // 领域对象（内存态女仆）
 │   ├─ MaidManager               // 召唤/移除/查询/持久化调度
 │   ├─ MaidTrait                 // extends net.citizensnpcs.api.trait.Trait
 │   ├─ MaidInventory             // 虚拟背包（36格语义：0-5装备位，6-35存储位）
 │   ├─ MaidStats                 // 统计
 │   └─ ModelRegistry             // 可用东方模型清单（读 config）
 ├─ behavior
 │   ├─ BehaviorManager           // 模式注册表 + tick 分发 + 全局节流
 │   ├─ BehaviorMode              // 接口
 │   └─ mode
 │       ├─ IdleMode / FollowMode / CombatMode / FarmMode / RanchMode
 ├─ gui
 │   ├─ GuiManager                // 5 个 GUI 的开启与事件分发
 │   ├─ MaidMenuHolder            // implements InventoryHolder，GUI 身份识别
 │   └─ GuiLayout                 // 静态常量：槽位布局
 ├─ data
 │   ├─ Database                  // SQLite 连接与建表
 │   ├─ MaidRepository            // maids/maid_items/maid_stats CRUD
 │   └─ ItemStackCodec            // ItemStack ⇄ byte[]（serializeAsBytes / deserializeBytes）
 ├─ event
 │   ├─ MaidSummonEvent / MaidRemoveEvent
 │   ├─ MaidModeChangeEvent / MaidAffectionChangeEvent
 │   └─ MaidDeathEvent            // 女仆倒下（转铃铛）
 ├─ listener
 │   ├─ MaidInteractListener      // NPCRightClickEvent：开菜单/喂食
 │   ├─ MaidDamageListener        // 女仆受击/死亡
 │   ├─ OwnerProtectionListener   // 主人被打 → 战斗模式仇恨
 │   ├─ TalismanListener          // 魂符召唤 / 铃铛复活
 │   └─ PlayerConnectionListener  // 上线补挂/下线保存
 ├─ command
 │   └─ CatMaidCommand            // /catmaid 命令树 + Tab 补全
 ├─ dialogue
 │   ├─ DialogueManager           // 台词触发与发送
 │   └─ AffectionManager          // 好感度数值与等级
 └─ util
     ├─ Locations                 // Location ⇄ (world,x,y,z) 编解码
     └─ Text                      // 颜色/MiniMessage 工具
```

### 0.6 数据库 Schema（SQLite，`plugins/CatMaid/catmaid.db`）

```sql
CREATE TABLE IF NOT EXISTS maids (
  id          TEXT PRIMARY KEY,             -- 女仆 UUID
  owner_uuid  TEXT NOT NULL,                -- 主人玩家 UUID
  name        TEXT NOT NULL,                -- 女仆名字（默认从名字池随机）
  model_id    TEXT NOT NULL,                -- BetterModel 模型名，如 "reimu"
  mode        TEXT NOT NULL DEFAULT 'FOLLOW',-- IDLE/FOLLOW/COMBAT/FARM/RANCH
  affection   REAL NOT NULL DEFAULT 10,
  owner_title TEXT NOT NULL DEFAULT '主人',  -- 女仆对主人的称呼
  alive       INTEGER NOT NULL DEFAULT 1,   -- 0=铃铛状态
  npc_id      INTEGER,                      -- Citizens NPC id，可能为 NULL
  home_world  TEXT, home_x REAL, home_y REAL, home_z REAL,
  sitting     INTEGER NOT NULL DEFAULT 0,
  created_at  INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS maid_items (
  maid_id TEXT NOT NULL,
  slot    INTEGER NOT NULL,                 -- 0-5装备位 6-35存储位
  data    BLOB NOT NULL,                    -- ItemStack.serializeAsBytes()
  PRIMARY KEY (maid_id, slot)
);
CREATE TABLE IF NOT EXISTS maid_stats (
  maid_id  TEXT PRIMARY KEY,
  kills    INTEGER NOT NULL DEFAULT 0,
  harvests INTEGER NOT NULL DEFAULT 0,
  breeds   INTEGER NOT NULL DEFAULT 0,
  shears   INTEGER NOT NULL DEFAULT 0,
  milks    INTEGER NOT NULL DEFAULT 0
);
```

### 0.7 核心领域概念

**Maid（女仆）内存对象**：
```java
public final class Maid {
    private final UUID id;
    private UUID ownerUuid;
    private String name;          // 展示名（头顶名牌 = NPC name）
    private String modelId;       // BetterModel 模型名
    private Mode mode;            // enum Mode { IDLE, FOLLOW, COMBAT, FARM, RANCH }
    private double affection;     // 0..100
    private String ownerTitle;    // 女仆称呼主人用
    private boolean alive;
    private boolean sitting;
    private Location home;
    private final MaidInventory inventory;  // 36 槽虚拟背包
    private final MaidStats stats;
    private int npcId;            // Citizens NPC id（-1 = 未生成）

    public NPC npc();                       // CitizensAPI.getNPCRegistry().getById(npcId)
    public LivingEntity entity();           // npc.getEntity()，可能为 null
    public void setMode(Mode mode);         // 触发 MaidModeChangeEvent + 台词
    public void applyEquipment();           // inventory 装备位 → npc.getEntity().getEquipment()
    public void addAffection(double delta, AffectionReason reason); // 见 Phase 4
    public boolean addItem(ItemStack item); // 入虚拟背包，满返回 false
}
```

**MaidInventory 槽位语义**（固定）：
```
slot 0 = 主手   slot 1 = 副手
slot 2 = 头盔   slot 3 = 胸甲   slot 4 = 护腿   slot 5 = 靴子
slot 6..(5+capacity) = 存储位，capacity 默认 27，好感度可扩至 36（见 Phase 4）
```

**好感度等级**（AffectionManager，阈值可配置）：
```
0-19  陌生人    → 无加成，背包 27 格
20-39 熟识      → 攻击伤害 +5%，背包 30 格
40-59 伙伴      → 攻击 +10%，背包 33 格，解锁改名
60-79 挚爱      → 攻击 +15%，背包 36 格
80-100 羁绊    → 攻击 +20%，死亡好感度损失减半
```

---

## Phase 0 — 依赖打通验证（Spike，不写业务代码）

**目标**：在本地 Paper 26.2 服务端上把"Citizens NPC + BetterModel 模型 + CraftEngine 强制包"整条链路跑通，并把验证到的 API 事实写入 `docs/api-facts.md`，供后续阶段引用。

**步骤**：

1. 搭本地测试服：Paper 26.2 + Citizens2 + BetterModel + CraftEngine，全部最新版。
2. 模型链路验证：
   - 取一个测试 `.bbmodel`（任意人形模型即可）放入 `plugins/BetterModel/models/`；
   - `/npc create 测试 -t player` → 选中 → `/npc model <模型名>`；
   - 验收点：NPC 显示自定义模型 + 行走动画随移动自动播放。
3. **API 事实采集**（写入 `docs/api-facts.md`，格式：FQCN / 方法签名 / 验证方式）：
   - BetterModel 给 NPC 挂模型的 Trait 类全限定名与 setModel 方法签名（从 BetterModel jar 的 Citizens 集成包里找，DeepWiki 显示为 ModelTrait，**必须以 jar 实际内容为准**）；
   - `NPCRightClickEvent` 的 FQCN 与 getPlayer()/getNPC() 签名；
   - `NPCRegistry.createNPC` / `npc.getNavigator()` 常用方法签名；
   - Paper 26.2 的 `Entity#attack(Entity)`、`Animals#setLoveMode(int)`、`Sheep#setSheared(boolean)`、`ItemStack#serializeAsBytes()` 是否存在（逐一到 paper-api jar 里确认）。
4. 资源包合并验证（关键路径）：
   - BetterModel `config.yml` 中 `pack-type` 设为 `folder`（输出文件夹供手动合并）；
   - 方案 A：BetterModel 生成的资源文件夹内容并入 CraftEngine 的资源包目录后由 CraftEngine 统一强制下发；
   - 方案 B（A 不通时）：装 ResourcePackManager（≥2.4.10），由它合并 `plugins/CraftEngine/generated/resource_pack.zip` 与 BetterModel 包并强制下发；
   - 验收点：玩家进服强制包生效后，**CraftEngine 自定义物品和 BetterModel 模型同时正常显示**，无紫黑棋盘格。
5. 记录最终选用的合并方案到 `docs/api-facts.md`，后续阶段的部署文档引用它。

**Phase 0 产出**：`docs/api-facts.md`（API 事实清单 + 资源包合并方案记录）。**不产出 Java 代码。**

**Phase 0 验收清单**：
- [ ] 模型 NPC 在 Paper 26.2 上正常渲染且有行走动画
- [ ] 强制包生效时 CE 物品与 BM 模型同时可见
- [ ] api-facts.md 中每条 API 都有"已从 jar 确认"标记

---

## Phase 1 — 女仆生命周期与持久化

> **状态：已完成（2026-11-11 收口）**。实现与蓝图有出入的地方、以及交接要点，
> 见 `docs/api-facts.md` 第 15 节（代码地图 / 数据模型 / 运行期不变量 / Phase 2 提醒）。
> 与本节的差异：Gradle 用 9.9.0 + `--release 25`（非 toolchain）、Citizens 关传递依赖、
> BetterModel 走反射不引入编译依赖、`plugin.yml` 用 softdepend（详见第 9 节）、
> `MaidTrait` 实为**纯数据标记**——玩家型 NPC 的 `Trait#run()` 永不被 Citizens 调用
> [已实测]（见下方 MaidTrait 修订与附录 A）。

**目标**：能召唤女仆、女仆随服务器重启恢复。这是所有后续阶段的地基。

### 新增文件与职责

| 文件 | 职责 |
|---|---|
| `CatMaidPlugin.java` | onEnable：依赖自检（Citizens/BetterModel 缺失则禁用自身并打印原因）→ Database → MaidRepository → MaidManager.loadAll() → 注册监听/命令 → 恢复 NPC（见下） |
| `data/Database.java` | `Connection connection()`；构造时 `Class.forName("dev.catmaid.libs.sqlite.JDBC")`（relocate 后）；执行 0.6 建表 |
| `data/MaidRepository.java` | `void insert(Maid)`、`void save(Maid)`（maids 全字段 + 全量重写 maid_items + maid_stats）、`List<Maid> loadAll()`、`void delete(UUID maidId)` |
| `data/ItemStackCodec.java` | `byte[] encode(ItemStack)`、`ItemStack decode(byte[])` |
| `maid/Maid.java` | 见 0.7 |
| `maid/MaidManager.java` | 见下 |
| `maid/MaidTrait.java` | 见下 |
| `maid/ModelRegistry.java` | 读 `config.yml` 的 `models` 列表；`List<String> all()`、`String random()` |
| `listener/PlayerConnectionListener.java` | 见下 |
| `event/MaidSummonEvent.java` | Cancellable，字段：owner、modelId、spawnLoc |

### MaidManager 关键方法签名

```java
public Maid summon(Player owner, String modelId, Location loc)
    // 1. 校验 owner 已有女仆数 < config.general.max-per-player（默认2）
    // 2. new Maid（name 从 config 名字池随机，affection=初始值10，mode=FOLLOW，home=loc）
    // 3. 创建 NPC：
    //    NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name);
    //    npc.setProtected(false);                 // 允许受击
    //    npc.getOrAddTrait(MaidTrait.class);
    //    npc.spawn(loc);
    //    再挂 BetterModel 模型 Trait（按 Phase 0 api-facts 记录的实际 API）
    // 4. fire MaidSummonEvent（可取消 → 回滚删除 NPC）
    // 5. 特效：loc 粒子 HEART + 音效 ENTITY_CAT_AMBIENT；台词 on_summon
    // 6. repository.insert(maid); 注册到内存 Map<UUID,Maid> 与 byNpcId 索引

public void remove(UUID maidId)          // fire MaidRemoveEvent → npc.destroy() → repository.delete → 移出内存
public Optional<Maid> byNpcId(int npcId) // NPC 事件 → Maid 的唯一查找路径
public List<Maid> byOwner(UUID owner)
public void saveAll()                    // onDisable 与每 5 分钟调度调用
```

**NPC 恢复流程**（onEnable，`MaidManager.restoreNpcs()`）：
```
for maid in repository.loadAll():
  if !maid.alive: continue                     // 铃铛态不生成实体
  npc = registry.getById(maid.npcId)
  if npc == null:                              // Citizens 数据丢失/首次
      npc = 重建（同 summon 流程，跳过事件与特效）
  if !npc.isSpawned(): npc.spawn(maid.lastLoc 或 home)
  maid.applyEquipment()                        // 装备位写回实体
  // [已实测]（冲突 6）装备写回由 MaidManager.attach() 在 trait bind 之后补做——不要只依赖
  // MaidTrait.onSpawn()：Citizens 恢复 NPC 时 onSpawn() 早于 bind() 触发，此时 maid 仍为
  // null，onSpawn 里的 applyEquipment() 不生效；运行期重新 spawn 时 maid 已注入，才有效。
  （Citizens 自身持久化 NPC，正常情况下 npc 已存在，恢复流程是兜底）
```

### MaidTrait

```java
public final class MaidTrait extends Trait {
    // [已实测]（P3 探针跑满 1400 tick 零触发，同期 bind()/onSpawn() 正常、实例一致）：
    // 玩家型 NPC 的 Trait#run() 永远不会被 Citizens 调用——PlayerUpdateTask 只对带
    // PacketNPC trait 的 NPC 调 AbstractNPC#update()，而 Trait.run() 与 navigator.run()
    // 都只在 update() 内执行；真实玩家实体的 NPC 走原版 NMS.playerTicker。
    // 采用方案：本 trait 退化为纯数据标记，只存 maidId 供 MaidManager 反查。
    // 对后续影响：P4-P8 的一切周期逻辑（动画/导航/好感计时）一律由插件调度器统一推行，勿挂 trait。
    private String maidId;             // 持久化唯一字段（saves.yml: traits.catmaid.maid）
    @Override public void run()        // 保持空实现——勿在此挂任何每 tick 逻辑（不会被调用）
    @Override public void onSpawn()   // [已实测] 启动恢复时早于 bind()，maid 为 null、装备写回不生效；
                                       // 装备写回由 attach() 负责（见上方恢复流程注释）
    @Override public void onDespawn() // 保存坐标
}
```

### 监听器（Phase 1 只做连接恢复）

```java
// PlayerConnectionListener
@EventHandler onJoin(PlayerJoinEvent e)   // 若该玩家有 alive 女仆且 NPC 未生成 → 生成在 home
@EventHandler onQuit(PlayerQuitEvent e)   // repository.save(该玩家全部女仆)
```

### 配置（config.yml 初版）

```yaml
general:
  max-per-player: 2
  initial-affection: 10
models:
  - reimu          # BetterModel 模型名，必须与 plugins/BetterModel/models/ 内文件名一致
  - marisa
  - youmu
  - remilia
names:             # 随机名字池
  - 小铃
  - 汐音
  - 千夜
  - 缀理
```

### 验收清单
- [ ] `/catmaid admin summon <玩家> [模型]` 生成带东方模型的 NPC，头顶名字
- [ ] `/catmaid admin list` 显示女仆与归属
- [ ] 重启服务器 → 女仆仍在原位置、名字/模型正确
- [ ] `/catmaid admin remove` 彻底删除（DB + NPC + 重启不复活）
- [ ] 卸载 Citizens 启动 → CatMaid 自禁用并打印中文原因

---

## Phase 2 — 背包 / 装备管理 GUI + 主菜单

> **状态：已完成（2026-11-11，蓝图验收清单 7/7 全部通过客户端实测）**。
> 交接要点见 `docs/api-facts.md` 第 16 节。与本节的差异：模式界面多了 slot 22 返回键、
> 好感度容量阈值落在 `MaidInventory`（P4 的 `AffectionManager` 复用）、背包装备区可直接编辑、
> 空装备位带位置提示、信息书列出当前装备（模型不显示装备的补偿）。
> **头顶名牌已提前实现**（蓝图原在 P8）：格式 `[主人名] 女仆名`，用 TextDisplay 而非
> `setNameTagVisible`（模型隐藏原版实体，原版名牌看不见），详见 api-facts 16.9。

**目标**：右键女仆打开主菜单；能存取背包物品、穿脱装备并实时反映到 NPC 实体。

### 文件与 GUI 布局（槽位固定，GuiLayout 常量化）

**主菜单**（27 格 chest，`openMain`）：
```
slot 4  = 信息书（名字/好感度/等级/统计/称呼，展示用，点击无操作）
slot 10 = 模式选择（剑图标）      slot 12 = 背包管理（箱子图标）
slot 14 = 装备管理（盔甲架图标）  slot 16 = 模型选择（地图图标）
slot 22 = 坐下/起立 切换（钝色调亮/暗）
```

**背包 GUI**（54 格，`openBackpack`）：
```
slot 0-8   = 装备展示区（0主手 1副手 2头 3胸 4腿 5靴，6-8 灰玻璃填充）
slot 9-17  = 存储区第1行（对应 MaidInventory slot 6-14）
slot 18-26 = 存储区第2行（15-23）
slot 27-35 = 存储区第3行（24-32，好感度扩容后逐格替换灰玻璃）
slot 36-44 = 灰玻璃填充
slot 45-53 = 底部工具栏：53 = 返回主菜单
锁定槽 = 灰色染色玻璃板（名字"未解锁 · 提升好感度开启"）
```

**装备 GUI**（27 格，`openEquipment`）：
```
slot 10=主手 11=副手 12=头盔 13=胸甲 14=护腿 15=靴子；slot 22 = 返回
```

**模式选择**（27 格）：slot 10 闲置 / 11 跟随 / 12 战斗 / 13 农业 / 14 畜牧，图标对应（盾/栓绳/剑/锄/胡萝卜钓竿）。当前模式附魔光泽。

**模型选择**（54 格分页）：每页 28 个模型头颅图标（名字=模型ID）；slot 45=上一页 53=下一页 49=返回。选中项名字前缀 `§a✔`。

### 关键类签名

```java
public final class GuiManager implements Listener {
    public void openMain(Player p, Maid maid);
    public void openBackpack(Player p, Maid maid);
    public void openEquipment(Player p, Maid maid);
    public void openModeSelect(Player p, Maid maid);
    public void openModelSelect(Player p, Maid maid, int page);

    @EventHandler public void onInventoryClick(InventoryClickEvent e)
        // 顶部持有者 instanceof MaidMenuHolder → 取 type+maid 分发
        // 通用规则：
        //   1. 点击底部玩家背包时仅允许普通拿放，禁止 shift-click 到锁定槽
        //   2. shift-click 顶部合法槽 = 与玩家背包自动搬运
        //   3. 关闭/任何写操作后 → 同步 MaidInventory 并 saveAsync
        //   4. 装备槽写入后 → maid.applyEquipment()
    @EventHandler public void onInventoryDrag(InventoryDragEvent e) // 跨锁定槽一律取消
}

public final class MaidMenuHolder implements InventoryHolder {
    public enum MenuType { MAIN, BACKPACK, EQUIPMENT, MODE, MODEL }
    private final MenuType type; private final Maid maid; private int page;
    @Override public Inventory getInventory();  // 返回所持 inv
}
```

```java
// MaidInteractListener
@EventHandler public void onNpcRightClick(NPCRightClickEvent e)
    Maid maid = maidManager.byNpcId(e.getNPC().getId()).orElse(null); if null return;
    Player p = e.getClicker();
    if (!maid.getOwnerUuid().equals(p.getUniqueId())) {
        p.sendMessage("<red>她不是你的女仆哦。"); return;
    }
    ItemStack hand = e.getHand() == EquipmentSlot.HAND ? p.getInventory().getItemInMainHand() : ...;
    if (hand 是食物且 config.interaction.feedable 包含该 material) { /* Phase 4 实现喂食，此处留 TODO */ }
    guiManager.openMain(p, maid);
```

**同步规则**：GUI 的 Inventory 对象由 `MaidInventory` 生成（`Bukkit.createInventory(holder, size, title)`），打开时从 `MaidInventory` 填充，关闭时（`InventoryCloseEvent`）全量回读存储区/装备区到 `MaidInventory`。

### 验收清单
- [ ] 右键自己的女仆 → 主菜单；右键别人的女仆 → 拒绝提示
- [ ] 放入/取出背包物品，重启后仍在
- [ ] 给女仆穿钻石胸甲 → NPC 实体胸甲槽位同步（实体上可见，模型上不可见属预期）
- [ ] 锁定槽（好感度不足）无法放入物品
- [ ] 模式/模型选择点击生效（模型立即切换由 BetterModel Trait 处理）

---

## Phase 3 — 行为框架：跟随 / 坐下 / 待机

> **状态：已完成并收口（2026-11-11）**。服务端行为实测通过；跟随、坐下/起立、行走与坐下动画、
> >40 格传送、跨世界跟随（主世界 ↔ 末地双向）均实测通过（动画联调的三条踩坑事实见 api-facts 17.9，
> 验收明细见 17.10）。**服主决定不验**：10 只真人同屏 TPS；**未单独验证**：疾跑/骑马跟随。
> 与本节的差异：**行为 tick 不由 `MaidTrait.run()` 驱动**——玩家型 NPC 的 trait 根本不会被
> Citizens 调用（只对带 `PacketNPC` 的 NPC 调 `AbstractNPC#update()`），改由插件自己的调度器
> 每 tick 推行一次；`MaidTrait` 退化为数据标记。动画驱动另落在新增的 `maid/ModelAnimator`
> 三姿态状态机（见下方「坐下机制」修订与附录 A）。
> 细节见 `docs/api-facts.md` 第 17 节（**接手请直接读 17.2、17.4 与 17.9**）。

**目标**：女仆"活"起来：跟着主人走、太远传送、可坐下、待机时在 home 附近闲逛。行为框架是 Phase 5-7 的地基。

### 接口与实现

```java
public interface BehaviorMode {
    Mode type();
    default void onEnable(Maid maid) {}
    default void onDisable(Maid maid) {}
    void tick(Maid maid, int tick);   // 每个服务器 tick 调用，实现内部必须自行节流
}

public final class BehaviorManager {
    private final Map<Mode, BehaviorMode> modes;
    public BehaviorMode get(Mode mode);
    public void tickAll(Collection<Maid> maids)   // [已实测] 唯一驱动入口：由 CatMaidPlugin 的
        // runTaskTimer(this, ..., 1L, 1L) 每 tick 调用（trait.run() 驱动已作废，见附录 A）
        // 内部 tickCounter 自增；同时对全部女仆推导模型姿态（STAND/WALK/SIT，委托 ModelAnimator）
    public void tick(Maid maid, int tick)
        // sitting == true → 一律不移动（清导航目标），仅播放待机行为
        // 否则委托当前 mode.tick
        // 全局守卫：实体为 null / 世界区块未加载 → 直接 return
}
```

**通用工具**（写在各 Mode 内部或 util）：
```java
void navigateTo(Maid maid, Location loc, float speedMod)  // navigator.setTarget(loc); speedModifier
void clearNavigation(Maid maid)                            // navigator.cancelNavigation()
boolean arrived(LivingEntity e, Location loc, double dist) // 距离判断
```

### FollowMode（默认模式）
```
tick（内部每 5 tick 执行一次判断）:
  owner = Bukkit.getPlayer(ownerUuid); 若 owner == null → 转入在 home 闲逛（复用 IdleMode 逻辑）
  dist = maid.entity 到 owner 的距离
  if owner 跨世界            → 延迟 3s 后 teleport 女仆到 owner 身边
  else if dist > config.follow.teleport-dist (默认 40) → teleport
  else if dist > config.follow.start-dist (默认 6)     → navigateTo(owner, speed 1.1)
  else if dist < config.follow.stop-dist  (默认 2.5)   → clearNavigation, 面向主人
  行走速度 = config.follow.speed (默认 1.15，接近主人 1.3)
```

### IdleMode
```
tick（每 40+随机20 tick）:
  在 home 周围 config.idle.wander-radius（默认 5）随机选可站立位置 navigateTo（速度 1.0）
  概率 5%：面向最近的玩家看向 2 秒
```

### 坐下机制（P3 修订：动画不由坐下开关直接驱动，改由 ModelAnimator 姿态状态机统一推导）

```
Maid.sitting 切换时：
  true  → clearNavigation（BehaviorManager 对 sitting 一律硬拦截，任何模式不移动）
  false → 恢复
持久化 sitting 字段，重启恢复

动画侧 [已实测，方案已落码 maid/ModelAnimator]，姿态由 BehaviorManager 每 tick
按「是否在导航 / 是否 sitting」推导，各 Mode 不碰动画：
  SIT  = stop(walk) + animate("sit")，每 5000ms 补播
         ——[已实测] 单次 animate 播完停在最后一帧不自续，必须补播
  起立 = 回 STAND：stop(walk) + stop(sit) + 再 animate("idle")
         ——[已实测] stopAnimation("sit") 停得掉动画、停不掉骨骼姿势（tracker 不回
         bind pose），「点起立还坐着」必须用 idle 归位动画顶掉坐姿，不能只 stop
  前提 = 模型必须真有 idle/walk/sit 关键帧（部署前置条件，见附录 A「模型缺动画」行）：
         Tracker.animate(name) 找不到动画不抛异常、只返回 false，缺帧表现为
         服务端一路"成功"、客户端"卡住不动"
```

### 配置新增
```yaml
follow: { start-dist: 6, stop-dist: 2.5, teleport-dist: 40, speed: 1.15 }
idle:   { wander-radius: 5 }
```

### 验收清单
- [ ] 跟随模式下女仆稳定跟随，主人疾跑/骑马不丢失；>40 格自动传送到身边
- [ ] 主人下线后女仆回家闲逛；上线后重新跟来
- [ ] 坐下后任何模式都不移动，解除后恢复
- [ ] 10 只女仆同屏时 TPS 无明显下降（行为 tick 有节流与错峰：tick % N == npcId % N）

---

## Phase 4 — 养成层：好感度 / 台词 / 喂食 / 死亡复活

> **状态：已完成（2026-11-11）**。蓝图验收 7 项全部通过：服务端链路 4 项实测，喂食/死亡/复活/魂符/
> 台词 5 类交互由服主客户端实测确认。**所有养成数值均已配置化**（清单见 `docs/api-facts.md` 18.9）：
> 好感上下界、等级阈值/容量/伤害加成（`affection.levels` 可增删档）、死亡减半档、各类冷却、
> 待机台词概率、喂食食物表、复活代价、铃铛材质与发光。
> 与本节的差异（均记在 18.4）：`AffectionManager` 的 `apply()` 是唯一改动入口（不提供 `Maid#addAffection`）；
> `MaidModeChangeEvent` 暂不创建、模式台词接在 GUI 切模式处；受击扣好感有冷却（火焰每 10 tick 一跳）；
> 跟随计时间隔可配（默认 300 秒）；`/catmaid talisman` 只做自助领取。
> **实测最重要的两条**：玩家型 NPC 的死亡由"伤害事件兜底"处理（`health - finalDamage <= 0`）；
> 掉在地上的铃铛必须 `setInvulnerable` + `setUnlimitedLifetime`，否则会被死亡现场的火烧掉或被原版
> 5 分钟 Age 上限清掉（18.3）。铃铛态女仆的 NPC 会被 Citizens 按存档恢复成"无名幽灵身体"，启动时必须收起（18.3-6）。

**目标**：实现"养了个自己的女仆"的核心体验。本阶段是体验重心。

### AffectionManager

```java
public enum AffectionReason { FEED, WORK_HARVEST, WORK_BREED, WORK_KILL, FOLLOW_TICK, HURT, DEATH, REVIVE, IDLE_LONG }
public final class AffectionManager {
    public double gain(AffectionReason r);   // 读 config.affection.gain.<name>
    public double loss(AffectionReason r);   // 80+ 等级 DEATH 减半
    public int level(double affection);      // 0..4，对应 0.7 节等级表
    public void apply(Maid maid, double delta, AffectionReason r)
        // fire MaidAffectionChangeEvent（可取消）→ clamp(0,100) → 等级变化时：
        //   升级：粒子 HEART 爆发 + 台词 on_level_up + 背包容量变更提示
        //   降级：台词 on_level_down
    public int backpackCapacity(int level);  // 27/30/33/36
    public double damageBonus(int level);    // 0/0.05/0.10/0.15/0.20，Phase 7 使用
}
```

**触发点接线**（各处调用）：
| 事件 | Reason | 数值（默认） |
|---|---|---|
| 主人手持食物右键女仆 | FEED | +1.0（冷却 60s/次，消耗 1 个食物，播放吃粒子） |
| 完成一次收割/补种 | WORK_HARVEST | +0.1 |
| 完成一次繁殖/剪毛/挤奶 | WORK_BREED | +0.1 |
| 击杀一只敌怪 | WORK_KILL | +0.2 |
| 跟随状态下每 5 分钟 | FOLLOW_TICK | +0.5 |
| 女仆受击 | HURT | -0.5 |
| 女仆死亡 | DEATH | -20（80+ 减半） |
| 复活 | REVIVE | 0（死亡已扣） |

### DialogueManager

`dialogue.yml`（可热重载）：
```yaml
# {name}=女仆名 {owner}=称呼 {item}=物品名
on_summon:
  - "初次见面，{owner}！我叫{name}，请多指教～"
  - "{owner}，是您唤醒了我吗？喵。"
on_mode_change:
  COMBAT: [ "了解！{owner}请退后，交给我吧！", "剑已就绪……喵！" ]
  FARM:   [ "今天也来打理田地吧，{owner}。", "除草的时间到了喵～" ]
  RANCH:  [ "小家伙们，我来喂你们啦～" ]
  FOLLOW: [ "我会一直跟着{owner}的。", "请带我去看看外面的世界喵。" ]
  IDLE:   [ "那我在家等{owner}回来。" ]
on_feed:      [ "唔、好吃……谢谢{owner}！", "喵呜～还想再吃一口。" ]
on_hurt:      [ "呀！……没、没事的喵。", "好痛……{owner}，对不起……" ]
on_death:     [ "对不起……{owner}……我先休息一下……", "啊……头好晕……" ]
on_revive:    [ "让{owner}担心了，对不起喵……我回来了！", "铃铛里做了个好梦呢。" ]
on_level_up:  [ "感觉和{owner}的关系又更近了一步喵！", "嘿嘿……最喜欢{owner}了。" ]
on_level_down:[ "呜……最近好像有点疏远了呢……" ]
on_work_done: [ "今天的农活做完啦！", "收获满满喵～" ]
on_idle_random:[ "……在发什么呆呢喵。", "困了……zzZ", "{owner}还不回来吗……" ]
```

```java
public final class DialogueManager {
    public void send(Maid maid, String key)          // 随机一条；发送方式 config.dialogue.channel: chat|actionbar
    public void send(Maid maid, String key, String subKey) // on_mode_change.COMBAT 这类二级键
    // chat 格式: §d[name] §7» §f{line}；低频随机台词冷却 5 分钟/女仆
}
```
**随机待机台词**：IdleMode/FollowMode tick 中每 3 分钟 5% 概率触发 `on_idle_random`（每女仆独立冷却）。

### 死亡与复活（猫娘铃铛）

```java
// MaidDamageListener
@EventHandler(ignoreCancelled=true) public void onDamage(EntityDamageEvent e)
    // entity 是某 maid 的 NPC 实体 → 记录最后伤害；受伤台词（每30s冷却）
@EventHandler public void onDeath(EntityDeathEvent e)
    // e.getDrops().clear(); e.setDroppedExp(0)
    // fire MaidDeathEvent → maid.alive=false；affection 扣 DEATH
    // 铃铛 = ItemStack(Material.BELL)，displayName="§d{name}的铃铛"
    //   PDC key "catmaid:maid_id" = maid.id
    // 优先塞进主人背包，满则掉落在死亡位置
    // [已实测]（冲突 4）：玩家型 NPC 死亡后 Citizens 已自行 despawn（实体移除、
    // spawned=false），但 NPC 注册、saves.yml 条目与 npcId 全部保留（此时 /npc list
    // 看起来是空的）。因此死亡流程不必再调 npc.despawn()；且不要假设本事件里
    // npc.isSpawned() 仍为 true（maid.entity() 已为 null，行为 tick 自然空转）。
    // 手动救回 = /npc despawn <id> + /npc spawn <id>（实体重建后装备/行为自动恢复）
    repository.save
    // 台词 on_death（以聊天消息发送，此时实体已无）

// TalismanListener
@EventHandler public void onBellUse(PlayerInteractEvent e)
    // 主手右键空气/方块，item 是铃铛且有 maid_id
    // 校验使用者 == owner（否则提示"铃铛毫无反应"）
    // 消耗 config.death.revive-cost（默认 1 颗钻石，从玩家背包找）
    // npc 重新 spawn 在玩家旁；alive=true；特效+on_revive 台词；扣除铃铛
    // [已实测] 复活直接 npc.spawn(loc) 即可——死亡时 Citizens 只是把 NPC 置为
    // despawned（注册与 npcId 都在），spawn 对 despawned 状态的 NPC 直接可用
```

### 魂符召唤（玩家获取渠道）

```java
// TalismanListener
@EventHandler public void onTalismanUse(PlayerInteractEvent e)
    // 主手右键，item material == config.interaction.talisman-material（默认 AMETHYST_SHARD）
    // 且 PDC 含 "catmaid:soul_talisman"
    // → maidManager.summon(player, ModelRegistry.random(), loc) 成功则消耗 1 个
// /catmaid talisman [玩家] [数量]（catmaid.use）发放魂符；管理员可用 give 获取分发
```

### 喂食接线（补 Phase 2 的 TODO）
```
NPCRightClickEvent 且主人手持 config.interaction.feedable 中食物：
  冷却检查（Maid 内存 Map<UUID,Long>）→ 消耗 1 → 播放 eat 粒子+音效 → affection FEED → on_feed 台词 → return（不打开菜单）
```

### 配置新增
```yaml
interaction:
  talisman-material: AMETHYST_SHARD
  feedable: [ BREAD, COOKED_COD, COOKED_SALMON, BAKED_POTATO, COOKED_BEEF, COOKED_PORKCHOP, COOKIE, CAKE, SWEET_BERRIES, GLOW_BERRIES ]
  feed-cooldown-seconds: 60
affection:
  gain:  { feed: 1.0, work-harvest: 0.1, work-breed: 0.1, work-kill: 0.2, follow-tick: 0.5 }
  loss:  { hurt: 0.5, death: 20.0 }
dialogue:
  channel: chat        # chat | actionbar
death:
  revive-cost: DIAMOND:1
```

### 验收清单
> **验收前置**[已实测]（冲突 5）：无玩家在线时 Citizens 会自动 despawn 女仆（`maid.entity()`
> 为 null、行为全部空转，极易误判成「tick 没跑」）。服务端自动化验证前必须先 `/npc spawn <id>`；
> 「跟主人」类观感验收必须由真人客户端完成。

- [ ] 喂食：食物消耗、粒子、好感度+1、台词、60s 冷却内重复无效果
- [ ] 好感度跨等级：背包锁定槽解锁、升级特效与台词
- [ ] 杀死女仆 → 无掉落物、主人背包出现铃铛、DB alive=0、重启后不复活
- [ ] 铃铛使用：非主人无效；主人消耗钻石复活、好感度-20、on_revive 台词
- [ ] 随机待机台词低频出现且不刷屏

---

## Phase 5 — 农业模式

> **状态：已完成（2026-11-12）**。服务端链路 5 项全部实测通过（逐株收割+补种、收成入包、重启不丢、
> 满包落地、栅栏 8 秒放弃、坐下不干活），观感（走动姿态、`on_work_done` 台词）待真人客户端。
> 交接与踩坑见 `docs/api-facts.md` 第 19 节。与本节的差异：**`breakNaturally` 返回的是 boolean 不是
> 掉落列表**，改 `getDrops(tool)` + 静默清空方块（19.6）；空目标时的待机台词复用 `tickIdle` 的闸门，
> 不做"每 20 tick 掷 5%"；拉黑表按女仆存在 Mode 内、`onDisable` 清。
> **窗口复核（2026-11-12 晚，维护者）**：javap 独立验证 breakNaturally 冲突属实；另确认 1 条冲突——
> 本节拉黑表伪代码的 `Map<BlockPos, Long>` 引用了 NMS 类型（org.bukkit 无 `BlockPos`），实际实现用
> `BlockVector`，勿照抄伪代码。P5 窗口冲突共 2 条，均保守解决（见 api-facts 19.8 与附录 A）。

**目标**：女仆在 home（或自身）周围自动找成熟作物收割并补种，收成入女仆背包。

### FarmMode.tick（内部每 20 tick 决策一次，错峰：`tick % 20 == npcId % 20`）

```java
// 状态字段（Mode 实例内 per-maid 用 Map<UUID, State> 维护）：
Block currentTarget;   // 当前目标作物

tick:
  if (sitting) return;
  if (!world.isChunkLoaded(target 或自身位置)) return;

  if (currentTarget == null || 已非成熟):
      currentTarget = scanNearestMatureCrop(maid)   // 见下；无目标 → 概率 on_idle_random 后 return

  loc = currentTarget.location
  if (dist(maid.entity, loc) <= 3.5):
      harvest(maid, currentTarget)
      currentTarget = null
  else:
      navigateTo(maid, loc, speed 1.1)
      if 导航卡死（8s 内位移 < 0.5 格）→ 放弃该目标并拉黑 30s（Map<BlockPos, Long>）

harvest:
  tool = maid.inventory.getMainHand()               // 锄头/斧头等加速，breakNaturally 自带逻辑
  drops = block.breakNaturally(tool)                // 返回 List<ItemStack>
  for d in drops: maid.addItem(d)；放不下 → 掉落在地（天然行为）
  stats.harvests++; affection WORK_HARVEST
  // 补种：种子映射表（WHEAT→WHEAT_SEEDS, CARROTS→CARROT, POTATOES→POTATO,
  //   BEETROOTS→BEETROOT_SEEDS, NETHER_WART→NETHER_WART, COCOA→COCOA_BEANS）
  if (config.farm.replant && maid.inventory.consumeOne(seedOf(type)))
      block.setBlockData(对应作物的 Ageable age=0)
  概率 10% on_work_done 台词

scanNearestMatureCrop:
  center = (config.farm.center == 'home') ? maid.home : maid.entity.location
  for block in center 周围 config.farm.radius（默认 8）方块内（自外向内或取最近，用 BlockIterator/双层循环 y-1..+1）:
      if material in config.farm.crops 且 ((Ageable) blockData).getAge() == maxAge → 距离最近者优先
  注意：每次扫描范围 17x3x17，每秒每女仆最多一次，可接受
```

### 配置
```yaml
farm:
  radius: 8
  center: home            # home | self
  replant: true
  crops: [ WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA ]
```

### 验收清单
> 验收前置（同 Phase 4 注）：无玩家在线时 NPC 是 `spawned=false`——自动化验证前先 `/npc spawn <id>`；观感类验收需真人客户端。

- [ ] 种一片成熟小麦 → 女仆逐株收割、补种、种子从背包扣除
- [ ] 收获得到的物品进入女仆 GUI 背包，重启不丢
- [ ] 背包已满 → 物品掉落原地
- [ ] 用栅栏围住目标 → 8 秒后放弃转下一个，不卡死
- [ ] 空闲时坐在家里（sitting）不工作

---

## Phase 6 — 畜牧模式

> **状态：已验收通过（2026-11-12，服务端 + 客户端）**。四条链路服务端实测通过（捡拾鸡蛋入包、红羊剪出
> 红羊毛、空桶换奶桶、两只成年牛各喂 1 份小麦后双双求偶并生出小牛）；`on_work_done`/`on_no_food` 台词
> 与"玩家刚丢的物品不抢"由服主客户端确认（api-facts 20.8）。
> **与本节的重大差异**：`animals.setLoveMode(600)` 与备选的 `Breedable#setBreed(boolean)` **都不能用来求偶**
> ——后者改的是年龄（false 会把成年动物变成幼崽）；真正的入口是 `Animals#setLoveModeTicks(int)`，
> 且 `canBreed()` 只判年龄、必须额外加 `!isLoveMode()` 才不会被反复喂。其余差异：捡拾拆成
> "脚下 10 tick 直收 + 远处 30 tick 走一趟"、繁殖分两趟各喂一只、饲料不足 2 份不动手、
> 跳过已驯服的动物（详见 20.4）。交接与修正建议见 `docs/api-facts.md` 第 20 节。
> **与本节的重大差异**：`animals.setLoveMode(600)` 与备选的 `Breedable#setBreed(boolean)` **都不能用来求偶**
> ——后者改的是年龄（false 会把成年动物变成幼崽）；真正的入口是 `Animals#setLoveModeTicks(int)`，
> 且 `canBreed()` 只判年龄、必须额外加 `!isLoveMode()` 才不会被反复喂。其余差异：捡拾拆成
> "脚下 10 tick 直收 + 远处 30 tick 走一趟"、繁殖分两趟各喂一只、饲料不足 2 份不动手、
> 跳过已驯服的动物（详见 20.4）。

**目标**：自动繁殖配对、剪羊毛、挤牛奶、捡拾地面产物（鸡蛋/羊毛/掉落物）。

### RanchMode.tick（每 30 tick 决策一次，错峰）

```java
// 每女仆状态：Entity currentTarget; String actionType;
tick:
  if (sitting) return
  // 1. 优先捡拾：半径 config.ranch.radius 内 Item 实体（pickupDelay<=0 且 age>10tick）
  //    → maid.addItem() 并 remove；满则跳过
  // 2. 决策（每 30 tick）：
  //    a) 繁殖：收集半径内 Animals 按类型分组；对每种
  //       若成年对数 >= 2 且 maid 背包有对应饲料 → 选最近一对，target=第二只, action=BREED
  //    b) 剪毛：最近 Sheep isSheared()==false → action=SHEAR
  //    c) 挤奶：最近 Cow 且背包有空桶 → action=MILK
  //    优先级：BREED > SHEAR > MILK（config 可调顺序）
  // 3. 执行：
  //    dist > 2.0 → navigateTo(目标)；导航卡死处理同 Farm
  //    到达 → 执行动作：
  //      BREED: 两只都 animals.setLoveMode(600)；consume 饲料×1（一只一份）；
  //             stats.breeds++; affection WORK_BREED
  //      SHEAR: sheep.setSheared(true); addItem(new ItemStack(Material.WHITE_WOOL 或
  //             sheep.getColor() 对应羊毛)); stats.shears++; affection WORK_BREED
  //      MILK:  consume 空桶 → addItem(MILK_BUCKET×1)；stats.milks++
  //    每动作冷却 2s（防止瞬间连吸）

// 饲料映射（常量表，config.ranch.breed-food 可覆盖）：
// COW/SHEEP→WHEAT  PIG→CARROT  CHICKEN→WHEAT_SEEDS
// RABBIT→CARROT  HORSE→GOLDEN_CARROT  CAT→COD  WOLF→BONE（仅繁殖，狼用腐肉也行，取主流）
```

### 配置
```yaml
ranch:
  radius: 8
  pickup: true
  action-priority: [ BREED, SHEAR, MILK ]
  breed-food:
    COW: WHEAT; SHEEP: WHEAT; PIG: CARROT; CHICKEN: WHEAT_SEEDS
```

### 验收清单
> 验收前置（同 Phase 4 注）：无玩家在线时 NPC 是 `spawned=false`——自动化验证前先 `/npc spawn <id>`；观感类验收需真人客户端。

- [ ] 两只成年牛 + 女仆背包有小麦 → 走近、消耗小麦、双红心进入繁殖
- [ ] 有毛羊被剪 → 羊毛入女仆背包（颜色正确），羊变裸
- [ ] 空桶 → 牛奶桶转换正确
- [ ] 地上鸡蛋/羊毛自动拾取入背包；玩家刚丢的物品 10 tick 内不抢
- [ ] 饲料耗尽时不再尝试繁殖（有台词提示一次）

---

## Phase 7 — 战斗模式

> **开工前必读**：`docs/api-facts.md` 20.9（给本节伪代码的 5 条修正建议）与 20.10（P7 开工清单）。
> 其中三条会直接写出错代码或返工：①`entity.attack()` 在玩家型 NPC 上受**攻击冷却**影响，要判
> `getCooledAttackStrength`；②`Attribute.MAX_HEALTH` 必须配合 `setHealth`，且每次实体重建都要重设；
> ③"战斗模式挨打是否仍扣好感"这一口径待裁决（现状是所有伤害都扣，与 `WORK_KILL +0.2` 相抵）。

**目标**：主动索敌近战/弓箭，保护主人，受击反击，武器取自主手。

### CombatMode.tick（每 10 tick，错峰）

```java
// 每女仆状态：LivingEntity target; int attackCooldown;
tick:
  if (sitting) return
  // 目标维护：
  if (target == null || target.dead || dist(maid,target) > config.combat.lose-dist(20))
      target = nearestHostile(maid.entity.location, config.combat.radius(12))
               // world.getNearbyEntities → Monster 且非 Citizens NPC 自身/其他女仆
  if (target == null): 缓步跟随主人（复用 FollowMode 距离逻辑，standby 距离 4）→ return

  weapon = maid.inventory.getMainHand()
  ranged = config.combat.ranged-enabled && weapon 类型是 BOW/CROSSBOW
  if (ranged):
      if (dist <= config.combat.ranged-range(15) && hasLineOfSight(entity, target)):
          clearNavigation; 面向 target
          if (attackCooldown <= 0):
              Arrow a = world.spawnArrow(eyeLoc, dir(带小随机扰动 0.1), speed 2.0, spread 6f)
              a.setShooter(entity); a.setDamage(config.combat.arrow-damage 5.0)
              entity.swingMainHand(); attackCooldown = config.combat.attack-interval-ticks(30)
      else navigateTo(target, 1.2)   // 拉开/接近到射程
  else:
      if (dist > 2.8) navigateTo(target, 1.25)
      else:
          clearNavigation; 面向 target
          if (attackCooldown <= 0):
              entity.attack(target)          // Paper API，伤害含主手武器与攻击属性
              // 伤害加成：监听 EntityDamageByEntityEvent，damager==女仆实体 →
              //   setDamage(damage * (1 + affectionManager.damageBonus(level)))
              entity.swingMainHand(); attackCooldown = weaponCd(剑默认 20t)
  if (target.dead): stats.kills++; affection WORK_KILL; 概率 on_work_done; target=null
```

**主人保护**（OwnerProtectionListener，任何模式下生效，战斗模式优先响应）：
```java
@EventHandler public void onOwnerHurt(EntityDamageByEntityEvent e)
    // victim 是玩家且拥有 alive 女仆 → attacker 为 LivingEntity 时：
    // 该玩家的战斗模式女仆立即锁定 attacker 为 target（写入 CombatMode 的 per-maid 状态）
@EventHandler public void onMaidHurt(EntityDamageByEntityEvent e)
    // victim 是女仆实体 → 战斗模式下 attacker LivingEntity → 反击锁定
```

**女仆生命值**：NPC 实体为 Player 类型，`setProtected(false)` 后可受击。onEnable 恢复时设置 `getAttribute(MAX_HEALTH).setBaseValue(config.combat.maid-max-health 40)`。Phase 4 的死亡逻辑自动生效。

### 配置
```yaml
combat:
  radius: 12
  lose-dist: 20
  attack-interval-ticks: 20
  ranged-enabled: true
  ranged-range: 15
  arrow-damage: 5.0
  maid-max-health: 40
  protect-owner: true
```

### 验收清单
> 验收前置（同 Phase 4 注）：无玩家在线时 NPC 是 `spawned=false`——自动化验证前先 `/npc spawn <id>`；观感类验收需真人客户端。

- [ ] 附近刷僵尸 → 女仆主动迎敌、挥剑击杀、播放挥臂
- [ ] 主手给弓 → 保持距离射箭，箭有伤害归属（可被玩家拾取的箭不回收）
- [ ] 好感度 60+ 的女仆明显比 0 好感打得痛（+15%）
- [ ] 打主人 → 附近自家女仆群起反击；打女仆 → 反击
- [ ] 索敌半径外/目标死亡正确脱战并回到主人身边
- [ ] 女仆 HP 归零走 Phase 4 死亡流程（铃铛）

---

## Phase 8 — 配置化 / 命令 / 收尾打磨

**目标**：管理员工具齐全、全部数值可配、消息外置。

### /catmaid 命令树（CatMaidCommand，完整实现）

```
/catmaid                      → 帮助
/catmaid gui                  → 打开最近一只（或 8 格内）自己女仆的主菜单
/catmaid mode <模式>           → 切换最近女仆模式
/catmaid sethome              → 以脚下位置设为女仆的家（最近女仆）
/catmaid info                 → 自己女仆列表：名字/模式/好感度/存活
/catmaid talisman [数量]       → 给自己魂符（catmaid.use，冷却 24h，config 可关）
/catmaid rename <名字>         → 改名（好感度≥40；长度 2-12；过滤颜色码）
/catmaid title <称呼>          → 改称呼（好感度≥40）
/catmaid admin summon <玩家> [模型]
/catmaid admin remove <玩家|maidId>
/catmaid admin list [玩家]
/catmaid admin setaffection <maidId> <数值>
/catmaid admin reload         → 重载 config + dialogue
```

### messages.yml（全部玩家可见文案外置，MiniMessage 格式）
键结构：`prefix / not-your-maid / max-maids / summon-success / revive-no-item / rename-need-affection / ...`（实现时按实际文案补全，代码中禁止硬编码中文消息字符串，必须走 `Messages.get(key)`）。

### 打磨项
- `config.yml` 补 `performance` 段：`global-maid-cap: 60`、`behavior-tick-stagger: true`
- 女仆名牌：**方案已在 P2 落地**（TextDisplay 悬浮，见 `docs/api-facts.md` 16.9），
  本阶段只剩「名字颜色随好感度等级变化（灰→白→黄→粉→红）」——改 `NameTagManager` 的 text 即可
- Metrics：不接入（无付费服不必要）
- 更新检查：不做

### 最终验收（全链路）
> 验收前置（同 Phase 4 注）：无玩家在线时 NPC 是 `spawned=false`——自动化验证前先 `/npc spawn <id>`；观感类验收需真人客户端。

- [ ] 新玩家：领魂符 → 召唤 → 喂食涨好感 → 派去种田/放牧/打怪 → 死亡复活全流程无报错
- [ ] 服务器重启后所有状态（位置/模式/背包/好感度/坐下/存活）完整恢复
- [ ] 60 只女仆同服 TPS ≥ 19.5（行为错峰生效）
- [ ] `/catmaid admin reload` 后配置与台词即时生效

---

## 附录 A — 风险与降级预案

| 风险 | 预案 |
|---|---|
| BetterModel 挂模型 Trait 的确切 API 与预期不符 | Phase 0 已确认实际 API：`kr.toxicity.model.bukkit.compatibility.citizens.trait.ModelTrait#setRenderer(ModelRenderer)` + `BetterModel.modelOrNull(String)`（**没有 setModel**）。代码层仍按附录 A 封装 `ModelApplier.apply(npc, modelId)` 单点隔离 |
| 玩家型 NPC 移动时模型只平移、四肢不动（无行走动画） | **事实**[已实测]：根因同 Phase 0 定位（BM 对 `ServerPlayer` 的 `isWalking` 只看移动输入，Citizens 玩家型 NPC 无输入故恒 false），但代码驱动还有三条隐藏前提：①模型必须真有关键帧——`Tracker.animate(name)` 找不到动画**不抛异常、只返回 false**（首批 `foxmaid.bbmodel` 的 `animations` 为空即踩坑；P0 用 `demon_knight` 验证有动画，掩盖了这点）；②单次 `animate` 播完停在最后一帧不自续（表现为"跟几秒有动作，之后变平移"）；③`stopAnimation("sit")` 停得掉动画、停不掉骨骼姿势（tracker 不回 bind pose → "点起立还坐着"）。**采用方案**（已落码 `maid/ModelAnimator` 三姿态状态机 STAND/WALK/SIT）：STAND=`stop(walk)+stop(sit)+animate("idle")` 用归位动画顶掉坐姿；WALK=`stop(sit)+animate("walk")` 每 1050ms 续播（walk 周期 1s、首尾同帧，几乎无缝）；SIT=`stop(walk)+animate("sit")` 每 5000ms 补播。姿态由 `BehaviorManager` 每 tick 按「导航中/坐着」推导，各 Mode 不碰动画。**对后续影响**：P4 受击/死亡、P7 挥剑等动画同理需要「模型有该动画 + 续播 + 归位」三件套 [待验证：各动画周期与归位策略实现时逐一确定]（`Tracker`/`AnimationModifier` API 见 api-facts 17.5） |
| 玩家型 NPC 的 `Trait#run()` 不会被调用 | Phase 3 已实测确认：Citizens 的 `PlayerUpdateTask` 只对带 `PacketNPC` trait 的 NPC 调 `AbstractNPC#update()`，而 `Trait.run()` 与 `navigator.run()` 都在 `update()` 里；真实玩家实体的 NPC 走原版 `playerTicker`。**行为 tick 改由插件调度器驱动**，`MaidTrait` 退化为数据标记（`docs/api-facts.md` 17.2） |
| P4–P8 按「trait 每 tick 驱动」的直觉写周期逻辑 | **事实**[已实测]：见上一行——玩家型 NPC 的 `Trait#run()`/`navigator.run()` 永不被调用。**采用方案**：动画、导航、好感计时（P4 的 `FOLLOW_TICK` 等）等一切周期逻辑一律由插件调度器（`BehaviorManager.tickAll`，`CatMaidPlugin` 的 `runTaskTimer(…, 1L, 1L)`）统一推行；Mode/Listener 内的定时需求走 BehaviorManager 的 tick 计数，不挂 trait。**对后续影响**：任何"靠 trait 每 tick 驱动"的设计写法全部作废 |
| 资源包合并后玩家看到棋盘格 | **Phase 0 已定案：双包分离**——女仆模型包（`BetterModel/build.zip`）走原版 `server.properties` 的 `resource-pack` + `sha1` + `require-resource-pack=true` 强制下发；CraftEngine 只发自己的包（`merge-external-zip-files` 中移除 BetterModel）。客户端实测双包并存、模型正常渲染。细节与更新姿势见 `docs/api-facts.md` 第 4 节 |
| `Animals#setLoveMode` 等 Paper API 在 26.2 变动 | Phase 0 逐一从 paper-api jar 确认；缺失则用反射调用 NMS 之外的最后手段：`entity.setMetadata` 不可行时退化为"不自动繁殖，仅剪毛/挤奶/捡拾" |
| 蓝图 P6「`animals.setLoveMode(600)`」→ 备选「`Breedable#setBreed(true)`」 | **事实**[P6 实测 + 字节码复核]：`Animals#setLoveMode` 在 26.2 不存在；`Breedable#setBreed(boolean)` 的实现是 `setAge(0)` / `setAge(6000)`，改的是**年龄**，`setBreed(false)` 会把成年动物变成幼崽；`canBreed()` 也只看 `getAge()==0`。**采用方案**（P6 已落码 `RanchMode#feed`）：`Animals#setLoveModeTicks(600)` 才是真求偶入口，筛选/校验再加 `!isLoveMode()` 防止重复喂（api-facts 20.3、20.6） |
| 蓝图 P7 `entity.attack(target)` 默认满伤害 | **事实**[JAR 已核]：`LivingEntity#attack(Entity)` 存在，但女仆是玩家型 NPC，走 `ServerPlayer.attack`，伤害受**攻击冷却**（attack strength）影响；`Player` 只有 `getCooledAttackStrength(float)`/`getCooldownPeriod()`/`resetCooldown()`，**没有灌满强度的 setter**。**采用方案**（待 P7 落码）：攻击前判 `getCooledAttackStrength(0.5f) >= 0.9f`，否则等下一轮；`attack-interval-ticks: 20` 对剑够、对斧（0.8–1.0 攻速）略欠。**对后续影响**：好感伤害加成的 15% 差异会被攻强噪声淹没，验收时必须固定攻强（api-facts 20.9-A4） |
| 蓝图 P7 只设 `MAX_HEALTH` 的 baseValue | **事实**[JAR 已核 + 常规行为]：①只改上限不改当前血量会留下"20/40"的血条；②Citizens 每次 spawn 重建实体，onEnable 设一次会在 despawn/重生成后失效。**采用方案**（待 P7 落码）：每次实体 ready（attach/spawn 回调）都 `setBaseValue(max)` + `setHealth(max)`；常量名是 `Attribute.MAX_HEALTH`（无 `GENERIC_` 前缀） |
| 战斗模式下女仆挨打仍按 P4 扣好感 | **事实**[现状]：`MaidDamageListener.onHurt` 对所有伤害无差别扣 `affection.loss.hurt`（5 秒冷却）。P7 上线后"带她打怪"= 每次挨打 -0.5，与击杀奖励 +0.2 相抵，等于惩罚玩家用战斗模式。**采用方案**：**待产品裁决**——建议"战斗模式受击不扣好感（或减半），非战斗模式照旧"，裁决后在 P7 落码（api-facts 20.9-B5） |
| 蓝图 P5 伪代码 `block.breakNaturally(tool) // 返回 List<ItemStack>` | **事实**[已实测/JAR 复核]：Paper 26.2 的 `breakNaturally` 全部 7 个重载都返回 `boolean`（掉落直接掉进世界），没有返回掉落列表的重载；掉落列表只有 `getDrops(ItemStack) → Collection<ItemStack>`。**采用方案**（P5 已落码 `FarmMode#harvest`）：`getDrops(tool)` 算掉落 → `setType(AIR, false)` 静默清空 → 入包、满才 `dropItemNaturally`，并手动补收割音效/粒子。**对后续影响**：P6 剪毛/挤奶/捡拾勿照抄该伪代码（api-facts 18.11-2 已作废）；同段 `Map<BlockPos, Long>` 用 `BlockVector`（`BlockPos` 是 NMS 类型，agent.md 1.2 禁用） |
| 东方模型资产 | TLM 素材 CC BY-NC-SA 4.0：非商用服可移植，须在服务器公告/关于页署名 TartaricAcid 与 TLM 项目并以相同协议共享改编物；或用 Blockbench 自制。模型文件放 `plugins/BetterModel/models/`，属部署资产不进代码仓库 |
| NPC 被其他插件清理 | MaidManager.restoreNpcs() 每次 onEnable 兜底重建 |
| 女仆模型缺 `idle`/`walk`/`sit` 动画 → 对应姿态「卡住不动」且服务端零报错 | **部署前置条件**[已实测]：女仆 bbmodel 必须自带 `idle`/`walk`/`sit` 三条动画，缺一条对应姿态的表现就是"卡住不动"（`Tracker.animate` 找不到名字时静默返回 false，服务端一路"成功"）。缺动画用 `tools/bbmodel_anim.py` 按骨骼名注入；注入时手臂必须写 ±13° 的 bind 倾斜，不能写 0。P4 受击/死亡、P7 攻击动画同理——先补资产再写代码 |
| 误以为改模型/调动画要重新下发资源包 | **事实**[已实测]：BetterModel 动画不在资源包内（`build8.zip` 内 `animation` 匹配数为 0，模型几何也不在包内，只有贴图和骨骼用的物品模型）——动画是服务端逐帧驱动 display 实体算出来的。**采用方案**：改 bbmodel/调动画只需重启服务器让 BM 重读，**不必重打包、不必让客户端重下资源包**；只有贴图/几何变化才需要重建包并换名更新 `resource-pack` 与 `sha1`（更新姿势见 api-facts 4.2） |
| 死亡后误判 NPC 状态 | **事实**[已实测]：玩家型 NPC 死亡后 Citizens 已自行 despawn（实体移除、`spawned=false`），但 NPC 注册、saves.yml 条目与 npcId 全部保留，`/npc list` 看起来是空的。**采用方案**：死亡流程不必自己 `despawn()`；复活直接 `npc.spawn(loc)`；死亡事件里**不要假设 `npc.isSpawned()` 仍为 true**（详见 Phase 4 死亡流程修订） |
| 无玩家在线时 NPC 被自动 despawn，误判「tick 没跑」 | **事实**[已实测]：附近无玩家时 Citizens 置女仆 `spawned=false`、`maid.entity()` 为 null，所有行为逻辑空转。**采用方案**：服务端自动化验证前必须先 `/npc spawn <id>`；「跟主人」类观感验收必须由真人客户端完成（P4–P8 验收清单已注明） |
| `Trait.onSpawn()` 早于 `bind()` 触发 | **事实**[已实测]：Citizens 恢复 NPC 时 `onSpawn()` 触发时 `maid` 仍为 null，`onSpawn` 里的 `maid.applyEquipment()` 不生效。**采用方案**：装备写回由 `MaidManager.attach()` 在 bind 之后补做（Phase 1 恢复流程已注明）；运行期重新 spawn 时 maid 已注入，onSpawn 可正常生效 |
| 女仆死亡流程不触发 | **事实**[已实测]：玩家型 NPC 的死亡由 `EntityDamageEvent` 里 `health - finalDamage <= 0` 的**兜底分支**处理——我们收到伤害事件时原版尚未完成死亡（日志顺序：铃铛→倒下→原版死亡消息）。**采用方案**：死亡同时挂 `EntityDamageEvent` 兜底 + `EntityDeathEvent`/`PlayerDeathEvent`，`handleDeath` 用 `maid.isAlive()` 幂等；**不要删兜底那一段**（api-facts 18.3-2） |
| 掉落的铃铛被火烧掉 / 永久丢人 | **事实**[已实测]：雷击引燃的火几秒就把地上的铃铛烧没了。**采用方案**：`drop()` 给物品实体 `setInvulnerable(true)` + `setGlowing(true)`，岩浆里实测存活（18.3-3） |
| 无玩家在线导致 tick 计时永远攒不满 | **事实**[已实测]：`/npc spawn` 后约 1 分钟 Citizens 就把 NPC 丢掉，FOLLOW_TICK 这类 5 分钟计时在无客户端环境永远到不了阈值。**采用方案**：计时间隔做成配置项（`affection.follow-tick-interval-seconds`），验收时调短验证机制；真人观感仍需客户端（18.3-4） |

## 附录 B — 阶段依赖关系

```
P0 ─→ P1 ─→ P2 ─→ P3 ─→ P4 ─→ P5 ─→ P6 ─→ P7 ─→ P8
              │                    │
              └─ P2 依赖 P1 的 Maid/GUI 持久化    └─ P5/P6 依赖 P3 行为框架与 P4 好感度钩子
```
严格按序实施；每阶段验收清单全部勾选后才允许进入下一阶段（见 agent.md 第 3 节）。
