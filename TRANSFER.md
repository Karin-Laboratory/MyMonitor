# MyMonitor 015 / MyCastReceiver 015

## Goal: reduce the ~0.5 second Android-preview-to-Windows display delay

The physical UVC capture and Android preview are already responsive in the user's setup. V015 therefore keeps Wi-Fi/TCP and the proven MJPEG protocol, focusing on **Windows JPEG decode, copy, paint and Android's stop-and-wait ACK dependency**. Improvements are measurable but actual end-to-end delay still requires a real-device comparison.

### Findings in v014

- Windows used SHCreateMemStream -> GDI+ JPEG decode -> Bitmap.Clone, all on the TCP receive thread, then ACKed. This serialized the Android sender behind receiver-side decompression/allocation.
- Each WM_PAINT made a new Full HD-compatible bitmap, painted a black backing image, called GDI+ DrawImage, then BitBlt to the actual window. The UI work could lag behind the next packet and required repeated 1920x1080 intermediate images.
- The v014 Android sender was latest-frame-wins but could not send another frame until the Windows JPEG decoder had returned its ACK. That coupling is removed.

### Changes implemented in v015

1. **ACK immediately after receipt of a bounded complete JPEG packet** (still TCP 57007, MMC9, ACK 0x06). The ACK means *packet received*, not successful JPEG decode or screen paint.
2. A dedicated Windows decode thread handles packets through a **one-slot latest-frame queue**. Frames that wait while a newer packet arrives are discarded, preventing growing display latency.
3. Windows GDI+ only decodes JPEG and exposes its raw pixels. Pixels are copied into a 32-bit top-down GDI DIB. Windows uses native BitBlt for 1:1 video (e.g. Full HD) and StretchBlt for resized windows. The old Bitmap.Clone and full-screen offscreen compatible bitmap per paint were removed.
4. The video HBITMAP is reference-counted. WM_PAINT snapshots it without holding the decoder's mutex for the duration of drawing. The last successfully decoded image persists on disconnect.
5. Windows title reports receiver-stage timings, refreshed about once per second: **rx ms** (time to receive full JPEG), **decode ms**, **draw ms**, **decoded** and **dropped** image counts. These are local processing measurements, not total glass-to-glass latency.
6. Android logcat has a **MyMonitorLatency** line every ~5 seconds with frame count, avg_queue_ms, avg_write_ms, avg_ack_wait_ms and max_ack_wait_ms. ACK measurement now excludes Windows JPEG decoding.
7. v014 authenticated receiver discovery, TCP identity proof, UVC first/source arbitration, screen capture, recording, SMB export, 7-second static-screen heartbeat, PCM internal audio, watchdog and automatic reconnection are retained.

### Safety and video behavior

- Corrupt JPEGs are ACKed then rejected asynchronously; the next good frame must recover. The decoder may legitimately skip a corrupt JPEG that is replaced while it waits in the latest-only queue. Dropped != corrupted.
- TCP input sizes are bounded to 8 MiB and decoded dimensions to 4096x4096. At most one waiting compressed packet is retained, plus the JPEG currently decoding and one displayed DIB.
- The direct GDI paint path intentionally favors latency over high-quality resampling when the window is resized. 1:1 display uses BitBlt without filtering.
- The existing one-connection receiver, frozen last frame on disconnect and visual warning remain.
- The Windows receiver retains legacy MMC9 video compatibility. For verified discovery and zero-frame heartbeat, use a matching v014/v015 sender and receiver.

### Installation / verification

1. Close all prior Windows receiver processes; run **mycastreceiver015.exe** on the Windows PC. It listens on TCP 57007 (JPEG + V2 identity proof), TCP 57009 (audio), UDP 57008 (discovery). Allow only on the Private LAN.
2. Update Android in-place to **mymonitor015.apk**. UVC/capture/recording settings and SMB credentials should remain intact with the cached signing key; do not uninstall first.
3. Automatic discovery is unchanged from v014, and the user's LAN currently requires manual IP selection. If discovery finds no receiver, use **Windowsへ転送** and enter the actual PC IPv4; v015 probes the Windows process before saving.
4. To compare v014 vs v015, show the same UVC output side by side on the Android preview and Windows display. Move a cursor or type while observing lag. Use the EXE window title's **rx/decode/draw** and Android logcat's **MyMonitorLatency** to distinguish processing and ACK delay.
5. Prefer the initial 1920x1080 client area for the fastest BitBlt path. Scaling uses native StretchBlt rather than expensive GDI+ resampling. OBS Window Capture is unchanged.

### CI regression suite

- Pinned libuvc/native Android debug APK and statically linked x64 EXE compilation.
- V2 UDP nonce and TCP MCP1/MCR1 proof plus fake DHCP response rejection.
- Existing Full HD JPEG, corrupt-frame skip, static heartbeat, reconnect and icon tests.
- New 140-frame burst ACK/decode/paint diagnostics regression (explicit loopback latency threshold).
- PCM audio test and a 650-frame, >2-minute reconnect/corrupt-frame soak.
- Both release files SHA-256 verified before upload.

CI's loopback ACK timings are **not** the same as the user's ~0.5 second end-to-end Wi-Fi/monitor lag. Reduction is a hypothesis until demonstrated on their real hardware. Existing v014 release remains available for immediate rollback: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.14

## Source and release

- Source: https://github.com/Karin-Laboratory/MyMonitor
- Android: mymonitor015.apk
- Windows: mycastreceiver015.exe
