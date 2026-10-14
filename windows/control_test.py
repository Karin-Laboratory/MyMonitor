"""v025 reverse control on existing JPEG/ACK connection. No real touch injection."""
import socket, subprocess, time, pathlib, sys
exe=pathlib.Path(__file__).with_name("mycastreceiver027.exe")
if not exe.exists(): exe=pathlib.Path("mycastreceiver027.exe")
assert exe.exists(), exe
proc=subprocess.Popen([str(exe.resolve())],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
    s=None
    for attempt in range(50):
        try:
            s=socket.create_connection(("127.0.0.1",57007),timeout=2)
            break
        except OSError: time.sleep(.1)
    assert s is not None,"receiver never started"
    with s:
        s.settimeout(5)
        s.sendall(b"MMC5")
        for i in range(5):
            s.sendall((0).to_bytes(4,"big"))
            buf=bytearray()
            while len(buf)<18:
                block=s.recv(18-len(buf))
                assert block,"closed control channel"
                buf.extend(block)
            assert buf[0]==6,repr(buf)
            assert len(buf)==18
            assert buf[1:]==bytes(17),"Control must default OFF"
    print("V025 MMC5 ACK+17 CONTROL SAFE-OFF PASS")
finally:
    proc.terminate()
    try:proc.wait(timeout=4)
    except subprocess.TimeoutExpired:proc.kill()
