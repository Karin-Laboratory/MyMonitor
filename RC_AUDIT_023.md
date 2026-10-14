# MyMonitor v023 RC engineering audit

## Release classification
v023 is an **RC candidate pending real-device acceptance**. CI cannot demonstrate hotplug reliability on a Pixel 10a or cross-device power management behavior.

## Incident and root-cause candidates
Field logs through v022 showed native UVC `result=-99` after repeated device reconnect and occasional OPENED followed by a second stale onConnect callback. Source review found that both MainActivity and MonitorFragment handled USB broadcasts with independent delayed tasks; USBMonitor listeners posted work after their registration had been destroyed. CameraUVC OPENED state and monitor registration could therefore disagree.

## Changes made
- Activity now coalesces USB attach/detach broadcasts into one enumeration-and-reconcile path, cancelling outdated delayed callbacks; the Fragment alone owns CameraUVC and USBMonitor.
- Fragment USB callbacks are tagged by a monitor epoch and ignored if they belong to an obsolete USBMonitor registration.
- Native monitor restarts invalidate old epochs before releasing the previous monitor; timeouts prevent a missing permission result from leaving the UI stuck.
- USB preview is first initialized only when the TextureView surface is ready.
- Physical-device presence check remains the authority for deciding to close a camera after a transient USBMonitor disconnect.
- USB retry backoff and stale-event policy have pure Kotlin unit tests.
- Android versionName and Windows resource version aligned to 0.23; release docs refreshed.

## Regression protections
- Retain low-latency MJPEG sender/receiver, full HD preview geometry, screen rotation resize, audio reconnect after unlock, SMB storage, screen+UVC recording and receiver discovery.
- CI validates Android native compile and JUnit, Windows MSVC native build, Defender scan when available, video reconnect, latency, discovery, audio, 2-minute soak and SHA-256 release provenance.
- Android target SDK remains 28 by design for existing legacy implementation; modern target SDK migration, Android policy compliance and full permission behavior are **not** claimed as RC-ready.

## Physical RC acceptance checklist (not run by CI)
1. Pixel 10a: cold launch with and without UVC; 20 USB unplug/replug cycles, both orientations and varied intervals.
2. Confirm exactly one accepted UVC OPENED per connection epoch, no endless -99 loops; include 10+ rapid hotplugs.
3. Windows receiver: automatic discovery, unplug fallback, reconnect UVC and recovery of audio after lock/unlock.
4. Leave session running 30 minutes with screen lock/unlock and Android foreground/background changes.
5. Record screenshot, video-only UVC MP4, screen MP4, SMB/SAF save, and verify file playback.
6. Defender check on target Windows PC, including source verification SHA-256.
7. If any failure persists, capture `MyMonitor-UVC` plus `UsbManager`, `USBMonitor`, and native stderr logs for the matching event epoch.

Only after these pass can the release be promoted from RC candidate to accepted RC.