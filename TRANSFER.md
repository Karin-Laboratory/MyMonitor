# My Monitor 012 / My Cast Receiver

## 012: Fix video disappearing after several minutes

- **Android automatically reconnects** to the selected Windows receiver after a broken TCP connection, ACK timeout or receiver restart. No manual reconnect after a transient failure. Retry delay grows from 250ms to 2s.
- **8-second write/ACK watchdog** on the Android side closes a stalled socket to ensure the retry loop can recover even if a network write becomes blocked.
- **Windows no longer disconnects on one malformed MJPEG JPEG.** It discards only that frame, sends ACK and continues with the next latest frame. The window title shows cumulative skipped frames for diagnostics.
- **Windows retains the last valid video frame** when a sender disconnects instead of showing a blank window. A visible "Connection lost - waiting for automatic reconnect" warning prevents mistaking the still image for live video. The warning disappears on the next valid decoded frame.
- Windows CI tests include bad frames, immediate reconnect, and a 650-frame, 2+ minute stream with multiple disconnect/reconnect cycles. Actual Pixel / Wi-Fi / HDMI grabber runtime still requires testing.
- No codec downgrade: true USB MJPEG passthrough and NV21/JPEG fallback from 011 remain.
- Same Android/Windows monitor icons. Same Full HD client (1920x1080), TCP 57007, UDP discovery 57008. Video only.

## Install and use

1. Download both files from the v0.12 GitHub Release: mymonitor012.apk and mycastreceiver012.exe.
2. Stop/close all older receiver EXEs before starting 012 (TCP 57007 can be bound by only one process).
3. Windows Firewall: permit TCP 57007 and UDP 57008 on the **Private** network only.
4. Start 012 receiver, open 012 Android APK with UVC/HDMI capture connected.
5. Tap "Windowsへ転送": auto-search and connect if one receiver responds. If discovery fails, use manual IPv4/port.
6. OBS -> Window Capture -> My Cast Receiver; use a 1920x1080 canvas/output for Full HD.
7. If Wi-Fi goes away, a labeled last image remains on Windows; on restoration the Pixel should retry and the live image should resume without tapping connect again.

## Known constraints

- Real hardware tests are still necessary: automated CI tests simulate JPEG frame sends to the actual Windows EXE, not USB capture and radio interference.
- If the HDMI source stops producing frames while TCP stays open, there is no video to transmit; the receiver disconnects after its existing 5-second idle receive timeout and displays a warning. Automatic sender retry is activated when a subsequent frame attempts to use that stale connection.
- We deliberately keep the latest one-frame ACK protocol (MMC9), no growing latency queue.
- TCP video and UDP discovery have **no encryption or authentication**. Trusted home Wi-Fi/LAN only. Never forward these ports to the internet.
- The UVC 3.6.0 source patch is pinned to commit 09df386a46e6aef22e4a020105246b2c9805ed05.
- CI uses cached development signing. If the Android APK signature unexpectedly changes, an uninstall/reinstall can be necessary; back up your app settings first.

## Verification scope

- APK builds with patched native MJPEG data tap and keeps local photo/MP4/SMB.
- Windows executable remains a single statically linked x64 EXE.
- Smoke test checks Full HD ACK, LAN discovery, simulated corrupt MJPEG drop, last-frame preservation and reconnect.
- Soak test keeps a receiver active for >120 seconds with 650 frames, 13 invalid frames and five deliberate transport reconnects.
