# MyMonitor / MyCastReceiver v020

## Android UVC recovery
- Pixel 10a log at 2026-10-14 09:06 showed repeated concurrent `USB許可取得、UVCオープン中` events followed by native -99 and unsupported preview size.
- Serialize UVC opens with an opening flag; reject repeated USBMonitor onConnect while opening or active.
- Use a stable Fragment container ID on Activity recreation and defer fragment attachment after saved state instead of committing during saved state.
- Preserve v018 NORMAL SurfaceTexture and PreviewGeometry display path.

## Landscape screen transmission
- Periodically verify physical display dimensions against the MediaProjection capture dimensions and resize the existing VirtualDisplay and ImageReader on a mismatch.
- Keep v015 low latency Windows MJPEG / ACK and Windows receiver behavior untouched.

## Validation limits
- CI unit and smoke tests are not a substitute for real Pixel 10a UVC connect/unplug/replug, portrait-to-landscape screen transfer, or long-run testing.
- The unsupported preview size may need a separate capability fallback if it remains after open serialization.
- Windows executable remains unsigned; local Defender results may differ.

## Real device checks
1. With MyMonitor visible, plug/unplug UVC repeatedly. Verify UVC video on phone and receiver.
2. Observe no repeating OPEN events or `result=-99` in `MyMonitor-UVC` logs.
3. Without UVC, share Android screen. Rotate and verify Windows displays correct area; check `MyMonitor-Preview` for screen capture resize logs.
4. Verify background transmission, lock-unlock audio, receiver discovery, low latency, and Defender.
