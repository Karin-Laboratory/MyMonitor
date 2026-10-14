# MyMonitor / MyCastReceiver v026

Downloads: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.26

- Android: mymonitor026.apk (in-place upgrade from v025)
- Windows: mycastreceiver026.exe (native unsigned Windows EXE)

v026 changes: remote taps use Android's physical full display size instead of application displayMetrics, correcting the growing Y error toward the bottom. Mouse side Back (XBUTTON1) now sends Android Home, side Forward (XBUTTON2) sends Android Back. Ordinary right click is still Android Back.

Enable remote control in Android accessibility settings, start phone screen casting, tap 遠隔操作 in MyMonitor to arm the session, then Alt+R in the Windows viewer. Only Android screen mirroring is remotely controllable, not UVC. Control requires a trusted private LAN; cryptographic pairing is not implemented.

CI tests compile/verify APK and Windows EXE, ACK/control framing, MJPEG, audio, reconnection and soak. Physical Pixel 10a gesture positioning and mouse side buttons need real-device verification.
