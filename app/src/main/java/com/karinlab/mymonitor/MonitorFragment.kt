package com.karinlab.mymonitor

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.IFrameCallback
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB enumeration and UVC opening use the same path.
 * CameraFragment / MultiCameraClient silently filter devices of class 0 with
 * a UVC video interface (common for inexpensive HDMI grabbers).
 */
class MonitorFragment : Fragment(), ICameraStateCallBack {
    private val windowsSender get() = StreamHub.sender
    private var usbMonitor: USBMonitor? = null
    private var camera: CameraUVC? = null
    private var currentDeviceId: Int? = null
    private var waitingForPermission = false
    private var opening = false
    private var closing = false
    private var nativeFailures = 0
    private var lastFailedDeviceId: Int? = null
    private var retryBlockedUntil = 0L
    private var retryGeneration = 0
    private var monitorEpoch = 0
    private var lastEnumeratedDeviceId: Int? = null
    private var resettingMonitor = false
    private var permissionDeadline = 0L
    private val permissionTimeoutMs = 4000L
    private fun trace(event: String, device: UsbDevice? = null) {
        Log.i("MyMonitor-UVC", "RC23 $event epoch=$monitorEpoch device=${device?.deviceId ?: currentDeviceId} " +
            "camera=${if (ready) "READY" else if (opening) "OPENING" else if (closing) "CLOSING" else "IDLE"} " +
            "registered=${usbMonitor?.isRegistered} permission=$waitingForPermission failures=$nativeFailures")
    }
    private val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun physicalPresent(device: UsbDevice): Boolean =
        (context?.getSystemService(Context.USB_SERVICE) as? UsbManager)?.deviceList?.values?.any {
            it.deviceId == device.deviceId && it.vendorId == device.vendorId && it.productId == device.productId
        } == true
    private fun scheduleRecheck(delay: Long = 650L) {
        val generation = ++retryGeneration
        retryHandler.postDelayed({ if (generation == retryGeneration && isAdded && viewReady && !closing) refreshUsb() }, delay)
    }
    private var ready = false
    private var recording = false
    private var stopping = false
    private var viewReady = false
    private var inputWidth = 1920
    private var inputHeight = 1080
    private var lastFit = PreviewGeometry.Size(0, 0)
    private lateinit var preview: AspectRatioTextureView
    private lateinit var placeholder: TextView

    private fun host() = activity as? MainActivity
    private fun status(message: String) {
        Log.i("MyMonitor-UVC", message)
        host()?.showStatus(message)
    }
    private fun isUvc(device: UsbDevice): Boolean =
        device.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val root = FrameLayout(requireContext()).apply { setBackgroundColor(Color.BLACK) }
        placeholder = TextView(requireContext()).apply {
            text = "USB UVC / HDMI capture"
            textSize = 18f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(placeholder, FrameLayout.LayoutParams(-1, -1))
        preview = AspectRatioTextureView(requireContext())
        // Only OUR FrameLayout sizes the preview. The library's own
        // AspectRatioTextureView aspect ratio must remain disabled to prevent
        // a second, conflicting re-measure during landscape rotation.
        // Direct UVC rendering bypasses its stale OpenGL viewport.
        root.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            adjustPreviewViewport(right - left, bottom - top)
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                viewReady = true
                status("プレビュー領域準備完了")
                ensureUsbMonitor()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // The direct UVC SurfaceTexture follows measured sizes itself.
                // AUSBC setRenderSize() affects only the one-shot GL startup
                // future; calling it here does NOT update an existing GL viewport.
                Log.i("MyMonitor-Preview", "UVC surface now ${width}x${height}")
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                viewReady = false
                closeCamera()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        return root
    }

    override fun onStart() {
        super.onStart()
        ensureUsbMonitor()
    }

    private fun ensureUsbMonitor() {
        if (!isAdded || !viewReady || resettingMonitor) return
        if (usbMonitor != null) {
            // Keep the same listener through a background/foreground round trip.
            refreshUsb()
            return
        }
        try {
            val epoch = ++monitorEpoch
            usbMonitor = USBMonitor.getInstance(requireContext()).also { monitor ->
                monitor.setOnDeviceConnectListener(object : USBMonitor.OnDeviceConnectListener {
                    override fun onAttach(device: UsbDevice?) {
                        host()?.runOnUiThread { if (UsbRecoveryPolicy.acceptEvent(epoch, monitorEpoch) && device != null && isUvc(device)) { trace("ATTACH", device); status("USB装着: VID %04X PID %04X".format(device.vendorId, device.productId)); refreshUsb() } }
                    }
                    override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
                        host()?.runOnUiThread {
                            if (!UsbRecoveryPolicy.acceptEvent(epoch, monitorEpoch)) return@runOnUiThread
                            trace("CONNECT new=$createNew", device)
                            if (device != null && ctrlBlock != null && isUvc(device) && !opening && !closing && camera == null) {
                                waitingForPermission = false
                                openCamera(device, ctrlBlock)
                            }
                        }
                    }
                    override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                        host()?.runOnUiThread {
                            if (!UsbRecoveryPolicy.acceptEvent(epoch, monitorEpoch)) return@runOnUiThread
                            trace("DISCONNECT", device)
                            if (device != null && isUvc(device) &&
                                (currentDeviceId == null || currentDeviceId == device.deviceId)) {
                                if (!UsbRecoveryPolicy.shouldCloseOnDisconnect(physicalPresent(device))) {
                                    status("USB切断通知（機器は接続中）。プレビューを保持")
                                    if (camera == null) scheduleRecheck()
                                } else {
                                    status("USB切断（物理切断）")
                                    usbDetached()
                                }
                            }
                        }
                    }
                    override fun onDetach(device: UsbDevice?) {
                        host()?.runOnUiThread {
                            if (!UsbRecoveryPolicy.acceptEvent(epoch, monitorEpoch)) return@runOnUiThread
                            trace("DETACH", device)
                            if (device != null && isUvc(device) &&
                                (currentDeviceId == null || currentDeviceId == device.deviceId)) {
                                if (!physicalPresent(device)) {
                                    usbDetached()
                                    status("USB機器を取り外しました")
                                } else scheduleRecheck()
                            }
                        }
                    }
                    override fun onCancel(device: UsbDevice?) {
                        host()?.runOnUiThread {
                            if (!UsbRecoveryPolicy.acceptEvent(epoch, monitorEpoch)) return@runOnUiThread
                            waitingForPermission = false
                            trace("PERMISSION_CANCEL", device)
                            status("USB機器へのアクセスが許可されませんでした。USB確認から再試行")
                        }
                    }
                })
                monitor.register()
            }
            refreshUsb()
        } catch (e: Exception) { status("USB初期化失敗: ${e.message}") }
    }

    /**
     * Re-fit the native 16:9 HDMI/UVC frame after BOTH rotation and control-bar
     * visibility changes. HDMI pixels are never rotated or stretched.
     */
    private fun adjustPreviewViewport(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || !::preview.isInitialized) return
        val fitted = PreviewGeometry.fit(inputWidth, inputHeight, w, h)
        if (fitted == lastFit) return
        lastFit = fitted
        val existing = preview.layoutParams as FrameLayout.LayoutParams
        if (existing.width != fitted.width || existing.height != fitted.height ||
            existing.gravity != Gravity.CENTER) {
            preview.layoutParams = FrameLayout.LayoutParams(
                fitted.width, fitted.height, Gravity.CENTER)
        }
        val rect = PreviewGeometry.centered(inputWidth, inputHeight, w, h)
        Log.i("MyMonitor-Preview", "viewport=${w}x${h} UVC=${inputWidth}x${inputHeight} " +
            "preview=${fitted.width}x${fitted.height} offset=${rect.left},${rect.top}")
        // No library setAspectRatio() here: that used to apply aspect fitting
        // a second time and could make the picture undersized/off-center.
    }

    fun updateSurfaceSize() {
        if (!isAdded || !::preview.isInitialized) return
        val root = preview.parent as? FrameLayout ?: return
        // During configuration updates the layout may not yet be measured.
        // A post-layout pass reads the NEW orientation bounds.
        root.post {
            if (isAdded) adjustPreviewViewport(root.width, root.height)
        }
    }

    fun refreshUsb() {
        if (!isAdded || !viewReady) return
        val manager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.filter(::isUvc)
        if (devices.isEmpty()) {
            if (camera != null || ready || lastEnumeratedDeviceId != null) usbDetached()
            else status("UVCなし。スマホ画面入力を利用できます")
            return
        }
        val device = devices.first()
        if (lastEnumeratedDeviceId != null && lastEnumeratedDeviceId != device.deviceId) {
            trace("DEVICE_ID_CHANGED", device)
            usbDetached()
        }
        lastEnumeratedDeviceId = device.deviceId
        if (usbMonitor == null && !resettingMonitor) {
            ensureUsbMonitor()
            return
        }
        if (camera != null || opening || closing || resettingMonitor) return
        if (waitingForPermission) {
            if (android.os.SystemClock.uptimeMillis() < permissionDeadline) return
            waitingForPermission = false
            trace("PERMISSION_TIMEOUT")
        }
        if (android.os.SystemClock.uptimeMillis() < retryBlockedUntil) return
        waitingForPermission = true
        permissionDeadline = android.os.SystemClock.uptimeMillis() + permissionTimeoutMs
        status("UVC検出 VID:%04X PID:%04X / USBアクセス許可を要求中".format(device.vendorId, device.productId))
        try {
            val monitor = usbMonitor
            if (monitor == null || !monitor.isRegistered) {
                waitingForPermission = false
                status("USB監視が未登録です")
            } else {
                trace("REQUEST_PERMISSION", device)
                monitor.requestPermission(device)
                val epoch = monitorEpoch
                retryHandler.postDelayed({
                    if (UsbRecoveryPolicy.acceptEvent(epoch, monitorEpoch) && waitingForPermission &&
                        android.os.SystemClock.uptimeMillis() >= permissionDeadline) {
                        waitingForPermission = false
                        trace("PERMISSION_TIMEOUT", device)
                        scheduleRecheck(500L)
                    }
                }, permissionTimeoutMs + 50L)
            }
        } catch (e: Exception) {
            waitingForPermission = false
            status("USB許可要求失敗: ${e.message}")
        }
    }

    private fun openCamera(device: UsbDevice, control: USBMonitor.UsbControlBlock) {
        if (!viewReady || !isAdded) return
        if (opening || closing || camera != null || !physicalPresent(device)) return
        opening = true
        currentDeviceId = device.deviceId
        status("USB許可取得、UVCオープン中")
        try {
            val newCamera = CameraUVC(requireContext(), device)
            camera = newCamera
            newCamera.setUsbControlBlock(control)
            newCamera.setCameraStateCallBack(this)
            newCamera.addPreviewDataCallBack(object : IPreviewDataCallBack {
                override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
                    if (data != null && format == IPreviewDataCallBack.DataFormat.NV21) {
                        StreamHub.offerUvcNv21(data, width, height)
                    }
                }
            })
            val request = CameraRequest.Builder()
                .setPreviewWidth(1920).setPreviewHeight(1080)
                .setRawPreviewData(true)
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                // NORMAL uses UVCCamera.setPreviewTexture directly; OPENGL
                // cached its original EGL viewport, leaving a 480x270 picture
                // after phone rotation despite a larger TextureView rectangle.
                // Independent MJPEG passthrough and MP4/NV21 capture are kept.
                .setRenderMode(CameraRequest.RenderMode.NORMAL)
                .setAspectRatioShow(false)
                .setAudioSource(CameraRequest.AudioSource.NONE)
                .create()
            newCamera.openCamera(preview, request)
        } catch (e: Exception) {
            status("UVCオープン失敗: ${e.message}")
            closeCamera()
            scheduleRecheck(2500L)
        }
    }

    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        host()?.runOnUiThread {
            if (self !== camera) return@runOnUiThread
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    nativeFailures = 0
                    retryBlockedUntil = 0L
                    opening = false
                    ready = true
                    StreamHub.setUvcReady(true)
                    host()?.onUvcReady()
                    // Native MJPEG callback is independent of NV21 used for photo/recording.
                    (self as? CameraUVC)?.setMjpegDataCallback(IFrameCallback { buffer ->
                        if (buffer != null) StreamHub.offerUvc(buffer)
                    })
                    placeholder.visibility = View.GONE
                    val request = self.getCameraRequest()
                    val width = request?.previewWidth ?: 0
                    val height = request?.previewHeight ?: 0
                    if (width > 0 && height > 0) {
                        inputWidth = width
                        inputHeight = height
                        lastFit = PreviewGeometry.Size(0, 0)
                        updateSurfaceSize()
                    }
                    trace("OPENED")
                    status("UVC映像 ${width}×${height}" + if (width == 1920 && height == 1080) " / Full HD" else " / 機器が選択した解像度")
                }
                ICameraStateCallBack.State.CLOSED -> { opening = false; camera = null; ready = false; StreamHub.setUvcReady(false); status("UVCクローズ") }
                ICameraStateCallBack.State.ERROR -> {
                    status("UVCエラー: ${msg ?: "unknown"}")
                    val failedDeviceId = currentDeviceId
                    closeCamera()
                    if (failedDeviceId != lastFailedDeviceId) nativeFailures = 0
                    lastFailedDeviceId = failedDeviceId
                    nativeFailures++
                    // Invalidate posted callbacks before releasing the old control block.
                    resetUsbMonitor("NATIVE_ERROR")
                    val delay = UsbRecoveryPolicy.retryDelayMs(nativeFailures)
                    retryBlockedUntil = android.os.SystemClock.uptimeMillis() + delay
                    status("UVC native再接続失敗 ${nativeFailures}回。USB監視を再初期化 (${delay}ms)")
                    val generation = ++retryGeneration
                    retryHandler.postDelayed({
                        if (generation == retryGeneration && isAdded && viewReady && usbMonitor == null) ensureUsbMonitor()
                    }, delay)
                }
            }
        }
    }

    fun startWindows(host: String, port: Int) {
        StreamHub.enable()
        // Never connect to a saved/unverified host, even from the legacy UVC UI.
        StreamHub.connectManual(host, port) { ok ->
            status(if (ok) "受信機を確認済み。映像入力を待機中" else "Windows受信機を確認できません: " + host)
        }
    }
    fun stopWindows() { StreamHub.stopStreaming(); status("Windows転送停止") }

    /** Physical unplug path. Different from onStop, which only pauses preview. */
    fun usbDetached() {
        val hadDevice = lastEnumeratedDeviceId != null || camera != null || opening || ready
        lastEnumeratedDeviceId = null
        ++retryGeneration
        nativeFailures = 0
        retryBlockedUntil = 0L
        lastFailedDeviceId = null
        val wasRecording = recording
        closeCamera()
        waitingForPermission = false
        host()?.onUvcGone(wasRecording)
        if (hadDevice) resetUsbMonitor("PHYSICAL_DETACH")
    }

    /** Dispose USBMonitor and any cached UsbControlBlock on each real unplug.
     * Old callbacks are invalidated before release. The next physical attach
     * starts a fresh monitor, rather than reusing an alternating stale handle.
     */
    private fun resetUsbMonitor(reason: String) {
        if (resettingMonitor) return
        resettingMonitor = true
        ++monitorEpoch
        waitingForPermission = false
        trace("RESET_MONITOR:$reason")
        val old = usbMonitor
        usbMonitor = null
        try {
            old?.unregister()
            old?.destroy()
        } catch (e: Exception) {
            Log.w("MyMonitor-UVC", "USB monitor reset failed: $reason", e)
        } finally {
            resettingMonitor = false
        }
    }

    private fun closeCamera() {
        if (recording && !stopping) {
            // Best effort: finish the current MP4 before closing an unplugged
            // UVC device. The hardware may be already unavailable.
            stopping = true
            try { camera?.captureVideoStop() } catch (e: Exception) {
                status("USB切断中の録画終了に失敗: ${e.message}")
            }
        }
        if (closing) return
        closing = true
        opening = false
        StreamHub.setUvcReady(false)
        ready = false
        recording = false
        stopping = false
        host()?.setRecording(false)
        val old = camera
        camera = null
        try {
            old?.setCameraStateCallBack(null)
            old?.setMjpegDataCallback(null)
            old?.closeCamera()
        } catch (e: Exception) {
            Log.w("MyMonitor-UVC", "UVC cleanup failed", e)
        } finally {
            closing = false
        }
        currentDeviceId = null
        if (::placeholder.isInitialized) placeholder.visibility = View.VISIBLE
    }

    override fun onStop() {
        // A foreground-service session may outlive the Activity's visibility.
        // Do NOT forcibly close a working UVC stream merely because the user
        // opened another app. The TextureView destruction, USB detach or
        // explicit exit remains responsible for releasing the camera.
        super.onStop()
    }

    override fun onDestroyView() {
        // Real view destruction is different from transient Activity onStop.
        ++retryGeneration
        closeCamera()
        resetUsbMonitor("VIEW_DESTROY")
        waitingForPermission = false
        super.onDestroyView()
    }

    private fun destination(ext: String): File {
        val base = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val dir = File(base, "captures").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "MyMonitor_${name}.${ext}")
    }

    fun takePhoto() {
        val c = camera
        if (!ready || c == null) { status("UVC映像未接続。USB確認から再試行"); return }
        val output = destination("jpg")
        c.captureImage(object : ICaptureCallBack {
            override fun onBegin() { status("撮影中…") }
            override fun onError(error: String?) { status("撮影失敗: ${error ?: "unknown"}") }
            override fun onComplete(path: String?) {
                val result = path?.let(::File) ?: output
                if (result.exists() && result.length() > 0L) host()?.saveMedia(result, "image/jpeg")
                else status("撮影ファイルが見つかりません")
            }
        }, output.absolutePath)
    }

    fun requestStopRecording() {
        if (recording && !stopping) {
            stopping = true
            try { camera?.captureVideoStop() } catch (e: Exception) {
                status("録画終了処理に失敗: ${e.message}")
            }
        }
    }

    fun toggleRecording() {
        val c = camera
        if (recording) {
            if (!stopping) { stopping = true; status("録画を書き込み中…"); c?.captureVideoStop() }
            return
        }
        if (!ready || c == null) { status("UVC映像未接続。USB確認から再試行"); return }
        val output = destination("mp4")
        status("映像のみのMP4録画を開始中…")
        try { c.captureVideoStart(object : ICaptureCallBack {
            override fun onBegin() {
                recording = true; stopping = false
                host()?.setRecording(true); status("● 録画中")
            }
            override fun onError(error: String?) {
                recording = false; stopping = false
                host()?.setRecording(false); status("録画エラー: ${error ?: "unknown"}")
            }
            override fun onComplete(path: String?) {
                recording = false; stopping = false
                host()?.setRecording(false)
                val result = path?.let(::File) ?: output
                if (result.exists() && result.length() > 0L) host()?.saveMedia(result, "video/mp4")
                else status("録画ファイルが見つかりません")
            }
        }, output.absolutePath) } catch (e: Exception) {
            recording = false; stopping = false
            host()?.setRecording(false)
            status("録画開始失敗: ${e.message}")
            Log.e("MyMonitor-UVC", "Video capture start failed", e)
        }
    }
}
