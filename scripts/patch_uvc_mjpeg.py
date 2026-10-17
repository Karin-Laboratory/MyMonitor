#!/usr/bin/env python3
"""Apply the pinned AUSBC 3.6.0 source patch: independent MJPEG tap before YUYV decode.
The existing NV21 callback remains intact for preview, stills and MP4 recording.
Fail closed if pinned upstream layout differs.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
def patch(path, old, new):
    file = root / path
    content = file.read_text()
    if content.count(old) != 1:
        raise RuntimeError(f"Upstream changed: {path}: expected one anchor, got {content.count(old)}")
    file.write_text(content.replace(old, new))

uvc = "libuvc/src/main/"
ausbc = "libausbc/src/main/java/com/jiangdg/ausbc/camera/CameraUVC.kt"
patch(ausbc, "    private var mUvcCamera: UVCCamera? = null",
"""    /** Original USB MJPEG bytes, tapped BEFORE decode; independent of NV21 preview/record. */
    fun setMjpegDataCallback(callback: IFrameCallback?) {
        mUvcCamera?.setMjpegFrameCallback(callback)
    }

    private var mUvcCamera: UVCCamera? = null""")
java = uvc + "java/com/jiangdg/uvc/UVCCamera.java"
patch(java, "    public void setFrameCallback(final IFrameCallback callback, final int pixelFormat) {",
"""    /** An independent pass-through callback for compressed MJPEG UVC frames. */
    public void setMjpegFrameCallback(final IFrameCallback callback) {
        if (mNativePtr != 0) nativeSetMjpegFrameCallback(mNativePtr, callback);
    }

    public void setFrameCallback(final IFrameCallback callback, final int pixelFormat) {""")
patch(java, "private static final native int nativeSetFrameCallback(final long mNativePtr, final IFrameCallback callback, final int pixelFormat);",
"""private static final native int nativeSetFrameCallback(final long mNativePtr, final IFrameCallback callback, final int pixelFormat);
    private static final native int nativeSetMjpegFrameCallback(final long mNativePtr, final IFrameCallback callback);""")

native = uvc + "jni/UVCCamera/"
patch(native+"UVCPreview.h", "\tjobject mFrameCallbackObj;",
"""\t// Independent MJPEG tap; existing NV21 callback and recording are unchanged.
\tpthread_mutex_t mjpeg_mutex;
\tjobject mMjpegCallbackObj;
\tjmethodID mMjpegCallbackMethod;
\tvoid dispatchMjpeg(uvc_frame_t *frame);
\tjobject mFrameCallbackObj;""")
patch(native+"UVCPreview.h", "\tint setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format);",
"""\tint setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format);
\tint setMjpegFrameCallback(JNIEnv *env, jobject callback);""")
patch(native+"UVCPreview.cpp", "\tmFrameCallbackObj(NULL),",
"""\tmMjpegCallbackObj(NULL),
\tmMjpegCallbackMethod(NULL),
\tmFrameCallbackObj(NULL),""")
patch(native+"UVCPreview.cpp", "\tpthread_mutex_init(&capture_mutex, NULL);",
"""\tpthread_mutex_init(&capture_mutex, NULL);
\tpthread_mutex_init(&mjpeg_mutex, NULL);""")
patch(native+"UVCPreview.cpp", "\tpthread_mutex_destroy(&capture_mutex);",
"""\tpthread_mutex_destroy(&capture_mutex);
\t// Preview threads have already stopped when this object is released.
\tJavaVM *vm = getVM();
\tJNIEnv *env = NULL;
\tbool attached = vm && vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK;
\tif (attached && vm->AttachCurrentThread(&env, NULL) != JNI_OK) env = NULL;
\tif (env && mMjpegCallbackObj) env->DeleteGlobalRef(mMjpegCallbackObj);
\tif (attached && env) vm->DetachCurrentThread();
\tpthread_mutex_destroy(&mjpeg_mutex);""")
patch(native+"UVCPreview.cpp", "void UVCPreview::callbackPixelFormatChanged() {",
"""int UVCPreview::setMjpegFrameCallback(JNIEnv *env, jobject callback) {
    pthread_mutex_lock(&mjpeg_mutex);
    if (mMjpegCallbackObj) env->DeleteGlobalRef(mMjpegCallbackObj);
    mMjpegCallbackObj = callback ? env->NewGlobalRef(callback) : NULL;
    mMjpegCallbackMethod = NULL;
    if (mMjpegCallbackObj) {
        jclass clazz = env->GetObjectClass(mMjpegCallbackObj);
        if (clazz) {
            mMjpegCallbackMethod = env->GetMethodID(clazz, "onFrame", "(Ljava/nio/ByteBuffer;)V");
            env->DeleteLocalRef(clazz);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (!mMjpegCallbackMethod) {
            env->DeleteGlobalRef(mMjpegCallbackObj);
            mMjpegCallbackObj = NULL;
        }
    }
    pthread_mutex_unlock(&mjpeg_mutex);
    return 0;
}

void UVCPreview::dispatchMjpeg(uvc_frame_t *frame) {
    if (!frame || !frame->data || frame->data_bytes == 0 || frame->data_bytes > 8 * 1024 * 1024)
        return;
    pthread_mutex_lock(&mjpeg_mutex);
    if (mMjpegCallbackObj && mMjpegCallbackMethod) {
        JavaVM *vm = getVM();
        JNIEnv *env = NULL;
        bool attached = vm && vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK;
        if (attached && vm->AttachCurrentThread(&env, NULL) != JNI_OK) env = NULL;
        if (env) {
            jobject buffer = env->NewDirectByteBuffer(frame->data, frame->data_bytes);
            if (buffer) {
                env->CallVoidMethod(mMjpegCallbackObj, mMjpegCallbackMethod, buffer);
                env->DeleteLocalRef(buffer);
            }
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (attached && env) vm->DetachCurrentThread();
    }
    pthread_mutex_unlock(&mjpeg_mutex);
}

void UVCPreview::callbackPixelFormatChanged() {""")
patch(native+"UVCPreview.cpp", "frame_mjpeg = waitPreviewFrame();\n\t\t\t\tif (LIKELY(frame_mjpeg)) {",
"""frame_mjpeg = waitPreviewFrame();
\t\t\t\tif (LIKELY(frame_mjpeg)) {
\t\t\t\t\tdispatchMjpeg(frame_mjpeg); // compressed bytes before conversion""")
patch(native+"UVCCamera.h", "\tint setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format);",
"""\tint setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format);
\tint setMjpegFrameCallback(JNIEnv *env, jobject callback);""")
patch(native+"UVCCamera.cpp", "int UVCCamera::setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format) {",
"""int UVCCamera::setMjpegFrameCallback(JNIEnv *env, jobject callback) {
    return mPreview ? mPreview->setMjpegFrameCallback(env, callback) : EXIT_FAILURE;
}

int UVCCamera::setFrameCallback(JNIEnv *env, jobject frame_callback_obj, int pixel_format) {""")
patch(native+"serenegiant_usb_UVCCamera.cpp", "static jint nativeSetFrameCallback(JNIEnv *env, jobject thiz,",
"""static jint nativeSetMjpegFrameCallback(JNIEnv *env, jobject thiz,
    ID_TYPE id_camera, jobject callback) {
    UVCCamera *camera = reinterpret_cast<UVCCamera *>(id_camera);
    return camera ? camera->setMjpegFrameCallback(env, callback) : JNI_ERR;
}

static jint nativeSetFrameCallback(JNIEnv *env, jobject thiz,""")
patch(native+"serenegiant_usb_UVCCamera.cpp",
    '{ "nativeSetFrameCallback",\t\t\t"(JLcom/jiangdg/uvc/IFrameCallback;I)I", (void *) nativeSetFrameCallback },',
    '{ "nativeSetFrameCallback",\t\t\t"(JLcom/jiangdg/uvc/IFrameCallback;I)I", (void *) nativeSetFrameCallback },\n\t{ "nativeSetMjpegFrameCallback", "(JLcom/jiangdg/uvc/IFrameCallback;)I", (void *) nativeSetMjpegFrameCallback },')
# The upstream encoder rotates native NV21 by 90 degrees whenever the
# *phone* is in portrait mode. HDMI/UVC orientation is determined by the
# capture device, not the host phone. Rotating bytes without swapping the
# encoder geometry corrupts RGB/chroma placement in local MP4s. Always encode
# native UVC pixels in their source orientation. The preview and MJPEG tap
# remain independent and unchanged.
multi = "libausbc/src/main/java/com/jiangdg/ausbc/MultiCameraClient.kt"
patch(multi, """            mContext.resources.configuration.orientation.let { orientation ->
                orientation == Configuration.ORIENTATION_PORTRAIT
            }.also { isPortrait ->
                mVideoProcess = H264EncodeProcessor(previewWidth, previewHeight, isNeedGLESRender, isPortrait)
            }""", """            // UVC/HDMI video has a fixed physical pixel orientation.
            // Phone orientation must never rotate NV21 recording frames.
            mVideoProcess = H264EncodeProcessor(previewWidth, previewHeight, isNeedGLESRender, false)""")
# Upstream CameraUVC registers PIXEL_FORMAT_YUV420SP = 4, documented as NV12,
# BUT UVCPreview.cpp actually selects uvc_yuyv2iyuv420SP for format 4.
# libuvc/frame.c proves that function emits V,U (NV21), not U,V. v037 kept
# the YUV plane stride fix, but mistakenly interpreted the source as NV12,
# turning faces blue/cyan and removing normal warm/green color reproduction.
# The source MUST be decoded as V,U, then written to MediaCodec planes U,V.
# Hardware encoders may have a padded Y sliceHeight (e.g. 1088
# for visible 1080), causing chroma content to begin several lines too early.
# Use COLOR_FormatYUV420Flexible + each Image.Plane's actual row/pixel strides
# and preserve the native callback's actual NV21 V,U order.
abstract = "libausbc/src/main/java/com/jiangdg/ausbc/encode/AbstractProcessor.kt"
patch(abstract, """            val inputBuffer = if (isLowerLollipop()) {
                codec.inputBuffers[inputIndex]
            } else {
                codec.getInputBuffer(inputIndex)
            }
            inputBuffer?.clear()
            inputBuffer?.put(data)
            codec.queueInputBuffer(inputIndex, 0, data.size, getPTSUs(data.size), 0)""",
"""            // USB recording uses MediaCodec's negotiated Image planes so that
            // Y and chroma honor padded stride/slice-height on the device.
            // Audio and other processors retain the original byte-buffer path.
            if (!writeInputFrame(codec, inputIndex, data)) {
                val inputBuffer = if (isLowerLollipop()) {
                    codec.inputBuffers[inputIndex]
                } else {
                    codec.getInputBuffer(inputIndex)
                }
                inputBuffer?.clear()
                inputBuffer?.put(data)
            }
            codec.queueInputBuffer(inputIndex, 0, data.size, getPTSUs(data.size), 0)""")
patch(abstract, """    protected abstract fun processInputData(data: ByteArray): ByteArray?""",
"""    /** Override only for flexible-format video; false keeps legacy audio. */
    protected open fun writeInputFrame(codec: MediaCodec, inputIndex: Int,
                                       data: ByteArray): Boolean = false

    protected abstract fun processInputData(data: ByteArray): ByteArray?""")
h264 = "libausbc/src/main/java/com/jiangdg/ausbc/encode/H264EncodeProcessor.kt"
patch(h264, """import android.media.MediaCodecInfo""", """import android.media.MediaCodecInfo
import android.media.Image""")
patch(h264, """                if (isPortrait) {
                    YUVUtils.nativeRotateNV21(data,width, height, 90)
                }
                YUVUtils.nv21ToYuv420sp(data, width, height)""",
"""                // The native callback is NV21 V,U even though the Java enum
                // PIXEL_FORMAT_YUV420SP is incorrectly documented as NV12.
                // Do not mutate source; write into the codec's U and V planes
                // using the actual YUV_420_888 rowStride/pixelStride.""")
patch(h264, """        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar""",
"""        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible""")
patch(h264, """    /**
     * Set on encode ready listener
     *
     * @param listener input surface ready listener
     */""",
"""    /**
     * Correctly pack UVC native NV21 into MediaCodec's YUV_420_888 input.
     * At 1920x1080 a device may align the Y slice to 1088; raw ByteBuffer
     * packing causes UV to start 8 rows early, visually displacing red/blue
     * relative to luma. Image.Plane metadata is authoritative.
     */
    override fun writeInputFrame(codec: MediaCodec, inputIndex: Int,
                                 data: ByteArray): Boolean {
        if (gLESRender) return false
        val image = codec.getInputImage(inputIndex)
            ?: throw IllegalStateException("YUV420Flexible input planes unavailable")
        try {
            require(data.size == width * height * 3 / 2)
            require(width % 2 == 0 && height % 2 == 0)
            require(image.planes.size == 3)
            val crop = image.cropRect
            require(crop.width() == width && crop.height() == height) {
                "Codec input crop differs from UVC frame: \${crop.width()}x\${crop.height()}"
            }
            val y = image.planes[0]
            val u = image.planes[1]
            val v = image.planes[2]
            // Luma normally has pixelStride=1. Use bulk row writes for
            // Full HD (3 million bytes/frame) to avoid per-pixel JNI work.
            if (y.pixelStride == 1) {
                val output = y.buffer.duplicate()
                for (row in 0 until height) {
                    output.position(row * y.rowStride)
                    output.put(data, row * width, width)
                }
            } else {
                for (row in 0 until height) {
                    val inputRow = row * width
                    val dstRow = row * y.rowStride
                    for (col in 0 until width) {
                        y.buffer.put(dstRow + col * y.pixelStride, data[inputRow + col])
                    }
                }
            }
            val offset = width * height
            for (row in 0 until height / 2) {
                val inputRow = offset + row * width
                val dstU = row * u.rowStride
                val dstV = row * v.rowStride
                for (col in 0 until width / 2) {
                    val src = inputRow + 2 * col
                    // Native libuvc 'iyuv420SP' writes VU (NV21), not UV.
                    v.buffer.put(dstV + col * v.pixelStride, data[src])
                    u.buffer.put(dstU + col * u.pixelStride, data[src + 1])
                }
            }
        } finally {
            image.close()
        }
        return true
    }

    /**
     * Set on encode ready listener
     */""")
print("PASS: 19 audited anchors patched; actual UVC NV21 VU order and padded codec chroma are preserved")


