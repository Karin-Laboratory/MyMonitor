# MyMonitor v036: UVC recording frame geometry and safely muxed USB sound

## Why UVC MP4 could have shifted/distorted colour/pixel placement

AUSBC 3.6.0's `H264EncodeProcessor.processInputData()` rotates NV21 frame bytes by 90° whenever the *phone* configuration is portrait, while its encoder continues using the original physical UVC width/height. The HDMI input is not a phone camera and should never be rotated merely because the phone is portrait. This can misplace recorded pixels/chroma even though the direct TextureView preview and MyCast MJPEG network display look fine.

**v036 pinned-upstream source patch:** keep `H264EncodeProcessor(..., isPortrait=false)` for HDMI/UVC, so raw 1920×1080 data is encoded in the same orientation and dimensions. No changes to the native MJPEG fast path, USB camera handle ownership, or preview fitting. Exact observed artifact still requires comparing a v035 vs v036 test clip captured on the phone; the screenshot currently supplied in chat appears to show a Windows receiver title bar rather than a shifted recording frame.

## Safe USB audio strategy

Do **not** restore v034's native `UACAudioHandler` sharing the USB camera `UsbControlBlock`. It caused a plausible native handle race and repeated Android process termination. The v035 fix remains in force:

1. Android `AudioManager` must expose exactly one USB INPUT; `AudioRecord.setPreferredDevice` is requested and `record.routedDevice` must confirm it is ACTUALLY routed there before any audio can be consumed.
2. That single `AudioRecord` continues to send 48 kHz PCM to Windows and optional local Android speakers. During recording, its already-verified PCM is also put into a bounded `UsbAudioTake` background file writer. Never open another native USB audio capture.
3. The native AUSBC H.264 MP4 video is recorded unchanged with `CameraRequest.AudioSource.NONE`. Only **after** the video file is finalized, a separate worker encodes saved USB PCM as AAC-LC (128 kbps) and creates a NEW MP4 with the ORIGINAL video track plus AAC track via `MediaExtractor/MediaMuxer`.
4. The original video-only MP4 is never deleted or overwritten. If sound is unsupported, blocked, interrupted, a queue overflows or audio/mux fails, the original video is still saved as-is. Only export with-audio MP4 after the new file can be reopened and both tracks are verified. Unplugging USB abandons the temporary audio take without touching the native video handle.

## Tradeoffs

- v036 audio is only added to local MP4 if the Android OS exposes that capture adapter as a USB audio input and successfully routes it to `AudioRecord`. Some UVC devices only expose libusb UAC, not an Android input; **these remain video-only** and should not be treated as a broken microphone permission.
- An additional merge pass after pressing stop takes processing time proportional to the recording size. The original MP4 is preserved locally as a backup, so both files consume space until manually cleaned.
- This build does **not** demonstrate sample-perfect A/V sync on all devices. USB AudioRecord route changes, device clock drift, long recordings and native MediaCodec compatibility need real-device testing. Never claim physical hardware validation from CI.
- Since the screenshot did not show the frame shift, the source-level UVC orientation bug is confirmed but the user's specific visual symptom has not been directly reproduced.

## Real-phone tests

1. Upgrade in-place and plug UVC in/out 10 times: no recurrent app crash, full-screen HDMI preview preserved.
2. Record 20 seconds in portrait, then 20 seconds in landscape using the same physical HDMI input. Compare decoded MP4 image geometry, color and any displaced red elements against the direct preview and Windows screen.
3. Play audio on the HDMI source during UVC recording; when stopping, wait for 「録画したUSB音声をMP4に追加しています…」. Check the saved file has H.264 plus AAC, and both are roughly in sync.
4. Re-test with USB audio unavailable/permission declined. UVC must remain stable, video-only recording must be saved, no smartphone mic audio appears.
5. During recording, check Windows MyCast video+sound continues and there are no pops/crashes on USB removal.
6. If colors/frames still shift, collect a screenshot of the recorded output and matching live preview; if crash recurs, gather `adb logcat -b crash -d` and native tombstone.
