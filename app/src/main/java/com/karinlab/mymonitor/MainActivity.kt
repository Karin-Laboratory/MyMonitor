package com.karinlab.mymonitor

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbConstants
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private lateinit var monitor: MonitorFragment
    private lateinit var video: FrameLayout
    private var cameraMounted = false
    private var receiverRegistered = false
    private var requestingProjection = false
    private var audioPermissionAsked = false
    private var pendingUvcRecord = false
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED || intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                video.postDelayed({ scanUsbDevices(); requestCameraForUvc(); if (cameraMounted) monitor.refreshUsb() }, 400)
            }
        }
    }
    private lateinit var state: TextView
    private val messageHandler = Handler(Looper.getMainLooper())
    private var expireMessage: Runnable? = null
    private var recordingActive = false
    private lateinit var record: Button
    private var controlsVisible = true
    @Volatile private var discoveringWindows = false
    private lateinit var controls: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StreamHub.init(applicationContext)
        StreamHub.statusSink = { showStatus(it) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        state = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); text = "USBカメラを接続してください"; setPadding(16, 8, 16, 8); textSize = 14f }
        root.addView(state, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(8, 10, 8, 10)
            setBackgroundColor(0x99000000.toInt())
        }
        val photo = button("📷 撮影") {
            if (StreamHub.uvcReady) monitor.takePhoto() else showStatus("スマホ画面の保存は録画を使用してください")
        }
        record = button("● 録画") {
            if (StreamHub.uvcReady) monitor.toggleRecording()
            else startService(Intent(this, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_RECORD
            })
        }
        setRecording(false)
        val folder = button("📁 保存先") { chooseFolder() }
        val smb = button("NAS / SMB") { smbSettings() }
        val scan = button("USB確認") { scanUsbDevices(); monitor.refreshUsb() }
        controls.addView(photo)
        controls.addView(record)
        controls.addView(folder)
        controls.addView(smb)
        controls.addView(scan)
        controls.addView(button("Windowsへ転送") { discoverWindows() })
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        monitor = (supportFragmentManager.findFragmentByTag("monitor") as? MonitorFragment) ?: MonitorFragment()
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        requestCameraForUvc()
        scanUsbDevices()
        ContextCompat.startForegroundService(this,
            Intent(this, ScreenStreamService::class.java).apply { action = ScreenStreamService.ACTION_START })
        // On first launch the app is foreground, so Android can show the required
        // MediaProjection consent dialog when a single receiver is discovered.
        Thread({
            Thread.sleep(1800)
            val count = StreamHub.scanAndConnect()
            if (count > 0) runOnUiThread {
                if (!hasUvcDevice() && !ScreenStreamService.projectionActive) {
                    if (StreamHub.selectedHost != null) beginScreenConsent()
                    else if (count > 1) discoverWindows()
                }
            }
        }, "InitialReceiverSearch").start()
        if (intent?.action == "com.karinlab.mymonitor.GRANT_SCREEN") {
            messageHandler.postDelayed({ beginScreenConsent() }, 500)
        }
        if (intent?.action == "com.karinlab.mymonitor.UVC_RECORD") {
            pendingUvcRecord = true
            messageHandler.postDelayed({ onUvcReady() }, 1000)
        }
        root.setOnClickListener {
            controlsVisible = !controlsVisible
            controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
        }
    }

    private fun hasUvcDevice(): Boolean {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.any { device ->
            device.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                (0 until device.interfaceCount).any {
                    device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                }
        }
    }

    private fun requestCameraForUvc() {
        if (!hasUvcDevice()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            mountCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 101)
    }

    private fun beginScreenConsent() {
        if (requestingProjection || ScreenStreamService.projectionActive || StreamHub.uvcReady) return
        if (!audioPermissionAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }
        requestingProjection = true
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val request = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            startActivityForResult(request, 490)
        } catch (e: Exception) {
            requestingProjection = false
            showStatus("画面共有の許可画面を開けません: " + e.message)
        }
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        setIntent(next)
        when (next.action) {
            "com.karinlab.mymonitor.GRANT_SCREEN" -> beginScreenConsent()
            "com.karinlab.mymonitor.UVC_RECORD" -> {
                pendingUvcRecord = true
                onUvcReady()
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                messageHandler.postDelayed({ requestCameraForUvc(); if (cameraMounted) monitor.refreshUsb() }, 500)
            }
        }
    }

    fun onUvcReady() {
        if (pendingUvcRecord && StreamHub.uvcReady) {
            pendingUvcRecord = false
            monitor.toggleRecording()
        }
    }

    private fun mountCamera() {
        if (cameraMounted) return
        cameraMounted = true
        supportFragmentManager.beginTransaction().replace(video.id, monitor, "monitor").commitNow()
    }

    @Deprecated("Permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                mountCamera()
                scanUsbDevices()
            } else showStatus("UVCカメラ権限が拒否されました")
        }
        if (requestCode == 102) {
            // Screen capture still works without audio, but playback capture needs RECORD_AUDIO.
            beginScreenConsent()
        }
    }

    private fun scanUsbDevices() {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usb.deviceList.values.toList()
        val cameras = devices.filter { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
                || device.deviceClass == UsbConstants.USB_CLASS_VIDEO
        }
        val details = devices.joinToString("; ") { device ->
            val classes = (0 until device.interfaceCount).joinToString(",") { device.getInterface(it).interfaceClass.toString() }
            "VID:%04X PID:%04X cls:%d if:[%s]".format(device.vendorId, device.productId, device.deviceClass, classes)
        }
        val message = when {
            devices.isEmpty() -> "USB機器なし。OTG・ケーブル・給電を確認"
            cameras.isEmpty() -> "USB機器 ${devices.size}台検出、映像用インターフェースなし: $details"
            else -> "UVC候補 ${cameras.size}台検出: $details"
        }
        showStatus(message)
    }

    override fun onDestroy() {
        expireMessage?.let { messageHandler.removeCallbacks(it) }
        if (receiverRegistered) { unregisterReceiver(usbReceiver); receiverRegistered = false }
        StreamHub.statusSink = null
        super.onDestroy()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }

    /**
     * Notifications expire after 7 seconds. The persistent recording indicator is
     * restored after other notifications and never expires while capture is active.
     * Each update invalidates the previous timeout so stale callbacks cannot hide
     * a newer message.
     */
    private fun redDotLabel(text: String): SpannableString =
        SpannableString(text).apply {
            val dot = text.indexOf('●')
            if (dot >= 0) setSpan(ForegroundColorSpan(Color.RED), dot, dot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun showRecordingStatus() {
        state.text = redDotLabel("● 録画中")
        state.visibility = View.VISIBLE
    }

    fun showStatus(message: String) {
        runOnUiThread {
            expireMessage?.let { messageHandler.removeCallbacks(it) }
            expireMessage = null
            if (recordingActive && message == "● 録画中") {
                showRecordingStatus()
                return@runOnUiThread
            }
            state.text = if (message.startsWith("● 録画中")) redDotLabel(message) else message
            state.visibility = View.VISIBLE
            val expiration = Runnable {
                if (recordingActive) showRecordingStatus()
                else state.visibility = View.GONE
                expireMessage = null
            }
            expireMessage = expiration
            messageHandler.postDelayed(expiration, 7_000L)
        }
    }

    fun setRecording(active: Boolean) {
        runOnUiThread {
            recordingActive = active
            StreamHub.uvcRecording = active
            if (active) {
                record.text = "■ 停止"
                expireMessage?.let { messageHandler.removeCallbacks(it) }
                expireMessage = null
                showRecordingStatus()
            } else {
                record.text = redDotLabel("● 録画")
                // Current notifications will still expire normally after their 7-second timer.
                if (state.text.toString() == "● 録画中") {
                    state.visibility = View.GONE
                }
            }
            record.setTextColor(Color.WHITE)
        }
    }
    fun saveMedia(file: java.io.File, mime: String) { Storage.save(this, file, mime, prefs) { showStatus(it) } }

    private fun chooseFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 47)
    }

    @Deprecated("Android Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 490) {
            requestingProjection = false
            if (resultCode == Activity.RESULT_OK && data != null) {
                ContextCompat.startForegroundService(this,
                    Intent(this, ScreenStreamService::class.java).apply {
                        action = ScreenStreamService.ACTION_PROJECTION
                        putExtra("resultCode", resultCode)
                        putExtra("projectionData", data)
                    })
                if (!hasUvcDevice()) moveTaskToBack(true)
            } else showStatus("画面共有が許可されませんでした")
            return
        }
        if (requestCode == 47 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("folder", uri.toString()).apply()
            showStatus("保存先を設定しました")
        }
    }

    private fun discoverWindows() {
        if (discoveringWindows) return
        discoveringWindows = true
        showStatus("LAN内のMy Cast Receiverを検索中…")
        Thread({
            val results = try { ReceiverDiscovery.scan() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                discoveringWindows = false
                when (results.size) {
                    0 -> {
                        showStatus("検証できるWindows受信機がありません。EXEを起動するかIPを入力")
                        windowsSettings()
                    }
                    1 -> {
                        val receiver = results.single()
                        StreamHub.enable()
                        StreamHub.connectVerified(receiver)
                        showStatus("受信機を二重確認済み: " + receiver.name + " (" + receiver.host + ")")
                        if (!StreamHub.uvcReady) beginScreenConsent()
                    }
                    else -> {
                        val items = results.map { it.name + " (" + it.host + ":" + it.port + ")" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("受信機を" + results.size + "台発見（接続先を選択）")
                            .setItems(items) { _, index ->
                                val receiver = results[index]
                                StreamHub.enable()
                                StreamHub.connectVerified(receiver)
                                if (!StreamHub.uvcReady) beginScreenConsent()
                            }
                            .setNeutralButton("IPを手入力") { _, _ -> windowsSettings() }
                            .setNegativeButton("キャンセル", null)
                            .show()
                    }
                }
            }
        }, "ReceiverDiscovery").start()
    }

    private fun windowsSettings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 10, 24, 0) }
        val address = EditText(this).apply { hint = "WindowsのIPv4アドレス"; setText(prefs.getString("windowsHost", "")); inputType = 1 }
        val port = EditText(this).apply { hint = "ポート"; setText(prefs.getInt("windowsPort", 57007).toString()); inputType = 2 }
        layout.addView(address); layout.addView(port)
        AlertDialog.Builder(this).setTitle("Windows受信機を確認して転送").setView(layout)
            .setNeutralButton("停止") { _, _ -> StreamHub.stopStreaming() }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("送信開始") { _, _ ->
                val host = address.text.toString().trim()
                val number = port.text.toString().toIntOrNull()
                if (host.isEmpty() || number == null || number !in 1..65535)
                    showStatus("接続先とポートを確認してください")
                else {
                    showStatus("受信機を確認中: " + host)
                    StreamHub.enable()
                    StreamHub.connectManual(host, number) { verified ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (verified) {
                                showStatus("受信機の実在を確認: " + host)
                                if (!StreamHub.uvcReady) beginScreenConsent()
                            } else showStatus("接続不可: " + host + " は My Cast Receiver と応答しません")
                        }
                    }
                }
            }.show()
    }

    private fun smbSettings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 10, 24, 0) }
        fun field(hint: String, key: String, secret: Boolean = false): EditText = EditText(this).also {
            it.hint = hint
            if (!secret) it.setText(prefs.getString(key, "") ?: "")
            if (secret) it.inputType = 129
            layout.addView(it)
        }
        val path = field("smb://192.168.1.10/share/folder", "smb")
        val domain = field("ドメイン (省略可)", "domain")
        val username = field("ユーザー名", "username")
        val password = field("パスワード (変更時のみ入力)", "unused", true)
        if (SmbCredentials.hasSavedPassword(this)) password.hint = "保存済みパスワードあり (変更する場合のみ入力)"
        AlertDialog.Builder(this).setTitle("SMB保存先")
            .setView(layout)
            .setNeutralButton("無効にする") { _, _ ->
                prefs.edit().remove("smb").remove("domain").remove("username").apply(); SmbCredentials.clear(this); showStatus("SMB転送設定と保存済みパスワードを削除しました")
            }
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("保存") { _, _ ->
                val url = path.text.toString().trim().trimEnd('/')
                if (!url.startsWith("smb://") || url.contains("@")) {
                    showStatus("SMB URLは smb://host/share の形式で入力してください")
                } else {
                    val entered = password.text.toString().toCharArray()
                    try {
                        if (entered.isNotEmpty()) SmbCredentials.save(this, entered)
                        if (!SmbCredentials.hasSavedPassword(this)) {
                            showStatus("SMBパスワードを入力してください")
                        } else {
                            prefs.edit().putString("smb", url).putString("domain", domain.text.toString())
                                .putString("username", username.text.toString()).apply()
                            showStatus("SMB設定と暗号化パスワードを保存しました")
                        }
                    } catch (e: Exception) {
                        showStatus("SMB資格情報の保存に失敗: ${e.message}")
                    } finally { entered.fill('\u0000') }
                }
            }.show()
    }
}
