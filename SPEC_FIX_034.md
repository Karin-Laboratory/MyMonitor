# MyMonitor v0.34: USB HDMI capture now includes UAC sound

## What changed
- The Android UVC camera uses `SOURCE_DEV_MIC`, AUSBC's USB Audio Class interface, **not** `SOURCE_AUTO` (which could capture the phone's microphone).
- Local UVC MP4 recordings use AUSBC's native UAC audio path when the capture adapter provides sound and Android's microphone permission is granted.
- Live UVC streaming extracts PCM from the same USB capture device using AUSBC's `UACAudioHandler`. It converts 8/16-bit mono/stereo, 8–96 kHz native PCM into MyCast's existing **48 kHz, 16-bit little-endian stereo**, then reuses the MMA1 TCP audio channel on port **57009**. The Windows receiver requires no protocol changes.
- Local Android playback of captured USB sound is on by default. Settings: **「USBキャプチャの音をスマホでも再生」** to mute/unmute the smartphone speaker. Windows audio is independent of this toggle.
- USB video still runs when the capture device does not offer UAC or the user declines Android's audio recording permission. No fallback to the phone's built-in microphone.
- USB unplug, stream source changes, screen lock, reconnect, and close release audio recording handles. Existing MJPEG passthrough/video and F11/fullscreen/input features remain unchanged.

## Limitation

The native AUSBC MP4 recorder and live UAC monitor both require exclusive access to the same USB audio interface. v034 releases the live monitor before local MP4 recording and resumes it after the MP4 finishes. Therefore **Windows may temporarily receive no USB sound during local MP4 recording**. Video streaming continues throughout. This avoids parallel libusb interface claims and potential crashes; a future unified PCM tap inside AUSBC could remove the interruption.

Real capture devices vary in their UAC implementation (some only expose video, some offer rates/bit-depths unsupported by this build). Runtime falls back to *silent USB video*, never the phone microphone. The app does not automatically convert copyrighted/protected audio sources.

## How to test

1. Install `mymonitor034.apk` as an in-place update, and run `mycastreceiver034.exe` on Windows.
2. Connect an HDMI UVC+UAC capture device to the Android phone and accept Android audio permission.
3. Confirm USB video is visible in MyMonitor and Windows; play audio from the HDMI source and check Android and Windows speaker sound.
4. Turn **「USBキャプチャの音をスマホでも再生」** OFF and verify only Android local monitoring mutes. Windows audio continues.
5. Record and stop an MP4 from USB input; play it to check the audio track. The Windows audio stream may pause until the recording ends.
6. Unplug/replug the capture device, then try Android screen sharing with internal sound. Neither source should capture the smartphone microphone.
7. Regress F11, screen streaming, low latency, windows touch/keyboard, audio connection/reconnect and recording.

CI covers converter unit tests, app build, Windows MMA1 socket tests, and all existing receiver regressions. **Physical USB/UAC input and speaker output require a real device**, and are not proven by Actions.
