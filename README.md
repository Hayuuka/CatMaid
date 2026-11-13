# CatMaid 猫娘女仆

Paper 26.2 纯插件：把车万女仆搬到生存服——召唤一只属于自己的猫娘女仆，养成、干活、并肩作战。

## 特性

- **女仆实体**：BetterModel 东方模型（自带贴图），头顶名牌随好感变色
- **养成**：好感度等级（背包扩容 / 战斗加成）、喂食、改名改称呼、台词系统
- **五种模式**：跟随 / 待机 / 战斗（近战 + 弓弩）/ 农业（收割补种）/ 畜牧（配对·剪毛·挤奶）
- **背包与装备**：独立虚拟背包（随好感扩容）+ 主副手/护甲 GUI 管理
- **死亡与复活**：倒下变铃铛，凭材料唤醒；铃铛丢了可补发
- **魂符三态**：对齐车万女仆——未唤醒（召唤）/ 空（收纳）/ 装着（放出），一张符走完全流程
- **多女仆**：每玩家可养多只，命令按名字定向
- **零网络、零经济依赖**：只依赖 Citizens2 + BetterModel，CraftEngine 可选

## 环境要求

| 项 | 要求 |
|---|---|
| 服务端 | Paper 26.2 |
| Java | 25 |
| 必装插件 | Citizens2、BetterModel |
| 可选插件 | CraftEngine（`dependency.provider=craftengine` 时才需要） |
| 玩家侧 | 无需安装任何东西；强制资源包下发女仆模型（原版 `server.properties` 槽位） |

## 快速开始

1. **构建**：`gradlew shadowJar`，产物在 `build/libs/CatMaid-0.1.0.jar`
2. **部署**：jar 放入 `plugins/`，连同 Citizens、BetterModel 一起启动
3. **模型包**：BetterModel 生成的模型 zip 挂到静态 HTTP 并写入 `server.properties` 的 `resource-pack` / `resource-pack-sha1` / `require-resource-pack=true`
4. **配置**：首次启动自动生成 `plugins/CatMaid/config.yml`（含注释）；各键含义见 **docs/config-guide.md**
5. **开玩**：新玩家进服自动领一张未唤醒的魂符，对地右键即召唤

## 命令与权限

唯一入口 `/catmaid`（子命令：`gui / mode / sethome / info / rename / title / talisman / soul / bell / release / admin`）。

| 权限 | 默认 | 说明 |
|---|---|---|
| `catmaid.use` | 全员 | 玩家侧命令与日常交互 |
| `catmaid.admin` | op | 管理命令（召唤/移除/列表/设好感/重载） |

每条命令可单独设为管理员专用：`config.yml` → `commands.admin-only`，详见 docs/config-guide.md 第 2 节。

## 目录结构

```
├── agent.md                # AI 开发行为约束（改代码前必读）
├── blueprint.md            # 唯一设计事实来源（分阶段蓝图）
├── build.gradle.kts        # 构建脚本（shadowJar）
├── docs/
│   ├── api-facts.md        # API 事实与各阶段交接记录
│   └── config-guide.md     # 配置详解（本仓库配置的逐键说明）
├── model_src/              # 女仆模型素材（CC BY-NC-SA 4.0）
├── src/main/               # 插件源码（dev.catmaid）
├── testserver/             # 本地测试服（不入库）
└── tools/                  # 资源包托管 / RCON / 模型转换脚本
```

## 文档索引

| 文档 | 内容 |
|---|---|
| `blueprint.md` | 设计与验收的唯一事实来源，按 Phase 0–10 推进 |
| `docs/api-facts.md` | Citizens / BetterModel / Paper 的实测 API 事实、每阶段交接与冲突记录 |
| `docs/config-guide.md` | config.yml 逐键详解与升级速查 |
| `agent.md` | 参与开发的 AI 编码行为约束 |

## 许可

- 代码许可见 `LICENSE`
- 女仆模型素材来自车万女仆（TLM）项目，**CC BY-NC-SA 4.0**：仅限非商用服务器使用，须署名 TartaricAcid 与车万女仆项目
