# MyMonitor v037: fix vertically displaced red/chroma in UVC recordings

## Observed defect

A real UVC MP4 played in VLC showed colored outlines (especially the red part of TV captions) **several pixels above the corresponding white/luma strokes**. The direct UVC preview and Windows MJPEG transmission were not implicated. v036 had already disabled the upstream phone-portrait-dependent NV21 rotation, but the recorded MP4 still exhibited a color-plane displacement.

## Confirmed upstream source format and suspected vertical offset cause

The pinned AUSBC v3.6.0 registers `UVCCamera.PIXEL_FORMAT_YUV420SP` (value 4, **NV12**, with interleaved **U,V** bytes), yet `H264EncodeProcessor.processInputData()` treated the callback bytes as NV21 and called `YUVUtils.nv21ToYuv420sp`, swapping chroma U/V unnecessarily. In addition, `AbstractProcessor.queueFrameIfNeed()` used to write the tightly packed `width × height × 3/2` bytes directly to the opaque hardware encoder input buffer. On codecs with a padded Y plane or slice height, chroma begins at a *later physical byte offset* than `width×height`. For example, a slice height of 1088 instead of 1080 at 1920 pixels/row makes the offset differ by 15,360 bytes, often seen as vertically displaced colored edges.

The upstream source mismatch is verified directly. The actual hardware encoder's configured Y stride/slice height has not been captured, so the specific offset amount remains a strong but unconfirmed hypothesis until a hardware test.

## v037 fix

- Keep HDMI/UVC source frames unrotated, independent of smartphone orientation (v036).
- Keep the UVC video preview, native MJPEG passthrough and Windows MyCast transfer unchanged.
- Configure the H.264 encoder with `COLOR_FormatYUV420Flexible` instead of assuming contiguous `COLOR_FormatYUV420SemiPlanar`.
- For each encoder input, use `MediaCodec.getInputImage(index)` and write Y/U/V using **each actual `Image.Plane.rowStride` and `pixelStride`**. Source is NV12, so U and V keep their existing order; no erroneous NV21↔NV12 swizzle.
- Optimize the Full HD Y plane using bulk row writes; only smaller chroma planes require per-pixel indexing.
- Keep v035's native UAC crash containment and v036's optionally post-muxed USB audio. No new USB handle or audio interface claim.
- When a hardware codec does not provide YUV420Flexible input images, record an explicit encoder error rather than silently creating color-corrupted MP4s.

## CI versus hardware verification

CI checks the exact source code contracts, applies the pinned AUSBC patches, compiles all Java/Kotlin and native UVC components and runs synthetic tests across planar/interleaved and padded image layouts. The existing Windows MJPEG/audio/remote-control tests also run.

**CI cannot validate MediaCodec plane support or actual UVC color alignment on the user's Android model.** Test a 10- to 20-second video with highly saturated red and blue text against white captions, in both portrait and landscape, and compare a frame from the MP4 against the live preview. Verify that recording audio still works when the device exposes USB audio through Android, and that unplug/replug still does not cause process termination. If the device does not expose YUV420Flexible, take `adb logcat -d -s H264EncodeProcessor:* AndroidRuntime:* MyMonitor-UVC:*` for codec selection work.

This correction primarily targets the **color components in the recorded file**, not UI red button positioning.
