# v030: Ctrl+wheel direction reversal

## User acceptance
v029's rapid Ctrl+wheel pinch handling was tested on an Android map and reported smooth and comfortable. The user requested the **opposite wheel direction**, with all other behavior unchanged.

## Change
- Windows Ctrl+wheel **UP** => two fingers move **OUT** => map **zoom in**.
- Windows Ctrl+wheel **DOWN** => two fingers move **IN** => map **zoom out**.
- Only the sign selection in `windows/receiver.cpp`'s `flushPinchWheel` changes. The 160 ms batch window, accumulated fine wheel deltas, amplitude bounds, image-relative finger coordinates, Android completed/cancelled callback queue, and all remote-control access guards stay untouched.
- `windows/wheel_test.py` checks the reverse directions against the actual running EXE through MMC6 ACKs, plus batch coalescing, partial detents and disabled-control safety.
- Version bumps: Android APK versionCode 30, native Windows EXE VERSIONINFO 0.30.0.0 and release asset names.

## Retained and tested
- UVC priority / reconnect, automatic screen source switching, F11 fullscreen, streaming and audio.
- Phone-screen remote control only; user must enable accessibility plus application approval and Windows Alt+R. Network still private LAN only and unpaired.
- Existing MJPEG, discovery, audio, reconnect, 2-minute reliability tests remain in Actions.
- MMC6 packet wire format is unchanged from v029. **Windows v030 is sufficient for a v029 Android installation** to experience corrected wheel direction; v030 APK is also built for version alignment.
- The native EXE is unsigned. Automated CI can test emitted gestures but cannot validate the physical Google Maps app; please confirm on device.

## Rollback
Previous stable release: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.29
