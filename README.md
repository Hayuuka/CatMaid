# CatMaid 猫娘女仆————基于Citizens和BetterModel的女仆插件

本插件为实验性插件，目的是模仿车万女仆mod的核心功能，非移植或重构。本插件为MIT开源，不保证更新和维护，如有问题请提交issue（不一定处理）或自行解决

## 特性

- **女仆实体**：可更换模型，头顶名牌随好感变色
- **养成**：好感度等级（背包扩容 / 战斗加成）、喂食、改名改称呼、台词系统
- **五种模式**：跟随 / 待机 / 战斗（近战 + 弓弩）/ 农业（收割补种）/ 畜牧（配对·剪毛·挤奶）
- **背包与装备**：独立虚拟背包（随好感扩容）+ 主副手/护甲 GUI 管理
- **死亡与复活**：倒下变铃铛，凭材料唤醒；铃铛丢了可补发
- **魂符三态**：对齐车万女仆——未唤醒（召唤）/ 空（收纳）/ 装着（放出），一张符走完全流程
- **多女仆**：每玩家可养多只，命令按名字定向
- **猫娘隐藏**：`/catmaid hide` 一键对所有猫娘模型隐身（含自己的），不影响其他玩家与女仆工作
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

**方法一：**

1. 下载 Release 中的 `CatMaid-1.1.0.jar`（插件）与 `CatMaid-Models-1.1.0.zip`（模型包，含授权文件）
2. 安装依赖插件并核对版本：
   - **Citizens2 2.0.44-SNAPSHOT (build 4269)**：官方 Jenkins 发布页
   - **BetterModel 3.6.0**：官方发布页（Modrinth / GitHub Releases）
   - 三者一同放入服务器 `plugins/` 文件夹
3. 解压 `CatMaid-Models-1.0.0.zip` 到 `plugins/BetterModel/models/`（解压后该目录下应出现 27 个 `.bbmodel`）
4. 启动服务器并进行配置：
   - `config.yml` 的 `models:` 名单补入模型名（名单 = 模型文件名去扩展名，须与 models 目录一致）
   - BetterModel 生成的模型 zip 挂到静态 HTTP，写入 `server.properties` 的 `resource-pack` / `resource-pack-sha1` / `require-resource-pack=true`
5. 开玩：新玩家进服自动领一张未唤醒的魂符，对地右键即召唤

**方法二（自行构建）：**

1. **构建**：`gradlew shadowJar`，产物在 `build/libs/CatMaid-1.0.0.jar`
2. **部署**：jar 放入 `plugins/`，连同 Citizens、BetterModel 一起启动
3. **模型包**：BetterModel 生成的模型 zip 挂到静态 HTTP 并写入 `server.properties` 的 `resource-pack` / `resource-pack-sha1` / `require-resource-pack=true`
4. **配置**：首次启动自动生成 `plugins/CatMaid/config.yml`（含注释）；各键含义见 **docs/config-guide.md**
5. **开玩**：新玩家进服自动领一张未唤醒的魂符，对地右键即召唤

## 命令与权限

唯一入口 `/catmaid`（子命令：`gui / mode / sethome / info / rename / title / talisman / soul / bell / release / hide / admin`）。

| 权限 | 默认 | 说明 |
|---|---|---|
| `catmaid.use` | 全员 | 玩家侧命令与日常交互 |
| `catmaid.admin` | op | 管理命令（召唤/移除/列表/设好感/重载） |

每条命令可单独设为管理员专用：`config.yml` → `commands.admin-only`，详见 docs/config-guide.md 第 2 节。

## 目录结构

```
├── agent.md                # 本插件开发时的AI 开发行为约束
├── blueprint.md            # 唯一设计事实来源（分阶段蓝图）
├── build.gradle.kts        # 构建脚本（shadowJar）
├── docs/
│   ├── api-facts.md        # API 事实与各阶段交接记录
│   └── config-guide.md     # 配置详解（本仓库配置的逐键说明）
├── model_src/              # 女仆模型素材（CC BY-NC-SA 4.0）
├── src/main/               # 插件源码（dev.catmaid）
└── tools/                  # 资源包托管 / RCON / 模型转换脚本
```

## 文档索引

| 文档 | 内容 |
|---|---|
| `blueprint.md` | 设计与验收的唯一事实来源，按 Phase 0–11 推进 |
| `docs/api-facts.md` | Citizens / BetterModel / Paper 的实测 API 事实、每阶段交接与冲突记录 |
| `docs/config-guide.md` | config.yml 逐键详解与升级速查 |
| `CHANGELOG.md` | 版本更新日志（Keep a Changelog 格式） |
| `agent.md` | 参与开发的 AI 编码行为约束 |

## 许可

**分层授权：**

- **代码**（`src/`、`tools/`、`docs/`、构建脚本）：[MIT](LICENSE)，可自由使用、修改、分发
- **女仆模型素材**（`model_src/` 下全部 `.bbmodel` 及其派生物，含服务器资源包中的模型内容）：
  改编自车万女仆（Touhou Little Maid）项目美术资源，**CC BY-NC-SA 4.0**——仅限非商用、
  须署名 TartaricAcid 与车万女仆项目、改编物以相同协议共享。
  详细边界与服务器使用规则见 **[LICENSE-MODELS.md](LICENSE-MODELS.md)**

## 免责声明

1. 本插件按"现状"提供，**不附带任何形式的担保**；作者不保证其可用性、稳定性或不间断运行，
   使用本插件（含模型素材）产生的一切后果由服务器运营者自行承担。
2. 本插件为社区同人性质的实验作品，与 Mojang/Microsoft、TartaricAcid 及车万女仆项目、
   上海艺术电影制片厂（东方 Project 相关权利方）**无任何隶属或合作关系**。
3. 模型素材的授权边界以 [LICENSE-MODELS.md](LICENSE-MODELS.md) 为准；服务器运营者有义务
   在其分发场景（含资源包下发）中遵守对应署名与非商用条款，逾期不遵循的责任由运营者承担。
4. 如素材权利人认为本仓库的使用方式侵犯了其权益，请提交 issue，将第一时间处理下架。
