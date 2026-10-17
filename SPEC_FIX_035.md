# v035: prevent repeat crashes on UVC connection

## Evidence and probable root cause

**Symptom (reported on actual Android device):** v034 repeatedly terminates when a USB/UVC capture adapter is connected. v033 did not have the v034 audio code.

**Confirmed source-level defect:** v034 added a second UAC capture lifecycle which calls `UACAudioHandler.createHandler(UsbControlBlock)` on a background thread after the UVC camera has already opened. The block belongs to the shared USBMonitor / native UVC camera lifecycle. Multiple asynchronous native libusb operations on the same block, combined with disconnect and asynchronous handler release, can cause interface claim conflicts and dangling native handles. In the v034 implementation a thread could return before releasing its newly created UAC handler, increasing this risk. This is a strong **suspected** crash cause; without `adb logcat` / native tombstone the exact faulting thread and native stack cannot be confirmed.

## v035 containment

- Restore `CameraRequest.AudioSource.NONE` for **AUSBC native UVC** (the stable v033 initialization). No native UAC handlers are started by the app.
- New `UsbAudioCapture` uses Android `AudioRecord` + `AudioManager.getDevices(GET_DEVICES_INPUTS)`. It requires exactly **one USB audio input**, `setPreferredDevice(usbDevice)`, and verification through `getRoutedDevice()` **before reading or transmitting any bytes**. If any step fails, gracefully continue **video only**; the phone microphone cannot be streamed by mistake.
- Defer USB audio permission/start until UVC has opened and stabilized; no audio request if Android cannot see a USB input.
- Plug/unplug, recording, source changes and explicit app exit stop the audio capture without sharing libusb USB camera handles with audio.
- The existing Windows PCM port 57009 / MJPEG port 57007 protocol, Android-screen audio, remote keyboard, F11, source priority and full-screen video behavior are unchanged.
- The screen-local speaker toggle applies only after the USB audio input route is confirmed.

## Intentional safety tradeoff

**UVC MP4 files are temporarily video-only in v035.** Audio may still be heard locally and on Windows *if the Android OS exposes the USB audio input*. v034 claimed USB sound in the local MP4 through native AUSBC UAC; re-enabling that path without reproducing and eliminating the native crash would be unsafe. Confirm v035 crash-free operation on actual hardware first, then independently develop an audio+video muxer that consumes Android `AudioRecord` PCM instead of re-opening the native USB interface.

## On-device acceptance tests

1. Install v035 in place of v034, attach UVC and allow USB camera access: app no longer repeatedly closes, picture shown normally.
2. Disconnect/reconnect UVC 10 times, try landscape/portrait and auto Windows reconnection. No freezes or terminal crashes.
3. If USB audio appears as an Android system input, grant RECORD_AUDIO and confirm live sound on Windows. With unsupported UAC, video should remain stable and the app should report video-only.
4. Record UVC video MP4 (no audio track in v035), stop and replay. No native UAC JNI should be invoked.
5. Confirm Android screen + internal audio playback and Windows remote input still work.
6. If crashes persist, collect `adb logcat -b crash -d` and `adb logcat -d -s AndroidRuntime:* MyMonitor-UVC:* MyMonitor-USB-Audio:*`. If the log shows `Fatal signal 11` / `libusb` / `libuvc`, investigate native faults beyond v034 audio path.

Unit tests and GitHub Actions verify code compilation and existing Windows protocol behaviors, not USB device/native hardware on a real phone.
