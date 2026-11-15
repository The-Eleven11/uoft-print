"""Minimal RFC 1179 receiver that saves each job's control and data files for inspection."""
import os
import socket
import sys
import time

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5515
OUT = sys.argv[2] if len(sys.argv) > 2 else "lpd_jobs"
os.makedirs(OUT, exist_ok=True)


def read_line(conn):
    buf = b""
    while not buf.endswith(b"\n"):
        c = conn.recv(1)
        if not c:
            raise EOFError
        buf += c
    return buf[:-1]


def read_exact(conn, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = conn.recv(min(1 << 16, n - len(buf)))
        if not chunk:
            raise EOFError
        buf += chunk
    return bytes(buf)


srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("0.0.0.0", PORT))
srv.listen(5)
print(f"listening on {PORT}, saving to {OUT}", flush=True)
while True:
    conn, addr = srv.accept()
    stamp = time.strftime("%H%M%S")
    try:
        first = read_line(conn)
        queue = first[1:].decode()
        print(f"[{stamp}] job for queue {queue!r} from {addr}", flush=True)
        conn.sendall(b"\0")
        order = []
        while True:
            try:
                sub = read_line(conn)
            except EOFError:
                break
            kind = sub[0]
            count, name = sub[1:].split(b" ", 1)
            conn.sendall(b"\0")
            data = read_exact(conn, int(count))
            assert conn.recv(1) == b"\0", "missing terminating zero"
            conn.sendall(b"\0")
            ext = "control" if kind == 2 else "data"
            order.append(ext)
            path = os.path.join(OUT, f"{stamp}_{queue}_{ext}")
            with open(path, "wb") as f:
                f.write(data)
            print(f"  {ext}: {name.decode()} {len(data)} bytes -> {path}", flush=True)
            if ext == "control":
                print("  " + data.decode("ascii", "replace").replace("\n", "\n  "), flush=True)
        print(f"  order: {order}", flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"  error: {e}", flush=True)
    finally:
        conn.close()
