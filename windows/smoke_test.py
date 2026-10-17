"""Exercise the actual Windows EXE: icon, Full HD decode/ACK, reconnect, and close."""
import ctypes
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path
u = ctypes.WinDLL('user32', use_last_error=True)
u.GetPropW.argtypes = (ctypes.c_void_p, ctypes.c_wchar_p)
u.GetPropW.restype = ctypes.c_size_t

u.FindWindowW.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p]
u.FindWindowW.restype = ctypes.c_void_p
u.SendMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
u.SendMessageW.restype = ctypes.c_ssize_t
u.PostMessageW.argtypes = u.SendMessageW.argtypes
u.GetWindowTextW.argtypes = [ctypes.c_void_p, ctypes.c_wchar_p, ctypes.c_int]
process = subprocess.Popen([str(Path('mycastreceiver039.exe').resolve())])
try:
    deadline = time.monotonic() + 15
    hwnd = None
    while time.monotonic() < deadline:
        hwnd = u.FindWindowW('MyCastReceiver019', None)
        if hwnd: break
        time.sleep(.1)
    assert hwnd, 'Receiver window not created'
    assert u.SendMessageW(hwnd, 0x7f, 0, 0), 'Small window icon missing'
    assert u.SendMessageW(hwnd, 0x7f, 1, 0), 'Large window icon missing'
    u.GetClientRect.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    class RECT(ctypes.Structure):
        _fields_ = [('left', ctypes.c_long), ('top', ctypes.c_long),
                    ('right', ctypes.c_long), ('bottom', ctypes.c_long)]
    rc = RECT()
    assert u.GetClientRect(hwnd, ctypes.byref(rc))
    assert (rc.right - rc.left, rc.bottom - rc.top) == (1920,1080), f'client size: {rc.right} x {rc.bottom}'
    # A native Windows receiver displays its window slightly before its UDP
    # discovery worker binds. Some CI runners return WSAECONNRESET (10054)
    # when an early UDP probe hits an unopened localhost port. Retry startup,
    # but still require the exact receiver proof before proceeding.
    reply = None
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.settimeout(.4)
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            try:
                udp.sendto(b'MYMONITOR_DISCOVER_V1', ('127.0.0.1', 57008))
                reply, _ = udp.recvfrom(128)
                break
            except (ConnectionResetError, socket.timeout):
                time.sleep(.15)
        assert reply == b'MYCAST_RECEIVER_V1|57007', f'Discovery reply: {reply!r}'
    print('LAN discovery + 1920x1080 client area: PASS')
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
            if '映像 1920 x 1080' in title.value: break
            time.sleep(.1)
        assert '映像 1920 x 1080' in title.value, title.value
        # Live display/client sizes must follow an actual window resize immediately.
        u.MoveWindow.argtypes = (ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_bool)
        u.MoveWindow(hwnd, 0, 0, 900, 700, True)
        bounds = (ctypes.c_long * 4)()
        u.GetClientRect(hwnd, ctypes.byref(bounds))
        u.GetWindowTextW(hwnd, title, 256)
        assert f"ウィンドウ {bounds[2]} x {bounds[3]}" in title.value, title.value
        scale = min(bounds[2]/1920, bounds[3]/1080)
        assert f"表示 {int(1920*scale)} x {int(1080*scale)}" in title.value, title.value
        print(f'Full HD ACK: mean={sum(times)/len(times):.1f}ms max={max(times):.1f}ms (loopback; not Wi-Fi latency)')
        # One corrupt MJPEG frame must be ACKed/skipped, not destroy the stream.
        bad = b'broken-JPEG-no-SOI'
        s.sendall(struct.pack('!I', len(bad)) + bad)
        assert s.recv(1) == b'\x06', 'Bad JPEG broke streaming connection'
        # Decoder runs asynchronously in v015, so wait for the isolated
        # corrupt packet to be evaluated before sending another JPEG that
        # could correctly replace it in the one-slot latest-frame queue.
        for _ in range(40):
            u.GetWindowTextW(hwnd, title, 256)
            if u.GetPropW(hwnd, "MyCastRejectedFrames") == 1: break
            time.sleep(.1)
        assert u.GetPropW(hwnd, "MyCastRejectedFrames") == 1, title.value
        s.sendall(packet)
        assert s.recv(1) == b'\x06', 'Good frame not accepted after corrupt JPEG'
        print('Corrupt MJPEG skipped with ACK, next valid frame accepted: PASS')
    # MediaProjection may pause on a static screen. Zero-length packets are
    # keepalive heartbeats in v014 and must not blank or drop the last video.
    with socket.create_connection(('127.0.0.1', 57007), timeout=3) as s:
        s.settimeout(3)
        s.sendall(b'MMC9' + packet)
        assert s.recv(1) == b'\x06'
        for _ in range(7):
            time.sleep(1)
            s.sendall(b'\x00\x00\x00\x00')
            assert s.recv(1) == b'\x06', 'Heartbeat failed'
        title = ctypes.create_unicode_buffer(256)
        u.GetWindowTextW(hwnd, title, 256)
        assert 'Android未接続' not in title.value and '映像 1920 x 1080' in title.value, title.value
    print('7-second static-screen idle / heartbeat / no blank frame: PASS')
    # Sender vanished: retain LAST good frame and signal link loss.
    for _ in range(30):
        u.GetWindowTextW(hwnd, title, 256)
        if 'Android未接続' in title.value: break
        time.sleep(.1)
    assert 'Android未接続' in title.value, title.value
    print('Connection loss preserves last Full HD image metadata: PASS')
    with socket.create_connection(('127.0.0.1', 57007), timeout=3) as s:
        s.sendall(b'MMC9' + packet)
        assert s.recv(1) == b'\x06', 'Reconnect failed'
        for _ in range(50):
            u.GetWindowTextW(hwnd, title, 256)
            if 'Android未接続' not in title.value: break
            time.sleep(.1)
        assert 'Android未接続' not in title.value, title.value
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
