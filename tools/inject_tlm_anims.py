"""把 TLM（车万女仆）的 geckolib 动画注入现成的 .bbmodel。

与 tools/tlm_to_bbmodel.py --anim 的区别：本脚本面向"用户已在 Blockbench 里
手工转换好"的 bbmodel——它们的骨骼 uuid 是 Blockbench 随机生成的，无法用
确定性映射反推，所以这里按骨骼名从 bbmodel 自己的 groups 里查真实 uuid。

动画源：模型目录下 animation/*.animation.json（专属 + 借用 + 公共 maid），
按动画名合并（同名时后读的文件覆盖先读的通道值，与 tlm_to_bbmodel 一致）。

用法：
    python tools/inject_tlm_anims.py <模型目录或 bbmodel 文件>... [--anims a,b,c] [--dry-run]

默认只导 idle / walk / run / sit / jump / attacked / death（与 CatMaid
ModelAnimator 的 Pose 对齐）；maid.animation.json 里还有 90 多个用不上。
原文件先备份为 <名>.bbmodel.orig（已存在则不覆盖）。

转换规则（沿用 tlm_to_bbmodel 已验证的实现）：
  * 静态姿势（通道值直接是 [x,y,z]）→ 0 秒单帧
  * molang 表达式关键帧 → 跳过（不记失败）
  * lerp_mode=catmullrom → 插值 catmullrom，其余 linear
  * loop: true→loop / hold_on_last_frame→hold / 其余→once
  * 轴向不做翻转（BetterModel 不解析 model_format；四肢方向是否合理
    要客户端实测，见 docs/api-facts.md 14.5）
"""

from __future__ import annotations

import json
import shutil
import sys
import uuid
from pathlib import Path

DEFAULT_ANIMS = ("idle", "walk", "run", "sit", "jump", "attacked", "death")


def anim_value(entry):
    """geckolib 关键帧值：[x,y,z] 或 {pre/post}；molang 字符串返回 None。"""
    def as_vec(value):
        if not isinstance(value, list) or len(value) < 3:
            return None
        try:
            return [float(x) for x in value[:3]]
        except (TypeError, ValueError):
            return None

    if isinstance(entry, list):
        return as_vec(entry)
    if isinstance(entry, dict):
        return as_vec(entry.get("post", entry.get("pre")))
    return None


def convert_axis(channel: str, vec: list[float]) -> list[float]:
    """基岩动画 json → bbmodel 关键帧的轴向换算。

    2026-11-13 客户端实测后启用（此前原样搬运导致坐姿扭成一团、走路单腿平移，
    见 api-facts 14.5 的预判与注入报告 §7-A）。规则与 Blockbench compileBedrockKeyframe
    / TACZ merge-bedrock-animation.mjs 一致（做过往返验证）：
    rotation 的 x/y 取反、position 的 x 取反、scale 不变。
    """
    x, y, z = vec
    if channel == "rotation":
        return [-x, -y, z]
    if channel == "position":
        return [-x, y, z]
    return [x, y, z]


def anim_easing(entry) -> str:
    if isinstance(entry, dict) and entry.get("lerp_mode") == "catmullrom":
        return "catmullrom"
    return "linear"


def loop_mode(loop) -> str:
    if loop is True:
        return "loop"
    if loop == "hold_on_last_frame":
        return "hold"
    return "once"


def convert_animations(anim_files: list[Path], wanted: tuple[str, ...]) -> tuple[list[dict], int]:
    """合并多个动画文件 → bbmodel animations（animator 键先放骨骼名，调用方再换成 uuid）。"""
    merged: dict[str, dict] = {}
    skipped = 0
    for path in anim_files:
        data = json.loads(path.read_text(encoding="utf-8"))
        for anim_name, anim in data.get("animations", {}).items():
            if wanted and anim_name not in wanted:
                continue
            slot = merged.setdefault(anim_name, {"length": 0.0, "loop": anim.get("loop"), "bones": {}})
            length = float(anim.get("animation_length") or 0)
            slot["length"] = max(slot["length"], length)
            for bone, channels in anim.get("bones", {}).items():
                target = slot["bones"].setdefault(bone, {})
                for channel, frames in channels.items():
                    if channel not in ("rotation", "position", "scale"):
                        continue
                    if isinstance(frames, (int, float)):
                        frames = {"0.0": [frames, frames, frames]}
                    elif isinstance(frames, list):
                        frames = {"0.0": frames}
                    if not isinstance(frames, dict):
                        continue
                    target.setdefault(channel, {}).update(frames)

    out = []
    for anim_name, anim in merged.items():
        animators: dict[str, dict] = {}
        max_time = 0.0
        for bone, channels in anim["bones"].items():
            keyframes = []
            for channel, frames in channels.items():
                try:
                    ordered = sorted(frames.items(), key=lambda kv: float(kv[0]))
                except (TypeError, ValueError):
                    continue
                for time_str, value in ordered:
                    vec = anim_value(value)
                    if vec is None:
                        skipped += 1
                        continue
                    t = float(time_str)
                    max_time = max(max_time, t)
                    cx, cy, cz = convert_axis(channel, vec)
                    # 每个时间点必须是独立 keyframe，time 写在 keyframe 层。
                    # 2026-11-13 修：旧写法把全部时间点塞进同一个 time=0 的 keyframe
                    # （ModelDatapoint 没有 time 字段，点内时间被忽略），整条动画坍缩成
                    # t=0 单帧——客户端表现为"走路单腿平移/坐姿扭曲"。结构与
                    # bbmodel_anim.py（实测可播）对齐。
                    keyframes.append({
                        "channel": channel,
                        "data_points": [{
                            "x": cx, "y": cy, "z": cz,
                            "uuid": str(uuid.uuid4()),
                            "color": -1,
                        }],
                        "uuid": str(uuid.uuid4()),
                        "time": t,
                        "color": -1,
                        "interpolation": anim_easing(value),
                    })
            if keyframes:
                # animator 键先占位骨骼名，注入时替换为 bbmodel 里的真实 uuid
                animators[f"@{bone}"] = {
                    "name": bone, "type": "bone", "keyframes": keyframes,
                }
        if not animators:
            continue
        out.append({
            "name": anim_name,
            "uuid": str(uuid.uuid4()),
            "loop": loop_mode(anim["loop"]),
            "override": False,
            "length": max(anim["length"], max_time) or 1.0,
            "snapping": 24,
            "selected": False,
            "anim_time_update": "",
            "blend_weight": "",
            "start_delay": "",
            "loop_delay": "",
            "animators": animators,
        })
    return out, skipped


def inject(bbmodel_path: Path, anim_dir: Path, wanted: tuple[str, ...], dry: bool) -> str:
    data = json.loads(bbmodel_path.read_text(encoding="utf-8"))
    groups = data.get("groups") or []
    by_name = {g.get("name"): g.get("uuid") for g in groups if g.get("name") and g.get("uuid")}
    if not by_name:
        return f"[skip] {bbmodel_path.name}: groups 里没有可用的骨骼名/uuid"

    anim_files = sorted(anim_dir.glob("*.animation.json")) if anim_dir.is_dir() else []
    if not anim_files:
        return f"[skip] {bbmodel_path.name}: 没有 animation/*.animation.json"

    new_anims, skipped = convert_animations(anim_files, wanted)
    if not new_anims:
        return f"[skip] {bbmodel_path.name}: 源动画里没有目标动作 {wanted}"

    # 骨骼名 → 真实 uuid；找不到的骨骼整个 animator 丢弃（少动一根，不报错）
    resolved, missing = {}, set()
    for anim in new_anims:
        real: dict[str, dict] = {}
        for key, animator in anim["animators"].items():
            bone = key[1:]  # 去掉占位前缀 @
            uid = by_name.get(bone)
            if uid:
                real[uid] = animator
            else:
                missing.add(bone)
        anim["animators"] = real
    resolved = [a for a in new_anims if a["animators"]]

    if not resolved:
        return f"[skip] {bbmodel_path.name}: 目标骨骼一个都匹配不上"

    names = {a["name"] for a in resolved}
    old = data.get("animations") or []
    data["animations"] = [a for a in old if a.get("name") not in names] + resolved

    detail = ", ".join(f"{a['name']}({len(a['animators'])}骨)" for a in resolved)
    miss = f"，缺骨骼:{sorted(missing)}" if missing else ""
    if dry:
        return f"[dry] {bbmodel_path.name}: {detail}，跳过molang帧{skipped}{miss}"

    backup = bbmodel_path.with_suffix(".bbmodel.orig")
    if not backup.exists():
        shutil.copy2(bbmodel_path, backup)
    bbmodel_path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return f"[ok] {bbmodel_path.name}: {detail}，跳过molang帧{skipped}{miss}"


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    wanted = DEFAULT_ANIMS
    for a in sys.argv[1:]:
        if a.startswith("--anims="):
            wanted = tuple(x for x in a.split("=", 1)[1].split(",") if x)
    dry = "--dry-run" in sys.argv
    if not args:
        print(__doc__)
        return 2

    targets: list[tuple[Path, Path]] = []
    for arg in args:
        p = Path(arg)
        if p.is_file() and p.suffix == ".bbmodel":
            targets.append((p, p.parent / "animation"))
        elif p.is_dir():
            for bb in sorted(p.rglob("*.bbmodel")):
                if bb.name.endswith(".orig") or bb.name.endswith(".bak"):
                    continue
                targets.append((bb, bb.parent / "animation"))

    for bb, anim_dir in targets:
        print(inject(bb, anim_dir, wanted, dry))
    return 0


if __name__ == "__main__":
    sys.exit(main())
