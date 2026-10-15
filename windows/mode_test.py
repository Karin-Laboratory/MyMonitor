"""v029 MMC6 source metadata and reversible F11 smoke test on native Windows EXE."""
import ctypes
import pathlib
import socket
import struct
import subprocess
import time

exe = pathlib.Path("mycastreceiver029.exe").resolve()
assert exe.is_file(), exe
user32 = ctypes.WinDLL("user32", use_last_error=True)
user32.FindWindowW.argtypes = (ctypes.c_wchar_p, ctypes.c_wchar_p)
user32.FindWindowW.restype = ctypes.c_void_p
user32.PostMessageW.argtypes = (ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t)
user32.GetWindowLongW.argtypes = (ctypes.c_void_p, ctypes.c_int)
user32.GetWindowLongW.restype = ctypes.c_long
WS_OVERLAPPEDWINDOW = 0x00CF0000
WM_KEYDOWN, WM_KEYUP, WM_CLOSE, VK_F11 = 0x0100, 0x0101, 0x0010, 0x7A

def read_all(sock, count):
    result = b""
    while len(result) < count:
        block = sock.recv(count - len(result))
        assert block, "receiver closed connection before ACK"
        result += block
    return result

def wait_until(predicate, timeout=3):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(.05)
    return False

process = subprocess.Popen([str(exe)])
hwnd = None
try:
    assert wait_until(lambda: bool(user32.FindWindowW("MyCastReceiver019", None)), 10), "No receiver window"
    hwnd = user32.FindWindowW("MyCastReceiver019", None)
    with socket.create_connection(("127.0.0.1", 57007), timeout=4) as sock:
        sock.settimeout(5)
        sock.sendall(b"MMC6")
        def set_mode(mode):
            sock.sendall(struct.pack("!I", 0xFFFFFFFF) + bytes([mode]))
            ack = read_all(sock, 18)
            assert ack == bytes([6]) + bytes(17), ("Unexpected source-mode ACK", ack)
        def fullscreen():
            return (user32.GetWindowLongW(hwnd, -16) & WS_OVERLAPPEDWINDOW) == 0
        assert not fullscreen(), "Receiver must start windowed"
        set_mode(2) # Android screen cannot trigger UVC auto layout.
        time.sleep(.25)
        assert not fullscreen(), "Phone screen should not force fullscreen"
        set_mode(1) # Real UVC signal, not image resolution.
        time.sleep(.5) # give UI thread time to apply mode
        initial_fullscreen = fullscreen()
        # F11 must toggle and then restore in either monitor-resolution case.
        user32.PostMessageW(hwnd, WM_KEYDOWN, VK_F11, 0)
        assert wait_until(lambda: fullscreen() != initial_fullscreen), "F11 toggle did not occur"
        user32.PostMessageW(hwnd, WM_KEYUP, VK_F11, 0)
        toggled = fullscreen()
        user32.PostMessageW(hwnd, WM_KEYDOWN, VK_F11, 0)
        assert wait_until(lambda: fullscreen() != toggled), "F11 did not restore"
        user32.PostMessageW(hwnd, WM_KEYUP, VK_F11, 0)
        set_mode(2)
        sock.sendall(struct.pack("!I", 0)) # still uses standard video heartbeat
        assert read_all(sock, 18) == bytes([6]) + bytes(17)
        assert process.poll() is None, "Windows receiver crashed after MMC6/F11"
    print("V029 MMC6 SOURCE + F11 REVERSIBLE FULLSCREEN PASS")
finally:
    if hwnd:
        user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
    try:
        process.wait(timeout=8)
    except subprocess.TimeoutExpired:
        process.kill()
