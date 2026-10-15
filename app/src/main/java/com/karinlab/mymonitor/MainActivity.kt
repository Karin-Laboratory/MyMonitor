package com.karinlab.mymonitor

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbConstants
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
import android.widget.ScrollView
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
    private var exitReceiverRegistered = false
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenStreamService.ACTION_FINISH_UI) {
                if (::monitor.isInitialized) monitor.requestStopRecording()
                messageHandler.postDelayed({ if (!isFinishing) finish() }, 450)
            }
        }
    }
    private var requestingProjection = false
    private var audioPermissionAsked = false
    private var pendingUvcRecord = false
    private var receiverChooserShown = false
    private var wasUvcEverReady = false
    private var resumeRecordingOnScreen = false
    private var screenConsentDeclined = false
    private var notificationPermissionRequested = false
    private var usbReturnTask: Runnable? = null
    // Only MonitorFragment owns USBMonitor and CameraUVC. Activity coalesces
    // Android broadcasts into a single physical-enumeration reconciliation.
    private var usbReconcileTask: Runnable? = null
    private fun reconcilePhysicalUsb() {
        usbReconcileTask?.let { messageHandler.removeCallbacks(it) }
        val task = Runnable {
            if (isFinishing || isDestroyed || !::monitor.isInitialized) return@Runnable
            scanUsbDevices()
            if (hasUvcDevice()) {
                screenConsentDeclined = false
                requestCameraForUvc()
                if (cameraMounted) monitor.refreshUsb()
            } else {
                if (cameraMounted) monitor.usbDetached()
                onUvcGone()
            }
        }
        usbReconcileTask = task
        messageHandler.postDelayed(task, 450)
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) reconcilePhysicalUsb()
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
    private lateinit var setupOverlay: ScrollView
    private lateinit var setupStatus: TextView
    private lateinit var setupRemoteButton: Button
    private lateinit var setupReturnButton: Button
    private var settingsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StreamHub.init(applicationContext)
        StreamHub.statusSink = { showStatus(it) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = FrameLayout(this).apply { id = 0x04D4D020 }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        state = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0x88000000.toInt()); text = "USBカメラを接続してください"; setPadding(16, 8, 16, 8); textSize = 14f }
        root.addView(state, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        // Without a real USB camera there is no preview to paint. The previous
        // launcher re-entry showed an empty black monitor; present usable
        // connection/settings controls instead. This never replaces the UVC view.
        val dp = resources.displayMetrics.density
        val setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (54 * dp).toInt(),
                (24 * dp).toInt(), (110 * dp).toInt())
        }
        val heading = TextView(this).apply {
            text = "MyMonitor"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        setup.addView(heading, LinearLayout.LayoutParams(-1, -2))
        setupStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(0xffd8e5f5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (18 * dp).toInt(), 0, (20 * dp).toInt())
        }
        setup.addView(setupStatus, LinearLayout.LayoutParams(-1, -2))
        fun setupAction(label: String, click: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = (8 * dp).toInt()
            }
        }
        setup.addView(setupAction("📺 Windowsアプリを探す") { discoverWindows() })
        setup.addView(setupAction("📱 Android画面をWindowsに映す") {
            if (StreamHub.selectedHost == null) discoverWindows()
            else beginScreenConsent()
        })
        setupRemoteButton = setupAction("🔒 遠隔操作を許可する") { toggleRemoteControlPermission() }
        setup.addView(setupRemoteButton)
        setup.addView(android.widget.CheckBox(this).apply {
            text = "画面ロックを使用しない"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("keep_screen_unlocked", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("keep_screen_unlocked", checked).apply()
                startService(Intent(this@MainActivity, ScreenStreamService::class.java).apply {
                    action = ScreenStreamService.ACTION_SOURCE_CHANGED
                })
            }
        })
        setup.addView(setupAction("⚙ Windows接続先を手動設定") { windowsSettings() })
        setupReturnButton = setupAction("← Android画面へ戻る") {
            if (StreamHub.uvcReady) {
                settingsRequested = false
                refreshPresentation()
            } else moveTaskToBack(true)
        }
        setup.addView(setupReturnButton)
        setup.addView(setupAction("MyMonitorを終了") {
            startService(Intent(this, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_EXIT
            })
        }.apply {
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xffb3261e.toInt())
            (layoutParams as LinearLayout.LayoutParams).topMargin = (28 * dp).toInt()
        })
        setupOverlay = ScrollView(this).apply {
            setBackgroundColor(0xff121b29.toInt())
            setFillViewport(true)
            addView(setup)
        }
        root.addView(setupOverlay, FrameLayout.LayoutParams(-1, -1))

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
        val scan = button("USB確認") {
            scanUsbDevices()
            if (hasUvcDevice()) { requestCameraForUvc(); if (cameraMounted) monitor.refreshUsb() }
            else onUvcGone()
        }
        controls.addView(photo)
        controls.addView(record)
        controls.addView(folder)
        controls.addView(smb)
        controls.addView(scan)
        controls.addView(button("Windowsへ転送") { discoverWindows() })
        controls.addView(button("遠隔操作") { toggleRemoteControlPermission() })
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        state.bringToFront()
        refreshPresentation()
        // Reserve real room for the bottom toolbar rather than letting its
        // rotated/higher landscape layout cover the HDMI video viewport.
        controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoViewport() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoViewport() }
        monitor = (supportFragmentManager.findFragmentByTag("monitor") as? MonitorFragment) ?: MonitorFragment()
        cameraMounted = monitor.isAdded
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        ContextCompat.registerReceiver(this, exitReceiver,
            IntentFilter(ScreenStreamService.ACTION_FINISH_UI),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        exitReceiverRegistered = true
        requestCameraForUvc()
        scanUsbDevices()
        ContextCompat.startForegroundService(this,
            Intent(this, ScreenStreamService::class.java).apply { action = ScreenStreamService.ACTION_START })
        // On first launch the app is foreground, so Android can show the required
        // MediaProjection consent dialog when a single receiver is discovered.
        Thread({
            try { Thread.sleep(1200) } catch (_: InterruptedException) { return@Thread }
            val count = StreamHub.scanAndConnect()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshPresentation()
                if (StreamHub.selectedHost != null && !hasUvcDevice() &&
                    !ScreenStreamService.projectionActive) beginScreenConsent()
                else if (count > 1 && StreamHub.selectedHost == null &&
                    !receiverChooserShown) discoverWindows()
            }
        }, "InitialReceiverSearch").start()
        if (intent?.action == "com.karinlab.mymonitor.GRANT_SCREEN") {
            messageHandler.postDelayed({ beginScreenConsent() }, 500)
        }
        if (intent?.action == "com.karinlab.mymonitor.UVC_RECORD") {
            pendingUvcRecord = true
            messageHandler.postDelayed({ onUvcReady() }, 1000)
        }
        messageHandler.postDelayed({ askNotificationPermissionIfNeeded() }, 900)
        // Android installs cannot silently grant power-exemption permissions.
        // Explain the choice once, then open the explicit Android system dialog.
        messageHandler.postDelayed({ promptBatteryOptimizationOnce() }, 3200)
        root.setOnClickListener {
            controlsVisible = !controlsVisible
            controls.visibility = if (controlsVisible) View.VISIBLE else View.GONE
            updateVideoViewport()
        }
    }

    /** Keep the physical UVC path untouched. No camera means settings, not black. */
    private fun refreshPresentation() {
        if (!::setupOverlay.isInitialized) return
        val uvcReady = StreamHub.uvcReady
        val showSettings = settingsRequested || !uvcReady
        setupOverlay.visibility = if (showSettings) View.VISIBLE else View.GONE
        if (!showSettings) return
        val receiver = StreamHub.selectedHost?.let { "接続先: $it" }
            ?: "Windows: 未接続（Windowsアプリを起動してください）"
        val projection = if (ScreenStreamService.projectionActive) {
            if (StreamHub.sender.isConnected()) "Android画面: Windowsへ転送中"
            else "Android画面: Windowsへの接続待ち"
        } else "Android画面: 転送するには画面共有を許可してください"
        val remote = when {
            !RemoteControlService.active() -> "未設定（ユーザー補助の許可が必要）"
            StreamHub.controlArmed -> "Windowsから操作できます"
            else -> "停止中"
        }
        val usb = when {
            uvcReady -> "USBカメラ: 映像を表示中"
            hasUvcDevice() -> "USBカメラ: 映像を準備中"
            else -> "USBカメラ: 未接続"
        }
        setupStatus.text = "$usb\n$receiver\n$projection\n遠隔操作: $remote"
        setupReturnButton.text = if (uvcReady) "← UVCモニターへ戻る"
            else "← Android画面へ戻る"
        setupRemoteButton.text = when {
            !RemoteControlService.active() -> "🔒 遠隔操作の権限を設定"
            StreamHub.controlArmed -> "🔓 遠隔操作を停止する"
            else -> "🔒 遠隔操作を許可する"
        }
    }

    private fun toggleRemoteControlPermission() {
        if (!RemoteControlService.active()) {
            showStatus("ユーザー補助でMyMonitorの遠隔操作を許可してください")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else if (StreamHub.source() != CapturePolicy.Source.SCREEN ||
            !ScreenStreamService.projectionActive) {
            StreamHub.disarmControl()
            showStatus("Android画面をWindowsへ転送中のみ遠隔操作できます")
        } else {
            StreamHub.setControlArmed(!StreamHub.controlArmed)
            showStatus(if (StreamHub.controlArmed)
                "Windowsからの遠隔操作を許可しました" else "Windowsからの遠隔操作を停止しました")
        }
        refreshPresentation()
    }

    private fun updateVideoViewport() {
        if (!::video.isInitialized || !::controls.isInitialized) return
        val params = video.layoutParams as? FrameLayout.LayoutParams ?: return
        val reserved = if (controls.visibility == View.VISIBLE) controls.height else 0
        if (params.bottomMargin == reserved) return
        params.bottomMargin = reserved
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        video.layoutParams = params
        if (::monitor.isInitialized) monitor.updateSurfaceSize()
    }

    private fun promptBatteryOptimizationOnce() {
        if (isFinishing || isDestroyed || Build.VERSION.SDK_INT < 23) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        if (prefs.getBoolean("batteryPromptShown017", false)) return
        // Avoid obscuring an active Android screen-capture consent sheet.
        if (requestingProjection || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            // Try again on a user-visible onResume, not forever in background.
            return
        }
        prefs.edit().putBoolean("batteryPromptShown017", true).apply()
        AlertDialog.Builder(this)
            .setTitle("バックグラウンド転送の省電力設定")
            .setMessage("画面転送中にAndroidがアプリを停止しにくくするため、MyMonitorの電池最適化を解除できます。消費電力は増えます。許可は端末の設定画面でユーザーが選びます。")
            .setPositiveButton("設定を開く") { _, _ -> openBatteryExemptionRequest() }
            .setNegativeButton("あとで", null)
            .show()
    }

    private fun openBatteryExemptionRequest() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            Log.w("MyMonitorPower", "Direct battery exemption unavailable", e)
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationPermissionRequested &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            notificationPermissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
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
        // Explicit user consent may also be granted while a UVC device is
        // present; UVC keeps priority, and projection is ready for its removal.
        // Automatic call sites suppress the dialog during USB opening.
        if (requestingProjection || ScreenStreamService.projectionActive) return
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
            showStatus("Androidの画面共有を開始できませんでした: " + e.message)
        }
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        setIntent(next)
        if (next.action == Intent.ACTION_MAIN) settingsRequested = true
        if (next.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) settingsRequested = false
        refreshPresentation()
        when (next.action) {
            "com.karinlab.mymonitor.GRANT_SCREEN" -> {
                screenConsentDeclined = false
                beginScreenConsent()
            }
            "com.karinlab.mymonitor.OPEN" -> {
                if (cameraMounted && hasUvcDevice()) monitor.refreshUsb()
            }
            "com.karinlab.mymonitor.UVC_RECORD" -> {
                pendingUvcRecord = true
                onUvcReady()
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED ->
                reconcilePhysicalUsb()
        }
    }

    fun onUvcReady() {
        // A newly opened camera takes priority; it must never be obscured by
        // settings left over from a previous launcher re-entry.
        settingsRequested = false
        refreshPresentation()
        wasUvcEverReady = true
        notifyServiceSource()
        if (ScreenStreamService.screenRecording) {
            startService(Intent(this, ScreenStreamService::class.java).apply {
                action = ScreenStreamService.ACTION_STOP_SCREEN_RECORD
            })
            // Recording changes source: terminate the screen MP4 and open a
            // separate UVC file rather than mixing two different formats.
            pendingUvcRecord = true
        }
        if (pendingUvcRecord && StreamHub.uvcReady) {
            pendingUvcRecord = false
            monitor.toggleRecording()
        }
    }

    private fun notifyServiceSource() {
        startService(Intent(this, ScreenStreamService::class.java).apply {
            action = ScreenStreamService.ACTION_SOURCE_CHANGED
        })
    }

    /** Called on USB DETACHED, on fragment USBMonitor callback, and on resume.
     * Debounced, because Android may deliver both USB broadcasts for one event.
     */
    fun onUvcGone(wasRecording: Boolean = false) {
        refreshPresentation()
        if (wasRecording) resumeRecordingOnScreen = true
        if (hasUvcDevice()) return
        usbReturnTask?.let { messageHandler.removeCallbacks(it) }
        val pending = Runnable {
            if (isFinishing || isDestroyed || hasUvcDevice()) return@Runnable
            val hadUvc = wasUvcEverReady
            wasUvcEverReady = false
            notifyServiceSource()
            if (ScreenStreamService.projectionActive) {
                showStatus("USBカメラを外したため、Android画面の転送に戻ります")
                if (resumeRecordingOnScreen) {
                    resumeRecordingOnScreen = false
                    startService(Intent(this, ScreenStreamService::class.java).apply {
                        action = ScreenStreamService.ACTION_RECORD
                    })
                }
                // UVC preview is our own activity. Hide it to reveal the
                // genuine Android screen, not the black disconnected preview.
                if (hadUvc) moveTaskToBack(true)
            } else if (StreamHub.needsConsent() || resumeRecordingOnScreen) {
                showStatus("UVC取り外し。Android画面共有を許可してください")
                if (!screenConsentDeclined) beginScreenConsent()
            } else {
                showStatus("UVCを取り外しました。Androidのホーム画面へ戻ります")
                if (hadUvc) moveTaskToBack(true)
            }
        }
        usbReturnTask = pending
        messageHandler.postDelayed(pending, 220)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Avoid destroying and re-opening the USB preview on portrait/landscape.
        // Existing UVC TextureView receives a size change, projection VirtualDisplay
        // receives onCapturedContentResize, and the TCP source stays selected.
        updateVideoViewport()
        if (cameraMounted && hasUvcDevice()) monitor.updateSurfaceSize()
    }

    override fun onResume() {
        super.onResume()
        refreshPresentation()
        if (::state.isInitialized)
            messageHandler.postDelayed({ promptBatteryOptimizationOnce() }, 1200)
        if (::monitor.isInitialized && ::video.isInitialized && !hasUvcDevice())
            onUvcGone()
        else if (::monitor.isInitialized && hasUvcDevice()) {
            // Android may suppress background USB auto-launch. When the user
            // returns to MyMonitor, enumerate the already-connected device.
            requestCameraForUvc()
            if (cameraMounted) messageHandler.postDelayed({ monitor.refreshUsb() }, 250)
        }
    }

    private fun mountCamera() {
        if (cameraMounted) return
        if (supportFragmentManager.isStateSaved) {
            video.postDelayed({ if (!isFinishing && !isDestroyed) mountCamera() }, 300)
            return
        }
        cameraMounted = true
        if (!monitor.isAdded) supportFragmentManager.beginTransaction().replace(video.id, monitor, "monitor").commitNow()
    }

    @Deprecated("Permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                mountCamera()
                scanUsbDevices()
            } else showStatus("USBカメラを使うにはカメラの許可が必要です")
        }
        if (requestCode == 102) {
            // Screen capture still works without audio, but playback capture needs RECORD_AUDIO.
            beginScreenConsent()
        }
        if (requestCode == 103 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            showStatus("通知が無効です。通知から停止するには設定で許可してください")
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
        usbReconcileTask?.let { messageHandler.removeCallbacks(it) }
        usbReturnTask?.let { messageHandler.removeCallbacks(it) }
        expireMessage?.let { messageHandler.removeCallbacks(it) }
        if (receiverRegistered) { unregisterReceiver(usbReceiver); receiverRegistered = false }
        if (exitReceiverRegistered) { unregisterReceiver(exitReceiver); exitReceiverRegistered = false }
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
            refreshPresentation()
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
            if (::monitor.isInitialized) notifyServiceSource()
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
                screenConsentDeclined = false
                ContextCompat.startForegroundService(this,
                    Intent(this, ScreenStreamService::class.java).apply {
                        action = ScreenStreamService.ACTION_PROJECTION
                        putExtra("resultCode", resultCode)
                        putExtra("projectionData", data)
                    })
                if (resumeRecordingOnScreen) {
                    resumeRecordingOnScreen = false
                    startService(Intent(this, ScreenStreamService::class.java).apply {
                        action = ScreenStreamService.ACTION_RECORD
                    })
                }
                if (!hasUvcDevice()) moveTaskToBack(true)
            } else {
                screenConsentDeclined = true
                resumeRecordingOnScreen = false
                showStatus("画面共有が拒否されました。通知から再度許可できます")
            }
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
        showStatus("同じネットワークのWindowsアプリを探しています…")
        receiverChooserShown = true
        Thread({
            val results = try { StreamHub.scanAll() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                discoveringWindows = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshPresentation()
                when (results.size) {
                    0 -> {
                        showStatus("Windowsアプリが見つかりません。同じWi-FiでMy Cast Receiverを起動してください")
                        windowsSettings()
                    }
                    1 -> {
                        val receiver = results.single()
                        StreamHub.enable()
                        StreamHub.connectVerified(receiver)
                        showStatus("Windowsアプリが見つかりました: " + receiver.name + " (" + receiver.host + ")")
                        if (!hasUvcDevice() && !ScreenStreamService.projectionActive) beginScreenConsent()
                    }
                    else -> {
                        val items = results.map { it.name + " (" + it.host + ":" + it.port + ")" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("受信機を" + results.size + "台発見（接続先を選択）")
                            .setItems(items) { _, index ->
                                val receiver = results[index]
                                StreamHub.enable()
                                StreamHub.connectVerified(receiver)
                                if (!hasUvcDevice() && !ScreenStreamService.projectionActive) beginScreenConsent()
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
                    showStatus("Windowsアプリへの接続を確認しています: " + host)
                    StreamHub.enable()
                    StreamHub.connectManual(host, number) { verified ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (verified) {
                                showStatus("Windowsアプリが見つかりました: " + host)
                                if (!hasUvcDevice() && !ScreenStreamService.projectionActive) beginScreenConsent()
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
