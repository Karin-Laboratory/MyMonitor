# v029: Ctrl+mouse-wheel pinch reliability

## Reported symptom
The v028 Windows receiver could pinch-zoom Android maps with one slow Ctrl+wheel detent, but rapid repeated wheel events frequently had little or no effect.

## Root cause and v029 correction
- v028 emitted one independent two-finger Accessibility gesture for each wheel message and Android called `dispatchGesture()` immediately. Android can cancel a previously running gesture when a second gesture is dispatched, so rapid input caused gesture cancellation.
- Windows v029 accumulates partial/high-resolution wheel deltas to 120-unit detents and groups all wheel events in a 160ms window into **one** two-finger packet. Batching is bounded to 24 detents, gesture strength to 6, and the two simulated fingers stay inside the current letterboxed video area.
- Android v029 queues remote input on its main looper and only starts another `dispatchGesture()` after `GestureResultCallback.onCompleted` or `onCancelled` fires. Two-finger strokes share one 220ms duration. A bounded waiting-pinch queue favors fresh map input during sustained fast rotation without interrupting an in-progress gesture.
- Ctrl+wheel **DOWN** moves fingers outward (zoom in), **UP** inward (zoom out). Normal wheel scrolling is not otherwise changed. Windows now recognizes the documented `MK_CONTROL` flag on `WM_MOUSEWHEEL`.
- Failed accessibility dispatches do not block later input. Disabling remote control clears Windows queued packets and its pending wheel batch. UVC source changes clear pending wheel batches.

## Safety and compatibility
- No changes to network protocol: MMC6 source metadata + existing 18-byte ACK with 17-byte remote command.
- Android-side control still requires an enabled accessibility service, explicit in-app remote permission, live phone-screen MediaProjection, an active private-LAN stream and an unlocked screen.
- UVC source remains non-controllable. Audio, recording, JPEG streaming, USB recovery, F11 and screen rotation paths unchanged.
- Both APK and EXE should be upgraded together to v029. Prior v028 release remains available.
- Windows EXE unsigned. No cryptographic peer pairing; use trusted LAN only.

## CI coverage
- Android Kotlin/JUnit and 29-version source contract guard
- Native Windows MSVC build and metadata verification
- Native `windows/wheel_test.py` sends rapid Ctrl-wheel bursts to the running receiver, confirms one packet per batch, verifies pinch direction, accumulates 60+60 partial wheel delta, checks remote control OFF suppression
- Existing MMC6/F11, low-latency MJPEG, TCP discovery, audio reconnect and 2-minute soak tests retained

**Physical Pixel / Google Maps gesture confirmation remains required.** Windows tests exercise emitted packets; they cannot observe real Android Accessibility behavior.
