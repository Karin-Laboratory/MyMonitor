# MyMonitor 019: Windows Defender packaging and Android lock/unlock audio repair

## Observed symptoms and retained baseline

User confirmed that **v018 Android UVC landscape display is fixed** and that **v015 onward Windows keyboard latency is effectively imperceptible**. Do not change these.

New reports:
1. `mycastreceiver018.exe` is blocked/detected by Windows' built-in antivirus (precise threat name, Defender verdict and sample have not been provided; **cannot conclude false positive from report alone**). `mymonitor018.apk` installs.
2. Windows playback audio ceases after the Android phone leaves its lock screen (present since at least v017). Video appears to continue, but exact phone/audio logs are not available.

## Relevant source analysis (18)

- Windows was built using **MinGW-w64 static linking and `-mwindows`** on an Ubuntu runner. The final EXE was unsigned and included a network listener. Defender detection might be reputation/heuristic or a real security finding; the cause is **unconfirmed**. Do not whitelist it blindly.
- Android `ScreenStreamService.startAudio()` creates a single `AudioRecord` using `AudioPlaybackCaptureConfiguration(MediaProjection)` and starts a blocking `read()` loop once on projection start. There were no screen-lock/unlock broadcasts, reinitialization after `AudioRecord` read failure, or recording-state validation.
- Android `StreamHub.audio` keeps one `MMA1` socket open until the screen/UVC source or receiver changes. The Windows `waveOut` device was also opened only on socket acceptance. Consequently, a locked/unlocked phone or changed Windows output endpoint could leave stale PCM capture or playback state without a recovery path.

## Changes in v019

### Android audio lifecycle

- Register SCREEN_OFF, SCREEN_ON and USER_PRESENT receiver *while the foreground service exists*. Screen-off **suspends only internal-audio capture/streaming**, releases the current `AudioRecord` and stops the PCM connection. Video, UVC, MediaProjection (if OS keeps it) and MP4 recording remain independent.
- On user-present/screen-on **only once Android reports the device is no longer locked**, recreate a fresh `AudioRecord` with the existing MediaProjection instance (only if still valid and previously suspended). Check `STATE_INITIALIZED` and `RECORDSTATE_RECORDING`; reset audio retry count.
- Restart `MMA1` TCP 57009 to make the Windows receiver reopen its playback session. The low-latency JPEG/ACK TCP 57007 connection is not restarted for audio resume.
- On `AudioRecord.read()` error, guarded reconnect/recreation with at most three retries. Never loop/retry while the phone is locked. Log to `MyMonitorAudio` and indicate when new MediaProjection consent is necessary.
- If Android invalidated MediaProjection on locking, automatic reacquisition is **not possible**; inform user to tap the notification and grant permission again. Some content/DRM/capture policies prohibit internal audio regardless of session health.

### Windows audio and build provenance

- On output device failure `waveOutWrite`, reset and release broken waveOut headers/device, then retry opening WAVE_MAPPER at a bounded interval. Bound waits for stuck audio buffers (800ms) so one bad device does not pin the playback thread indefinitely.
- Add Windows CI regression: three consecutive PCM16/48kHz `MMA1` TCP sessions (models phone lock/unlock/reconnect). No device in CI means this verifies protocol recovery, not physical audible sound.
- Replace Linux MinGW-w64 static EXE cross-build with **Windows-hosted native Microsoft Visual C++ compiler (MSVC)** using `/MT` and Windows SDK resource compiler. Ship a single x64 EXE with Windows native CRT statically linked. Keep existing low-latency JPEG/GDI pipeline unchanged.
- Add standard Windows VERSIONINFO metadata (product, executable name and 0.19 version) plus the existing app icon. Publish SHA-256 checksums.
- On runners with Defender enabled, request a file scan and fail on a reported detection. On hosted runners without Defender, mark scan **not performed**. No attempt to disable antivirus, bypass detection or conceal executable.
- **No Authenticode certificate** is available. MSVC and metadata do not guarantee Defender/SmartScreen acceptance. If v019 is flagged, obtain the exact Defender alert name, file SHA-256 and Protection history details. Investigate and submit a verified false-positive sample through Microsoft's official workflow rather than using exclusions.

### Not changed

- v018 native UVC preview fit-center and rotation repair
- v015 asynchronous JPEG decode, receive ACK, GDI DIB paint, frame-dropping policy
- v016 LAN receiver discovery and identity challenge
- audio transport format (PCM16 stereo, 48000Hz, TCP57009), screen/UVC input priority, local/SMB recording

## Test and acceptance plan

| Scenario | Expected |
|---|---|
| EXE compile | MSVC compiler on GitHub Windows x64 runner, no MinGW in Windows binary |
| Defender verdict | runner scan if enabled; user PC still must determine local Defender result |
| Start screen cast | video and PCM audio as before |
| Android lock for 30+ sec | video service remains if Android permits; audio gracefully closes |
| Unlock with valid MediaProjection | Android internal audio and Windows playback TCP restart without relaunch |
| Projection stopped by OS | Notification asks user to reauthorize; no silent bypass |
| Locked UVC source | UVC MJPEG remains independent from internal audio |
| Repeated 3x audio sessions | Windows EXE stays alive and accepts each new session |
| 1080p display / fast ACK | unchanged low-latency video and Windows tests PASS |
| >2min Windows soak | automatic reconnects / corrupted JPEG handling as in v018 |

**Limitations:** CI cannot reproduce Pixel's actual lock behavior or play audible PCM on physical speakers. Actual Defender threat verdict may differ by AV definitions and reputation. Both require user-side verification. Keep v018 intact for rollback.
