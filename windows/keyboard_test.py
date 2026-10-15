"""v032 receiver keyboard framing smoke test against the actual native EXE.

Tests Win32 UTF-16 -> UTF-8 and the existing 17-byte MMC6 reverse ACK;
Android accessibility behavior still requires phone-side testing.
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
u.SendMessageW.argtypes = (ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t)
u.SendMessageW.restype = ctypes.c_ssize_t
u.PostMessageW.argtypes = u.SendMessageW.argtypes
u.PostMessageW.restype = ctypes.c_bool

WM_CHAR, WM_KEYDOWN, WM_SYSKEYDOWN, WM_CLOSE = 0x0102, 0x0100, 0x0104, 0x0010
VK_BACK = 0x08
exe = pathlib.Path("mycastreceiver032.exe").resolve()
jpeg = pathlib.Path(sys.argv[1]).read_bytes()

def ack(sock, payload):
    sock.sendall(payload)
    data = bytearray()
    while len(data) < 18:
        chunk = sock.recv(18 - len(data))
        assert chunk, "TCP disconnected"
        data.extend(chunk)
    assert data[0] == 6
    return bytes(data[1:])

proc = subprocess.Popen([str(exe)])
hwnd = None
try:
    for _ in range(120):
        hwnd = u.FindWindowW("MyCastReceiver019", None)
        if hwnd:
            break
        time.sleep(.1)
    assert hwnd, "Receiver window never appeared"
    with socket.create_connection(("127.0.0.1", 57007), timeout=5) as sock:
        sock.settimeout(5)
        sock.sendall(b"MMC6")
        assert ack(sock, struct.pack("!I", 0xffffffff) + b"\x02") == bytes(17)
        assert ack(sock, struct.pack("!I", len(jpeg)) + jpeg) == bytes(17)
        time.sleep(.5)  # decoder makes linkConnected true after first valid frame

        # Android is the only consent authority; Windows needs no Alt+R.
        u.SendMessageW(hwnd, WM_CHAR, ord("a"), 0)
        u.SendMessageW(hwnd, WM_CHAR, ord("日"), 0)
        u.SendMessageW(hwnd, WM_KEYDOWN, VK_BACK, 0)

        p = ack(sock, struct.pack("!I", 0))
        assert p[0] == 8, p
        assert p[1] == 4, p
        assert p[2:6] == "a日".encode("utf-8"), p
        k = ack(sock, struct.pack("!I", 0))
        assert k[:2] == bytes([9, 1]), k
        assert ack(sock, struct.pack("!I", 0)) == bytes(17)

        # Physical UVC display is never a keyboard input target.
        assert ack(sock, struct.pack("!I", 0xffffffff) + b"\x01") == bytes(17)
        u.SendMessageW(hwnd, WM_CHAR, ord("b"), 0)
        assert ack(sock, struct.pack("!I", 0)) == bytes(17)

        assert ack(sock, struct.pack("!I", 0xffffffff) + b"\x02") == bytes(17)
        u.SendMessageW(hwnd, WM_CHAR, ord("c"), 0)
        packet = ack(sock, struct.pack("!I", 0))
        assert packet[:3] == bytes([8, 1, ord("c")]), packet
    print("V032 NATIVE UTF8 KEYBOARD / SAFE-OFF / UVC ISOLATION PASS")
finally:
    if hwnd:
        u.PostMessageW(hwnd, WM_CLOSE, 0, 0)
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
