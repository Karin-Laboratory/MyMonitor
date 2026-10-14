# MyMonitor v0.23 RC candidate / MyCastReceiver v0.23

Use [v023 release](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.23) for the APK and native Windows EXE. The existing package ID and debug-signing key are retained to allow in-place installation. Keep [v022](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.22) as a rollback.

## Changes
v023 makes MonitorFragment the sole USBMonitor/CameraUVC owner, coalesces Activity USB events, rejects callbacks from destroyed USB monitor generations, and adds a permission-result watchdog. A pure recovery policy controls event validity, disconnect behavior and bounded retry intervals; JUnit exercises those rules.

## Validation
GitHub CI runs Android native and unit tests, Windows MSVC build, low-latency MJPEG/ACK, discovery, audio reconnect, 2-minute soak and optional Defender scan. It **does not** test real Pixel 10a USB replug or background behavior. See [RC_AUDIT_023.md](RC_AUDIT_023.md) for the acceptance checklist.

## Installation
Install `mymonitor023.apk` over v022. Close the earlier Windows receiver, then run `mycastreceiver023.exe`. The executable is **unsigned**, so retain Windows Defender protection; do not bypass a detection. LAN traffic uses TCP 57007 (video), UDP 57008 (discovery), and TCP 57009 (audio) on trusted private networks only. MediaProjection screen capture requires Android consent after token invalidation.

## Diagnostics
Use wireless ADB and capture `MyMonitor-UVC`, `MyMonitor-Preview` and `AndroidRuntime`. v023 logs connection generation, device ID, permission state and camera state. Real-device reconnect errors should be correlated using those fields.

## Compatibility note
Android target SDK is still 28; this is a legacy-API compatibility choice, not proof of compliance with current store publishing requirements or every modern Android power-management policy.
