#!/usr/bin/env python3
"""极简 RCON 客户端，用于 Phase 0 在无客户端环境下驱动测试服控制台。

用法: python rcon.py "命令1" "命令2" ...
"""
import socket
import struct
import sys

HOST = "127.0.0.1"
PORT = 25575
PASSWORD = "catmaid"


def pack(req_id: int, body: int, payload: str) -> bytes:
    data = struct.pack("<ii", req_id, body) + payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read_packet(sock: socket.socket):
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        return None
    (length,) = struct.unpack("<i", raw_len)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    req_id, body = struct.unpack("<ii", data[:8])
    return req_id, body, data[8:-2].decode("utf-8", "replace")


def main() -> int:
    commands = sys.argv[1:]
    sock = socket.create_connection((HOST, PORT), timeout=15)
    sock.sendall(pack(1, 3, PASSWORD))
    resp = read_packet(sock)
    if resp is None or resp[0] == -1:
        print("AUTH FAILED")
        return 1
    for idx, cmd in enumerate(commands, start=2):
        sock.sendall(pack(idx, 2, cmd))
        out = read_packet(sock)
        print(f">>> {cmd}")
        print(out[2] if out else "(no response)")
    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
