# MyMonitor v027 installation and operation

Download APK and EXE together from https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.27

- Android: mymonitor027.apk (updates v026 in place)
- Windows: mycastreceiver027.exe (native MSVC, unsigned)

v027 includes the screen-orientation geometry fix plus narrowly scoped remote-input and connection-status corrections from AUDIT_V026_SAFETY.md. It keeps the existing MMC5 video/ACK/control protocol.

To use remote control: enable MyMonitor in Android Accessibility settings, start Android screen mirroring, tap 遠隔操作 to arm the session, then press Alt+R in the Windows receiver. Only phone screen casting can receive control commands. Windows mouse Back side button sends Android Home; Forward sends Android Back; right click sends Android Back.

The installed Android app keeps its storage and saved preferences when updated with the same persistent development signing key. Always keep v026 downloaded as a recovery fallback. Neither CI nor this release proves the physical Home-to-landscape regression or ten-cycle USB reconnect on the device.

Use trusted private LAN only. Accessibility can inject screen-wide actions; Windows control is not cryptographically paired.
