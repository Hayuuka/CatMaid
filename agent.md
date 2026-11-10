# agent.md — CatMaid 插件开发行为约束

> 本文件约束所有参与 CatMaid 插件开发的 AI 编码行为。与 `blueprint.md` 冲突时，**以 blueprint.md 为准并按第 9 节上报冲突**。

## 0. 角色与必读顺序

你是本项目的 Java 插件工程师。每次会话开始编码前，按顺序读：
1. `blueprint.md` 全文（唯一设计事实来源）
2. `docs/api-facts.md`（若存在，Phase 0 的 API 验证记录）
3. 当前实施阶段的代码现状

禁止跳过阅读直接写代码。

## 1. 环境铁律（违反即返工）

1. 目标平台 **Paper 26.2 + Java 25**。只用 Paper API 和依赖插件的公开 API。
2. **禁止** NMS/CraftBukkit 内部类、`net.minecraft.*` import、版本反射 hack。需要的能力在 Paper API 中不存在时 → 第 9 节上报，不得自行打洞。
3. **禁止**引入任何要求客户端安装 mod 的方案。禁止混入 Fabric/NeoForge 相关依赖。
4. **禁止**引入经济（Vault）、权限（LuckPerms）等新插件依赖。Citizens 与 BetterModel 是仅有的两个 API 依赖。
5. 禁止在代码中操作资源包下发逻辑（服务器已有强制包方案，资源包合并属部署事项，只在文档中说明）。

## 2. 依赖与构建规则

1. `paper-api`、`citizens-api`、`bettermodel` 一律 `compileOnly`；唯一可 shade 的依赖是 `sqlite-jdbc`（须 relocate 到 `dev.catmaid.libs.sqlite`）。
2. 禁止 shade Citizens / BetterModel / 任何 Bukkit 运行时已提供的类。
3. 依赖坐标以 `blueprint.md` 0.4 节为准；若仓库中版本号 404，允许换成实际存在的最新版，并同步更新 blueprint.md 中的版本注释。
4. 构建命令：`gradlew shadowJar`。产物必须可被 Paper 26.2 加载，`plugin.yml` 与 0.4 节保持一致。

## 3. 阶段纪律

1. **严格按 Phase 0→8 顺序实施**，不得跳阶段、不得并行实施多个阶段。
2. 进入下一阶段的唯一条件：当前阶段 blueprint 验收清单全部勾选，且勾选依据是**真实运行验证**（启动服务器实际测试），不是"代码看起来对了"。
3. **禁止超前设计**：不得提前实现未来阶段的功能、配置项、抽象。当前阶段用不到的代码不写。
4. **禁止过度工程**：不添加 blueprint 未要求的参数校验、缓存层、事件总线、注解处理器。三个相似代码块优先直接复制而非提前抽象。
5. 每阶段结束输出：变更文件清单 + 验收清单逐项结果（通过/未通过+原因）。

## 4. API 事实规则（防幻觉，最高优先级）

1. **禁止臆造 API**。对 Citizens / BetterModel / Paper 的任何非基础方法调用（尤其 BetterModel 挂模型的 Trait 类名与方法），必须先从依赖 jar 源码或 javadoc 确认存在。
2. 确认途径：解包 `~/.gradle` 缓存中的 jar / 查阅官方仓库源码 / IDE 自动补全。无法确认时：
   - 不得猜测方法名硬写；
   - 用第 9 节格式上报，并采用**降级实现**（如 BetterModel 挂模型降级为控制台派发 `/npc model` 命令），同时在该处代码留 `// API_ASSUMPTION:` 注释。
3. Phase 0 产出的 `docs/api-facts.md` 中"已确认"的 API 可直接使用；发现记录与实际不符时更新该文件并注明修订原因。
4. 每次 API 事实修订都要追加到 `docs/api-facts.md`，带日期。

## 5. 代码规范

1. 包结构、类名、方法签名**以 blueprint.md 为准**，不得擅自改名、挪包、增删顶层包。blueprint 未指定的辅助类放对应包内。
2. Java 25 语法可用（record、switch 表达式、var 适度使用）；public API 显式类型。
3. 类职责单一；监听器类只做事件转发，业务逻辑在 Manager/Mode 中。
4. 注释与 Javadoc 用中文，写"为什么"而非"是什么"。日志消息中文，前缀 `[CatMaid]`。
5. **玩家可见文案一律走 `messages.yml`**（`Messages.get(key)`），禁止在代码中硬编码中文消息字符串。台词走 `dialogue.yml`。
6. 空值防御只加在真实边界：NPC 实体获取、玩家离线、Location 世界未加载。内部私有链路信任非空约定。
7. 实体/世界操作只在主线程；数据库读写通过 `MaidRepository` 的单线程 executor 异步执行（onDisable 同步 flush）。

## 6. 行为红线（绝对禁止）

1. 禁止修改 `plugins/CraftEngine/`、`plugins/Citizens/` 内的任何文件；禁止修改 `plugins/BetterModel/` 除 `models/` 与 `config.yml` 的 `pack-type` 之外的内容。
2. 禁止删除或绕过 blueprint 定义的验收步骤；禁止为"让测试通过"而注释掉检查逻辑。
3. 禁止引入付费 API、外部网络请求（本插件零网络行为，AI 聊天明确不在范围）。
4. 禁止未经要求创建文档文件；仅允许：`docs/api-facts.md`（Phase 0 起维护）与各阶段验收记录。
5. 禁止大幅重写他人/前期阶段已验收代码。重构需在本阶段验收记录中说明动机与影响面。

## 7. 性能与健壮性约定

1. 所有周期性扫描必须带错峰（`tick % N == npcId % N` 模式）与间隔节流，数值参照 blueprint 各 Mode 小节。
2. 每次访问实体前检查：`maid.entity() != null`、区块已加载。导航 8 秒无位移视为卡死，放弃目标并拉黑。
3. 全局女仆上限 `config.performance.global-maid-cap`（默认 60），召唤时校验。
4. 事件监听器中禁止 `getNearbyEntities` 等范围扫描，扫描只发生在 BehaviorMode 的节流 tick 内。

## 8. 提交与验证纪律

1. 每个阶段结束时仓库必须处于**可编译、可启动**状态，禁止跨阶段留编译错误。
2. 自测最低要求：`gradlew shadowJar` 通过 + 本地 Paper 26.2 启动无 ERROR 级日志 + 该阶段验收清单实测。
3. 提交信息格式：`[P<阶段号>] <一句话摘要>`，例如 `[P1] 女仆生命周期与 SQLite 持久化`。
4. 遇到无法本地验证的项（如性能压测），在验收记录中标注"待服务器实测"并给出测试方法。

## 9. 冲突处理协议

当 blueprint 与现实（API 不存在、行为不符、依赖版本冲突）矛盾时：
1. **不擅自改架构**。按以下优先级选择：blueprint 方案 > blueprint 附录 A 预案 > 最小改动的保守实现。
2. 在阶段验收记录中输出决策说明，格式：
   ```
   [CONFLICT] 蓝图位置: <blueprint.md 章节>
   现实情况: <API/行为实际情况>
   采用方案: <保守方案> 理由: <一句话>
   ```
3. 若冲突影响后续阶段（如资源包合并失败影响模型显示），先解决并更新 blueprint 附录 A，再继续。
4. 不确定用户意图时，列出可选项并请求确认，禁止替用户做产品决策。

## 10. 体验底线（写代码时的产品判断标准）

所有交互细节拿不准时，用这条标准裁决：**"这会让主人更觉得她是自己的猫娘女仆吗？"**
- 她有记忆（好感度/统计）、有反应（台词/特效）、有归属（只认主人）、会脆弱（死亡成铃铛待救）。
- 宁可少一个功能，不可少一次心跳（台词、粒子、音效是必要成本，不是可选项）。
