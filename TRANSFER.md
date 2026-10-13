# MyMonitor 014 / MyCastReceiver 014

## Fixed: wrong Windows receiver detected (including 192.168.0.88)

v013 could treat an unverified discovery address as a real receiver, saving that IP before the TCP/video stream succeeded. A forwarded UDP response or an old stored address such as an ARM DHCP server could be shown as an auto-connected target. Android also opened the TCP connection before UVC or MediaProjection supplied frames, causing an idle-timeout loop during screen-sharing permission prompts.

v014 checks the receiver in TWO stages:

1. Android sends a UDP 57008 discovery request MYMONITOR_DISCOVER_V2 with a fresh 128-bit random nonce. Windows echoes that exact nonce and advertises its computer name and TCP 57007. The Android app rejects stale, mismatched and legacy replies.
2. Android directly connects to the response source IP on TCP 57007 and sends MCP1. Only the actual v014 Windows receiver answers MCR1. A DHCP server, broadcast relay or other service is rejected even if it caused UDP discovery traffic.

Manual IP input MUST also pass the direct TCP identity check before it is saved. Receiver discovery, TCP connection and actual first JPEG ACK now have distinct status messages: the app never calls a mere UDP reply a successful video connection.

## Connection reliability

- Do not open the video socket until a UVC source is OPENED or the user has consented to screen MediaProjection.
- Source changes and endpoint switching run through a serialized connection manager that waits for the old sender thread to stop before starting a replacement.
- A static screen sends an MMC9 v014 zero-length heartbeat approximately every 1.5 seconds, acknowledged by Windows, without inventing video frames.
- v012 one-frame ACK MJPEG sender, watchdog, reconnect backoff, corrupt JPEG skip and last-frame warning remain in place.
- The 014 Windows EXE also accepts legacy MMC9 JPEG frames from older Android versions; v014 Android sender requires a v014 receiver for discovery verification and heartbeat support.
- Internal-audio PCM16 48kHz stereo is still sent over TCP 57009; H.264/AAC MP4 recording and folder/SMB export are unchanged.

## Installation

1. Close any older MyCastReceiver EXE. Start mycastreceiver014.exe on the actual Windows computer.
2. Permit UDP 57008 and TCP 57007/57009 on the Windows Private network firewall profile, never a public internet-forwarded port.
3. Update to mymonitor014.apk; do not uninstall first unless necessary, to preserve folder and SMB settings.
4. Start MyMonitor. Automatic search selects only receivers proven by BOTH the UDP nonce and the direct TCP proof. If the saved address 192.168.0.88 is merely the ARM DHCP server, it will be ignored.
5. For a smartphone screen, grant the Android screen-sharing permission for the current session (and internal-audio permission if desired). With USB UVC, the UVC preview takes priority.
6. For multiple verified receivers, select the computer by displayed Windows hostname plus IP. Only a previously remembered AND currently verified receiver may be chosen automatically.
7. If no verified receiver appears, enter the actual Windows computer IPv4 address manually in Windowsへ転送. A validation failure indicates EXE not reachable or incorrect IP/firewall; the bogus IP is not saved.

## CI coverage

- Android + pinned libuvc source compiled and Windows x64 standalone EXE generated.
- End-to-end Windows UDP nonce echo and TCP MCP1/MCR1 proof, false host response and closed port rejection.
- Full HD MJPEG ACK, corrupt JPEG skip and reconnect; seven seconds of heartbeat on a static screen.
- PCM internal-audio listener; 650-frame and >2-minute stream soak with 13 corrupt frames and five deliberate reconnections.
- SHA-256 check of APK and EXE before release.

CI cannot exercise the user's exact household network, Pixel, USB hardware, physical Windows firewall or actual MediaProjection audio/video. Real-device verification remains necessary. Discovery and streaming are for trusted LAN only; this handshake proves receiver implementation but is not cryptographic host authentication.

## Files and rollback

- Android: mymonitor014.apk
- Windows: mycastreceiver014.exe
- GitHub source: https://github.com/Karin-Laboratory/MyMonitor
- Rollback: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.12
- v013 archive: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.13

The same package ID and cached development signing key are retained for in-place Android upgrades when the key cache is still valid.
