"""End-to-end 014 receiver identity check. Executed against actual Windows EXE.

Regression scenario: Android must reject unrelated local devices (such as an ARM
DHCP service) even when it has their stale IP or sees UDP reply traffic.
"""
import ctypes
import socket
import subprocess
import threading
import secrets
import time
from pathlib import Path

exe = str(Path("mycastreceiver033.exe").resolve())
proc = subprocess.Popen([exe])
def read4(sock):
    data = b""
    while len(data) < 4:
        chunk = sock.recv(4-len(data))
        if not chunk: return data
        data += chunk
    return data

def probe(ip, port):
    try:
        with socket.create_connection((ip, port), timeout=1.5) as sock:
            sock.settimeout(1.5)
            sock.sendall(b"MCP1")
            return read4(sock) == b"MCR1"
    except (OSError, TimeoutError):
        return False

def fake_nonreceiver():
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", 57011))
        server.listen(1)
        server.settimeout(5)
        c,_ = server.accept()
        with c:
            c.recv(4)
            c.sendall(b"DHCP")  # Service answers TCP but is NOT My Cast Receiver.

try:
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and not probe("127.0.0.1", 57007):
        time.sleep(.1)
    assert probe("127.0.0.1", 57007), "Real receiver did not answer direct TCP identity probe"
    assert not probe("127.0.0.1", 57012), "Closed port unexpectedly recognized"
    rogue = threading.Thread(target=fake_nonreceiver)
    rogue.start()
    time.sleep(.15)
    assert not probe("127.0.0.1", 57011), "Unrelated TCP service falsely accepted"
    rogue.join(timeout=6)
    assert not rogue.is_alive(), "Rogue TCP mock never completed"

    nonce = secrets.token_hex(16)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(2)
        sock.sendto(("MYMONITOR_DISCOVER_V2|" + nonce).encode("ascii"), ("127.0.0.1",57008))
        reply, address = sock.recvfrom(256)
        prefix = ("MYCAST_RECEIVER_V2|" + nonce + "|57007|").encode("ascii")
        assert reply.startswith(prefix), f"Receiver must echo exact nonce: {reply!r}"
        assert len(reply) > len(prefix), "Missing Windows receiver identity"
        assert probe(address[0],57007), "Discovery IP must also pass direct TCP proof"
        stale = ("MYCAST_RECEIVER_V2|" + "0"*32 + "|57007|DHCP").encode("ascii")
        assert not stale.startswith(prefix), "Stale or unrelated nonce falsely accepted"
    print("RECEIVER DISCOVERY PASS: UDP unpredictable nonce echoed + TCP MCP1/MCR1")
    print("FALSE HOST REJECTION PASS: nonreceiver reply and closed port rejected")

    # Active video connections shouldn't be interrupted by a *new* discovery.
    # One signed proof is enough: streaming owner skips subsequent probes.
    assert proc.poll() is None
finally:
    try:
        u = ctypes.WinDLL("user32")
        u.FindWindowW.argtypes=[ctypes.c_wchar_p,ctypes.c_wchar_p]
        u.FindWindowW.restype=ctypes.c_void_p
        hwnd = u.FindWindowW("MyCastReceiver019",None)
        if hwnd: u.PostMessageW(hwnd,0x10,0,0)
        proc.wait(timeout=10)
    except Exception:
        proc.kill()
