"""Windows CI: live audio channel contract independent of UVC JPEG."""
import socket, struct, subprocess, time
from pathlib import Path
p = subprocess.Popen([str(Path("mycastreceiver013.exe").resolve())])
try:
    time.sleep(2)
    with socket.create_connection(("127.0.0.1",57009), timeout=5) as s:
        s.settimeout(4)
        s.sendall(b"MMA1")
        pcm = bytes(3840)
        for i in range(100):
            s.sendall(struct.pack("!I", len(pcm)) + pcm)
            if p.poll() is not None: raise AssertionError("Receiver crashed handling PCM")
    assert p.poll() is None, "Audio receiver exited"
    print("PCM stereo 48kHz TCP audio receiver: PASS (no physical sound device needed)")
finally:
    try:
        import ctypes
        u=ctypes.WinDLL("user32")
        u.FindWindowW.argtypes=[ctypes.c_wchar_p,ctypes.c_wchar_p]
        u.FindWindowW.restype=ctypes.c_void_p
        hwnd=u.FindWindowW("MyCastReceiver013",None)
        if hwnd: u.PostMessageW(hwnd,0x10,0,0)
        p.wait(timeout=10)
    except Exception:
        p.kill()
