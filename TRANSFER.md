# MyMonitor v0.16 + MyCastReceiver v0.16

## Purpose

One Android APK automatically switches input between USB UVC/HDMI capture and Android screen; one low-latency Windows x64 EXE receives the selected video and any eligible Android internal audio.

User-verified v015 low-latency keyboard performance is deliberately preserved. V016 is a lifecycle, source arbitration, pairing, notification and recording consistency release, not another codec/transport change.

## Download and upgrade

- mymonitor016.apk (same Android app ID, cached signing key). Update in place; do not uninstall first if you want to retain saved receiver IPv4, SMB credentials and SAF folder access.
- mycastreceiver016.exe (single Windows EXE). Stop 015.exe before opening 016.exe because only one instance can listen on the network ports.
- Previous rollback: https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.15
- Full specification audit and acceptance checklist: SPEC_AUDIT_016.md in the repository and attached release.

Use the Windows Private network firewall profile to allow UDP 57008 (discovery), TCP 57007 (MJPEG/identity probe), TCP 57009 (eligible Android playback audio). Never expose them to the public Internet.

## Source preference and UI

| State | Behavior |
|---|---|
| UVC OPENED | Show UVC preview on Android and send UVC MJPEG to the verified Windows receiver |
| UVC physically detached, MediaProjection still active | Close UVC, discard old queued UVC JPEG, automatically return to Android home and send the native Android screen; audio follows the screen |
| UVC detached, screen-sharing permission invalid/absent | Request Android's current-session screen-sharing consent if the Activity is foreground and streaming was requested; if not available, tap the ongoing notification to authorize |
| Screen-sharing consent denied | Respect denial and do not repeatedly trigger dialogs; tap the foreground notification later to retry |
| UVC connected again | USB attached intent/USB permission, preview becomes available and takes priority without selecting a "mode" |
| Phone portrait/landscape | MediaProjection resizes live video with aspect ratio preserved, up to 1080x1920 / 1920x1080 |
| Receiver absent | Local preview, photo and MP4 recording still work; reconnect discovery continues |
| Notification "送信停止" | Stops video/audio transport but does NOT stop an active local screen MP4 recording |
| Notification "送信再開" | Rechecks remembered Windows IPv4 / broadcast and resumes the existing capture source |
| Notification "● 録画" / "録画停止" | Controls current source's MP4 recording |
| Notification "終了" | Stops service, projection and network; attempts MP4 finalization |

## Receiver auto-connection

V016 ALWAYS tries the last successfully validated IPv4 + TCP port by connecting directly with MCP1/MCR1 receiver proof before using UDP broadcast discovery. A DHCP server or stale IP does not qualify.

This directly addresses the user's home LAN, where manual IPv4 entry succeeded but broadcasts did not. The phone can reconnect to that same Windows PC without relying on UDP broadcasts after it has been paired once.

If remembered Windows has moved to a different IP, phone falls back to nonce-protected UDP V2 discovery: two sends, all per-interface broadcast addresses + 255.255.255.255, temporary Android multicast lock, and 2.2s receive window. Every UDP response must still pass the direct TCP proof. If both direct saved-IP verification and UDP fail, a **rate-limited, private-LAN-only /24 TCP check** probes at most 254 addresses on the dedicated receiver port, with a 3.5-second deadline and at most 24 concurrent probes. This runs at most once per 90 seconds in background discovery and never during an established connection.

The picker shows all verified receivers plus an active already-verified receiver, even though TCP identity probing cannot run concurrently with a running one-client video connection.

**Limit:** First-time discovery can still fail across a different subnet/VLAN or with a blocked receiver port; type the actual Windows PC IPv4 one time in "Windowsへ転送". Save occurs only after actual receiver reply.

## Recording

- UVC: existing captureImage/captureVideoStart/captureVideoStop; video-only MP4. Finalized recordings are saved locally first and then asynchronously exported to SAF folder and/or SMB. Export failures keep the original.
- Android native screen: MediaProjection ImageReader, H.264 video, eligible internal audio AAC (or video-only if no audio); MP4 on Android, export to configured folder/SMB after finalization.
- Network sending and local recording are independent; pressing "送信停止" will no longer starve native screen recording of video frames.
- Changing from native screen recording to UVC attempts to finish screen MP4 and opens a separate UVC MP4. Unplugging a UVC device during recording attempts video stop and a separate native screen MP4 after consent, but the old USB recording can be lost if hardware disappears before the MP4 container is finalized.

## Notification & Android permissions

- Foreground notification includes source/recording state, record toggle, stream stop/resume and exit.
- Android 13+ may require POST_NOTIFICATIONS; allow it to use the notification controls.
- Android MediaProjection permission is per session. It is NEVER silently bypassed or reused after the session is stopped; screen lock, user stop or OS projection replacement can invalidate permission.
- USB auto-foreground display depends on Android and the user accepting the USB-attach permission prompt. This is not guaranteed when OS background Activity restrictions apply.
- When UVC Activity goes to background, its TextureView-owned camera preview stops; true headless UVC recording is outside v016 scope.

## Verification

The v016 CI runs:
1. Source-level specification contract test.
2. JUnit Android host tests for input priority/USB lifecycle and receiver selection.
3. Direct-TCP fake receiver/false DHCP tests, including rediscovery of a saved receiver without UDP broadcast.
4. Android native UVC + APK build and Windows x64 static EXE build.
5. Live Windows EXE Full HD, JPEG decode timing, 140-frame rapid ACK, V2 UDP/TCP receiver proof, audio TCP, heartbeat, corrupted JPEG and reconnection, plus >2-minute soak.
6. SHA-256 checks before release publication.

GitHub Actions cannot validate the actual phone, USB dongle, Android background-launch constraints, Wi-Fi latency or user's Windows firewall. Those require on-device testing.

## Real-device acceptance checklist

1. With 016.exe running, restart 016.apk. It should reconnect directly to the Windows IP last used successfully.
2. Allow screen sharing, then insert UVC. The UVC preview must take priority; Windows should show UVC without manual mode selection.
3. Unplug UVC with valid screen sharing; phone returns to native home and Windows displays native screen. With invalid sharing, grant consent once through Activity or notification. Denying must not loop.
4. Record with stream stopped, then resume stream without stopping the local MP4.
5. Check notification stop/resume/record/exit and folder/SMB exports.
6. Confirm keyboard responsiveness matches v015 and that portrait/landscape and a transient Wi-Fi outage recover.

The full v016 audit is in SPEC_AUDIT_016.md.
