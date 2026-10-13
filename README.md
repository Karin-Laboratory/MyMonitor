# MyMonitor / MyCastReceiver

One Android app for **USB UVC HDMI capture + native Android screen/audio streaming** and a standalone Windows x64 MJPEG receiver. The input switches automatically: a successfully opened USB UVC camera takes priority; unplugging returns to the Android screen (after OS permission when required).

## Latest: v0.16

- [mymonitor016.apk](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.16/mymonitor016.apk)
- [mycastreceiver016.exe](https://github.com/Karin-Laboratory/MyMonitor/releases/download/v0.16/mycastreceiver016.exe)
- [Instructions / migration](TRANSFER.md)
- [Specification-to-code audit and acceptance matrix](SPEC_AUDIT_016.md)

v016 fixes USB detach/native screen fallback, uses a previously paired Windows IPv4 through verified TCP even on networks blocking discovery broadcasts, synchronizes notification controls, and ensures stopping streaming does not halt Android screen recording. It **retains v015's user-confirmed low-latency sender/decoder/renderer**, including JPEG latest-frame replacement, early ACK and GDI BitBlt. Full HD / portrait rendering preserves aspect ratio, and eligible Android playback audio travels over separate TCP 57009.

## Features and permissions

Android can take UVC screenshots, create video-only UVC MP4s, record the screen with eligible internal audio to MP4, and save to an SAF folder and/or SMB 2/3 server after the local file has been finalized. SMB credentials are encrypted through Android Keystore.

A real Android screen-sharing session ALWAYS needs current MediaProjection consent; it cannot restart silently if stopped by the system. On Android 13+, allow notifications to use the record / streaming stop / resume actions. Android background Activity restrictions may affect automatic UVC foreground launch after USB insertion.

Windows receiver is one standalone EXE. OBS can use Window Capture. TCP 57007 JPEG input is backward compatible with earlier MMC9 senders; audio TCP 57009 and discovery UDP 57008 should be permitted on the Windows **Private** network profile only. Never expose these ports to the Internet.

## Build and tests

The GitHub Actions workflow builds the Java/Kotlin APK and bundled native UVC code with Gradle/Java 17, builds a static Windows x64 EXE using MinGW-w64, runs JUnit and source-contract tests, then exercises the EXE under Windows with JPEG/ACK, UDP discovery, false receiver, audio, reconnection and >2-minute soak tests. It verifies SHA-256 checksums before publishing.

UVC backend: [AndroidUSBCamera](https://github.com/ernestp/AndroidUSBCamera), Apache-2.0, pinned to a known source commit and patched with an MJPEG callback. Audio/recording/SMB and detailed limitations are documented in [TRANSFER.md](TRANSFER.md).

Previous low-latency release: [v0.15](https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.15).
