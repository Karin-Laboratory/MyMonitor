package com.karinlab.mymonitor

import android.content.Context
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Single authority for selection and stream lifecycle. Auto discovery MUST pass
 * nonce + TCP receiver verification; an old saved IP is never trusted blindly.
 *
 * Do not open a streaming TCP connection until a real UVC/MediaProjection source
 * is ready, otherwise the idle receiver times out during permission prompts.
 */
object StreamHub {
    @Volatile var uvcReady = false
        private set
    @Volatile var projectionReady = false
        set(value) {
            field = value
            reconcileAsync()
        }
    @Volatile var uvcRecording = false
    @Volatile var selectedHost: String? = null
        private set
    @Volatile private var selectedPort = 57007
    @Volatile var enabled = true
        private set
    @Volatile var statusSink: ((String) -> Unit)? = null

    val sender = WindowsSender { message -> statusSink?.invoke(message) }
    val audio = PcmSender()
    private lateinit var app: Context
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MyMonitorConnection").apply { isDaemon = true }
    }
    private var activeHost: String? = null
    private var activePort = 0
    @Volatile private var lastVerifiedAt = 0L

    fun init(context: Context) {
        app = context.applicationContext
        enabled = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            .getBoolean("autoStream", true)
    }

    fun setUvcReady(ready: Boolean) {
        if (uvcReady == ready) return
        uvcReady = ready
        statusSink?.invoke(if (ready) "UVC入力へ切替" else "Android画面へ切替")
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
        val change = selectedHost != host || selectedPort != port
        selectedHost = host
        selectedPort = port
        lastVerifiedAt = SystemClock.elapsedRealtime()
        if (remember) {
            app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
                .putString("windowsHost", host).putInt("windowsPort", port).apply()
        }
        if (change) statusSink?.invoke("My Cast Receiver確認済み: " + host + "（映像入力待ち）")
        reconcileAsync()
    }

    /** This API accepts ONLY entries returned by ReceiverDiscovery.scan(). */
    fun connectVerified(receiver: ReceiverDiscovery.Receiver, remember: Boolean = true) {
        selectVerified(receiver.host, receiver.port, remember)
    }

    /**
     * Manual IP entry is not trusted: TCP-probe it on a worker, and notify
     * the activity of the result. Never persist/stream to an unrelated host.
     */
    fun connectManual(host: String, port: Int, callback: (Boolean) -> Unit) {
        executor.execute {
            val ok = ReceiverDiscovery.probe(host, port)
            if (ok && enabled) selectVerified(host, port, true)
            else statusSink?.invoke(host + " はMy Cast Receiverとして確認できません。受信EXEを起動してください")
            callback(ok && enabled)
        }
    }

    fun scanAndConnect(): Int {
        if (!enabled) return 0
        // An active connection owns the video TCP listener, so another TCP
        // probe would be queued behind it. Never churn a working session.
        if (selectedHost != null && sender.isRunning()) return 1
        // A verified choice remains selected while the OS consent dialog runs.
        if (selectedHost != null && !(uvcReady || projectionReady) &&
            SystemClock.elapsedRealtime() - lastVerifiedAt < 30000L) return 1
        val receivers = try { ReceiverDiscovery.scan() } catch (_: Exception) { emptyList() }
        val preference = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            .getString("windowsHost", null)
        val target = when {
            receivers.size == 1 -> receivers.first()
            receivers.size > 1 -> receivers.firstOrNull { it.host == preference }
            else -> null
        }
        if (target != null) selectVerified(target.host, target.port, true)
        else if (receivers.isEmpty() && selectedHost != null && !sender.isRunning()) {
            // A stale remembered IP is NOT a proof that receiver exists.
            selectedHost = null
            statusSink?.invoke("確認できるWindows受信機がありません")
        }
        return receivers.size
    }

    private fun reconcileAsync() {
        executor.execute { reconcile() }
    }

    private fun reconcile() {
        val host = selectedHost
        val streaming = enabled && host != null && (uvcReady || projectionReady)
        if (!streaming) {
            if (sender.isRunning()) sender.stop()
            sender.awaitStopped()
            audio.stop()
            activeHost = null
            activePort = 0
            return
        }
        if (activeHost != host || activePort != selectedPort) {
            sender.stop()
            sender.awaitStopped()
            audio.stop()
            activeHost = null
            activePort = 0
        }
        if (!sender.isRunning()) {
            sender.awaitStopped()
            sender.start(host!!, selectedPort)
            activeHost = host
            activePort = selectedPort
        }
        if (projectionReady && !uvcReady) {
            if (!audio.isRunning()) audio.start(host!!)
        } else if (audio.isRunning()) audio.stop()
    }

    fun stopStreaming() {
        enabled = false
        app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
            .putBoolean("autoStream", false).apply()
        shutdown()
    }
    /** Stop process transport but do not permanently disable future auto-start. */
    fun shutdown() {
        selectedHost = null
        executor.execute {
            sender.stop()
            sender.awaitStopped()
            audio.stop()
            activeHost = null
            activePort = 0
        }
    }
    fun offerUvc(frame: ByteBuffer) {
        if (enabled && uvcReady) sender.offerJpeg(frame)
    }
    fun offerUvcNv21(bytes: ByteArray, w: Int, h: Int) {
        if (enabled && uvcReady) sender.offer(bytes, w, h)
    }
    fun offerScreen(jpeg: ByteArray) {
        if (enabled && !uvcReady && projectionReady) sender.offerJpeg(ByteBuffer.wrap(jpeg))
    }
    fun offerAudio(pcm: ByteArray, size: Int) {
        if (enabled && !uvcReady && projectionReady) audio.offer(pcm, size)
    }
}
