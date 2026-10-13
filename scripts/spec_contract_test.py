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
