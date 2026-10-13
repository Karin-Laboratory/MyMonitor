package com.karinlab.mymonitor

import android.content.Context
import java.nio.ByteBuffer

/**
 * Shared transport for USB MJPEG and Android MediaProjection JPEG.
 * The two producers never fight over a socket; UVC takes precedence.
 */
object StreamHub {
    @Volatile var uvcReady = false
        private set
    @Volatile var projectionReady = false
    @Volatile var uvcRecording = false
    @Volatile var selectedHost: String? = null
        private set
    @Volatile var enabled = true
        private set
    @Volatile var statusSink: ((String) -> Unit)? = null
    val sender = WindowsSender { message -> statusSink?.invoke(message) }
    val audio = PcmSender()
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        enabled = app.getSharedPreferences("monitor", Context.MODE_PRIVATE).getBoolean("autoStream", true)
    }
    fun setUvcReady(ready: Boolean) {
        uvcReady = ready
        statusSink?.invoke(if (ready) "UVC入力へ切替" else "Android画面へ切替")
    }
    @Synchronized fun connect(host: String, port: Int = 57007, remember: Boolean = true) {
        if (!enabled) return
        if (selectedHost != null && (selectedHost != host || !sender.isRunning())) {
            sender.stop()
            audio.stop()
        }
        selectedHost = host
        if (remember) app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
            .putString("windowsHost", host).putInt("windowsPort", port).apply()
        if (!sender.isRunning()) sender.start(host, port)
        if (!audio.isRunning()) audio.start(host)
    }
    fun enable() {
        enabled = true
        app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit().putBoolean("autoStream", true).apply()
    }
    fun stopStreaming() {
        enabled = false
        app.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit().putBoolean("autoStream", false).apply()
        shutdown()
    }
    /** Lifecycle cleanup must not silently disable auto-connect on next launch. */
    fun shutdown() {
        sender.stop()
        audio.stop()
        selectedHost = null
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
    /** Scan is slow: call from background thread only. */
    fun scanAndConnect(): Int {
        if (!enabled) return 0
        val receivers = try { ReceiverDiscovery.scan() } catch (_: Exception) { emptyList() }
        val prefs = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
        val saved = prefs.getString("windowsHost", null)
        val target = when {
            receivers.size == 1 -> receivers.first()
            receivers.size > 1 -> receivers.firstOrNull { it.host == saved }
            else -> null
        }
        if (target != null) connect(target.host, target.port, true)
        return receivers.size
    }
}
