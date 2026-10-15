package com.karinlab.mymonitor

import android.content.Context
import android.util.Log
import android.net.wifi.WifiManager
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * StreamHub is the ONLY connection/source arbiter. Do not duplicate saved-IP
 * selection, USB source priorities or receiver retry loops in an Activity.
 *
 * Priority: physically OPEN UVC > consented Android screen > needs permission.
 * Recording is independent of StreamHub.enabled and survives "送信停止".
 */
object StreamHub {
    @Volatile var controlArmed = false
        private set
    fun setControlArmed(value: Boolean) { controlArmed = value && source() == CapturePolicy.Source.SCREEN && projectionReady && enabled }
    fun disarmControl() { controlArmed = false }
    @Volatile var uvcReady = false
        private set
    @Volatile var projectionReady = false
        set(value) {
            if (field == value) return
            field = value
            controlArmed = false
            sender.discardPending()
            reconcileAsync()
        }
    @Volatile var uvcRecording = false
    @Volatile var selectedHost: String? = null
        private set
    @Volatile private var selectedPort = 57007
    @Volatile var enabled = true
        private set
    @Volatile var statusSink: ((String) -> Unit)? = null

    val sender = WindowsSender { statusSink?.invoke(it) }
    val audio = PcmSender()
    /** Locking suspends capture audio, not the ongoing MJPEG/video transport. */
    @Volatile private var playbackAudioAllowed = true

    fun suspendAudioForScreenLock() {
        playbackAudioAllowed = false
        reconcileAsync()
        Log.i("MyMonitorAudio", "Screen locked: audio TCP will close, video remains active")
    }

    fun resumeAudioAfterUnlock() {
        playbackAudioAllowed = true
        // A formerly live socket can be stale after a screen-lock audio-route
        // change. Force a fresh MMA1 TCP session so WinMM waveOut is reopened.
        executor.execute {
            audio.stop()
            audio.awaitStopped()
            reconcile()
            Log.i("MyMonitorAudio", "Screen unlocked: rebuilt Windows audio socket")
        }
    }
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MyMonitorConnection").apply { isDaemon = true }
    }
    private val discoveryLock = Any()
    private lateinit var app: Context
    @Volatile private var initialized = false
    private var activeHost: String? = null
    private var activePort = 0
    private var lastSubnetSweepMs = -100_000L

    @Synchronized fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        enabled = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            .getBoolean("autoStream", true)
        initialized = true
    }

    fun source() = CapturePolicy.source(uvcReady, projectionReady)
    fun needsConsent(): Boolean = CapturePolicy.needsConsent(
        selectedHost != null, enabled, uvcReady, projectionReady)

    fun setUvcReady(value: Boolean) {
        if (uvcReady == value) return
        uvcReady = value
        controlArmed = false
        sender.discardPending()
        statusSink?.invoke(if (value) "USBカメラの映像に切り替えました" else
            if (projectionReady) "スマホ画面へ自動復帰" else "UVC切断。スマホ画面の共有許可が必要")
        reconcileAsync()
    }

    fun enable() {
        enabled = true
        app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
            .putBoolean("autoStream", true).apply()
        reconcileAsync()
    }

    private fun selectVerified(host: String, port: Int, remember: Boolean) {
        if (!enabled) return
        val changed = host != selectedHost || port != selectedPort
        selectedHost = host
        selectedPort = port
        if (remember) app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
            .putString("windowsHost", host).putInt("windowsPort", port).apply()
        if (changed) statusSink?.invoke("Windowsアプリが見つかりました: " + host +
            if (source() == CapturePolicy.Source.AWAITING_SCREEN_PERMISSION) " (画面許可待ち)" else " (映像入力待ち)")
        reconcileAsync()
    }

    fun connectVerified(receiver: ReceiverDiscovery.Receiver, remember: Boolean = true) {
        // ReceiverDiscovery only emits TCP-verified candidates.
        selectVerified(receiver.host, receiver.port, remember)
    }

    fun connectManual(host: String, port: Int, callback: (Boolean) -> Unit) {
        executor.execute {
            val verified = ReceiverDiscovery.probe(host, port)
            if (verified && enabled) selectVerified(host, port, true)
            else statusSink?.invoke("Windowsアプリに接続できません: $host:$port")
            callback(verified && enabled)
        }
    }

    /**
     * 016: known-good Windows IPv4 is actively VERIFIED first on TCP. This
     * solves Wi-Fi networks blocking UDP broadcast even after manual pairing.
     * If unavailable, V2 nonce broadcast + TCP validation is still attempted.
     */
    private fun <T> withDiscoveryWifiLock(block: () -> T): T {
        val lock = try {
            val manager = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            manager?.createMulticastLock("MyMonitorDiscovery016")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { null }
        return try { block() } finally {
            try { if (lock?.isHeld == true) lock.release() } catch (_: Exception) { }
        }
    }

    fun scanAndConnect(): Int {
        if (!enabled) return 0
        synchronized(discoveryLock) {
            if (!enabled) return 0
            // The single-thread Windows receiver cannot accept an identity
            // probe while the existing MJPEG connection is open.
            if (selectedHost != null && sender.hasTransportConnection()) return 1
            val prefs = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            val saved = prefs.getString("windowsHost", null)
            val savedPort = prefs.getInt("windowsPort", 57007)
            val now = android.os.SystemClock.elapsedRealtime()
            val allowSubnet = now - lastSubnetSweepMs >= 90_000L
            if (allowSubnet) lastSubnetSweepMs = now
            val choices = withDiscoveryWifiLock {
                ReceiverDiscovery.scan(saved, savedPort,
                    allowSubnetFallback = allowSubnet)
            }
            val target = CapturePolicy.choose(choices, saved) { it.host }
            if (target != null) {
                selectVerified(target.host, target.port, true)
                return choices.size
            }
            // Do not silently connect to stale memory. Try again on next loop.
            if (selectedHost != null) {
                selectedHost = null
                reconcileAsync()
            }
            return choices.size
        }
    }

    /** Explicit picker can list all available receivers, not just remembered. */
    fun scanAll(): List<ReceiverDiscovery.Receiver> {
        val prefs = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
        val connected = selectedHost
        if (connected != null && sender.isRunning()) {
            // Windows video receiver handles one TCP client at a time, so
            // probing it while streaming would falsely mark it unavailable.
            val active = ReceiverDiscovery.Receiver(connected, selectedPort, "接続中のWindows")
            return listOf(active) + withDiscoveryWifiLock {
                ReceiverDiscovery.scanAll()
            }.filter { it.host != connected }
        }
        return withDiscoveryWifiLock {
            ReceiverDiscovery.scanAll(prefs.getString("windowsHost", null),
                prefs.getInt("windowsPort", 57007), allowSubnetFallback = true)
        }
    }

    private fun reconcileAsync() { executor.execute { reconcile() } }

    private fun reconcile() {
        val host = selectedHost
        val shouldSend = enabled && host != null &&
            source() != CapturePolicy.Source.AWAITING_SCREEN_PERMISSION
        if (!shouldSend) {
            sender.stop()
            sender.awaitStopped()
            audio.stop()
            audio.awaitStopped()
            activeHost = null
            activePort = 0
            return
        }
        if (activeHost != host || activePort != selectedPort) {
            sender.stop()
            sender.awaitStopped()
            audio.stop()
            audio.awaitStopped()
            sender.discardPending()
            activeHost = null
            activePort = 0
        }
        if (!sender.isRunning()) {
            sender.awaitStopped()
            sender.start(host!!, selectedPort)
            activeHost = host
            activePort = selectedPort
        }
        if (source() == CapturePolicy.Source.SCREEN && playbackAudioAllowed) {
            if (!audio.isRunning()) {
                audio.awaitStopped()
                audio.start(host!!)
            }
        } else if (audio.isRunning()) {
            audio.stop()
            audio.awaitStopped()
        }
    }

    fun stopStreaming() {
        controlArmed = false
        enabled = false
        app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
            .putBoolean("autoStream", false).apply()
        shutdown()
    }

    fun shutdown() {
        controlArmed = false
        selectedHost = null
        executor.execute {
            sender.stop()
            sender.awaitStopped()
            sender.discardPending()
            audio.stop()
            audio.awaitStopped()
            activeHost = null
            activePort = 0
            Log.i("MyMonitorState", "Streaming stopped; recording unaffected")
        }
    }

    fun offerUvc(frame: ByteBuffer) {
        if (enabled && source() == CapturePolicy.Source.UVC) sender.offerJpeg(frame)
    }
    fun offerUvcNv21(bytes: ByteArray, w: Int, h: Int) {
        if (enabled && source() == CapturePolicy.Source.UVC) sender.offer(bytes, w, h)
    }
    fun offerScreen(jpeg: ByteArray) {
        if (enabled && source() == CapturePolicy.Source.SCREEN)
            sender.offerJpeg(ByteBuffer.wrap(jpeg))
    }
    fun offerAudio(pcm: ByteArray, size: Int) {
        if (enabled && source() == CapturePolicy.Source.SCREEN) audio.offer(pcm, size)
    }
}
