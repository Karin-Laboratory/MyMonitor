# MyMonitor / MyCast Receiver v029

After CI passes, download both updated binaries:
https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.28

- `mymonitor029.apk`: Android (in-place update from v028 using the same persistent build signing key)
- `mycastreceiver029.exe`: native Windows x64 (unsigned; runs standalone)

Upgrade **both** components to v029 for the complete wheel fix. MMC6 is unchanged from v028, but the new Android gesture scheduler is needed to prevent successive pinches cancelling each other.

### Windows remote control
To control the Pixel 10a, grant the MyMonitor accessibility service in Android Settings, start phone screen casting, tap Android 遠隔操作 to arm the session, then press Alt+R in Windows. UVC cannot be remotely controlled. Side Back sends Android Home, side Forward sends Android Back. Right click also sends Android Back.

Ctrl+wheel **down**: pinch out (zoom in); Ctrl+wheel **up**: pinch in (zoom out). Rapid detents now batch within 160ms, and the Android Accessibility service waits for gesture completion before sending another. High-resolution half-detent wheel values accumulate. Hardware zoom behavior must still be verified on the phone.

F11 toggles fullscreen and returns to the previously saved window placement. On an actual UVC-source switch, Windows automatically selects a 1920x1080 client area if it fits on the current display, otherwise sizes to the available work area. When the display is physically 1920x1080 it enters borderless fullscreen automatically. Phone-screen rotation by itself never triggers UVC resizing.

Audio/video ports and discovery remain unchanged. v028 USB hotplug and low-latency pathways are preserved. The receiver is unsigned, and remote control is not cryptographically paired; trusted local networks only. Hardware checks remain necessary.
