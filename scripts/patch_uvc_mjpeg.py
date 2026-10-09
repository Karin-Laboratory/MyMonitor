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
print("PASS: 11 audited anchors patched; native MJPEG callback preserves NV21 capture")
