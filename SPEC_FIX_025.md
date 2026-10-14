# v025 remote screen control (experimental RC)

## Scope
Windows mouse and touchscreen can control the **Android screen only**, not an external UVC capture source. All old capture, audio, UVC hotplug and storage features remain independent of the new service.

## User-controlled permissions
1. In MyMonitor, press 遠隔操作 and enable the MyMonitor accessibility service in Android system settings.
2. Start Android screen sharing with MediaProjection consent and connect MyCastReceiver 025.
3. In MyMonitor press 遠隔操作 again and arm remote input for THIS transport session.
4. On Windows press Alt+R to turn remote input ON or OFF. It starts OFF, and resets OFF on every disconnect.
5. On Android, UVC priority, screen-projection loss, source switch, lock screen, or lost video TCP transport must inhibit input. Permission can be revoked in Android accessibility settings at any time.

## Controls
- Left click: tap.
- Hold left click >=500 ms: long press.
- Left drag and release: single swipe.
- Right click: Android back.
- Wheel: scroll through gesture; Ctrl+wheel: synthetic symmetric pinch.
- Windows multi-touch pointer: individual touch strokes; two-finger gesture packet supports two independent start/end paths.

## Protocol
v025 sends MMC5 magic to port 57007, retaining the same Android-outbound TCP video socket. Each standard video/heartbeat ACK is followed by a fixed 17-byte control packet, opcode + eight unsigned 16-bit normalized coordinates. No new listening port or open inbound access on the phone. Legacy MMC9 senders remain accepted by the v025 receiver but v025 Android requires v025 Windows receiver.

The sender receives at most one command per video ACK; drag/swipe executes on mouse release. Windows touch input and Android dispatchGesture introduce approximation and are not a hardware-level input mirror. Long continuous drag, advanced multitouch gestures and secure cryptographic peer pairing are limitations of this initial implementation.

## Security caveats
Accessibility dispatchGesture is a powerful permission. Android user must enable it explicitly AND arm input for each session. Windows also defaults to control OFF. No guarantee of authenticated identity beyond the established selected video transport; run only on a trusted private LAN and never expose TCP 57007/57009 or UDP 57008 publicly. On-device PIN cryptographic pairing is recommended before broader deployment.

## Testing
CI verifies Android build, static contracts, the control-ACK default-OFF framing, Windows MJPEG/ACK legacy compatibility and soak/audio/latency tests. Pixel 10a accessibility gesture injection, physical Windows touch panel, pinch and coordinates must be tested on hardware before RC acceptance.
