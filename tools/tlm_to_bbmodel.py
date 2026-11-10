#!/usr/bin/env python3
"""TLM（车万女仆）基岩版几何 → Blockbench .bbmodel（BetterModel 可直接加载）

背景：TLM 的角色模型是基岩几何 JSON（bones + cubes，box UV），BetterModel 只吃 .bbmodel。
本脚本做格式搬运，不改几何数据：

  * bone        → outliner 分组（origin=pivot，rotation 原样）
  * cube        → element（type=cube，from=origin，to=origin+size）
  * box UV      → 六个面的逐面 UV（Blockbench 标准展开方式，与 TLM 渲染一致）
  * png 贴图    → 以 data URI 内嵌进 bbmodel（BetterModel 用 Base64 解码）

用法：
  python tlm_to_bbmodel.py <TLM.jar> <输出目录> foxmaid hakurei_reimu ...

注意：TLM 美术资源为 CC BY-NC-SA 4.0，仅可用于非商用服，须署名 TartaricAcid 与 TLM 项目，
并以相同协议共享改编物。转换产物属部署资产，不进代码仓库。
"""
from __future__ import annotations

import base64
import json
import sys
import uuid
import zipfile
from pathlib import Path

PACK_ROOT = "assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/assets"
# 角色模型分散在两处：普通基岩模型在 touhou_little_maid/ 下，geckolib 风格的（如酒狐）在 geckolib/ 下
PACK_PREFIXES = (f"{PACK_ROOT}/touhou_little_maid", f"{PACK_ROOT}/geckolib")
FACES = ("north", "east", "south", "west", "up", "down")


def ns_uuid(name: str) -> str:
    """稳定 uuid：同一个模型重复转换得到同样的 id，便于 diff。"""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "catmaid/tlm/" + name))


def box_face_uvs(u: float, v: float, w: float, h: float, d: float) -> dict[str, list[float]]:
    """基岩 box UV → 六面 UV（左闭右开像素坐标）。"""
    return {
        "north": [u + d, v + d, u + d + w, v + d + h],
        "east": [u, v + d, u + d, v + d + h],
        "south": [u + d + w, v + d, u + d + w + w, v + d + h],
        "west": [u + d + w + d, v + d, u + d + w + d + d, v + d + h],
        "up": [u + d, v, u + d + w, v + d],
        "down": [u + d + w, v, u + d + w + w, v + d],
    }


def per_face_uvs(uv_obj: dict, size: list[float]) -> dict[str, list[float]]:
    """cube 自带逐面 uv 时的分支（少数模型这么写）。"""
    out: dict[str, list[float]] = {}
    for face in FACES:
        entry = uv_obj.get(face)
        if not entry:
            continue
        fu, fv = entry.get("uv", [0, 0])[:2]
        fw, fh = entry.get("uv_size", [size[0], size[1]])[:2]
        out[face] = [fu, fv, fu + fw, fv + fh]
    return out


def convert_cube(cube: dict, bone_pivot: list[float], idx: int) -> dict:
    origin = list(cube.get("origin", [0, 0, 0]))
    size = list(cube.get("size", [1, 1, 1]))
    to = [origin[i] + size[i] for i in range(3)]
    uv = cube.get("uv", [0, 0])

    if isinstance(uv, dict):
        face_uvs = per_face_uvs(uv, size)
    else:
        face_uvs = box_face_uvs(uv[0], uv[1], size[0], size[1], size[2])

    faces = {}
    for face in FACES:
        coords = face_uvs.get(face)
        if coords is None:
            continue
        faces[face] = {"uv": coords, "texture": 0}

    pivot = list(cube.get("pivot", bone_pivot))
    return {
        "name": "cube" if idx == 0 else f"cube{idx}",
        "uuid": ns_uuid(f"cube/{id(cube)}/{origin}/{size}"),
        "type": "cube",
        "from": origin,
        "to": to,
        "origin": pivot,
        "rotation": list(cube.get("rotation", [0, 0, 0])),
        "inflate": float(cube.get("inflate", 0)),
        "faces": faces,
        # Blockbench 用这两个字段决定 UV 编辑模式，保留 box UV 语义
        "box_uv": not isinstance(uv, dict),
        "uv_offset": uv if not isinstance(uv, dict) else [0, 0],
    }


def convert_geometry(geo: dict, name: str, texture_png: bytes, tex_w: int, tex_h: int) -> dict:
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
        pivot = list(bone.get("pivot", [0, 0, 0]))
        cube_ids: list[str] = []
        for i, cube in enumerate(bone.get("cubes", [])):
            element = convert_cube(cube, pivot, i)
            # 同一坐标系里可能撞 uuid，按骨骼名再掺一次
            element["uuid"] = ns_uuid(f"{name}/{bone['name']}/{i}/{cube.get('origin')}")
            elements.append(element)
            cube_ids.append(element["uuid"])
        cubes_of[bone["name"]] = cube_ids

    def build_group(bone_name: str) -> dict:
        bone = by_name[bone_name]
        return {
            "name": bone_name,
            "uuid": ns_uuid(f"{name}/group/{bone_name}"),
            "origin": list(bone.get("pivot", [0, 0, 0])),
            "rotation": list(bone.get("rotation", [0, 0, 0])),
            # 关键：BetterModel 的 outliner 反序列化把"字符串"当元素引用、把"对象"当子分组，
            # 所以 cube 必须是裸 uuid 字符串，不能写成 {"uuid": ...}（会 NPE: children is null）
            "children": list(cubes_of[bone_name]) + [build_group(child) for child in children[bone_name]],
        }

    return {
        "meta": {"format_version": "4.10", "model_format": "bedrock_entity", "box_uv": True},
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
                "folder": "",
                "namespace": "",
                "id": "0",
                "group": "",
                "width": tex_w,
                "height": tex_h,
                "uv_width": tex_w,
                "uv_height": tex_h,
                "particle": True,
                "render_mode": "default",
                "render_sides": "auto",
                "frame_time": 1,
                "frame_order_type": "loop",
                "frame_order": "",
                "frame_interpolate": False,
                "visible": True,
                "mode": "bitmap",
                "saved": False,
                "uuid": ns_uuid(f"{name}/texture"),
                "relative_path": "",
                "source": "data:image/png;base64," + base64.b64encode(texture_png).decode("ascii"),
            }
        ],
        "animations": [],
    }


def load_geometry(raw: bytes) -> dict:
    data = json.loads(raw)
    if "geometry.model" in data:
        return data["geometry.model"]
    geo = data.get("minecraft:geometry")
    if isinstance(geo, list) and geo:
        return geo[0].get("description", {}) | geo[0]
    raise ValueError("无法识别的几何格式")


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    jar_path, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    names = sys.argv[3:]
    out_dir.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(jar_path) as jar:
        entries = set(jar.namelist())
        for name in names:
            model_entry = tex_entry = None
            for prefix in PACK_PREFIXES:
                candidate_model = f"{prefix}/models/entity/{name}.json"
                candidate_tex = f"{prefix}/textures/entity/{name}.png"
                if candidate_model in entries and candidate_tex in entries:
                    model_entry, tex_entry = candidate_model, candidate_tex
                    break
            if model_entry is None:
                print(f"[skip] {name}: 找不到模型 json 或贴图 png")
                continue
            geo = load_geometry(jar.read(model_entry))
            png = jar.read(tex_entry)
            tex_w = int(geo.get("texturewidth", 64))
            tex_h = int(geo.get("textureheight", 64))
            bbmodel = convert_geometry(geo, name, png, tex_w, tex_h)
            target = out_dir / f"{name}.bbmodel"
            target.write_text(json.dumps(bbmodel, ensure_ascii=False), encoding="utf-8")
            print(f"[ok] {name}: {len(bbmodel['elements'])} cubes, {tex_w}x{tex_h}, {target.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
