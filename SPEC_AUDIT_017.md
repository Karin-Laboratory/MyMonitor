# MyMonitor v017: background survival and UVC preview geometry audit

Baseline: v016 (source f1848957526aea5bb330397d8c14e51e395fc7ef). User reports Android background capture or sender stops after ~91 seconds and incorrect UVC preview coverage/position upon automatic rotation into landscape. Duration is a user observation: the exact 91-second failure cause cannot be established without an Android logcat on the device.

## Source-derived issues identified

1. v016 used a long-running `dataSync` foreground-service type even while simply operating as a LAN/USB receiver sender. This service was visible but did not offer a device-specific battery-optimization user prompt, nor an active-capture CPU wake lock. Modern Android can still stop work due to system restrictions.
2. `MonitorFragment.onStop()` explicitly **closed the camera and unregistered USB monitoring** whenever the Activity was backgrounded. This guaranteed loss of UVC input even if the foreground casting service itself survived. Other causes for a 91-second process kill remain unverified.
3. The upstream `AspectRatioTextureView` overrides `onMeasure` to enforce its aspect ratio, while v016 declared it MATCH_PARENT inside a full-screen FrameLayout. The FrameLayout and camera renderer were resized on rotation without independently computing the actual fit-center image rectangle. The bottom toolbar also overlaid the video viewport. This creates a plausible source of misplaced/cropped landscape content.

## v017 implementation

- Declare `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and `WAKE_LOCK`. Service is declared `connectedDevice|mediaProjection` rather than `dataSync|mediaProjection`, with the matching runtime FGS type on Android 14+.
- On first run after upgrading, show one opt-in explanation; **only after tapping Settings**, launch Android's `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` system permission screen. The OS and user make the final grant decision. On platforms where the direct action is unavailable, open general battery optimization or app settings.
- Keep a bounded `PARTIAL_WAKE_LOCK` refreshed only when screen projection/UVC capture/recording is active, release it on idle and service destruction. Improve process lifecycle logging (power, task removed, memory pressure, uptime).
- Preserve the USB monitor and camera on the Activity's transient `onStop()`. Release them on real TextureView destruction, USB unplug, app finish, or `onDestroyView()`. NOTE: this reduces app-background interruptions but does **not** make TextureView-owned UVC a fully headless capture source when Android destroys the window surface.
- Add `PreviewGeometry.fit()`, a side-effect-free, separately unit-tested fit-center helper. The UVC preview is sized to the available viewport at each layout/configuration change and uses its actual measured Surface dimensions when updating renderer size. It is centered, does not rotate HDMI pixels with the handset, and preserves the UVC format ratio.
- Reserve actual space for the bottom control bar, including after landscape rotation or visibility changes; re-fit preview on layout.
- Keep v015's TCP/JPEG immediate ACK, one-slot decode, low-latency GDI draw, v016's verified IP rediscovery, storage and notification functions unchanged.
- Version v017 ships `mymonitor017.apk` plus `mycastreceiver017.exe`. Windows video pipeline is unchanged except the displayed version and matching executable identity.

## Acceptance matrix

| Scenario | Expected |
|---|---|
| First run on v017 without battery optimization exemption | One explanation, then Android system consent screen only on explicit tap; denial respected |
| Grant exemption | `PowerManager.isIgnoringBatteryOptimizations(packageName)` becomes true after returning |
| Background the Android Activity during screen cast | Foreground notification and stream continue; wake lock active during capture |
| Background the Android Activity with UVC preview still attached | Fragment does not forcibly close camera on `onStop`; if OS destroys TextureView, camera closure is still possible |
| Rotate portrait -> landscape -> portrait with 1920x1080 UVC | Preview stays centered, correctly letterboxed, no cropping/stretch; toolbar does not cover frame |
| Unplug UVC | v016 auto-switch behavior remains, with OS screen-sharing consent if required |
| Save to device/SMB during stream | Local recorder remains independent and saved settings remain |
| Long session beyond 91 seconds | Verify on actual Android device; CI cannot simulate OS kill conditions |
| Windows keyboard-operation latency | Should match user-approved v015; unmodified live codec/ACK/decoder |

## Automated tests

- v016 specification contract plus v017 FGS/battery/preview/lifecycle assertions.
- Android JVM unit tests of fit-center geometry: 16:9 portrait and landscape, toolbar-constrained viewport, 4:3 input, exact-fit.
- Build Android APK with pinned native UVC dependencies.
- Build and run Windows single-EXE Full HD/ACK/decode/identity/PCM/heartbeat/2-minute reliability tests.
- SHA256 verify release artifacts.

## Platform limitations and diagnosis

A `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` manifest declaration **does not automatically grant exemption at installation time**, and does not protect against user force-stop, app crash, the OS killing a process under critical pressure, or loss of MediaProjection consent. A foreground service still depends on Android rules and battery policies. CPU wake locks increase battery drain. Reproduction should collect `adb logcat -v time -s MyMonitorPower MyMonitorState AndroidRuntime ActivityManager` around the observed stop. If UVC stops at exactly background transition, check `onSurfaceTextureDestroyed`; true window-independent UVC capture needs a headless service/offscreen surface redesign.

The v016 and user-confirmed low-latency v015 releases remain available for rollback.
