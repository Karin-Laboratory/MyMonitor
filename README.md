# MyMonitor / MyCastReceiver

One Android app for **USB UVC HDMI capture + native Android screen/audio streaming** and a standalone Windows x64 MJPEG receiver. The input switches automatically: a successfully opened USB UVC camera takes priority; unplugging returns to the Android screen (after OS permission when required).

## Current release: v0.23 (RC candidate, physical acceptance pending)

- [mymonitor023.apk](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.23)
- [mycastreceiver023.exe](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.23)
- [RC audit and device acceptance matrix](RC_AUDIT_023.md)
- [Install and operational guidance](TRANSFER.md)

v023 focuses on UVC disconnect/reconnect stability. The Activity now debounces physical USB events; the Fragment is the sole owner of USBMonitor and CameraUVC and rejects stale monitor-epoch callbacks. Permission timeouts and retry backoff are tested. The v015 low-latency path, v018 preview geometry, v019 audio recovery and v020 landscape screen transmission are retained.

**Status:** GitHub CI covers compilation and software regression but cannot test physical USB hotplug. Do not describe this candidate as field-proven until Pixel 10a acceptance testing passes.

## Features and permissions

Android can take UVC screenshots, create video-only UVC MP4s, record the screen with eligible internal audio to MP4, and save to an SAF folder and/or SMB 2/3 server after the local file has been finalized. SMB credentials are encrypted through Android Keystore.

A real Android screen-sharing session ALWAYS needs current MediaProjection consent; it cannot restart silently if stopped by the system. On Android 13+, allow notifications to use the record / streaming stop / resume actions. Android background Activity restrictions may affect automatic UVC foreground launch after USB insertion.

Windows receiver is one standalone EXE. OBS can use Window Capture. TCP 57007 JPEG input is backward compatible with earlier MMC9 senders; audio TCP 57009 and discovery UDP 57008 should be permitted on the Windows **Private** network profile only. Never expose these ports to the Internet.

## Build and tests

The GitHub Actions workflow builds the Java/Kotlin APK and bundled native UVC code with Gradle/Java 17, builds a native Windows x64 EXE using MSVC, runs JUnit and source-contract tests, then exercises the EXE under Windows with JPEG/ACK, UDP discovery, false receiver, audio, reconnection and >2-minute soak tests. It verifies SHA-256 checksums before publishing.

UVC backend: [AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera), Apache-2.0, pinned to a known source commit and patched with an MJPEG callback. Audio/recording/SMB and detailed limitations are documented in [TRANSFER.md](TRANSFER.md).

Previous low-latency release: [v0.15](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.15).
