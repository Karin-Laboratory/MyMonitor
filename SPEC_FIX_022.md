# MyMonitor v022: native UVC reconnect diagnostics

Pixel 10a v021 logs showed repeated `open camera failed:result=-99` after USB re-plug. The USB device remains enumerated even when the USBMonitor control handle no longer opens the native camera.

Changes:
- On camera ERROR, release CameraUVC and reset the USBMonitor instance to discard stale UsbControlBlock handles.
- Re-register USBMonitor through a normal helper, without invoking Fragment lifecycle callbacks manually.
- Apply increasing 1.5s, 3.5s and 9s delays on native errors; avoid rapid failures and repeated open/close churn.
- Clear retry backoff after successful OPENED or actual physical unplug.
- Add diagnostic status lines with native retry counts.
- Preserve v015 low-latency Windows transport, v018 native TextureView, v019 unlock audio, v020 rotation screen capture and v021 physical presence check.

CI cannot verify hotplug behavior on a physical Pixel 10a. Test initial UVC connection, several unplug/replug cycles, rotate, audio and latency. If native -99 persists even after monitor reset, capture full system UsbHostManager and USBMonitor logs and examine native library errno/root cause.
