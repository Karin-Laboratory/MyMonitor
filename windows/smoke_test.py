"""Exercise the actual Windows EXE: icon, Full HD decode/ACK, reconnect, and close."""
import ctypes
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path
u = ctypes.WinDLL('user32', use_last_error=True)
u.FindWindowW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p]
u.FindWindowW.restype = ctypes.c_void_p
u.SendMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
u.SendMessageW.restype = ctypes.c_ssize_t
u.PostMessageW.argtypes = u.SendMessageW.argtypes
u.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
process = subprocess.Popen([str(Path('mycastreceiver010.exe').resolve())])
try:
    deadline = time.monotonic() + 15
    hwnd = None
    while time.monotonic() < deadline:
        hwnd = u.FindWindowW('MyCastReceiver010', None)
        if hwnd: break
        time.sleep(.1)
    assert hwnd, 'Receiver window not created'
    assert u.SendMessageW(hwnd, 0x7f, 0, 0), 'Small window icon missing'
    assert u.SendMessageW(hwnd, 0x7f, 1, 0), 'Large window icon missing'
    jpeg = Path(sys.argv[1]).read_bytes()
    packet = struct.pack('!I', len(jpeg)) + jpeg
    with socket.create_connection(('127.0.0.1', 57007), timeout=3) as s:
        s.sendall(b'MMC9')
        s.sendall(packet[:len(packet)//2])
        s.settimeout(.15)
        try:
            unexpected = s.recv(1)
            raise AssertionError(f'ACK before frame complete: {unexpected!r}')
        except socket.timeout: pass
        s.settimeout(3)
        s.sendall(packet[len(packet)//2:])
        assert s.recv(1) == b'\x06'
        times = []
        for _ in range(20):
            start = time.monotonic()
            s.sendall(packet)
            assert s.recv(1) == b'\x06'
            times.append((time.monotonic()-start)*1000)
        title = ctypes.create_unicode_buffer(256)
        for _ in range(30):
            u.GetWindowTextW(hwnd, title, 256)
            if '1920 x 1080' in title.value: break
            time.sleep(.1)
        assert '1920 x 1080' in title.value, title.value
        print(f'Full HD ACK: mean={sum(times)/len(times):.1f}ms max={max(times):.1f}ms (loopback; not Wi-Fi latency)')
    with socket.create_connection(('127.0.0.1', 57007), timeout=3) as s:
        s.sendall(b'MMC9' + packet)
        assert s.recv(1) == b'\x06', 'Reconnect failed'
    with socket.create_connection(('127.0.0.1', 57007), timeout=3) as s:
        s.sendall(b'MMC9' + struct.pack('!I', 9*1024*1024))
        try: assert s.recv(1) == b'', 'Oversized frame accepted'
        except ConnectionResetError: pass
    print('Icon / partial-frame ACK / Full HD / reconnect / invalid length: PASS')
finally:
    if hwnd: u.PostMessageW(hwnd, 0x10, 0, 0)
    try: process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        raise AssertionError('Receiver failed to shut down')
