# MyMonitor 017: first-use power permission and HDMI preview fit fix

### Installation
Download and **update in place** `mymonitor017.apk`, and replace the older receiver with `mycastreceiver017.exe`. Keep the 015/016 builds for rollback, and stop prior Windows receiver processes before starting 017.

When first starting v017, MyMonitor offers to open Android's battery-optimization exemption consent. Press **設定を開く**, then explicitly approve the Android permission if you want a longer-running cast. This is **not an installation-time automatic grant**. Android may still terminate an application for crash, user force-stop or resource pressure. If permission was declined, change it later via Android Settings > Apps > My Monitor > Battery.

UVC remains open across ordinary Android Activity onStop when its video Surface survives. If Android destroys the TextureView in the background, UVC may still close; fully headless USB capture is not implemented. Active streaming/recording uses a foreground connected-device service, ongoing notification and a bounded partial CPU wake lock. Android system MediaProjection consent is still required for screen transmission.

### Display
On phone rotation and changing toolbar visibility, the UVC preview is recalculated using the true viewport excluding the bottom controls. UVC 1920x1080 stays 16:9 in BOTH device orientations, centered with letterboxing, without rotating or cropping the HDMI source. The preview renderer uses the new measured Surface size.

### What changed and did not
- Android battery-optimization opt-in prompt and FGS/power lifecycle
- UVC camera not forcibly closed on Activity background
- Aspect-correct, fit-center preview geometry with JVM tests
- Windows binary relabeled 017, video pipeline unchanged
- Existing LAN discovery, receiver reconnect, screen audio, notification buttons, MP4/SMB and v015 low latency retained

### Verification
The included GitHub CI performs Android native+APK build, v017 geometry/power contract tests, Windows synthetic video + PCM audio + receiver authentication + >2 minute reliability soak. The **91-second Android survival** and actual physical rotated HDMI placement must be tested on the device.

Details: `SPEC_AUDIT_017.md`.
