"""v033 receiver keyboard framing smoke test against the actual native EXE.

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
exe = pathlib.Path("mycastreceiver037.exe").resolve()
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

        # Native edit holds a draft; no character leaks before explicit send.
        u.GetDlgItem.argtypes = (ctypes.c_void_p, ctypes.c_int)
        u.GetDlgItem.restype = ctypes.c_void_p
        u.IsWindowVisible.argtypes = (ctypes.c_void_p,)
        u.GetPropW.argtypes = (ctypes.c_void_p, ctypes.c_wchar_p)
        u.GetPropW.restype = ctypes.c_size_t
        def diagnostics():
            return {"draft": get_draft(), **{name: u.GetPropW(edit, "MyCastComposer" + name) for name in ("Guard", "Send", "Pending")}}
        edit = u.GetDlgItem(hwnd, 3301)
        button = u.GetDlgItem(hwnd, 3302)
        # Controls belong to a different process: WM_SETTEXT / WM_GETTEXT are
        # marshalled by Windows, unlike SetWindowText/GetWindowText shortcuts.
        def set_draft(text):
            buffer = ctypes.create_unicode_buffer(text)
            assert u.SendMessageW(edit, 0x000c, 0, ctypes.addressof(buffer))
        def get_draft():
            buffer = ctypes.create_unicode_buffer(260)
            u.SendMessageW(edit, 0x000d, len(buffer), ctypes.addressof(buffer))
            return buffer.value
        assert edit and button and u.IsWindowVisible(edit), "Missing native text composer"
        u.SendMessageW(edit, WM_CHAR, ord("a"), 0)
        u.SendMessageW(edit, WM_CHAR, ord("日"), 0)
        assert ack(sock, struct.pack("!I", 0)) == bytes(17), "Draft leaked before send"
        u.SendMessageW(edit, WM_KEYDOWN, 0x0d, 0)
        u.SendMessageW(edit, 0x0101, 0x0d, 0)
        p = ack(sock, struct.pack("!I", 0))
        assert p[:6] == bytes([8, 4]) + "a日".encode("utf-8"), p
        assert ack(sock, struct.pack("!I", 0)) == bytes(17)
        assert not get_draft(), "Sent draft was not cleared"

        # Committed Japanese text is sent once by Enter.
        set_draft("日本語")
        u.SendMessageW(edit, WM_KEYDOWN, 0x0d, 0)
        u.SendMessageW(edit, 0x0101, 0x0d, 0)
        p = ack(sock, struct.pack("!I", 0))
        assert p[0] == 8 and p[2:2+p[1]].decode("utf-8") == "日本語", (p, diagnostics())
        assert ack(sock, struct.pack("!I", 0)) == bytes(17)

        # Editing keys operate locally; Send button submits the edited draft.
        set_draft("ab")
        u.SendMessageW(edit, 0x00b1, 2, 2)  # EM_SETSEL
        u.SendMessageW(edit, WM_CHAR, VK_BACK, 0)
        u.SendMessageW(button, 0x00f5, 0, 0)  # BM_CLICK
        p = ack(sock, struct.pack("!I", 0))
        assert p[:3] == bytes([8, 1, ord("a")]), p
        assert ack(sock, struct.pack("!I", 0)) == bytes(17)

        # A long Unicode draft crosses many packets, without splitting scalars.
        message = "日" * 120 + "😀" * 20
        set_draft(message)
        u.SendMessageW(button, 0x00f5, 0, 0)
        received = ""
        for _ in range(64):
            p = ack(sock, struct.pack("!I", 0))
            if p == bytes(17): break
            assert p[0] == 8 and 0 < p[1] <= 15, p
            received += p[2:2+p[1]].decode("utf-8")
        assert received == message, (len(received), len(message))

        # Physical UVC display never sends text and retains any unsent draft.
        set_draft("保留")
        assert ack(sock, struct.pack("!I", 0xffffffff) + b"\x01") == bytes(17)
        time.sleep(.3)
        u.SendMessageW(button, 0x00f5, 0, 0)
        assert ack(sock, struct.pack("!I", 0)) == bytes(17)
        assert get_draft() == "保留"
        assert not u.IsWindowVisible(edit)

        assert ack(sock, struct.pack("!I", 0xffffffff) + b"\x02") == bytes(17)
        time.sleep(.3)
        u.SendMessageW(button, 0x00f5, 0, 0)
        p = ack(sock, struct.pack("!I", 0))
        assert p[0] == 8 and p[2:2+p[1]].decode("utf-8") == "保留", p
        # Exercise our composition guard last. A synthetic WM_IME_START is
        # not a real IME transaction and must not contaminate subsequent native
        # EDIT tests. End-to-end conversion/commit still needs a real IME.
        set_draft("変換中")
        u.SendMessageW(edit, 0x010d, 0, 0)
        u.SendMessageW(edit, WM_KEYDOWN, 0x0d, 0)
        assert ack(sock, struct.pack("!I", 0)) == bytes(17), "Composition Enter sent text"
    print("V033 NATIVE COMPOSER / IME ENTER GUARD / UNICODE BATCH / UVC DRAFT RETENTION PASS")
finally:
    if hwnd:
        u.PostMessageW(hwnd, WM_CLOSE, 0, 0)
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
