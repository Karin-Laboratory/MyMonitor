# MyMonitor v021 UVC unplug/replug recovery

Pixel 10a v020 field log (2026-10-14 09:35) showed a successful OPENED at 1920x1080 followed immediately by USBMonitor disconnect callbacks while the device remained enumerated, followed by repeated native open failures -99.

Changes:
- USBMonitor disconnect/detach callbacks consult UsbManager.deviceList and do not close a live UVC preview when the same physical USB device remains enumerated.
- Actual removal closes the stream and resets pending USB permission state.
- A close-in-progress guard prevents opening a second native camera while release is underway, with delayed re-enumeration after error.
- A retry-generation counter avoids outdated delayed rechecks following detach.
- Preserve v015 Windows low latency, v018 preview geometry, v019 audio unlock, v020 landscape capture.

CI static checks and Windows/Android tests do not verify hardware USB hotplug. Verify Pixel 10a initial plug, unplug, repeated replug, portrait/landscape, audio, Defender and stream latency.