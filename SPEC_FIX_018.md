# MyMonitor 018: fix for undersized and displaced UVC display

## Evidence from user's screenshot

The user-provided landscape screenshot (1080 × 482 pixels) shows the HDMI color bars occupying approximately 480 × 272 pixels starting near x=204/y=145, leaving excess black space and placing the video off center. The bottom control strip starts at approximately y=428. The colored input maintains ~16:9 proportions but **is too small and incorrectly positioned**. The bug is in the phone's local UVC preview, not in the Windows stream.

For a 1080 × 428 video viewport (excluding the controls), the full-sized aspect-preserving 1920×1080 video rectangle should be 761 × 428, centered near x=159/y=0 within that viewport. The exact UVC crop and app status overlays may vary, but a tiny 480×270 centered-off-axis picture is incorrect.

## Source-verified root cause and implementation

1. v017 initialized AUSBC's OpenGL render mode **and** manually resized the enclosing FrameLayout **and** configured AUSBC's AspectRatioTextureView ratio. There were multiple independent sizing authorities.
2. In the pinned upstream AUSBC 3.6.0 source, `MultiCameraClient.ICamera.setRenderSize(width, height)` only sets `mSizeChangedFuture` (startup future). It does **not** call `RenderManager.setRenderSize` after the GL renderer has started. Rotating/resizing the Android preview then leaves the existing GLES viewport fixed to its original screen size and position, while Java UI changes the TextureView bounds. The reported undersized/cropped rendering matches this failure mechanism. This is code-backed reasoning; the specific Pixel GPU output is not available in CI.
3. v018 changes the UVC CameraRequest to **RenderMode.NORMAL** and `setAspectRatioShow(false)`. This uses native UVCCamera.setPreviewTexture directly on the actual TextureView SurfaceTexture instead of AUSBC's stale EGL-rendered intermediate image. The independent native MJPEG tap, NV21 still/record buffers and Windows sender are unaffected by that choice.
4. `PreviewGeometry.fit()` remains the *only* fit-center calculator, sized using the actual Fragment viewport excluding controls. Removed programmatic `preview.setAspectRatio` and misleading runtime `setRenderSize` calls that were ineffective after GL startup. Aspect ratio, centering and letterboxing remain deliberate.
5. On UVC resolution negotiation or device rotation, the parent fits the child to the actual viewport. The native UVC renderer fills the already aspect-correct TextureView rather than independently fitting again.
6. Added `PreviewGeometry.centered()` pure geometry and exact screenshot-sized regression case (1080x428, output 761x428, x=159), plus portrait then landscape return. Contract test fails if GLES preview/double aspect sizing regress.

## Scope & limitations

The change is restricted to the Android UVC preview and regression tests. The Windows receiver source is *functionally unchanged* except display/version labels, and retains the v015 low-latency early ACK + one-slot decoder. Battery-exemption and background-service logic from v017 are left alone.

This is a repair of what the screenshot exposes: **local preview geometry**. Pixel UVC USB runtime, rotation behavior and whether the new native preview is smoother must be verified on the physical phone. CI can compile, test geometry and Windows transport but has no Android UVC GPU device.

To verify: install `mymonitor018.apk` over 017, start `mycastreceiver018.exe` after stopping older receivers, attach the same UVC HDMI source, rotate landscape/portrait twice. The picture must expand to the available viewport and remain centered without stretching or jumping. Screenshots or logs tagged `MyMonitor-Preview` will show the calculated dimensions.

Rollback is available at https://github.com/Karin-Laboratory/MyMonitor/releases/tag/v0.17.
