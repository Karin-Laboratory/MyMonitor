# MyMonitor v0.13: Android screen + UVC in one app

## Deliverables

- **mymonitor013.apk**: one Android application for USB HDMI/UVC preview, automatic Windows casting, MediaProjection screen capture, internal playback audio, recordings and SMB/local exports. This replaces the planned separate MyCast sender APK.
- **mycastreceiver013.exe**: one Windows x64 EXE compatible with v012's MMC9 MJPEG sender. Receives smartphone screen JPEG and UVC's passthrough MJPEG on TCP 57007, plus internal audio PCM16/48kHz stereo on TCP 57009. Receiver discovery UDP 57008.
- **Recording:** UVC video capture retains existing video-only MP4, photos and SMB export; screen recording uses MediaCodec H.264 video plus AAC internal audio saved to MP4 locally, then exported asynchronously to local SAF folder and/or SMB.

## Start and permissions

1. Start mycastreceiver013.exe. Close previous receiver versions first. Allow TCP **57007**, **57009** and UDP **57008** on trusted Private LAN/Wi-Fi only.
2. Open MyMonitor013 on the phone. It starts an ongoing foreground notification and discovers LAN receivers. Exactly one receiver connects automatically; when several receivers appear, the last selected/remembered receiver wins, otherwise the user chooses from the existing receiver picker.
3. **User consent is mandatory for every Android screen-sharing session.** If the receiver is found while the app is open, Android asks for MediaProjection permission. Grant it; MyMonitor returns to the home screen and streams the screen. If the receiver appeared later while the app was backgrounded, tap the foreground notification to grant screen capture. The app cannot silently bypass Android's consent dialog.
4. Grant the RECORD_AUDIO permission for internal playback sound. Only other apps' capturable playback (media/game/unknown usage and allowed capture policy) can be sent; DRM-protected material and some apps do not permit capture.
5. Plug in a UVC capture adapter and grant USB/camera access. When the Android system delivers the USB attach intent and launches MyMonitor, the UVC preview takes priority; the receiver switches to UVC MJPEG automatically. On unplug it returns to the still-running screen projection, if valid. Android restrictions may require tapping the UVC/notification to bring the app forward.
6. Notification: recording start/stop, streaming stop and exit. A deliberate streaming stop persists until the user explicitly starts streaming again in-app. The projection may require fresh consent if it was stopped by Android.
7. Screen changes between portrait/landscape: MediaProjection's display is resized, retaining aspect ratio with a **1920x1080 landscape / 1080x1920 portrait maximum**. Receiver uses fixed initial 1920x1080 video area, preserves aspect ratio, and allows manual resizing.

## Live codec decision

**Live screen video deliberately uses JPEG rather than H.264, and internal audio uses PCM16 rather than AAC.** On trusted high-bandwidth home LAN, this keeps the already-proven low-queue MMC9 transport and avoids H.264 buffering, new Media Foundation decoder requirements and A/V jitter buffers. Sound is received using built-in Windows winmm waveOut; OBS may capture it via desktop audio. **MP4 files** are encoded H.264/AAC, not JPEG/PCM.

The v012 "one JPEG in flight + ACK + newest frame" policy, TCP watchdog and automatic reconnect remain. Stream and recording use separate queues: slow SMB uploads or MP4 encoding do not directly add frames to the network send queue.

## Limitations to confirm on real devices

- No userless MediaProjection activation. Each session needs permission; system can terminate it when screen locks, another projection starts or the user presses Stop. Notifications are the recovery path.
- On some Android releases, automatic foreground launch after USB insertion is restricted. The USB class filter provides OS-assisted opening when possible; otherwise the user must tap the ongoing notification.
- The foreground service itself is subject to OS background limits, battery saver, and notification permissions. "Permanent" service lifecycle is not guaranteed.
- The on-device H.264/AAC recording encoder depends on MediaCodec YUV420Flex support and availability of AudioPlaybackCapture. Recording frame conversion costs CPU, so capture/record stress on the user's exact phone remains to be verified.
- UVC recording is video only in v013, as in v012. If the USB device itself supplies audio, that is not added here.
- 013 Windows and Android audio is a separate TCP stream; there is no clock-corrected A/V sync engine. This is deliberately biased toward minimum perceived lag and may produce lip-sync offsets on congested WLAN.
- CI tests build the APK+EXE, decode synthetic Full HD MJPEG, test reconnection and corrupt frames, perform a 2+ minute video soak, and inject synthetic PCM. It cannot confirm actual on-phone MediaProjection, UVC capture, MediaCodec MP4 or speaker audio without real devices.
- Windows application runs on a single EXE using GDI+/winmm; no DLL download or additional runtime.
- Sender TCP/UDP protocols are unauthenticated and unencrypted. **Do not forward TCP 57007/57009 or UDP 57008 outside trusted home LAN.**

## Release / rollback

CI uses the same cached debug signing key as v0.12. v0.12 releases remain available:
https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.12

Use the matching v0.13 APK+EXE together. Older UVC sender 012 is also compatible with EXE 013 over MMC9. Ensure you have locally backed up recordings and SMB settings before changing debug signing identity or uninstalling.
