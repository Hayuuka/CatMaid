#!/usr/bin/env python3
"""资源包托管：把 BetterModel 最新生成的 build*.zip 同步成固定名，更新 server.properties，并起一个 HTTP 服务。

为什么需要它（api-facts 25.1）：
  BetterModel 每次构建资源包都写 build<N>.zip，N 递增。server.properties 里一旦写死 build8.zip，
  下次重启生成了 build9.zip，客户端拿到的就是旧包（模型缺失）或 sha1 不匹配（客户端直接拒绝下载）。
  这里把"最新包"复制成固定名 catmaid-pack.zip，重算 sha1 并写回 server.properties，
  于是无论 N 涨到多少，URL 都稳定。

用法：
  python tools/pack_host.py sync     只同步 + 更新 server.properties（起服前跑一次）
  python tools/pack_host.py serve    同步后在本机 8123 端口托管（前台，Ctrl+C 停）
"""

from __future__ import annotations

import hashlib
import re
import shutil
import sys
import zipfile
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BM_DIR = ROOT / "testserver" / "plugins" / "BetterModel"
HOST_DIR = ROOT / "testserver" / "packhost"
PACK_NAME = "catmaid-pack.zip"
PROPERTIES = ROOT / "testserver" / "server.properties"
PORT = 8123


def latest_build() -> Path:
    """取最新的资源包：编号最大的 build<N>.zip，或更新时间更新的裸名 build.zip。

    2026-11-13 修订：BetterModel 3.6.0 每次启动只重写裸名 build.zip（编号只增不减是
    3.7.0 的行为），所以裸名文件比最大编号还要新时以它为准，否则 sync 会拿到旧包。
    """
    candidates = []
    for path in BM_DIR.glob("build*.zip"):
        digits = re.fullmatch(r"build(\d+)\.zip", path.name)
        if digits:
            candidates.append((int(digits.group(1)), path))
    if not candidates:
        raise SystemExit(f"[pack] {BM_DIR} 下没有 build<N>.zip，先启动一次服务器让 BetterModel 生成资源包")
    numbered = max(candidates)[1]
    bare = BM_DIR / "build.zip"
    if bare.is_file() and bare.stat().st_mtime > numbered.stat().st_mtime:
        return bare
    return numbered


def sha1_of(path: Path) -> str:
    digest = hashlib.sha1()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sync() -> Path:
    source = latest_build()
    HOST_DIR.mkdir(parents=True, exist_ok=True)
    target = HOST_DIR / PACK_NAME
    shutil.copyfile(source, target)

    with zipfile.ZipFile(target) as pack:
        entries = pack.namelist()
    models = sum(1 for name in entries if "/foxmaid" in name)

    digest = sha1_of(target)
    url = f"http://127.0.0.1:{PORT}/{PACK_NAME}"

    # Java Properties 里 ':' 需要转义；沿用服里原有的写法，免得读配置时被当成 key 分隔符
    escaped = url.replace(":", "\\:")
    text = PROPERTIES.read_text(encoding="utf-8")
    text = re.sub(r"(?m)^resource-pack=.*$", f"resource-pack={escaped}", text)
    text = re.sub(r"(?m)^resource-pack-sha1=.*$", f"resource-pack-sha1={digest}", text)
    PROPERTIES.write_text(text, encoding="utf-8")

    size_mb = target.stat().st_size / 1048576
    print(f"[pack] 源包      {source.name}")
    print(f"[pack] 固定名    {target}（{size_mb:.2f} MB / {len(entries)} 项，foxmaid {models} 项）")
    print(f"[pack] sha1      {digest}")
    print(f"[pack] URL       {url}")
    return target


def serve() -> None:
    sync()
    handler = lambda *a, **kw: SimpleHTTPRequestHandler(*a, directory=str(HOST_DIR), **kw)  # noqa: E731
    server = ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    print(f"[pack] 托管中 http://127.0.0.1:{PORT}/  （Ctrl+C 停）")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[pack] 停止托管")
        server.server_close()


if __name__ == "__main__":
    action = sys.argv[1] if len(sys.argv) > 1 else "sync"
    if action == "sync":
        sync()
    elif action == "serve":
        serve()
    else:
        raise SystemExit("用法: pack_host.py sync|serve")
