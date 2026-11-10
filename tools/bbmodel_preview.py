#!/usr/bin/env python3
"""把 .bbmodel 软渲染成 PNG，用于在没有 Minecraft 客户端时检查模型/贴图是否正确。

做的事：读 bbmodel → 递归展开 outliner 的骨骼变换（pivot + rotation，bind pose）→
正交投影 → z-buffer 光栅化 → 按面的 UV 采样贴图 → 输出 PNG。

支持的视角：front(-Z) / back(+Z) / left(-X) / right(+X) / top(+Y) / bottom(-Y) / iso。

用法：
  python bbmodel_preview.py <模型.bbmodel> <输出.png> [--size 640] [--view front]
  python bbmodel_preview.py <模型.bbmodel> <输出.png> --sheet    # 一次输出六视图拼图
"""
from __future__ import annotations

import base64
import json
import math
import sys
from io import BytesIO
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# Blockbench 每个面的四个顶点，顺序固定为「从外面看的 左上 → 左下 → 右下 → 右上」，
# uv 的 [x1,y1] 对应第一个顶点（左上），依次 (u1,v2) (u2,v2) (u2,v1)。
# 之前版本把起始顶点写成右下，导致所有预览整体翻转 180°，看什么都像贴图错乱。
FACE_VERTS = {
    "north": lambda x1, y1, z1, x2, y2, z2: [(x2, y2, z1), (x2, y1, z1), (x1, y1, z1), (x1, y2, z1)],
    "south": lambda x1, y1, z1, x2, y2, z2: [(x1, y2, z2), (x1, y1, z2), (x2, y1, z2), (x2, y2, z2)],
    "west": lambda x1, y1, z1, x2, y2, z2: [(x1, y2, z2), (x1, y1, z2), (x1, y1, z1), (x1, y2, z1)],
    "east": lambda x1, y1, z1, x2, y2, z2: [(x2, y2, z1), (x2, y1, z1), (x2, y1, z2), (x2, y2, z2)],
    "up": lambda x1, y1, z1, x2, y2, z2: [(x1, y2, z1), (x1, y2, z2), (x2, y2, z2), (x2, y2, z1)],
    "down": lambda x1, y1, z1, x2, y2, z2: [(x1, y1, z2), (x1, y1, z1), (x2, y1, z1), (x2, y1, z2)],
}
FACE_UV_ORDER = ((0, 0), (0, 1), (1, 1), (1, 0))  # (u1,v1) (u1,v2) (u2,v2) (u2,v1)

VIEWS = {
    "front": (0.0, 0.0),
    "back": (math.pi, 0.0),
    "right": (math.pi / 2, 0.0),
    "left": (-math.pi / 2, 0.0),
    "top": (0.0, math.pi / 2 - 1e-4),
    "bottom": (0.0, -math.pi / 2 + 1e-4),
    "iso": (math.radians(35), math.radians(20)),
}


def rot_xyz(rx: float, ry: float, rz: float) -> np.ndarray:
    """Blockbench/three.js 的 ZYX 欧拉顺序（先 z，再 y，最后 x）。"""
    rx, ry, rz = (math.radians(v) for v in (rx, ry, rz))
    cx, sx = math.cos(rx), math.sin(rx)
    cy, sy = math.cos(ry), math.sin(ry)
    cz, sz = math.cos(rz), math.sin(rz)
    mx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]], dtype=float)
    my = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], dtype=float)
    mz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]], dtype=float)
    return mx @ my @ mz


def pivot_matrix(origin, rotation) -> np.ndarray:
    """绕 origin 旋转的 4x4 变换。"""
    m = np.eye(4)
    m[:3, :3] = rot_xyz(*rotation)
    o = np.array(origin, dtype=float)
    m[:3, 3] = o - m[:3, :3] @ o
    return m


def load_texture(bb: dict) -> Image.Image:
    src = bb["textures"][0]["source"]
    if src.startswith("data:"):
        return Image.open(BytesIO(base64.b64decode(src.split(",", 1)[1]))).convert("RGBA")
    return Image.open(Path(src)).convert("RGBA")


def collect_quads(bb: dict) -> list[tuple[np.ndarray, np.ndarray]]:
    """返回 (4x3 顶点世界坐标, 4x2 UV) 列表，已应用骨骼变换。"""
    elements = {e["uuid"]: e for e in bb["elements"]}
    quads: list[tuple[np.ndarray, np.ndarray]] = []

    def emit(elem: dict, parent: np.ndarray) -> None:
        local = np.eye(4)
        if elem.get("rotation") and any(abs(v) > 1e-6 for v in elem["rotation"]):
            local = pivot_matrix(elem.get("origin") or [0, 0, 0], elem["rotation"])
        m = parent @ local
        x1, y1, z1 = elem["from"]
        x2, y2, z2 = elem["to"]
        for face, data in elem.get("faces", {}).items():
            verts = np.array(FACE_VERTS[face](x1, y1, z1, x2, y2, z2), dtype=float)
            verts = (m @ np.hstack([verts, np.ones((4, 1))]).T).T[:, :3]
            u1, v1, u2, v2 = data["uv"]
            uv = np.array([(u1, v1), (u1, v2), (u2, v2), (u2, v1)], dtype=float)
            quads.append((verts, uv))

    def walk(node, parent: np.ndarray) -> None:
        if isinstance(node, str):
            elem = elements.get(node)
            if elem:
                emit(elem, parent)
            return
        m = parent
        if node.get("rotation") and any(abs(v) > 1e-6 for v in node["rotation"]):
            m = parent @ pivot_matrix(node.get("origin") or [0, 0, 0], node["rotation"])
        for child in node.get("children", []):
            walk(child, m)

    for root in bb["outliner"]:
        walk(root, np.eye(4))
    return quads


def render(bb: dict, tex: Image.Image, size: int, view: str) -> Image.Image:
    quads = collect_quads(bb)
    if not quads:
        raise SystemExit("模型没有可用几何")
    allv = np.vstack([q[0] for q in quads])
    lo, hi = allv.min(axis=0), allv.max(axis=0)
    center = (lo + hi) / 2
    span = float((hi - lo).max())
    step = (size - 16) / max(span, 1e-6)

    yaw, pitch = VIEWS[view]
    cy, sy = math.cos(yaw), math.sin(yaw)
    cp, sp = math.cos(pitch), math.sin(pitch)
    rot_y = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]], dtype=float)
    rot_x = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]], dtype=float)
    # 先转 yaw 再转 pitch；pitch 让摄像机从上方看
    rot = rot_x @ rot_y

    res = bb["resolution"]
    tex_np = np.array(tex)
    th, tw = tex_np.shape[:2]
    uv_scale = np.array([tw / res["width"], th / res["height"]])

    color = np.zeros((size, size, 4), dtype=np.uint8)
    depth = np.full((size, size), -1e9, dtype=float)

    for verts, uv in quads:
        p = (rot @ (verts - center).T).T
        sx = size / 2 + p[:, 0] * step
        sy_ = size / 2 - p[:, 1] * step
        sz = p[:, 2]

        # 两个三角形：0-1-2, 0-2-3
        for tri in ((0, 1, 2), (0, 2, 3)):
            tx = sx[list(tri)]
            ty = sy_[list(tri)]
            tz = sz[list(tri)]
            tu = (uv[list(tri)] * uv_scale)
            area = (tx[1] - tx[0]) * (ty[2] - ty[0]) - (tx[2] - tx[0]) * (ty[1] - ty[0])
            if abs(area) < 1e-9:
                continue
            x_lo = max(0, int(np.floor(tx.min())))
            x_hi = min(size - 1, int(np.ceil(tx.max())))
            y_lo = max(0, int(np.floor(ty.min())))
            y_hi = min(size - 1, int(np.ceil(ty.max())))
            if x_hi < x_lo or y_hi < y_lo:
                continue
            xs = np.arange(x_lo, x_hi + 1)
            ys = np.arange(y_lo, y_hi + 1)
            gx, gy = np.meshgrid(xs, ys)
            px = gx + 0.5
            py = gy + 0.5
            w0 = ((tx[1] - px) * (ty[2] - py) - (tx[2] - px) * (ty[1] - py)) / area
            w1 = ((tx[2] - px) * (ty[0] - py) - (tx[0] - px) * (ty[2] - py)) / area
            w2 = 1.0 - w0 - w1
            mask = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not mask.any():
                continue
            zz = w0 * tz[0] + w1 * tz[1] + w2 * tz[2]
            uu = w0 * tu[0, 0] + w1 * tu[1, 0] + w2 * tu[2, 0]
            vv = w0 * tu[0, 1] + w1 * tu[1, 1] + w2 * tu[2, 1]
            region = depth[y_lo:y_hi + 1, x_lo:x_hi + 1]
            zslot = zz > region
            upd = mask & zslot
            if not upd.any():
                continue
            region[upd] = zz[upd]
            tx_i = np.clip(uu[upd].astype(int), 0, tw - 1)
            ty_i = np.clip(vv[upd].astype(int), 0, th - 1)
            pix = tex_np[ty_i, tx_i]
            dst = color[y_lo:y_hi + 1, x_lo:x_hi + 1]
            dst[upd] = pix

    img = Image.fromarray(color, "RGBA")
    # 白底便于观察
    bg = Image.new("RGBA", img.size, (245, 245, 248, 255))
    return Image.alpha_composite(bg, img)


def main() -> int:
    raw = sys.argv[1:]
    flags = {"sheet": False}
    vals: list[str] = []
    size = 640
    view = "front"
    i = 0
    while i < len(raw):
        a = raw[i]
        if a == "--sheet":
            flags["sheet"] = True
            i += 1
        elif a == "--size":
            size = int(raw[i + 1])
            i += 2
        elif a == "--view":
            view = raw[i + 1]
            i += 2
        else:
            vals.append(a)
            i += 1
    if len(vals) < 2:
        print(__doc__)
        return 2

    bb = json.loads(Path(vals[0]).read_text(encoding="utf-8"))
    tex = load_texture(bb)
    out = Path(vals[1])

    if flags["sheet"]:
        order = ["front", "back", "right", "left", "top", "iso"]
        cell = size // 2
        sheet = Image.new("RGBA", (cell * 3, cell * 2), (245, 245, 248, 255))
        for idx, v in enumerate(order):
            im = render(bb, tex, cell, v)
            sheet.paste(im, ((idx % 3) * cell, (idx // 3) * cell))
            ImageDraw.Draw(sheet).text(((idx % 3) * cell + 6, (idx // 3) * cell + 4), v, fill=(20, 20, 20, 255))
        sheet.save(out)
        print(f"[ok] {out}  sheet {cell * 3}x{cell * 2}")
    else:
        render(bb, tex, size, view).save(out)
        print(f"[ok] {out}  {size}x{size} view={view}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
