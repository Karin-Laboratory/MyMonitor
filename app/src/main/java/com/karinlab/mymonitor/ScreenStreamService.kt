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
    private var recorder: ScreenRecorder? = null
    private var width = 0
    private var height = 0
    private val alive = java.util.concurrent.atomic.AtomicBoolean(true)
    private val main = Handler(Looper.getMainLooper())
    private var lastNotification = ""
    private var lastReceiverCount = 0

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        StreamHub.init(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "MyMonitor キャスト", NotificationManager.IMPORTANCE_LOW))
        startForegroundWithType(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
            .setContentTitle("MyMonitor 016")
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
        if (Build.VERSION.SDK_INT >= 29) startForeground(notificationId, notification(message), type)
        else startForeground(notificationId, notification(message))
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
                refreshNotification()
            }
            ACTION_STOP_SCREEN_RECORD -> {
                if (recorder != null) stopScreenRecord()
            }
            ACTION_EXIT -> { stopSelf(); return START_NOT_STICKY }
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captured = pm.getMediaProjection(code, data) ?: error("画面共有許可を取得できません")
            projection = captured
            frameThread = HandlerThread("MyMonitorScreenFrames").also { it.start() }
            frameHandler = Handler(frameThread!!.looper)
            captured.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { closeProjection() } }
                override fun onCapturedContentResize(w: Int, h: Int) {
                    frameHandler?.post { resizeCapture(w, h) }
                }
            }, frameHandler)
            val size = screenSize()
            width = size.first
            height = size.second
            reader = createReader(width, height)
            val density = resources.displayMetrics.densityDpi
            display = captured.createVirtualDisplay("MyMonitor016", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, frameHandler)
            projectionActive = true
            StreamHub.projectionReady = true
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

    private fun resizeCapture(w: Int, h: Int) {
        if (!projectionActive || w <= 0 || h <= 0) return
        val size = optimal(w, h)
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

    private fun processImage(source: ImageReader) {
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

    private fun startAudio(captured: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notifyState("映像送信中（内部音声は録音権限が必要）")
            return
        }
        try {
            val audioConfig = AudioPlaybackCaptureConfiguration.Builder(captured)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setAudioPlaybackCaptureConfig(audioConfig)
                .setBufferSizeInBytes(maxOf(minSize, 3840 * 4))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                notifyState("内部音声を初期化できません")
                return
            }
            audioRecord = record
            captureAudio = true
            record.startRecording()
            audioThread = Thread({
                val pcm = ByteArray(3840) // 20ms 48kHz stereo PCM16
                while (captureAudio && projectionActive) {
                    val n = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (n > 0 && !StreamHub.uvcReady) {
                        StreamHub.offerAudio(pcm, n)
                        recorder?.offerAudio(pcm, n)
                    }
                }
            }, "MyMonitorPlaybackAudio").also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
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
        projectionActive = false
        StreamHub.projectionReady = false
        captureAudio = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
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

    override fun onDestroy() {
        alive.set(false)
        closeProjection()
        StreamHub.shutdown()
        super.onDestroy()
    }
}
