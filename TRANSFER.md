# My Monitor 011 / My Cast Receiver

## What's new

- Native USB **MJPEG passthrough**. The pinned AUSBC 3.6.0 source is patched during CI: compressed MJPEG bytes are offered before conversion to YUYV/NV21. Local photo/MP4 capture continues using the original decoder pipeline.
- NV21→JPEG is retained as a fallback when the camera doesn't output MJPEG.
- Android **LAN receiver search** via UDP 57008, initiated by the "Windowsへ転送" button. Exactly 1 receiver -> auto-connect; 2+ -> choose; 0 -> manual IP address.
- Windows receiver requests an actual **1920×1080 client area** on launch. It still allows manual resizing.
- Existing 1-frame TCP ACK, center drawing, icon, Full HD, screenshot/recording/SMB features continue.
- Files: mymonitor011.apk / mycastreceiver011.exe.

## Usage

1. Start mycastreceiver011.exe on Windows. Allow TCP 57007 and UDP 57008 **only for the Private firewall profile**.
2. Pixel: connect USB UVC grabber, open My Monitor, allow camera and USB access.
3. Tap "Windowsへ転送". If exactly one receiver replies, it connects automatically. If discovery fails due to firewall, subnet separation or Wi-Fi isolation, enter the Windows IPv4 address manually.
4. OBS -> Window Capture -> My Cast Receiver. Set canvas/recording output to 1920×1080 for full-resolution output. Don't minimize the receiver window.

## Notes

- This changes the bundled UVC native library, not merely the application-side JPEG encoder. CI compile and synthetic JPEG tests cannot prove a particular USB HDMI grabber emits decodable MJPEG: confirm on the actual Pixel/UVC device.
- If your Windows desktop is 1920×1080, the 1080p client region plus title bar/borders may exceed the visible desktop. OBS can still capture the window; resize it if desired. We do not modify monitor display mode.
- Receiver scan uses subnet broadcast only. It intentionally does not brute-force the local network or auto-connect when more than one receiver is found.
- Discovery UDP and image TCP are **not encrypted or authenticated**; use trusted home Wi-Fi/LAN only. Never expose TCP 57007 or UDP 57008 to the public internet.
- Full HD video only: no audio, Windows virtual camera or display driver.
- APK development signing key uses CI cache; an expired cache can force reinstall. Do not remove/reissue keys without explaining this risk.

## Source reproducibility

GitHub Actions clones ernestp/AndroidUSBCamera at 3.6.0, pinned commit 09df386a46e6aef22e4a020105246b2c9805ed05. scripts/patch_uvc_mjpeg.py applies guarded C++ / Java / Kotlin patches before compilation. If upstream layout drifts, CI fails rather than silently reverting to JPEG re-encode.
