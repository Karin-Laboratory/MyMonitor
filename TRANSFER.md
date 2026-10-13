# MyMonitor v0.19 / MyCastReceiver v0.19

**Changes:** restores internal Android playback-audio capture and Windows PCM transport following phone screen lock/unlock, and builds the Windows EXE using Microsoft's native Visual C++ compiler instead of the previous static MinGW-w64 cross-compiler.

**Windows Defender caution:** the v018 detection is not confirmed to be a false positive. The v019 binary is unsigned. MSVC, embedded version information and CI antivirus check (when available) help improve provenance, but do not guarantee the file is accepted by your Windows Defender. Do not turn off Defender or add exclusions to run it. If blocked again, share the exact threat name and file SHA-256 from Windows Security > Virus & threat protection > Protection history.

**Installation:** update `mymonitor019.apk` in place to preserve your permissions/settings; close older receiver processes; run `mycastreceiver019.exe` only after it passes Windows antivirus checks. Receiver remains a single EXE, listens for verified discovery TCP57007/UDP57008, video TCP57007 and PCM audio TCP57009 on trusted private LAN.

**Audio test:** begin Android screen sharing with sound from an app that permits playback capture, lock the Pixel, then unlock it. If the original screen projection remains valid, the app recreates AudioRecord and reconnects the Windows audio socket. If the OS terminates MediaProjection, a fresh *user-approved* capture consent is required through the notification. Phone-screen audio may be silent during the locked state by Android's policy.

**Display & latency:** v018 UVC rotation display and v015 low-latency JPEG/GDI sender/receiver preserved.

**Code review/CI:** `SPEC_FIX_019.md` documents diagnosis and limits; the CI suite builds APK with unit tests and MSVC x64 EXE, exercises TCP video identity, PCM session reconnect 3 times and >2-minute video reliability, publishes matching release checksums.

Previous tested binaries remain at https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.18
