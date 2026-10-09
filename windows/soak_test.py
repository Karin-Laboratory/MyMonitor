"""Two-minute-plus receiver reliability test with malformed JPEGs and TCP reconnects.

Run only on GitHub Actions Windows, where the real standalone EXE is exercised.
This cannot model a real Wi-Fi radio or USB capture adapter.
"""
import ctypes
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

JPEG = Path(sys.argv[1]).read_bytes()
GOOD = struct.pack("!I", len(JPEG)) + JPEG
BAD_IMAGE = b"not-a-jpeg" * 10
BAD = struct.pack("!I", len(BAD_IMAGE)) + BAD_IMAGE
u = ctypes.WinDLL("user32")
u.FindWindowW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p]
u.FindWindowW.restype = ctypes.c_void_p
u.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
u.PostMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
process = subprocess.Popen([str(Path("mycastreceiver012.exe").resolve())])
s = None
hwnd = None

def connect():
    sock = socket.create_connection(("127.0.0.1", 57007), timeout=5)
    sock.settimeout(6)
    sock.sendall(b"MMC9")
    return sock

try:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        hwnd = u.FindWindowW("MyCastReceiver012", None)
        if hwnd:
            break
        time.sleep(.1)
    assert hwnd, "Receiver window did not start"
    s = connect()
    start = time.monotonic()
    bad_count = 0
    frames = 650
    for i in range(frames):
        if i and i % 120 == 0:
            s.close()
            s = connect()
        packet = BAD if i % 47 == 46 else GOOD
        s.sendall(packet)
        assert s.recv(1) == b"\x06", f"No ACK / disconnected after frame {i}"
        if packet is BAD:
            bad_count += 1
        time.sleep(.195)
    title = ctypes.create_unicode_buffer(256)
    u.GetWindowTextW(hwnd, title, 256)
    assert "1920 x 1080" in title.value, f"Frame lost: {title.value}"
    assert f"skipped {bad_count} bad frames" in title.value, title.value
    elapsed = time.monotonic() - start
    assert elapsed >= 120, f"Soak ran too quickly: {elapsed}"
    print(f"RELIABILITY SOAK PASS: {frames} packets / {bad_count} corrupt images / "
          f"5 reconnects / {elapsed:.1f}s. Last valid Full HD frame retained.")
finally:
    if s:
        s.close()
    if hwnd:
        u.PostMessageW(hwnd, 0x10, 0, 0)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        raise AssertionError("Receiver did not stop")
