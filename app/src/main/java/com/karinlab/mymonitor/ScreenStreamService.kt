package com.karinlab.mymonitor

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * User-initiated foreground service: discovers receivers, streams screen + internal
 * playback audio, and controls screen MP4 recording. One MediaProjection token is
 * used for exactly ONE VirtualDisplay; rotation resizes that VirtualDisplay.
 */
class ScreenStreamService : Service() {
    companion object {
        const val CHANNEL = "mymonitor_cast"
        const val ACTION_START = "com.karinlab.mymonitor.START"
        const val ACTION_PROJECTION = "com.karinlab.mymonitor.PROJECTION"
        const val ACTION_RECORD = "com.karinlab.mymonitor.RECORD"
        const val ACTION_STOP_STREAM = "com.karinlab.mymonitor.STOP_STREAM"
        const val ACTION_RESUME_STREAM = "com.karinlab.mymonitor.RESUME_STREAM"
        const val ACTION_SOURCE_CHANGED = "com.karinlab.mymonitor.SOURCE_CHANGED"
        const val ACTION_STOP_SCREEN_RECORD = "com.karinlab.mymonitor.STOP_SCREEN_RECORD"
        const val ACTION_EXIT = "com.karinlab.mymonitor.EXIT"
        const val ACTION_FINISH_UI = "com.karinlab.mymonitor.FINISH_UI"
        @Volatile var screenRecording = false
            private set
        @Volatile var projectionActive = false
            private set
    }
    private val notificationId = 1301
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var audioThread: Thread? = null
    @Volatile private var captureAudio = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioSuspendedForLock = false
    private var audioRetryCount = 0
    private var lockReceiverRegistered = false
    private val lockStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseAudioForLock()
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON ->
                    main.postDelayed({ restoreAudioAfterUnlock() }, 350)
            }
        }
    }
    private var recorder: ScreenRecorder? = null
    private var width = 0
    private var height = 0
    private val physicalDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                frameHandler?.post { reconcileCaptureGeometry("physical display changed") }
            }
        }
    }
    private var physicalDisplayListenerRegistered = false
    private val alive = java.util.concurrent.atomic.AtomicBoolean(true)
    private val main = Handler(Looper.getMainLooper())
    private var lastNotification = ""
    private var lastReceiverCount = 0
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val uptime = android.os.SystemClock.elapsedRealtime()
    private val powerHandler = Handler(Looper.getMainLooper())
    private val powerPoll = object : Runnable {
        override fun run() {
            if (!alive.get()) return
            refreshPowerLock()
            powerHandler.postDelayed(this, 10000)
        }
    }

    /** Hold CPU awake only during an active capture/stream or recording.
     * This does not override Android foreground-service limits or force-stop. */
    private fun refreshPowerLock() {
        val active = projectionActive || StreamHub.uvcReady || screenRecording || StreamHub.uvcRecording
        if (active) {
            try {
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                val lock = screenWakeLock ?: power.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "MyMonitor020:ActiveCapture").apply {
                    setReferenceCounted(false)
                    screenWakeLock = this
                }
                // Refresh the timeout every ten seconds; never leave a five
                // second CPU-sleep gap while a cast is actively running.
                lock.acquire(20000)
            } catch (e: Exception) {
                android.util.Log.w("MyMonitorPower", "WakeLock acquire failed", e)
            }
        } else if (!active && screenWakeLock?.isHeld == true) {
            try { screenWakeLock?.release() } catch (_: Exception) { }
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        StreamHub.init(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "MyMonitor キャスト", NotificationManager.IMPORTANCE_LOW))
        // connectedDevice is the appropriate ongoing LAN/USB receiver interaction.
        // dataSync is not a perpetual live-stream foreground-service category.
        startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        powerHandler.post(powerPoll)
        registerReceiver(lockStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        lockReceiverRegistered = true
        android.util.Log.i("MyMonitorPower", "FGS started (elapsed uptime=$uptime)")
        Thread({
            while (alive.get()) {
                try {
                    if (StreamHub.enabled) lastReceiverCount = StreamHub.scanAndConnect()
                    refreshNotification()
                } catch (e: Exception) {
                    android.util.Log.w("MyMonitorState", "Discovery loop: " + e.message)
                }
                try { Thread.sleep(6000) } catch (_: InterruptedException) { break }
            }
        }, "CastDiscovery").apply { isDaemon = true; start() }
    }

    private fun intentForActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = if (!StreamHub.uvcReady && !projectionActive)
                "com.karinlab.mymonitor.GRANT_SCREEN" else "com.karinlab.mymonitor.OPEN"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, 11, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(action: String, id: Int): PendingIntent =
        PendingIntent.getService(this, id, Intent(this, ScreenStreamService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(text: String): Notification {
        val recordIntent = if (StreamHub.uvcReady) {
            PendingIntent.getActivity(this, 24,
                Intent(this, MainActivity::class.java).apply {
                    action = "com.karinlab.mymonitor.UVC_RECORD"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else serviceAction(ACTION_RECORD, 21)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setContentTitle("MyMonitor 019")
            .setContentText(text)
            .setContentIntent(intentForActivity())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (recorder?.isRecording() == true || StreamHub.uvcRecording) "録画停止" else "● 録画",
                recordIntent).build())
            .addAction(Notification.Action.Builder(null,
                if (StreamHub.enabled) "送信停止" else "送信再開",
                serviceAction(if (StreamHub.enabled) ACTION_STOP_STREAM else ACTION_RESUME_STREAM, 22)).build())
            .addAction(Notification.Action.Builder(null, "終了", serviceAction(ACTION_EXIT, 23)).build())
        return builder.build()
    }

    private fun startForegroundWithType(type: Int) {
        val message = "受信機を検索中。画面共有には許可が必要"
        lastNotification = message
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(notificationId, notification(message), type)
        } else if (Build.VERSION.SDK_INT >= 29 &&
            type and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0) {
            startForeground(notificationId, notification(message),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(notificationId, notification(message))
        }
    }

    private fun notifyState(message: String, force: Boolean = false) {
        if (message == lastNotification && !force) return
        lastNotification = message
        getSystemService(NotificationManager::class.java).notify(notificationId, notification(message))
    }

    private fun refreshNotification() {
        val current = when {
            screenRecording -> "● Android画面を録画中" +
                if (StreamHub.enabled && StreamHub.sender.isConnected()) "・送信中" else ""
            StreamHub.uvcRecording -> "● UVC映像を録画中"
            !StreamHub.enabled -> "送信停止中（録画は利用できます）"
            StreamHub.uvcReady && StreamHub.sender.isConnected() -> "UVC映像をWindowsへ送信中"
            StreamHub.uvcReady -> "UVC表示中・受信機を検索中"
            projectionActive && StreamHub.sender.isConnected() -> "Android画面と内部音声を送信中"
            projectionActive -> "画面共有中・Windows受信機を検索中"
            lastReceiverCount > 1 && StreamHub.selectedHost == null -> "受信機が複数。アプリで選んでください"
            StreamHub.selectedHost != null -> "受信機確認済み。タップして画面共有を許可"
            else -> "受信機を検索中。必要ならWindowsのIPを手入力"
        }
        notifyState(current)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROJECTION -> {
                val result = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val token = intent.getParcelableExtra<Intent>("projectionData")
                if (result == Activity.RESULT_OK && token != null) startCapture(result, token)
            }
            ACTION_RECORD -> {
                if (StreamHub.uvcReady) {
                    val activity = Intent(this, MainActivity::class.java).apply {
                        action = "com.karinlab.mymonitor.UVC_RECORD"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(activity)
                } else toggleScreenRecord()
            }
            ACTION_STOP_STREAM -> {
                StreamHub.stopStreaming()
                refreshNotification()
            }
            ACTION_RESUME_STREAM -> {
                StreamHub.enable()
                Thread({ lastReceiverCount = StreamHub.scanAndConnect(); refreshNotification() },
                    "ManualResumeDiscovery").start()
                refreshNotification()
            }
            ACTION_SOURCE_CHANGED -> {
                // USB open/close changes notification actions and video source.
                refreshPowerLock()
                refreshNotification()
            }
            ACTION_STOP_SCREEN_RECORD -> {
                if (recorder != null) stopScreenRecord()
            }
            ACTION_EXIT -> {
                // Stop both the projection service and any foreground UVC
                // Activity. Merely stopSelf() left the UVC app open in v015.
                sendBroadcast(Intent(ACTION_FINISH_UI).setPackage(packageName))
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Unit
        }
        return START_STICKY
    }

    private fun optimal(w: Int, h: Int): Pair<Int, Int> {
        val long = maxOf(w, h).coerceAtLeast(2)
        val short = min(w, h).coerceAtLeast(2)
        val ratio = min(1.0, min(1920.0 / long, 1080.0 / short))
        return ((w * ratio).toInt() and -2).coerceAtLeast(2) to
            ((h * ratio).toInt() and -2).coerceAtLeast(2)
    }

    private fun screenSize(): Pair<Int, Int> {
        val point = Point()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(point)
        return optimal(point.x, point.y)
    }

    private fun startCapture(code: Int, data: Intent) {
        // Only one projection per consent token; never call createVirtualDisplay twice.
        if (projectionActive) return
        try {
            startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captured = pm.getMediaProjection(code, data) ?: error("画面共有許可を取得できません")
            projection = captured
            frameThread = HandlerThread("MyMonitorScreenFrames").also { it.start() }
            frameHandler = Handler(frameThread!!.looper)
            captured.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { closeProjection() } }
                override fun onCapturedContentResize(w: Int, h: Int) {
                    // A captured app's bounds need not match the physical display.
                    frameHandler?.post { reconcileCaptureGeometry("content callback") }
                }
            }, frameHandler)
            val size = screenSize()
            width = size.first
            height = size.second
            reader = createReader(width, height)
            val density = resources.displayMetrics.densityDpi
            display = captured.createVirtualDisplay("MyMonitor019", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, frameHandler)
            projectionActive = true
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(physicalDisplayListener, frameHandler)
            physicalDisplayListenerRegistered = true
            StreamHub.projectionReady = true
            refreshPowerLock()
            notifyState("画面共有を開始。Windows受信機へ自動接続")
            startAudio(captured)
        } catch (e: Exception) {
            notifyState("画面共有開始失敗: " + e.message)
            closeProjection()
        }
    }

    private fun createReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source -> processImage(source) }, frameHandler)
        }

    private fun reconcileCaptureGeometry(reason: String) {
        if (!projectionActive) return
        val expected = screenSize()
        if (expected.first != width || expected.second != height) {
            android.util.Log.i("MyMonitor-Preview",
                "Screen capture geometry ${width}x${height} -> ${expected.first}x${expected.second}: $reason")
            resizeCapture(expected.first, expected.second)
        }
    }

    private fun resizeCapture(w: Int, h: Int) {
        if (!projectionActive || w <= 0 || h <= 0) return
        val size = screenSize()
        if (size.first == width && size.second == height) return
        val old = reader
        width = size.first; height = size.second
        val next = createReader(width, height)
        display?.resize(width, height, resources.displayMetrics.densityDpi)
        display?.surface = next.surface
        reader = next
        old?.setOnImageAvailableListener(null, null)
        old?.close()
        if (recorder?.isRecording() == true) {
            recorder?.stop()
            recorder = null
            beginScreenRecord()
        }
    }

    private var lastSizeCheckMs = 0L

    private fun processImage(source: ImageReader) {
        if (source !== reader) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSizeCheckMs > 500L) {
            lastSizeCheckMs = now
            reconcileCaptureGeometry("frame watchdog")
        }
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            // Recording must continue after "送信停止" or when no Windows PC
            // is present. The network queue is optional; the MP4 is independent.
            if (StreamHub.uvcReady ||
                (!StreamHub.sender.isRunning() && recorder?.isRecording() != true)) return
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (pixelStride != 4) return
            val paddedW = rowStride / pixelStride
            val padded = Bitmap.createBitmap(paddedW, image.height, Bitmap.Config.ARGB_8888)
            val data = plane.buffer.duplicate()
            data.rewind()
            try {
                padded.copyPixelsFromBuffer(data)
            } catch (_: java.nio.BufferUnderflowException) {
                // Some GPUs omit unused padding after the final row.
                val paddedBytes = ByteArray(rowStride * image.height)
                data.rewind()
                data.get(paddedBytes, 0, minOf(data.remaining(), paddedBytes.size))
                padded.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(paddedBytes))
            }
            val bitmap = if (paddedW == image.width) padded else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
            }
            if (recorder?.isRecording() == true) recorder?.offerFrame(bitmap)
            if (StreamHub.enabled && StreamHub.sender.isRunning()) {
                val output = ByteArrayOutputStream(320_000)
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, 78, output))
                    StreamHub.offerScreen(output.toByteArray())
            }
            bitmap.recycle()
        } catch (_: Exception) {
            // Never let a single malformed graphics buffer terminate the projection.
        } finally { image.close() }
    }

    /**
     * AudioPlaybackCapture is tied to MediaProjection, but its AudioRecord
     * may become unusable after the handset locks. The screen-off event stops
     * only the audio session. On unlock we recreate AudioRecord and the TCP
     * MMA1 sender, leaving MediaProjection video and its consent token alone.
     */
    private fun pauseAudioForLock() {
        if (audioSuspendedForLock) return
        audioSuspendedForLock = true
        StreamHub.suspendAudioForScreenLock()
        stopAudioCapture()
        android.util.Log.i("MyMonitorAudio", "screen OFF: AudioRecord released (video remains)")
    }

    private fun restoreAudioAfterUnlock() {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceLocked) return
        if (!audioSuspendedForLock) return
        audioSuspendedForLock = false
        audioRetryCount = 0
        if (projectionActive && projection != null) {
            stopAudioCapture()
            startAudio(projection!!)
            // A new MMA1 TCP connection makes Windows re-open waveOut as well.
            StreamHub.resumeAudioAfterUnlock()
            android.util.Log.i("MyMonitorAudio",
                "user unlocked: recreated AudioRecord and Windows PCM TCP session")
            notifyState(if (audioRecord != null) "ロック解除後の内部音声を再開"
                else "画面共有は継続中。内部音声の再開に失敗しました", true)
        } else {
            // OS terminated the projection session: consent cannot be reused.
            StreamHub.resumeAudioAfterUnlock()
            android.util.Log.w("MyMonitorAudio",
                "unlock: MediaProjection not valid; new user consent required")
            notifyState("ロック解除後、画面共有の再許可が必要です。通知をタップ", true)
        }
    }

    private fun stopAudioCapture() {
        captureAudio = false
        val old = audioRecord
        audioRecord = null
        try { old?.stop() } catch (_: Exception) { }
        try { old?.release() } catch (_: Exception) { }
        audioThread?.interrupt()
        audioThread = null
    }

    private fun recoverAudioAfterFailure(failed: AudioRecord, code: Int) {
        // A deliberate screen-lock stop also makes read() fail; do not start a
        // new recorder while the phone remains locked or the projection ended.
        if (audioRecord !== failed || !projectionActive || audioSuspendedForLock) return
        android.util.Log.w("MyMonitorAudio",
            "AudioRecord.read failed (" + code + "), recovery attempt " + (audioRetryCount + 1))
        stopAudioCapture()
        if (audioRetryCount++ >= 3) {
            notifyState("内部音声の再開に失敗。画面共有を再許可してください", true)
            return
        }
        main.postDelayed({
            val activeProjection = projection
            if (projectionActive && !audioSuspendedForLock && activeProjection != null) {
                startAudio(activeProjection)
                StreamHub.resumeAudioAfterUnlock()
            }
        }, 800L * audioRetryCount)
    }

    private fun startAudio(captured: MediaProjection) {
        if (!projectionActive || audioSuspendedForLock) return
        if (Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notifyState("映像送信中（内部音声は録音権限が必要）")
            return
        }
        if (audioRecord != null) return
        var pending: AudioRecord? = null
        try {
            val audioConfig = AudioPlaybackCaptureConfiguration.Builder(captured)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT)
            require(minSize > 0) { "invalid AudioRecord buffer size " + minSize }
            val record = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setAudioPlaybackCaptureConfig(audioConfig)
                .setBufferSizeInBytes(maxOf(minSize, 3840 * 4))
                .build()
            pending = record
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord was not initialized"
            }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter RECORDING state"
            }
            audioRecord = record
            pending = null
            captureAudio = true
            audioThread = Thread({
                val pcm = ByteArray(3840)
                while (captureAudio && projectionActive && audioRecord === record) {
                    val n = try {
                        record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    } catch (e: Exception) {
                        android.util.Log.w("MyMonitorAudio", "AudioRecord read exception", e)
                        AudioRecord.ERROR_DEAD_OBJECT
                    }
                    if (!captureAudio || audioRecord !== record) break
                    if (n < 0) {
                        main.post { recoverAudioAfterFailure(record, n) }
                        break
                    }
                    if (n > 0 && !StreamHub.uvcReady) {
                        StreamHub.offerAudio(pcm, n)
                        recorder?.offerAudio(pcm, n)
                    }
                }
            }, "MyMonitorPlaybackAudio").also { it.isDaemon = true; it.start() }
            android.util.Log.i("MyMonitorAudio", "AudioRecord started: 48kHz stereo PCM16")
        } catch (e: Exception) {
            captureAudio = false
            if (audioRecord === pending) audioRecord = null
            try { pending?.release() } catch (_: Exception) {}
            android.util.Log.e("MyMonitorAudio", "AudioPlaybackCapture start failed", e)
            notifyState("内部音声取得不可: " + e.message)
        }
    }

    private fun beginScreenRecord() {
        if (!projectionActive || width <= 0 || height <= 0) {
            notifyState("画面共有許可後に録画できます")
            return
        }
        val rec = ScreenRecorder(this, audioRecord != null) { file, error ->
            if (file != null) {
                Storage.save(applicationContext, file, "video/mp4",
                    getSharedPreferences("monitor", MODE_PRIVATE)) { msg -> notifyState(msg) }
            } else notifyState(error ?: "録画保存に失敗")
        }
        if (rec.start(width, height)) {
            recorder = rec
            screenRecording = true
            notifyState("● Android画面を録画中", true)
        }
    }

    private fun stopScreenRecord() {
        recorder?.stop()
        recorder = null
        screenRecording = false
        notifyState("録画停止。ファイルを保存中", true)
    }

    private fun toggleScreenRecord() {
        if (recorder?.isRecording() == true) stopScreenRecord()
        else beginScreenRecord()
    }

    private fun closeProjection() {
        if (physicalDisplayListenerRegistered) {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(physicalDisplayListener)
            physicalDisplayListenerRegistered = false
        }
        projectionActive = false
        StreamHub.projectionReady = false
        refreshPowerLock()
        stopAudioCapture()
        if (recorder != null) stopScreenRecord()
        screenRecording = false
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        display?.release()
        display = null
        val old = projection
        projection = null
        try { old?.stop() } catch (_: Exception) { }
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
        if (alive.get()) notifyState("画面共有が終了しました。通知から再許可してください", true)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing an Activity task must not intentionally shut down a
        // user-started ongoing cast. Android still controls process lifecycle.
        android.util.Log.i("MyMonitorPower", "Task removed, foreground service remains active")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        android.util.Log.w("MyMonitorPower", "Memory pressure=$level uptime=" +
            (android.os.SystemClock.elapsedRealtime() - uptime) + "ms")
    }

    override fun onDestroy() {
        android.util.Log.w("MyMonitorPower", "Service stopped uptime=" +
            (android.os.SystemClock.elapsedRealtime() - uptime) + "ms")
        alive.set(false)
        if (lockReceiverRegistered) {
            try { unregisterReceiver(lockStateReceiver) } catch (_: Exception) { }
            lockReceiverRegistered = false
        }
        powerHandler.removeCallbacks(powerPoll)
        try { if (screenWakeLock?.isHeld == true) screenWakeLock?.release() } catch (_: Exception) { }
        closeProjection()
        StreamHub.shutdown()
        super.onDestroy()
    }
}
