"""给 Blockbench .bbmodel 注入 CatMaid 需要的基础动画（walk / sit）。

为什么需要它：TLM 素材转出来的 foxmaid.bbmodel 一个动画关键帧都没有，
`Tracker#animate("walk")` 必定返回 false，客户端就表现为"走路只是平移"。
手工在 Blockbench 里 K 帧当然更精细，这个脚本给的是能用的最小版本：
走路摆臂摆腿 + 轻微起伏、坐下大腿前伸小腿垂下。

用法：
    python tools/bbmodel_anim.py <模型.bbmodel> [--dry-run]

动画约定（与 maid/ModelAnimator 的 Pose 对应）：
    idle  loop  站姿，各骨骼回 bind pose（用来顶掉 sit）
    walk  loop  四肢前后摆，周期 1 秒
    sit   loop  静态单帧，大腿前伸 85°、小腿回落、身体下沉
"""

import json
import sys
import uuid

# 每只模型骨骼命名不同，按名字找；找不到就跳过（不会报错，只是少动一个骨头）
WALK_ARM = 25.0
WALK_LEG = 25.0
SIT_THIGH = 85.0
SIT_BODY_DROP = 8.0


def kf(channel, time, x, y, z, interp="catmullrom"):
    return {
        "channel": channel,
        "data_points": [{"x": f"{x}", "y": f"{y}", "z": f"{z}"}],
        "uuid": str(uuid.uuid4()),
        "time": time,
        "color": -1,
        "interpolation": interp,
    }


def bone(name, keyframes):
    return {
        "name": name,
        "type": "bone",
        "rotation_global": False,
        "quaternion_interpolation": False,
        "keyframes": keyframes,
    }


def animation(name, length, animators):
    return {
        "uuid": str(uuid.uuid4()),
        "name": name,
        "loop": "loop",
        "override": False,
        "length": length,
        "snapping": 24,
        "selected": False,
        "group_name": "",
        "scope": 0,
        "anim_time_update": "",
        "blend_weight": "",
        "start_delay": "",
        "loop_delay": "",
        "animators": animators,
    }


def build_walk(groups):
    """走路：左右臂/腿反相摆动，身体轻微上下。手臂保留各自的 Z 倾斜（自然垂放）。"""
    a = {}
    plan = [
        ("LeftArm", [0, WALK_ARM, 0, -WALK_ARM, 0], -13.0),
        ("RightArm", [0, -WALK_ARM, 0, WALK_ARM, 0], 13.0),
        ("LeftLeg", [0, -WALK_LEG, 0, WALK_LEG, 0], 0.0),
        ("RightLeg", [0, WALK_LEG, 0, -WALK_LEG, 0], 0.0),
    ]
    for name, xs, z in plan:
        if name not in groups:
            continue
        kfs = [kf("rotation", i * 0.25, x, 0, z) for i, x in enumerate(xs)]
        a[groups[name]] = bone(name, kfs)

    for name in ("LeftLowerLeg", "RightLowerLeg"):
        if name in groups:
            kfs = [kf("rotation", 0.0, 6, 0, 0), kf("rotation", 0.5, 0, 0, 0)]
            a[groups[name]] = bone(name, kfs)

    if "AllBody" in groups:
        # position 通道单位是模型像素，0.4 格 ≈ 走路的轻微起伏
        kfs = [kf("position", 0.0, 0, 0, 0), kf("position", 0.25, 0, 0.4, 0),
               kf("position", 0.5, 0, 0, 0), kf("position", 0.75, 0, 0.4, 0)]
        a[groups["AllBody"]] = bone("AllBody", kfs)
    return a


def build_sit(groups):
    """坐下：大腿向前（模型正面是 -Z，绕 X 正向即向前）、小腿回落到竖直，身体下沉。"""
    a = {}
    for thigh, calf in (("LeftLeg", "LeftLowerLeg"), ("RightLeg", "RightLowerLeg")):
        if thigh in groups:
            a[groups[thigh]] = bone(thigh, [kf("rotation", 0.0, SIT_THIGH, 0, 0)])
        if calf in groups:
            a[groups[calf]] = bone(calf, [kf("rotation", 0.0, -SIT_THIGH, 0, 0)])
    if "AllBody" in groups:
        a[groups["AllBody"]] = bone("AllBody", [kf("position", 0.0, 0, -SIT_BODY_DROP, 0)])
    for arm, z in (("LeftArm", -13.0), ("RightArm", 13.0)):
        if arm in groups:
            a[groups[arm]] = bone(arm, [kf("rotation", 0.0, 8, 0, z)])
    return a


def build_idle(groups):
    """站姿：把 walk/sit 动过的骨头显式归位。

    为什么要专门有一条 idle：实测 `stopAnimation("sit")` 之后模型**仍然保持坐姿**
    （玩家型 NPC 的 tracker 不会自己回到 bind pose），必须有另一条动画去顶掉它。
    旋转值写的是 bind pose（手臂各带 ±13° 的自然倾斜），不是全 0，否则手臂会平举。
    """
    a = {}
    plan = [
        ("LeftArm", 0.0, 0.0, -13.0),
        ("RightArm", 0.0, 0.0, 13.0),
        ("LeftLeg", 0.0, 0.0, 0.0),
        ("RightLeg", 0.0, 0.0, 0.0),
        ("LeftLowerLeg", 0.0, 0.0, 0.0),
        ("RightLowerLeg", 0.0, 0.0, 0.0),
    ]
    for name, x, y, z in plan:
        if name in groups:
            a[groups[name]] = bone(name, [kf("rotation", 0.0, x, y, z)])
    if "AllBody" in groups:
        a[groups["AllBody"]] = bone("AllBody", [kf("position", 0.0, 0, 0, 0)])
    return a


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    dry = "--dry-run" in sys.argv
    data = json.load(open(path, encoding="utf-8"))

    groups = {g["name"]: g["uuid"] for g in data.get("groups") or [] if g.get("name")}
    anims = data.get("animations") or []

    new = [
        animation("idle", 1.0, build_idle(groups)),
        animation("walk", 1.0, build_walk(groups)),
        animation("sit", 1.0, build_sit(groups)),
    ]
    names = {n["name"] for n in new}
    anims = [a for a in anims if a.get("name") not in names] + new
    data["animations"] = anims

    print(f"{path}: idle {len(new[0]['animators'])} / walk {len(new[1]['animators'])}"
          f" / sit {len(new[2]['animators'])} 个骨骼")
    if dry:
        return 0
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print("已写入")
    return 0


if __name__ == "__main__":
    sys.exit(main())
