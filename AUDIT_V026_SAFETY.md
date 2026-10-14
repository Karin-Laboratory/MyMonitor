# MyMonitor v026 safety audit (source-only, 2026-10-14)

## Scope and protection
- Starting point: main f5672b4d0d289237cc3f3adb13c25b991f08afff, including the not-yet-built landscape rotation fix.
- Work is isolated on `audit-v026-safety`; **main, v0.26 binaries and release are untouched**.
- Reviewed Kotlin core: MainActivity, MonitorFragment, StreamHub, WindowsSender, ScreenStreamService, ScreenRecorder, RemoteControlService, PcmSender, ReceiverDiscovery, Storage, SmbCredentials, PreviewGeometry, CapturePolicy, UsbRecoveryPolicy.
- Reviewed C++ receiver, WinMM audio backend, build scripts, pinned native UVC patch, manifest, test programs and the GitHub Actions release workflow.
- Goal: prioritize already observed v026 reliability and verified Pixel 10a UVC hotplug; avoid invasive redesign.

## Confirmed issues corrected (audit branch only)
1. **Stale screen rotation contract:** previous source-only rotation fix renamed the `Screen rotation capture` log. Main Actions run 38533800616 stopped at an obsolete text assertion before compiling. The guard now checks the actual display-listener registration, cleanup, frame watchdog and old-frame rejection.
2. **Wrong Android video connection status:** `WindowsSender` had a session-local `connected` variable shadowing the `@Volatile` property used by `isConnected()`. ACK reception set the local only, so notification text could remain in the disconnected state. Renamed the local to `sessionWasConnected`; ACK handling now updates the shared field without changing transport behavior.
3. **Stale remote actions after Alt+R OFF/ON:** Windows control commands were retained in the FIFO when input was switched OFF and could execute after ON. The queue is now cleared when disabled.
4. **Repeated Alt+R flips:** `WM_SYSKEYDOWN` autorepeat toggled remote control repeatedly when held. It now handles only the initial keydown (previous-state bit 30).
5. **Stuck Windows mouse capture:** `WM_LBUTTONUP` previously skipped `ReleaseCapture` if a touchscreen event had recently arrived. Capture is now released unconditionally for a captured mouse press, while suppressing the unintended tap.

## Inspected, deliberately unchanged
- **Physical UVC detach/reconnect**: USBMonitor generation gating, native camera closure and physical-device checks are left untouched after the known 10/10 hotplug acceptance.
- **Video and audio transport**: MMC5 18-byte ACK, JPEG fast-path, Android audio PCM and Windows WinMM playback unchanged to protect latency.
- **Gesture mapping**: v026 full-physical-display coordinates, mouse Back->Home / Forward->Back, screen-only permission and per-session arming unchanged.
- **Recorder and SMB**: these have potential edge cases around muxer shutdown, queueing and network failures, but there is insufficient reproduction to justify destabilizing working paths.
- **Network security**: the MCP1/MCR1 response confirms protocol compatibility but is not cryptographic identity verification. Video/audio/control on the trusted LAN have no end-to-end authenticated pairing. This is a known design risk, **not fixed** because pairing would require a new interoperable protocol, UI and hardware tests.
- **Landscape return from Home**: physical-display listener and full-screen resize fix remain provisional until tested on Pixel 10a. Android MediaProjection capture-size callbacks and display events are timing-sensitive; changing their policy further without a reproduction is not justified.

## Static review/checks
- Checked core ownership, callback registration/teardown, high-risk UVC path invariants, remote input gating, physical screen coordinates, explicit ACK handling, window capture release, and build/release artifacts.
- Source-pattern checks for 10 targeted critical invariants passed via repository read. The static contract script was updated but **not run in CI as part of this branch**.
- The Android/JVM test suite, native MSVC build, Windows integration/soak and Pixel 10a hardware tests **have not been run for these changes**.

## Promotion criteria (future user-approved step)
1. Explicitly trigger a build on the audit branch without publishing to v0.26; use a branch-specific workflow or isolated CI.
2. Android unit and native build, Windows MSVC + video/audio/discovery/control/soak tests.
3. Pixel 10a: screen portrait->landscape->Home->landscape; verify centered video and click accuracy.
4. UVC unplug/replug 10+ cycles, record/save/SMB, screen lock/unlock audio, Alt+R rapid toggles and physical mouse/touch.
5. Only then review for merge into main and plan a new version/tag.

No merge, build or release was intentionally performed.
