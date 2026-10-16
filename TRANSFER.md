# MyMonitor / MyCast Receiver v034

After GitHub Actions succeeds, download from:
https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.34

- **mymonitor034.apk**: Android, in-place upgrade from v033 with the existing GitHub Actions signing identity.
- **mycastreceiver034.exe**: Windows x64 native receiver (single unsigned EXE).
- **SHA256SUMS.txt**: checksums for both binaries.

## v034: sound from your HDMI capture adapter

UVC video and **USB Audio Class (UAC)** sound can play on the phone and stream together to Windows over the existing 48k PCM audio TCP channel (57009). Android Settings includes a toggle for local phone-speaker monitoring; Windows playback remains enabled independently. Android RECORD_AUDIO permission is required. If the adapter offers only UVC video, picture stays available without sound. Phone-microphone fallback is **never** used.

Local USB MP4 files can contain the device's UAC audio. Live USB sound may pause on Windows while recording a local MP4 to avoid two simultaneous native USB audio claims; audio resumes after recording ends.

See [SPEC_FIX_034.md](SPEC_FIX_034.md) for verification details. Android screen audio, TCP discovery, remote control, gesture queue, screen lock recovery and Windows UI from v033 remain in place. Windows EXE has no code signature; use only trusted LAN connections. A physical UAC device is required to validate sound end-to-end.
