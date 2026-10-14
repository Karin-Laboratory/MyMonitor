# MyMonitor v024: alternating USB hotplug failure

Pixel 10a observation: UVC capture consistently succeeds on odd insertions and fails on even insertions. This reproducible alternating pattern suggests lifecycle state, not a random timeout.

## Root-cause candidate
v023 detached the CameraUVC on physical removal, but left USBMonitor (including native and UsbControlBlock lifetime) registered for reuse on the next insertion. A stale previous USB connection may survive and collide with the next native open; error -99 remains opaque.

## Fix
- Track the enumerated Android USB deviceId.
- On confirmed physical unplug, close CameraUVC, invalidate pending retry callbacks, unregister and destroy USBMonitor, and clear the old monitor reference.
- On the next insertion, create a new USBMonitor before requesting permission.
- Protect monitor reset against recursion and reject callbacks from obsolete monitor generations, including onDetach.
- On unexpected deviceId changes, close/reset before making a new request.
- Keep v023 epoch checking, timeout and diagnostics; leave low-latency video, audio recovery and preview geometry unmodified.

## Verification required
- Pixel 10a: at least 20 complete detach/attach cycles at varying intervals. Record success/failure parity.
- Verify stale callback suppression and stable deviceId in MyMonitor-UVC log.
- Test Windows discovery, audio lock/unlock, portrait/landscape and background sessions.
- Android CI cannot prove a physical USB hotplug fix. The -99 native error is not yet independently root-caused.
