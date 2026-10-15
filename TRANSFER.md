# MyMonitor / MyCast Receiver v030

After CI passes, download both updated binaries:
https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.28

- `mymonitor030.apk`: Android (in-place update from v029 using the same persistent build signing key)
- `mycastreceiver030.exe`: native Windows x64 (unsigned; runs standalone)

Upgrade the Windows receiver to v030 for the corrected wheel direction; the Android v030 APK is also provided for matching version numbers. The v029 Android APK remains protocol-compatible with Windows v030. MMC6 is unchanged from v028, but the new Android gesture scheduler is needed to prevent successive pinches cancelling each other.

### Windows remote control
To control the Pixel 10a, grant the MyMonitor accessibility service in Android Settings, start phone screen casting, tap Android 遠隔操作 to arm the session, then press Alt+R in Windows. UVC cannot be remotely controlled. Side Back sends Android Home, side Forward sends Android Back. Right click also sends Android Back.

Ctrl+wheel **up**: pinch out (zoom in); Ctrl+wheel **down**: pinch in (zoom out). Rapid detents now batch within 160ms, and the Android Accessibility service waits for gesture completion before sending another. High-resolution half-detent wheel values accumulate. Hardware zoom behavior must still be verified on the phone.

F11 toggles fullscreen and returns to the previously saved window placement. On an actual UVC-source switch, Windows automatically selects a 1920x1080 client area if it fits on the current display, otherwise sizes to the available work area. When the display is physically 1920x1080 it enters borderless fullscreen automatically. Phone-screen rotation by itself never triggers UVC resizing.

Audio/video ports and discovery remain unchanged. v028 USB hotplug and low-latency pathways are preserved. The receiver is unsigned, and remote control is not cryptographically paired; trusted local networks only. Hardware checks remain necessary.
