# MyMonitor / MyCastReceiver v0.25 experimental remote control

Download Android APK `mymonitor025.apk` and Windows EXE `mycastreceiver025.exe` together from https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.25 . The Android package and signing key are unchanged; it supports an in-place update from v024.

1. Install APK and run v025 EXE on trusted Windows Private LAN.
2. On the Android app, tap **遠隔操作**. If not enabled, it opens Android's Accessibility settings. Enable the **MyMonitor remote control** accessibility service manually.
3. Return to MyMonitor, start native phone screen sharing with explicit MediaProjection consent, ensure Windows receiver is connected.
4. Tap **遠隔操作** again to arm this session. In the Windows EXE press **Alt+R** to enable input.
5. Click, press-and-release, drag-and-release, wheel, right click (Android Back), Ctrl+wheel (pinch), or touch the Windows screen. The current implementation dispatches gestures on release, with approximate durations.
6. Press Alt+R to stop Windows input. Android automatically disarms on USB UVC source change, projection loss, or video connection loss. Turn off the Accessibility service from Android Settings to revoke permission entirely.

Ports: video TCP57007, audio TCP57009 and discovery UDP57008. v025 uses MMC5: an extra 17-byte control instruction after each 1-byte video ACK on the **same existing video connection**, no additional listener. v024 Windows does not understand MMC5, so upgrade both ends.

This is not cryptographically paired to a specific Windows device. Trusted LAN only, no internet port exposure. Android targetSdk still 28; Win EXE unsigned. See SPEC_FIX_025.md for limitations.

Existing UVC and audio code is preserved; physical Windows multitouch, Pixel 10a gesture injection and ten-cycle UVC hotplug require hardware checks before RC promotion.
