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
    private val windowsSender = WindowsSender { status(it) }
    private var usbMonitor: USBMonitor? = null
    private var camera: CameraUVC? = null
    private var currentDeviceId: Int? = null
    private var waitingForPermission = false
    private var ready = false
    private var recording = false
    private var stopping = false
    private var viewReady = false
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
        root.addView(preview, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                viewReady = true
                status("プレビュー領域準備完了")
                refreshUsb()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                camera?.setRenderSize(width, height)
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
        try {
            usbMonitor = USBMonitor.getInstance(requireContext()).also { monitor ->
                monitor.setOnDeviceConnectListener(object : USBMonitor.OnDeviceConnectListener {
                    override fun onAttach(device: UsbDevice?) {
                        host()?.runOnUiThread { if (device != null && isUvc(device)) { status("USB装着: VID %04X PID %04X".format(device.vendorId, device.productId)); refreshUsb() } }
                    }
                    override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
                        host()?.runOnUiThread {
                            waitingForPermission = false
                            if (device != null && ctrlBlock != null && isUvc(device)) openCamera(device, ctrlBlock)
                        }
                    }
                    override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                        host()?.runOnUiThread { closeCamera(); status("USB切断") }
                    }
                    override fun onDetach(device: UsbDevice?) {
                        host()?.runOnUiThread { closeCamera(); waitingForPermission = false; status("USB機器を取り外しました") }
                    }
                    override fun onCancel(device: UsbDevice?) {
                        host()?.runOnUiThread { waitingForPermission = false; status("USB機器へのアクセスが許可されませんでした。USB確認から再試行") }
                    }
                })
                monitor.register()
            }
            refreshUsb()
        } catch (e: Exception) { status("USB初期化失敗: ${e.message}") }
    }

    fun refreshUsb() {
        if (!isAdded || !viewReady) return
        val manager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.filter(::isUvc)
        if (devices.isEmpty()) {
            status("UVC機器が見つかりません。USB確認でVID/PIDを表示")
            return
        }
        if (camera != null || waitingForPermission) return
        val device = devices.first()
        waitingForPermission = true
        status("UVC検出 VID:%04X PID:%04X / USBアクセス許可を要求中".format(device.vendorId, device.productId))
        try {
            val monitor = usbMonitor
            if (monitor == null || !monitor.isRegistered) {
                waitingForPermission = false
                status("USB監視が未登録です")
            } else monitor.requestPermission(device)
        } catch (e: Exception) {
            waitingForPermission = false
            status("USB許可要求失敗: ${e.message}")
        }
    }

    private fun openCamera(device: UsbDevice, control: USBMonitor.UsbControlBlock) {
        if (!viewReady || !isAdded) return
        closeCamera()
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
                        windowsSender.offer(data, width, height)
                    }
                }
            })
            val request = CameraRequest.Builder()
                .setPreviewWidth(1920).setPreviewHeight(1080)
                .setRawPreviewData(true)
                .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
                .setRenderMode(CameraRequest.RenderMode.OPENGL)
                .setAspectRatioShow(true)
                .setAudioSource(CameraRequest.AudioSource.NONE)
                .create()
            newCamera.openCamera(preview, request)
        } catch (e: Exception) {
            status("UVCオープン失敗: ${e.message}")
            closeCamera()
        }
    }

    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        host()?.runOnUiThread {
            if (self !== camera) return@runOnUiThread
            when (code) {
                ICameraStateCallBack.State.OPENED -> {
                    ready = true
                    placeholder.visibility = View.GONE
                    val request = self.getCameraRequest()
                    val width = request?.previewWidth ?: 0
                    val height = request?.previewHeight ?: 0
                    status("UVC映像 ${width}×${height}" + if (width == 1920 && height == 1080) " / Full HD" else " / 機器が選択した解像度")
                }
                ICameraStateCallBack.State.CLOSED -> { ready = false; status("UVCクローズ") }
                ICameraStateCallBack.State.ERROR -> { ready = false; status("UVCエラー: ${msg ?: "unknown"}") }
            }
        }
    }

    fun startWindows(host: String, port: Int) {
        if (!ready) { status("UVC映像を接続してから転送してください"); return }
        windowsSender.start(host, port)
    }
    fun stopWindows() { windowsSender.stop(); status("Windows転送停止") }

    private fun closeCamera() {
        windowsSender.stop()
        ready = false
        recording = false
        stopping = false
        host()?.setRecording(false)
        camera?.setCameraStateCallBack(null)
        camera?.closeCamera()
        camera = null
        currentDeviceId = null
        if (::placeholder.isInitialized) placeholder.visibility = View.VISIBLE
    }

    override fun onStop() {
        closeCamera()
        waitingForPermission = false
        usbMonitor?.unregister()
        usbMonitor?.destroy()
        usbMonitor = null
        super.onStop()
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
