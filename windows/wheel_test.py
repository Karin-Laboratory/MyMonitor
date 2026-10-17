"""v033 native receiver test: fast Ctrl-wheel batches are bounded pinch packets.

Runs against the built Windows EXE and reads its actual MMC6 reverse ACKs.
Android GestureResultCallback queuing is checked by source/Android build, not
asserted here as no Android hardware is available in Windows CI.
"""
import ctypes
import pathlib
import socket
import struct
import subprocess
import sys
import time

u = ctypes.WinDLL("user32", use_last_error=True)
u.FindWindowW.argtypes = (ctypes.c_wchar_p, ctypes.c_wchar_p)
u.FindWindowW.restype = ctypes.c_void_p
u.PostMessageW.argtypes = (ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t)
u.PostMessageW.restype = ctypes.c_bool
u.SendMessageW.argtypes = u.PostMessageW.argtypes
u.SendMessageW.restype = ctypes.c_ssize_t
u.ClientToScreen.argtypes = (ctypes.c_void_p, ctypes.c_void_p)
u.ClientToScreen.restype = ctypes.c_bool
u.GetWindowTextW.argtypes = (ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int)
u.GetWindowTextW.restype = ctypes.c_int

class POINT(ctypes.Structure):
    _fields_ = [("x", ctypes.c_long), ("y", ctypes.c_long)]

WM_MOUSEWHEEL = 0x020A
WM_SYSKEYDOWN = 0x0104
WM_CLOSE = 0x0010
MK_CONTROL = 0x0008
WHEEL_DELTA = 120
exe = pathlib.Path("mycastreceiver035.exe").resolve()
jpeg = pathlib.Path(sys.argv[1]).read_bytes()

def read_all(sock, count):
    buf = b""
    while len(buf) < count:
        block = sock.recv(count-len(buf))
        assert block, "Receiver connection closed while waiting for ACK"
        buf += block
    return buf

def ack(sock, packet):
    sock.sendall(packet)
    response = read_all(sock, 18)
    assert response[0] == 6, response
    return response[1:]

def wheel(hwnd, delta, point):
    wp = ((delta & 0xffff) << 16) | MK_CONTROL
    lp = ((point.y & 0xffff) << 16) | (point.x & 0xffff)
    u.SendMessageW(hwnd, WM_MOUSEWHEEL, wp, lp)

def wait_pinch(sock, timeout=2.5):
    deadline = time.monotonic() + timeout
    received = []
    while time.monotonic() < deadline:
        pkt = ack(sock, struct.pack("!I", 0))
        if pkt[0] == 4:
            received.append(pkt)
            time.sleep(.25)
            break
        time.sleep(.04)
    assert received, "No pinch packet produced by Ctrl-wheel"
    # Check there are no extra unbatched packets from a single burst.
    for _ in range(3):
        extra = ack(sock, struct.pack("!I", 0))
        assert extra[0] == 0, f"Extra gesture instead of one merged pinch: {extra[0]}"
    return received[0]

def coord(pkt, offset):
    return struct.unpack_from("!H", pkt, offset)[0]

p = subprocess.Popen([str(exe)])
hwnd = None
try:
    for _ in range(120):
        hwnd = u.FindWindowW("MyCastReceiver019", None)
        if hwnd:
            break
        time.sleep(.1)
    assert hwnd, "Receiver window did not appear"
    with socket.create_connection(("127.0.0.1", 57007), timeout=5) as sock:
        sock.settimeout(5)
        sock.sendall(b"MMC6")
        assert ack(sock, struct.pack("!I", 0xffffffff) + b"\x02") == bytes(17)
        assert ack(sock, struct.pack("!I", len(jpeg)) + jpeg) == bytes(17)
        # The picture must have been decoded before coordinate normalization.
        for _ in range(80):
            title = ctypes.create_unicode_buffer(512)
            u.GetWindowTextW(hwnd, title, len(title))
            if "映像 1920 x 1080" in title.value:
                break
            time.sleep(.08)
        assert "映像 1920 x 1080" in title.value, title.value

        point = POINT(960, 540)
        assert u.ClientToScreen(hwnd, ctypes.byref(point))
        # 5 DOWN detents in one 160ms batch -> exactly one zoom-out gesture.
        for _ in range(5):
            wheel(hwnd, -WHEEL_DELTA, point)
        down = wait_pinch(sock)
        assert coord(down, 5) > coord(down, 1), "Wheel DOWN first finger did not move right"
        assert coord(down, 13) < coord(down, 9), "Wheel DOWN second finger did not move left"

        # Five UP detents -> exactly one zoom-in gesture.
        for _ in range(5):
            wheel(hwnd, WHEEL_DELTA, point)
        up = wait_pinch(sock)
        assert coord(up, 5) < coord(up, 1), "Wheel UP first finger did not move left"
        assert coord(up, 13) > coord(up, 9), "Wheel UP second finger did not move right"

        # High-resolution (half-detent) devices accumulate two deltas into one.
        wheel(hwnd, -60, point)
        wheel(hwnd, -60, point)
        split = wait_pinch(sock)
        assert split[0] == 4

        # UVC remains excluded from remote gestures.
        assert ack(sock, struct.pack("!I", 0xffffffff) + b"\x01") == bytes(17)
        time.sleep(.1)
        for _ in range(3):
            wheel(hwnd, -120, point)
        time.sleep(.25)
        assert ack(sock, struct.pack("!I", 0)) == bytes(17)
        print("V033 RAPID WHEEL BATCH / ZOOM DIRECTION / FRACTIONAL DETENTS / OFF SAFETY PASS")
finally:
    if hwnd:
        u.PostMessageW(hwnd, WM_CLOSE, 0, 0)
    try:
        p.wait(timeout=10)
    except subprocess.TimeoutExpired:
        p.kill()
        raise
