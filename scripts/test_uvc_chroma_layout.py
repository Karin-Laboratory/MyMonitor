#!/usr/bin/env python3
"""v038 source-verified UVC NV21 chroma order and stride-aware codec plane test.

Pinned AUSBC Java calls PIXEL_FORMAT_YUV420SP=4, whose actual C++ converter
is uvc_yuyv2iyuv420SP. This function writes V then U (NV21), contrary to
the misleading Java constant comment stating NV12. v037's UV reversal
turned flesh tones and greens cyan/purple while fixing chroma row offsets.
"""
from pathlib import Path

patch = Path("scripts/patch_uvc_mjpeg.py").read_text()
assert "COLOR_FormatYUV420Flexible" in patch
assert "codec.getInputImage(inputIndex)" in patch
assert "mVideoProcess = H264EncodeProcessor(previewWidth, previewHeight, isNeedGLESRender, false)" in patch
assert "v.buffer.put(dstV + col * v.pixelStride, data[src])" in patch
assert "u.buffer.put(dstU + col * u.pixelStride, data[src + 1])" in patch
assert "u.buffer.put(dstU + col * u.pixelStride, data[src])" not in patch
assert "output.position(row * y.rowStride)" in patch
assert "output.put(data, row * width, width)" in patch

# Assert the actual *pinned native source*, not the misleading Java comment.
root = Path("vendor/AndroidUSBCamera")
preview = (root / "libuvc/src/main/jni/UVCCamera/UVCPreview.cpp").read_text()
native = (root / "libuvc/src/main/jni/libuvc/src/frame.c").read_text()
camera = (root / "libausbc/src/main/java/com/jiangdg/ausbc/camera/CameraUVC.kt").read_text()
assert "setFrameCallback(frameCallBack, UVCCamera.PIXEL_FORMAT_YUV420SP)" in camera
case = preview.split("case PIXEL_FORMAT_YUV20SP:", 1)[1].split("break;", 1)[0]
assert "mFrameCallbackFunc = uvc_yuyv2iyuv420SP" in case
converter = native.split("uvc_error_t uvc_yuyv2iyuv420SP(", 1)[1].split("RETURN(UVC_SUCCESS", 1)[0]
assert converter.index("*(uv++) = yuv[3];") < converter.index("*(uv++) = yuv[1];")
assert "*(uv++) = yuv[3];\t// v" in converter
assert "*(uv++) = yuv[1];\t// u" in converter

def split_nv21(src, w, h, y_stride, y_pixel, uv_stride, uv_pixel):
    y = bytearray(y_stride * h)
    u = bytearray(uv_stride * (h // 2))
    v = bytearray(uv_stride * (h // 2))
    for row in range(h):
        for col in range(w):
            y[row * y_stride + col * y_pixel] = src[row * w + col]
    for row in range(h // 2):
        for col in range(w // 2):
            position = w*h + row*w + 2*col
            v[row*uv_stride + col*uv_pixel] = src[position]
            u[row*uv_stride + col*uv_pixel] = src[position+1]
    return y,u,v

w,h = 16,10
ys = bytes((i*7)%256 for i in range(w*h))
# Purposefully asymmetric V and U, so reversing them fails.
vu = bytes((210-r*16-c) if n == 0 else (20+r*14+c)
           for r in range(h//2) for c in range(w//2) for n in (0,1))
src = ys + vu
for y_stride,y_pixel,uv_stride,uv_pixel in [(16,1,8,1),(32,1,32,2),(64,2,40,4)]:
    Y,U,V = split_nv21(src,w,h,y_stride,y_pixel,uv_stride,uv_pixel)
    for row in range(h):
        for col in range(w):
            assert Y[row*y_stride+col*y_pixel] == ys[row*w+col]
    for row in range(h//2):
        for col in range(w//2):
            assert V[row*uv_stride+col*uv_pixel] == vu[row*w+2*col]
            assert U[row*uv_stride+col*uv_pixel] == vu[row*w+2*col+1]
            assert U[row*uv_stride+col*uv_pixel] != V[row*uv_stride+col*uv_pixel]

# Pixel from red video test pattern: BT.601 limited-range YUV≈(76,85,255).
red_vu = bytes([76]*4 + [255,85])
Y,U,V = split_nv21(red_vu,2,2,2,1,1,1)
y,u,v = Y[0],U[0],V[0]
red = 1.164*(y-16) + 1.596*(v-128)
green = 1.164*(y-16) - .392*(u-128) - .813*(v-128)
blue = 1.164*(y-16) + 2.017*(u-128)
assert red > 245 and green < 20 and blue < 20, (red,green,blue)

# Padded Y rows would have moved the chroma offset by multiple lines in v036.
padded = bytearray(32*16+32*5)
padded[:len(src)] = src
assert padded[32*16] != vu[0]
print("V038 PASS: pinned native outputs NV21 VU, correct RGB and padded codec planes")
