"""Static lifecycle contract guard complementing JVM and Windows runtime tests.

No static test can prove physical USB/MediaProjection operation; this prevents
accidental deletion of the key v016 event and source paths.
"""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
def file(path): return (root / path).read_text(encoding="utf-8")
activity=file("app/src/main/java/com/karinlab/mymonitor/MainActivity.kt")
fragment=file("app/src/main/java/com/karinlab/mymonitor/MonitorFragment.kt")
service=file("app/src/main/java/com/karinlab/mymonitor/ScreenStreamService.kt")
hub=file("app/src/main/java/com/karinlab/mymonitor/StreamHub.kt")
discovery=file("app/src/main/java/com/karinlab/mymonitor/ReceiverDiscovery.kt")
sender=file("app/src/main/java/com/karinlab/mymonitor/WindowsSender.kt")
receiver=file("windows/receiver.cpp")
expected={
    "physical USB disconnect callback": (activity, "UsbManager.ACTION_USB_DEVICE_DETACHED"),
    "USB monitor detach": (fragment, "override fun onDetach("),
    "USB removed entrypoint": (fragment, "fun usbDetached()"),
    "return from UVC": (activity, "fun onUvcGone("),
    "projection optional after USB removal": (activity, "ScreenStreamService.projectionActive"),
    "consent when projection invalid": (activity, "beginScreenConsent()"),
    "permission rejection suppresses loop": (activity, "screenConsentDeclined = true"),
    "disconnected USB mode reset": (fragment, "StreamHub.setUvcReady(false)"),
    "screen priority policy": (hub, "CapturePolicy.source("),
    "stale UVC frames cleared": (hub, "sender.discardPending()"),
    "saved Windows IP is TCP verified": (discovery, "remembered(preferredHost, preferredPort)"),
    "TCP receiver handshake": (discovery, '"MCP1"'),
    "verified receiver response": (receiver, '"MCR1"'),
    "wire compatibility": (receiver, '"MMC9"'),
    "UDP fallback search": (discovery, "255.255.255.255"),
    "private LAN fallback": (discovery, "sweepLocal24()"),
    "rate-limited LAN scan": (hub, "lastSubnetSweepMs"),
    "re-discovery after transport loss": (hub, "sender.hasTransportConnection()"),
    "audio follows screen source": (hub, "CapturePolicy.Source.SCREEN"),
    "notification start/resume": (service, "ACTION_RESUME_STREAM"),
    "notification stop": (service, "ACTION_STOP_STREAM"),
    "service EXIT finishes UVC Activity": (service, "ACTION_FINISH_UI"),
    "Activity listens for EXiT": (activity, "exitReceiverRegistered"),
    "screen recording independent": (service, 'Recording must continue after "送信停止"'),
    "active record state": (service, "screenRecording = true"),
    "local UVC recording": (fragment, "captureVideoStart("),
    "local screen recording": (service, "ScreenRecorder(this, audioRecord != null)"),
    "separate live receiver decoder": (receiver, "decodeWorker = std::thread(decodeLoop)"),
    "low latency immediate ACK": (receiver, "ACK receipt immediately"),
    "latest-frame-only JPEG": (receiver, "pendingJPEG = std::move(bytes)"),
    "GDI direct paint": (receiver, "BitBlt(dc, x, y, w, h"),
    "Android timing instrumentation": (sender, "MyMonitorLatency"),
}
for name, (content, needle) in expected.items():
    assert needle in content, f"Missing v016 contract: {name} / {needle}"
print(f"SPEC CONTRACT PASS: {len(expected)} source/lifecycle/recording/latency anchors")

# v017: preventing a foreground Activity STOP from shutting down the capture
# is not the same as a guaranteed background camera service (surface can die).
manifest=file("app/src/main/AndroidManifest.xml")
preview=file("app/src/main/java/com/karinlab/mymonitor/PreviewGeometry.kt")
assert "FOREGROUND_SERVICE_CONNECTED_DEVICE" in manifest
assert "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in manifest
assert "WAKE_LOCK" in manifest
assert 'foregroundServiceType="connectedDevice|mediaProjection"' in manifest
assert "promptBatteryOptimizationOnce()" in activity
assert "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in activity
assert "updateVideoViewport()" in activity
assert "override fun onConfigurationChanged(" in activity
assert "PreviewGeometry.fit(" in fragment
assert ".setAspectRatioShow(false)" in fragment
assert "fun adjustPreviewViewport(" in fragment
assert "onDestroyView()" in fragment
activityStop=fragment.split("override fun onStop()",1)[1].split("override fun onDestroyView()",1)[0]
assert "closeCamera()" not in activityStop, "UVC must not be forcibly closed on ordinary backgrounding"
assert "FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE" in service
assert "PowerManager.PARTIAL_WAKE_LOCK" in service
assert "refreshPowerLock()" in service
assert "onTaskRemoved(" in service
assert "onTrimMemory(" in service
assert 'versionCode 19' in file("app/build.gradle")
print("V017 POWER/LIFECYCLE CONTRACTS RETAINED (v018 preview supersedes ratio check)")

# v018: the UVC image-size decision belongs to the parent viewport exactly once.
# OPENGL cached the old EGL viewport after screen rotation; NORMAL attaches
# UVCCamera directly to TextureView's surface and follows new measured bounds.
preview_geometry = file("app/src/main/java/com/karinlab/mymonitor/PreviewGeometry.kt")
assert ".setRenderMode(CameraRequest.RenderMode.NORMAL)" in fragment
assert ".setAspectRatioShow(false)" in fragment
assert "preview.setAspectRatio(" not in fragment
assert "camera?.setRenderSize(" not in fragment
assert "PreviewGeometry.centered(" in fragment
assert "fun centered(" in preview_geometry
assert "screenshotLandscapeViewportShouldFillHeightAndCenter" in file("app/src/test/java/com/karinlab/mymonitor/PreviewGeometryTest.kt")
assert "versionCode 19" in file("app/build.gradle")
print("V018 UVC PREVIEW CONTRACT PASS: native direct SurfaceTexture, single fit-center authority")

# v019 audio lifecycle and Windows binary provenance.
assert 'Intent.ACTION_SCREEN_OFF' in service
assert 'Intent.ACTION_USER_PRESENT' in service
assert 'Intent.ACTION_SCREEN_ON' in service
assert 'restoreAudioAfterUnlock()' in service
assert 'stopAudioCapture()' in service
assert 'recoverAudioAfterFailure(' in service
assert 'StreamHub.resumeAudioAfterUnlock()' in service
assert 'audioSuspendedForLock' in service
assert 'fun suspendAudioForScreenLock()' in hub
assert 'fun resumeAudioAfterUnlock()' in hub
assert 'playbackAudioAllowed' in hub
assert "waveOutReset(device)" in file("windows/audio_receiver.h")
assert "VERSIONINFO" in file("windows/receiver.rc")
assert "msvc" in file(".github/workflows/build-apk.yml").lower()
assert 'versionCode 19' in file("app/build.gradle")
print("V019 LOCK/UNLOCK AUDIO + MSVC CONTRACT PASS")
