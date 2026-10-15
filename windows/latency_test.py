"""Windows v015 latency regression: ACK is independent of JPEG decode and paint.

Exercises the real packaged receiver EXE, not a mock server. The display/title
tests do not rely on a physical monitor or hardware frame capture.
"""
import ctypes
import re
import socket
import statistics
import struct
import subprocess
import sys
import time
from pathlib import Path

image = Path(sys.argv[1]).read_bytes()
packet = struct.pack("!I", len(image)) + image
user32 = ctypes.WinDLL("user32", use_last_error=True)
user32.FindWindowW.argtypes = (ctypes.c_wchar_p, ctypes.c_wchar_p)
user32.FindWindowW.restype = ctypes.c_void_p
user32.GetWindowTextW.argtypes = (ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int)
user32.PostMessageW.argtypes = (ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t)

user32.GetPropW.argtypes = (ctypes.c_void_p, ctypes.c_wchar_p)
user32.GetPropW.restype = ctypes.c_size_t

def title(hwnd):
    buf = ctypes.create_unicode_buffer(512)
    user32.GetWindowTextW(hwnd, buf, len(buf))
    return buf.value

proc = subprocess.Popen([str(Path("mycastreceiver031.exe").resolve())])
hwnd = None
try:
    for _ in range(150):
        hwnd = user32.FindWindowW("MyCastReceiver019", None)
        if hwnd: break
        time.sleep(.1)
    assert hwnd, "Windows receiver process never opened a window"
    times = []
    with socket.create_connection(("127.0.0.1", 57007), timeout=5) as sock:
        sock.settimeout(5)
        sock.sendall(b"MMC9")
        for i in range(140):
            start = time.perf_counter()
            sock.sendall(packet)
            assert sock.recv(1) == b"\x06", f"missing frame {i} ACK"
            elapsed = (time.perf_counter()-start)*1000.0
            times.append(elapsed)
        # A burst may drop pending frames by design, but a fresh image must
        # eventually decode and display, even if the network pipe goes idle.
        sock.sendall(packet)
        assert sock.recv(1) == b"\x06"
        for _ in range(60):
            caption = title(hwnd)
            decoded = user32.GetPropW(hwnd, "MyCastDecodedFrames")
            if "映像 1920 x 1080" in caption and decoded > 0:
                break
            time.sleep(.1)
        assert "映像 1920 x 1080" in caption and "Android接続中" in caption, caption
        assert decoded > 0, caption
        assert "reconnecting" not in caption, caption
        assert proc.poll() is None, "Windows receiver crashed during burst"
    median = statistics.median(times)
    p95 = sorted(times)[int(len(times)*.95)]
    print(f"FAST ACK BURST PASS: 140 frames, median={median:.1f}ms p95={p95:.1f}ms")
    print(f"ASYNC DECODER / GDI TIMING PASS: {caption}")
    # GitHub virtual machines vary in speed, but half-second ACKs on local
    # loopback indicate a regression. This threshold is a safety guard, not
    # a promise of WAN/Wi-Fi latency.
    assert median < 250, f"Unacceptable loopback ACK median {median:.1f}ms"
    assert p95 < 450, f"Unacceptable loopback ACK p95 {p95:.1f}ms"
finally:
    if hwnd: user32.PostMessageW(hwnd, 0x10, 0, 0)
    try: proc.wait(timeout=12)
    except subprocess.TimeoutExpired: proc.kill()
