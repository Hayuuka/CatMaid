#!/usr/bin/env python3
"""TLM（车万女仆）模型 → Blockbench .bbmodel（BetterModel 可直接加载）

处理三件事：
  1. 基岩几何（bones/cubes）→ bbmodel 的 outliner/elements
  2. UV：box UV 展开成逐面 UV；up/down 面按 Blockbench 的角点反序写，否则上下两面贴图会翻
  3. geckolib 动画 json → bbmodel 的 animations（TLM 的模型和动画本来就是分开的两个文件）

UV 尺寸取值顺序：几何声明的 texture_width/height（1.12 格式）> 老格式 texturewidth/height > 贴图实际尺寸。
**不要**在几何没声明时默认 64——酒狐就是被这个默认值把 UV 缩了一半，贴图整个错位。

用法：
  python tlm_to_bbmodel.py <模型.json> <贴图.png> <输出.bbmodel> [--anim A.json ...] [--anims walk,sit]

注意：TLM 美术资源 CC BY-NC-SA 4.0，仅非商用服可用，须署名 TartaricAcid 与 TLM 项目，
并以相同协议共享改编物；转换产物属部署资产，不进代码仓库。
"""
from __future__ import annotations

import base64
import json
import sys
import uuid
from pathlib import Path

ALL_FACES = ("north", "east", "south", "west", "up", "down")
# 默认只导常用动作，maid.animation.json 里还有 swim/chair 等 90 多个用不上的
DEFAULT_ANIMS = ("idle", "walk", "run", "sit", "jump", "attacked", "death")


def ns_uuid(name: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "catmaid/tlm/" + name))


def png_size(png: bytes) -> tuple[int, int]:
    return int.from_bytes(png[16:20], "big"), int.from_bytes(png[20:24], "big")


# ---------- 几何 ----------

def box_face_rects(u: float, v: float, w: float, h: float, d: float) -> dict[str, list[float]]:
    """box UV 的六面矩形（未处理角点顺序）。"""
    return {
        "north": [u + d, v + d, u + d + w, v + d + h],
        "east": [u, v + d, u + d, v + d + h],
        "south": [u + d + w, v + d, u + d + w + w, v + d + h],
        "west": [u + d + w + d, v + d, u + d + w + d + d, v + d + h],
        "up": [u + d, v, u + d + w, v + d],
        "down": [u + d + w, v, u + d + w + w, v + d],
    }


def face_entry(rect: list[float], face: str) -> dict:
    """Blockbench 的角点顺序：四个侧面正向，up/down 两个角点反序（否则上下两面贴图翻转）。"""
    x1, y1, x2, y2 = rect
    if face in ("up", "down"):
        x1, y1, x2, y2 = x2, y2, x1, y1
    return {"uv": [x1, y1, x2, y2], "texture": 0}


def per_face_rects(uv_obj: dict, size: list[float]) -> dict[str, list[float]]:
    out: dict[str, list[float]] = {}
    for face in ALL_FACES:
        entry = uv_obj.get(face)
        if not entry:
            continue
        u, v = entry.get("uv", [0, 0])[:2]
        fw, fh = entry.get("uv_size", [size[0], size[1]])[:2]
        out[face] = [u, v, u + fw, v + fh]  # fh 为负数时自然形成反向矩形（镜像面）
    return out


def convert_cube(cube: dict, bone_pivot: list[float]) -> dict:
    origin = [float(x) for x in cube.get("origin", [0, 0, 0])]
    size = [float(x) for x in cube.get("size", [1, 1, 1])]
    uv = cube.get("uv", [0, 0])

    if isinstance(uv, dict):
        rects = per_face_rects(uv, size)
    else:
        rects = box_face_rects(uv[0], uv[1], size[0], size[1], size[2])

    faces = {face: face_entry(rects[face], face) for face in ALL_FACES if face in rects}
    return {
        "name": "cube",
        "uuid": "",
        "type": "cube",
        "from": origin,
        "to": [origin[i] + size[i] for i in range(3)],
        "origin": [float(x) for x in cube.get("pivot", bone_pivot)],
        "rotation": [float(x) for x in cube.get("rotation", [0, 0, 0])],
        "inflate": float(cube.get("inflate", 0)),
        "faces": faces,
        "box_uv": not isinstance(uv, dict),
        "uv_offset": list(uv) if not isinstance(uv, dict) else [0, 0],
    }


def geometry_of(data: dict) -> tuple[dict, int, int]:
    """兼容两种写法：1.12 的 minecraft:geometry[0].description，和老的 geometry.model。"""
    if "minecraft:geometry" in data:
        geo = data["minecraft:geometry"][0]
        desc = geo.get("description", {})
        return geo, int(desc.get("texture_width", 0)), int(desc.get("texture_height", 0))
    geo = data["geometry.model"]
    return geo, int(geo.get("texturewidth", 0)), int(geo.get("textureheight", 0))


def convert_geometry(geo: dict, name: str, png: bytes, tex_w: int, tex_h: int) -> dict:
    bones = geo.get("bones", [])
    by_name = {bone["name"]: bone for bone in bones}
    children: dict[str, list[str]] = {bone["name"]: [] for bone in bones}
    roots: list[str] = []
    for bone in bones:
        parent = bone.get("parent")
        if parent and parent in by_name:
            children[parent].append(bone["name"])
        else:
            roots.append(bone["name"])

    elements: list[dict] = []
    cubes_of: dict[str, list[str]] = {}
    for bone in bones:
        pivot = [float(x) for x in bone.get("pivot", [0, 0, 0])]
        ids: list[str] = []
        for i, cube in enumerate(bone.get("cubes", [])):
            element = convert_cube(cube, pivot)
            element["uuid"] = ns_uuid(f"{name}/{bone['name']}/{i}")
            element["name"] = f"{bone['name']}_{i}"
            elements.append(element)
            ids.append(element["uuid"])
        cubes_of[bone["name"]] = ids

    def build_group(bone_name: str) -> dict:
        bone = by_name[bone_name]
        return {
            "name": bone_name,
            "uuid": ns_uuid(f"{name}/group/{bone_name}"),
            "origin": [float(x) for x in bone.get("pivot", [0, 0, 0])],
            "rotation": [float(x) for x in bone.get("rotation", [0, 0, 0])],
            # 元素引用必须是裸 uuid 字符串：BetterModel 的反序列化把字符串当引用、对象当子分组
            "children": list(cubes_of[bone_name]) + [build_group(c) for c in children[bone_name]],
        }

    return {
        "meta": {"format_version": "5.0", "model_format": "bedrock", "box_uv": False},
        "name": name,
        "model_identifier": name,
        "variable_placeholders": "",
        "variable_placeholder_buttons": [],
        "resolution": {"width": tex_w, "height": tex_h},
        "elements": elements,
        "outliner": [build_group(root) for root in roots],
        "textures": [
            {
                "name": f"{name}.png",
                "path": "",
                "relative_path": f"{name}.png",
                "folder": "",
                "namespace": "",
                "id": "0",
                "group": "",
                "width": tex_w,
                "height": tex_h,
                "uv_width": tex_w,
                "uv_height": tex_h,
                "particle": False,
                "render_mode": "default",
                "render_sides": "auto",
                "frame_time": 1,
                "frame_order_type": "loop",
                "frame_order": "",
                "frame_interpolate": False,
                "visible": True,
                "internal": True,
                "saved": True,
                "uuid": ns_uuid(f"{name}/texture"),
                "source": "data:image/png;base64," + base64.b64encode(png).decode("ascii"),
            }
        ],
        "animations": [],
    }


# ---------- 动画 ----------

def anim_value(entry) -> list[float] | None:
    """geckolib 的关键帧值：要么 [x,y,z]，要么 {pre/post: [...]}。

    有少量关键帧是 molang 表达式（如 "0.4*ysm.head_yaw"），bbmodel 里没法表达，返回 None 跳过，
    不要因为它们让整段动画转换失败。
    """
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


def anim_easing(entry) -> str:
    if isinstance(entry, dict) and entry.get("lerp_mode") == "catmullrom":
        return "catmullrom"
    return "linear"


def bone_uuid(model_name: str, bone: str) -> str:
    return ns_uuid(f"{model_name}/group/{bone}")


def convert_animations(model_name: str, anim_files: list[Path], wanted: tuple[str, ...]) -> list[dict]:
    """geckolib animations → bbmodel animations。多个文件（maid 通用 + 角色专属）按动画名合并。"""
    merged: dict[str, dict] = {}
    for path in anim_files:
        data = json.loads(path.read_text(encoding="utf-8"))
        for anim_name, anim in data.get("animations", {}).items():
            if wanted and anim_name not in wanted:
                continue
            slot = merged.setdefault(anim_name, {
                "length": 0.0, "loop": anim.get("loop", False), "bones": {},
            })
            slot["length"] = max(slot["length"], float(anim.get("animation_length", 0)))
            for bone, channels in anim.get("bones", {}).items():
                target = slot["bones"].setdefault(bone, {})
                for channel, frames in channels.items():
                    if channel not in ("rotation", "position", "scale"):
                        continue
                    # 静态姿势（idle/sit 常见）：直接给 [x,y,z] 而不是 时间→值 的字典，
                    # 当作 0 秒的单个关键帧处理，否则整个动画会被丢掉
                    if isinstance(frames, (int, float)):
                        frames = {"0.0": [frames, frames, frames]}
                    elif isinstance(frames, list):
                        frames = {"0.0": frames}
                    if not isinstance(frames, dict):
                        continue
                    target.setdefault(channel, {}).update(frames)

    out: list[dict] = []
    for anim_name, anim in merged.items():
        animators: dict[str, dict] = {}
        for bone, channels in anim["bones"].items():
            keyframes = []
            for channel, frames in channels.items():
                points = []
                try:
                    ordered = sorted(frames.items(), key=lambda kv: float(kv[0]))
                except (TypeError, ValueError):
                    continue
                for time_str, value in ordered:
                    vec = anim_value(value)
                    if vec is None:
                        continue
                    points.append({
                        "x": vec[0], "y": vec[1], "z": vec[2],
                        "uuid": ns_uuid(f"{model_name}/{anim_name}/{bone}/{channel}/{time_str}"),
                        "time": float(time_str),
                        "color": -1,
                        "interpolation": anim_easing(value),
                    })
                keyframes.append({
                    "channel": channel,
                    "data_points": points,
                    "uuid": ns_uuid(f"{model_name}/{anim_name}/{bone}/{channel}"),
                    "time": 0,
                    "color": -1,
                    "interpolation": "linear",
                })
            if keyframes:
                animators[bone_uuid(model_name, bone)] = {
                    "name": bone, "type": "bone", "keyframes": keyframes,
                }
        if not animators:
            continue
        loop = anim["loop"]
        out.append({
            "name": anim_name,
            "uuid": ns_uuid(f"{model_name}/anim/{anim_name}"),
            "loop": "loop" if loop is True else ("hold" if loop == "hold_on_last_frame" else "once"),
            "override": False,
            "length": anim["length"],
            "snapping": 24,
            "selected": False,
            "anim_time_update": "",
            "blend_weight": "",
            "start_delay": "",
            "loop_delay": "",
            "animators": animators,
        })
    return out


def main() -> int:
    args = sys.argv[1:]
    anim_paths: list[Path] = []
    wanted: tuple[str, ...] = DEFAULT_ANIMS
    rest: list[str] = []
    i = 0
    while i < len(args):
        if args[i] == "--anim":
            anim_paths.append(Path(args[i + 1]))
            i += 2
        elif args[i] == "--anims":
            wanted = tuple(x.strip() for x in args[i + 1].split(",") if x.strip())
            i += 2
        else:
            rest.append(args[i])
            i += 1

    if len(rest) < 3:
        print(__doc__)
        return 2

    model_path, tex_path, out_path = Path(rest[0]), Path(rest[1]), Path(rest[2])
    model_name = out_path.stem
    data = json.loads(model_path.read_text(encoding="utf-8"))
    geo, tex_w, tex_h = geometry_of(data)
    png = tex_path.read_bytes()
    png_w, png_h = png_size(png)
    if tex_w <= 0 or tex_h <= 0:
        # 几何没声明 UV 尺寸时以贴图实际尺寸为准（宁可这样也不要默认 64）
        tex_w, tex_h = png_w, png_h
    if (tex_w, tex_h) != (png_w, png_h):
        print(f"[warn] {model_name}: 几何声明 {tex_w}x{tex_h} 与贴图 {png_w}x{png_h} 不一致，按几何声明写 UV")

    bbmodel = convert_geometry(geo, model_name, png, tex_w, tex_h)
    if anim_paths:
        bbmodel["animations"] = convert_animations(model_name, anim_paths, wanted)

    out_path.write_text(json.dumps(bbmodel, ensure_ascii=False), encoding="utf-8")
    print(f"[ok] {model_name}: {len(bbmodel['elements'])} cubes, "
          f"{tex_w}x{tex_h}, {len(bbmodel['animations'])} anims, {out_path.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
