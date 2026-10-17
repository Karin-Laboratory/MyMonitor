# MyMonitor v038: restore natural colors in UVC MP4

## Observed in real VLC playback

v037 corrected the several-line shift of red chroma in local UVC recordings, and USB audio was verified by the user, but the entire recording appeared blue/cyan and red/magenta accents looked unnatural (skin tones and green color reproduction badly affected).

## Actual cause (verified in pinned native C++)

**Do not infer the real bytes from the Java constant name!**

In the pinned AUSBC 3.6.0 sources:
1. `CameraUVC.kt` calls `setFrameCallback(frameCallBack, UVCCamera.PIXEL_FORMAT_YUV420SP)`.
2. The Java constant `PIXEL_FORMAT_YUV420SP=4` is incorrectly commented as NV12.
3. `UVCPreview.cpp` instead selects `uvc_yuyv2iyuv420SP` for that value.
4. In `libuvc/src/frame.c`, that function writes `yuv[3]` (**V**) then `yuv[1]` (**U**) into interleaved chroma, i.e., **NV21 = Y + VU**, not NV12 = Y + UV.
5. v037's new stride-correct H264 writer assigned the first byte to codec plane U and the second to plane V, so chroma was inverted. The user's screenshot establishes the real impact.

## The v038 change

Keep everything that v037 fixed: `COLOR_FormatYUV420Flexible`, MediaCodec `getInputImage`, plane `rowStride`, `pixelStride`, and HDMI orientation invariant.

Change only two lines in the video writer's chroma loop:
```kotlin
v.buffer.put(dstV + col * v.pixelStride, data[src])      // native V
u.buffer.put(dstU + col * u.pixelStride, data[src + 1])  // native U
```
There is no native USB audio handle reopening, no UVC preview or MJPEG protocol change, and no change to safe Android USB AudioRecord + offline MP4 AAC remux.

## Prevent this recurring

`scripts/test_uvc_chroma_layout.py` now runs against the *actual pinned upstream C++ sources* in the checkout and proves that format 4 selects the VU converter and that it writes V before U. Synthetic buffers exercise planar and interleaved pixel strides, padded luma rows and known saturated-red YUV→RGB reconstruction. If the upstream converter changes, CI fails instead of silently swapping colors again.

## Real device acceptance

Install v038, record the same colorful TV broadcast for 10–20 seconds, then compare a VLC screenshot with the preview. Look for (a) natural human skin tones and green grass, (b) red/cyan outlines aligned with the text, (c) audio present, and (d) no UVC connect/reconnect crashes. CI cannot play MP4 on the user's particular Android hardware encoder; the final confirmation is physical.

If red/green/cyan are still wrong, inspect actual hardware MP4/color metadata and the camera output instead of guessing another UV swizzle.
