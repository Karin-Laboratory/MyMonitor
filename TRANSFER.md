# MyMonitor / MyCast Receiver v035

Stable download after GitHub Actions succeeds:
https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.35

- **mymonitor035.apk**: Android (in-place upgrade from v034 with the existing development signing key)
- **mycastreceiver035.exe**: Windows x64 standalone unsigned receiver
- **SHA256SUMS.txt**: both hashes for verification

## v035 UVC repeated-crash fix

v034 attempted direct USB Audio Class capture on the same native USB control block already owned by the UVC video camera. The competing native lifecycles are the leading suspected cause of repeated Android app termination. v035 removes all direct native UAC calls from the app and restores the stable UVC preview/audio-source initialization from v033.

USB capture audio is now optional and captured only through Android's **system-recognized USB audio input**, with explicit device routing checks and **no phone microphone fallback**. If Android cannot route to a USB audio input, video continues silently, with a clear status. Windows audio continues to use TCP 57009.

**Important:** For safety, local UVC MP4 recording is **video-only** in v035. The MP4 USB audio feature from v034 is disabled until native stability is verified on an actual phone. Windows streaming and local audio can still work where Android exposes the adapter's USB input. See [SPEC_FIX_035.md](SPEC_FIX_035.md) for technical rationale and real-device tests.

Windows screen-casting and its internal audio, remote keyboard/gesture controls and video MJPEG transport are unchanged. GitHub Actions passing does not replace verification with a physical UVC device.
