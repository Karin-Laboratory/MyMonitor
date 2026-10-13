# MyMonitor 016: specification-to-code audit

Audit baseline: v015 commit cd772a90381870a226f43f2a6c71521f73c2f60e.
This report describes source inspection and implemented v016 changes; it is not a claim that a real Pixel, USB dongle, Windows display, SMB server or Wi-Fi topology was physically tested.

## Intended product

One Android APK, no user-visible input-mode switch:
- UVC/HDMI input attached and **OPENED** -> show UVC preview, stream UVC MJPEG.
- No UVC input -> stream Android-native screen and eligible playback audio, with Android's mandatory current-session screen-sharing consent.
- Unplug UVC -> immediately favor a still-valid Android screen capture; if permission is no longer valid, guide a new consent request, and bring the native Android home back rather than leaving a disconnected UVC preview covering the screen.
- Windows EXE starts and is automatically found. Once paired, reliably reconnect even if UDP broadcasts are blocked.
- User can independently record locally/SMB; stopping a live stream must not stop a local screen recording. Notification EXIT must stop the foreground UVC Activity as well as the streaming service.
- Persistent notification exposes recording, stop/resume stream, and exit.
- Preserve the v015 low-latency transport, high-res scaling, rotation adaptation and saved settings.

## Findings and changes

| Spec / scenario | v015 implementation defect | v016 correction |
|---|---|---|
| UVC unplug to native Android | Fragment closed UVC and cleared the source flag, but no Activity navigated home, resumed screen capture or sought consent | Activity's single debounced onUvcGone() handles USB broadcast / USBMonitor callback, returns home with active projection or requests current-session consent |
| USB removed without MediaProjection | The initial UVC-first startup never asked screen permission, so source switching stopped sending | Consent is requested only when needed; user denial suppresses repeated dialog loops and the notification provides recovery |
| USB reconnect | UVC monitor only opened in a foreground Activity and required USB permission; attach could be missed when Activity resumed | Activity onResume / OS USB attached intent / USBMonitor callbacks all refresh USB enumeration and open the capture |
| Multiple USB disconnect indications | ACTION_USB_DEVICE_DETACHED and USBMonitor onDetach/onDisconnect both fire | Debounced Activity unplug handler and idempotent close; record-handoff flag survives early onDisconnect |
| Stale source frame | A queued JPEG might come from the previous input immediately after switching | StreamHub discards pending JPEG when the source changes |
| Auto receiver discovery | Relied entirely on Android UDP broadcasts, even after manual IPv4 pairing worked | Saved Windows IPv4 and TCP port are probed directly first using MCP1/MCR1; if not verified, retry nonce-protected UDP with per-interface and global broadcast |
| Broadcast handling | Single send and 1.4s receive window could lose Wi-Fi broadcast responses; interface broadcast address may be absent | A second broadcast at 550ms, 2.2s discovery window, 255.255.255.255 fallback, temporary Wi-Fi multicast lock |
| Wrong receiver / DHCP server | Never trust an address just because it appeared in discovery traffic | Keep v014/v015 V2 nonce + direct TCP proof; expired saved addresses do not qualify |
| Manually selected receiver later restarts | A manual IPv4 could be remembered but not revalidated/used automatically on next start | StreamHub verifies remembered IPv4 directly before using it, without requiring UDP |
| Multiple receivers and selection | Streaming receiver can refuse further proof connections while the single TCP receiver is busy | Picker preserves already verified active connection and scans for additional discovered hosts |
| Recording with "送信停止" | ScreenStreamService.processImage returned immediately when StreamHub.enabled=false, starving ScreenRecorder | Recording source is independent; image processing continues for an active local recorder while stream is off |
| Stop / resume notification | Only "送信停止" existed, and notification controls/status could become stale | Stop and resume are distinct actions, controls refreshed on source/record/connection state changes |
| Notification "終了" | Stopped the background service but left a live UVC Activity running | The service broadcasts an app-scoped finish command, requests recording finalization, then finishes the Activity |
| Screen recorder -> UVC recorder | A new USB camera may appear while screen recorder is active | Finalize current screen MP4, start a separate UVC MP4 once UVC is opened (best-effort) |
| UVC recorder -> screen recorder | Unplug while recording would abruptly close capture, losing logical continuity | Attempt UVC MP4 finalization and request/restore native screen capture; resume to a new screen MP4 if capture continues (best-effort; physical unplug may prevent finishing old file) |
| Screen rotation and orientation | Activity recreation could close the UVC TextureView and briefly disconnect | Handle orientation/screenSize configuration changes without re-creating Activity; update UVC preview surface size while preserving MediaProjection VirtualDisplay resize and receiver scaling |
| Capture-to-Windows latency | v015 low-latency ACK + asynchronous JPEG decoder + GDI BitBlt already successful in user's real-world keyboard test | Unchanged except v016 file label. Protocol, frame queue and drawing behavior preserved |
| Android audio / Windows playback | AudioPlaybackCapture PCM TCP57009 (eligible applications only) | Retained |
| SMB export | Both recorders finalize locally then Storage exports asynchronously; export failures retain local originals | Retained; do not add network I/O to low-latency sender |

## State transitions

| Trigger | Input source | UI / consent | Network | Recording |
|---|---|---|---|---|
| Open app, no USB, paired receiver available | SCREEN if consented, otherwise AWAITING | Prompt for MediaProjection; go home after consent | Connect after capture ready | Screen recording available |
| Open app, UVC available | UVC when camera OPENED | UVC preview | Connect after capture ready | UVC MP4/photo |
| Connect UVC while projecting | UVC after camera OPENED | MyMonitor foreground USB view | Same verified connection; drop old queued JPEG | Split screen MP4 -> UVC MP4 when previously recording |
| Remove UVC with live projection | SCREEN | Return home | Same connection; flush queued UVC JPEG | Attempt split UVC MP4 -> screen MP4 |
| Remove UVC with expired/never-approved projection | AWAITING | Show Android OS consent | Connection waits; notification recovery | Resume recording if consent granted and handoff requested |
| User stops streaming | unchanged | notification shows "送信再開" | TCP + audio stop | Recording proceeds |
| User resumes streaming | unchanged | notification updates | Probe saved receiver, else UDP discovery | Recording unaffected |
| Receiver goes offline | unchanged | stale last frame annotated in Windows | WindowsSender retries | Local recording proceeds |
| App foreground service exits | none | no indefinite unattended capture | transport closed | MP4 finalized best-effort |

## Verified by v016 CI, independent of hardware

- Unit tests: CapturePolicy source priorities, USB removal and consent transitions, receiver selection with 0/1/2 candidates; ReceiverDiscovery valid TCP proof, bogus DHCP response rejection, saved receiver discovery without UDP, and bounded private-/24 peer enumeration.
- Source-level specification contract guard for USB callbacks, source arbitration, recorder independence, notification commands and v015 low-latency receiver paths.
- Build Android UVC native code and APK, compile static Windows EXE.
- Exercise real Windows EXE with Full HD JPEG, quick ACK burst, corrupted frames, V2 UDP + TCP receiver proof, audio TCP, heartbeat, reconnect, and 2-minute soak.
- Verify release SHA-256 for APK + EXE.

## Android/platform limitations (important)

1. A stopped/expired MediaProjection cannot be silently resurrected. Android requires a fresh user gesture and consent for a new session; we display the permission sheet when Activity is foreground or direct the user to tap the notification. Screen locks / OS projection controls may stop a session.
2. Auto-foregrounding the UVC preview depends on Android's USB-attach intent and user's USB app-choice/permission. Newer Android versions may restrict opening Activity from the background.
3. The UVC preview uses a TextureView owned by an Activity; it intentionally closes on Activity onStop. True background UVC capture with a headless capture service is a different architecture and is NOT in v016.
4. A physically unplugged UVC source may disappear before its MP4 muxer can finalize. V016 sends a best-effort capture stop, but cannot guarantee saving an already-disconnected USB recorder's last file.
5. Broadcast discovery still may fail across isolated SSIDs/VLANs/firewalls. The persistent *direct verified IPv4* fallback solves reconnection after one successful manual pairing. First-time cross-VLAN discovery may still need manual IPv4.
6. "No latency" is not guaranteed. v015 was user-confirmed as practically instantaneous for keyboard use; v016 leaves that pipeline unchanged.
7. The connection protocol is not encrypted or cryptographically authenticated. Use only trusted home LAN; never expose TCP 57007/57009 or UDP 57008 to public Internet.
8. Android users who deny notification permission cannot rely on notification buttons until they enable notifications in device settings.

## First real-device acceptance run

1. Upgrade APK/EXE together, without uninstalling the Android app, to keep encrypted SMB and saved IPv4.
2. Confirm prior successful manual Windows IP now connects automatically with EXE already running, even when UDP discovery still fails.
3. Start screen share, attach UVC, verify instant UVC priority and continued low-latency Windows feed.
4. Unplug UVC, verify Android home resumes immediately if screen sharing is still active; otherwise expect one OS consent dialog, and no repeated prompts after denial.
5. Record with Windows disconnected or "送信停止"; verify MP4 exists in local directory / configured SAF folder / SMB. Re-enable streaming while recording and verify no recorder reset.
6. Check portrait/landscape and receiver reconnection. See README / TRANSFER for fallback and rollback.

## Rollback

The stable-latency v015 release remains at:
https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.15
