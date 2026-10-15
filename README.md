# MyMonitor / MyCastReceiver

One Android app for **USB UVC HDMI capture + native Android screen/audio streaming** and a standalone Windows x64 MJPEG receiver. The input switches automatically: a successfully opened USB UVC camera takes priority; unplugging returns to the Android screen (after OS permission when required).

## Current release: v0.29 (reliable rapid Ctrl+wheel map zoom)

- [Android APK / Windows EXE release](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.29)
- [v029 wheel zoom fix and limitations](SPEC_FIX_029.md)
- [Installation and operations](TRANSFER.md)

v029 retains an **opt-in remote screen-control channel** to the existing Android-to-Windows MJPEG/ACK transport. It controls the **Android screen only**, never the external UVC source. Enable MyMonitor in Android Accessibility Settings, start screen capture, arm the app's 遠隔操作 toggle, then press **Alt+R** in MyCastReceiver. Both toggles default disabled or are cleared on source/transport changes.

Mouse click: tap; hold/release: long press; drag/release: swipe; wheel: scroll; Ctrl+wheel down: pinch out, up: pinch in; right click: Back. v029 coalesces rapid wheel ticks every 160ms into an image-bounded pinch, and Android serializes gestures by completion callbacks to avoid cancelling the previous pinch. Burst strength is bounded to prevent runaway zoom; repeated bursts continue zooming. Press F11 for reversible fullscreen. A genuine UVC source switch sizes the window for 1920x1080 content, auto-fullscreen on physical 1920x1080 monitors. Windows touch pointer gestures are converted to strokes, including paired two-finger start/end paths. Motion is applied on release, not streamed continuously. Both components must be v029 for the new MMC6 source-mode notifications and existing reverse-control packets.

**Security:** Android accessibility permission can drive the entire screen. Only use a private LAN and explicitly arm control per session. Peer cryptographic pairing is not implemented. Physical gesture and touch screen validation remains pending.

## Features and permissions

Android can take UVC screenshots, create video-only UVC MP4s, record the screen with eligible internal audio to MP4, and save to an SAF folder and/or SMB 2/3 server after the local file has been finalized. SMB credentials are encrypted through Android Keystore.

A real Android screen-sharing session ALWAYS needs current MediaProjection consent; it cannot restart silently if stopped by the system. On Android 13+, allow notifications to use the record / streaming stop / resume actions. Android background Activity restrictions may affect automatic UVC foreground launch after USB insertion.

Windows receiver is one standalone EXE. OBS can use Window Capture. TCP 57007 JPEG input is backward compatible with earlier MMC9 senders; audio TCP 57009 and discovery UDP 57008 should be permitted on the Windows **Private** network profile only. Never expose these ports to the Internet.

## Build and tests

The GitHub Actions workflow builds the Java/Kotlin APK and bundled native UVC code with Gradle/Java 17, builds a native Windows x64 EXE using MSVC, runs JUnit and source-contract tests, then exercises the EXE under Windows with JPEG/ACK, UDP discovery, false receiver, audio, reconnection and >2-minute soak tests. It verifies SHA-256 checksums before publishing.

UVC backend: [AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera), Apache-2.0, pinned to a known source commit and patched with an MJPEG callback. Audio/recording/SMB and detailed limitations are documented in [TRANSFER.md](TRANSFER.md).

Previous low-latency release: [v0.15](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.15).
