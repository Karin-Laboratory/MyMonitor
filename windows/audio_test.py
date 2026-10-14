"""Windows CI: live audio channel contract independent of UVC JPEG."""
import socket, struct, subprocess, time
from pathlib import Path
p = subprocess.Popen([str(Path("mycastreceiver022.exe").resolve())])
try:
    time.sleep(2)
    pcm = bytes(3840)
    # Lock/unlock of Android rebuilds MMA1. The receiver must release the old
    # waveOut session, accept the next audio TCP socket and keep working.
    for session in range(3):
        with socket.create_connection(("127.0.0.1",57009), timeout=5) as s:
            s.settimeout(4)
            s.sendall(b"MMA1")
            for i in range(100):
                s.sendall(struct.pack("!I", len(pcm)) + pcm)
                if p.poll() is not None: raise AssertionError("Receiver crashed handling PCM")
        time.sleep(.3)
    assert p.poll() is None, "Audio receiver exited after session reconnect"
    print("PCM 48kHz: PASS, 3 audio TCP sessions (lock/unlock reconnect)")
finally:
    try:
        import ctypes
        u=ctypes.WinDLL("user32")
        u.FindWindowW.argtypes=[ctypes.c_wchar_p,ctypes.c_wchar_p]
        u.FindWindowW.restype=ctypes.c_void_p
        hwnd=u.FindWindowW("MyCastReceiver019",None)
        if hwnd: u.PostMessageW(hwnd,0x10,0,0)
        p.wait(timeout=10)
    except Exception:
        p.kill()
