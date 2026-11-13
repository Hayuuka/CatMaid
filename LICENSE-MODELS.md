# 模型素材授权声明（LICENSE-MODELS）

本文件覆盖本仓库中全部女仆模型素材，与仓库代码的 MIT 授权相互独立。

## 1. 覆盖范围

- `model_src/` 目录下全部 `.bbmodel` 文件（27 个女仆模型成品，内嵌贴图与动画）
- 依据本仓库工具链（`tools/tlm_to_bbmodel.py`、`tools/inject_tlm_anims.py` 等）从上述模型派生的任何产物，
  包括服务器资源包（如 `catmaid-pack.zip`）中包含的模型贴图与骨骼物品模型

## 2. 来源与署名（CC BY-NC-SA 4.0 的 BY 义务）

上述模型素材**改编自**：

> **车万女仆（Touhou Little Maid）1.5.3**
> 作者：TartaricAcid 与车万女仆项目
> 发布页：https://modrinth.com/mod/touhou-little-maid
> 美术资源授权：Creative Commons Attribution-NonCommercial-ShareAlike 4.0（CC BY-NC-SA 4.0）

本仓库中的 `.bbmodel` 为其**改编物**（基岩几何 → Blockbench bbmodel 格式转换、
TLM 动画数据注入、坐姿落地钳制等修改），依 CC BY-NC-SA 4.0 的 ShareAlike 条款，
**以相同协议（CC BY-NC-SA 4.0）共享**。

## 3. 使用限制

- **非商用（NC）**：不得将模型素材或包含它们的服务器内容用于主要目的为商业利益的场景。
  具体到 Minecraft 服务器：
  - ✅ 允许：免费服、全员平等使用女仆功能、不定向的自愿捐赠/赞助
  - ❌ 禁止：付费进服、VIP/付费专属模型或女仆功能、将模型打包进收费产品
- **相同方式共享（SA）**：再次分发（含服务器资源包向玩家下发）或二次改编时，
  必须以 CC BY-NC-SA 4.0 授权，并保留本署名声明
- **署名（BY）**：分发或公开展示时，须在显著位置（如服务器公告、发布页、README）
  保留第 2 节的署名信息

## 4. 与仓库 MIT 授权的关系

- 本仓库根目录 `LICENSE`（MIT）**仅覆盖代码**：`src/`、`tools/`、`docs/`、构建脚本等
- 模型素材（第 1 节所列）**不受 MIT 授权覆盖**，一律按本文件（CC BY-NC-SA 4.0）执行
- 两者边界以本文件为准；将模型素材按 MIT/商用授权再分发即构成违约
