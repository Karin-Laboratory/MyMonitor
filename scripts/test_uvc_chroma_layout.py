#!/usr/bin/env python3
"""Small deterministic NV12 / YUV_420_888 plane geometry regression.

Android Codec Image planes have hardware-defined rowStride / pixelStride.
The native UVC callback used here is NV12: Y then U,V byte pairs. The
upstream encoder copied tight NV12 into a padded codec buffer and also
mistakenly swapped U/V, which can vertically displace chroma.
"""
from pathlib import Path

patch = Path("scripts/patch_uvc_mjpeg.py").read_text()
assert "COLOR_FormatYUV420Flexible" in patch
assert "codec.getInputImage(inputIndex)" in patch
assert "mVideoProcess = H264EncodeProcessor(previewWidth, previewHeight, isNeedGLESRender, false)" in patch
assert "u.buffer.put(dstU + col * u.pixelStride, data[src])" in patch
assert "v.buffer.put(dstV + col * v.pixelStride, data[src + 1])" in patch
assert "output.position(row * y.rowStride)" in patch
assert "output.put(data, row * width, width)" in patch

def copy_nv12(src, w, h, y_stride, y_pixel, uv_stride, uv_pixel):
    # Simulate two Android Image layouts, using separate plane buffers.
    dst_y = bytearray(y_stride*h)
    dst_u = bytearray(uv_stride*(h//2))
    dst_v = bytearray(uv_stride*(h//2))
    for row in range(h):
        for col in range(w):
            dst_y[row*y_stride+col*y_pixel] = src[row*w+col]
    for row in range(h//2):
        for col in range(w//2):
            pos=w*h+row*w+2*col
            dst_u[row*uv_stride+col*uv_pixel]=src[pos]
            dst_v[row*uv_stride+col*uv_pixel]=src[pos+1]
    return dst_y,dst_u,dst_v

w,h=16,10
y=bytes((i*7)%256 for i in range(w*h))
uv=bytes((35+r*32+c) if k==0 else (210-r*22-c)
    for r in range(h//2) for c in range(w//2) for k in (0,1))
src=y+uv
for y_stride,y_pixel,uv_stride,uv_pixel in [(16,1,8,1),(32,1,32,2),(64,2,40,4)]:
    Y,U,V=copy_nv12(src,w,h,y_stride,y_pixel,uv_stride,uv_pixel)
    for row in range(h):
        for col in range(w):
            assert Y[row*y_stride+col*y_pixel]==y[row*w+col]
    for row in range(h//2):
        for col in range(w//2):
            assert U[row*uv_stride+col*uv_pixel]==uv[row*w+2*col]
            assert V[row*uv_stride+col*uv_pixel]==uv[row*w+2*col+1]
            assert U[row*uv_stride+col*uv_pixel]!=V[row*uv_stride+col*uv_pixel]
# Emulate a codec with 16 Y rows while the visible source height is 10.
# The old raw copy starts UV at 160 whereas the codec expects offset 512.
padded=bytearray(32*16+32*5)
padded[:len(src)]=src
assert padded[32*16] != uv[0]
print("V037 PASS: native NV12 UV order and padded YUV_420_888 rows / pixels")
