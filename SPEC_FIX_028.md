# MyMonitor v028: receiver display controls

## New functions
- Windows Ctrl+mouse-wheel **down** produces a two-finger **pinch out** (spread the fingers; Android zoom in). Ctrl+wheel **up** produces **pinch in** (Android zoom out).
- F11 toggles borderless fullscreen on the monitor containing the Windows receiver. Press F11 again to restore the previous window style, position and size. Autorepeated keydown events are ignored.
- When Android's *actual UVC source* becomes active, Windows automatically sizes the viewer for **1920x1080 content**. A monitor currently at physical 1920x1080 enters fullscreen; other monitors use a 1920x1080 client area when it fits, or a centered size fitted within the usable desktop area when smaller.
- No automatic resize is triggered by phone-screen orientation alone, including apps presenting 1920x1080 screen frames. F11 works in any source mode.

## Explicit source protocol
- The Android sender uses `MMC6` at the beginning of its TCP57007 connection.
- In addition to the existing 4-byte length + JPEG, an out-of-band packet `length=0xFFFFFFFF` followed by one byte indicates `0=unavailable, 1=UVC, 2=phone screen`. This uses the same 18-byte ACK (0x06 followed by 17 remote-control bytes) as MMC5.
- Source changes are sent before subsequent frames, including when there are no fresh JPEG frames (sender loop checks at least every 500 ms). Metadata does not enter the JPEG decoder.
- Windows v028 can still accept legacy `MMC5` and `MMC9` senders, but **Android v028 must be used with Windows v028** because older receivers do not recognize MMC6.
- Audio TCP57009 and discovery UDP57008 remain unchanged. UVC USBMonitor recovery, screen capture, recording, and Pixel 10a v026 coordinate mapping are unchanged.

## Safety and testing
- UVC notifications are processed on the Win32 UI thread; stale notifications from disconnected sessions are ignored.
- All real-time remote-control gating and user authorization settings remain unchanged. Use private LAN only; cryptographic pairing is still not implemented.
- CI verifies Kotlin/native Android APK, Windows MSVC binary, audio/video/protocol legacy regression, v028 MMC6 + F11 window behavior, and continuous-reception soak.
- Physical FHD display/DPI/dual-monitor layout, real UVC hotplug and pinching in a third-party app require real-device validation.
