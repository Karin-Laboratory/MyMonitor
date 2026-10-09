package com.karinlab.mymonitor

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorFragment : CameraFragment() {
    private lateinit var preview: FrameLayout
    private var ready = false
    private var recording = false
    private var stopping = false
    private fun host() = activity as? MainActivity

    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View {
        return FrameLayout(requireContext()).also { root ->
            root.setBackgroundColor(Color.BLACK)
            preview = FrameLayout(requireContext())
            root.addView(preview, FrameLayout.LayoutParams(-1, -1))
            val label = TextView(requireContext()).apply {
                text = "USB UVC / HDMI capture"
                textSize = 16f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
            }
            // Under the camera surface, so it disappears naturally when video starts.
            root.addView(label, 0, FrameLayout.LayoutParams(-1, -1))
        }
    }

    override fun getCameraView(): IAspectRatio = AspectRatioTextureView(requireContext())
    override fun getCameraViewContainer(): ViewGroup = preview

    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> { ready = true; host()?.showStatus("UVC映像を表示中") }
            ICameraStateCallBack.State.CLOSED -> { ready = false; host()?.showStatus("USBカメラが切断されました") }
            ICameraStateCallBack.State.ERROR -> { ready = false; host()?.showStatus("カメラエラー: ${msg ?: "unknown"}") }
        }
    }

    private fun destination(ext: String): File {
        val base = requireContext().getExternalFilesDir(null) ?: requireContext().filesDir
        val dir = File(base, "captures").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "MyMonitor_${name}.${ext}")
    }

    fun takePhoto() {
        if (!ready) { host()?.showStatus("UVCカメラを接続してください"); return }
        val output = destination("png")
        captureImage(object : ICaptureCallBack {
            override fun onBegin() { host()?.showStatus("撮影中…") }
            override fun onError(error: String?) { host()?.showStatus("撮影失敗: ${error ?: "unknown"}") }
            override fun onComplete(path: String?) {
                val result = path?.let { File(it) } ?: output
                if (result.exists() && result.length() > 0L) host()?.saveMedia(result, "image/png")
                else host()?.showStatus("撮影ファイルが見つかりません")
            }
        }, output.absolutePath)
    }

    fun toggleRecording() {
        if (recording) {
            if (!stopping) { stopping = true; host()?.showStatus("録画を書き込み中…"); captureVideoStop() }
            return
        }
        if (!ready) { host()?.showStatus("UVCカメラを接続してください"); return }
        val output = destination("mp4")
        captureVideoStart(object : ICaptureCallBack {
            override fun onBegin() {
                recording = true; stopping = false
                host()?.setRecording(true)
                host()?.showStatus("● 録画中")
            }
            override fun onError(error: String?) {
                recording = false; stopping = false
                host()?.setRecording(false)
                host()?.showStatus("録画エラー: ${error ?: "unknown"}")
            }
            override fun onComplete(path: String?) {
                recording = false; stopping = false
                host()?.setRecording(false)
                val result = path?.let { File(it) } ?: output
                if (result.exists() && result.length() > 0L) host()?.saveMedia(result, "video/mp4")
                else host()?.showStatus("録画ファイルが見つかりません")
            }
        }, output.absolutePath)
    }
}
