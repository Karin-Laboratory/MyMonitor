# MyMonitor 018

**Issue:** user screenshot shows landscape UVC image much smaller and off center than the available viewport.

**Fix:** disable double aspect ratio fitting and use direct native UVC SurfaceTexture preview (no stale OpenGL viewport). The single Android-side `PreviewGeometry` utility fits and centers the video inside the viewport above the toolbar. Test case reproduces screenshot dimensions. Camera USB access, NV21 stills/MP4, independent MJPEG passthrough, low-latency Windows streaming, internal audio, verified receiver search, background/battery controls are preserved.

**Files:** `mymonitor018.apk`, `mycastreceiver018.exe` (receiver only renamed; no video codec/latency changes). Please close any earlier EXE before running 018. Install APK on top of 017 to retain settings. Try several portrait/landscape rotations with an actual 16:9 HDMI source. Source and automated tests cannot prove physical Pixel preview behavior.

**Test:** `gradle :app:testDebugUnitTest :app:assembleDebug`, including exact 1080x428 screenshot fit-center regression, then existing full Windows stream/PCM/identity/reconnect/soak tests. GitHub Release publishes after all checks pass.
