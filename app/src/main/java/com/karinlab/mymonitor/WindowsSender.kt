package com.karinlab.mymonitor

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** One in-flight frame, no growing latency queue. Wire: MMC7 + uint32 JPEG length + JPEG. */
class WindowsSender(private val report: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private val frames = ArrayBlockingQueue<Bitmap>(1)
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    fun isRunning() = running.get()
    fun offer(bitmap: Bitmap) {
        if (!running.get() || !frames.offer(bitmap)) bitmap.recycle()
    }
    @Synchronized fun start(host: String, port: Int) {
        if (worker?.isAlive == true) { report("前の転送を終了中です。少し待って再試行してください"); return }
        if (!running.compareAndSet(false, true)) return
        worker = Thread({
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                s.use {
                    val out = DataOutputStream(s.getOutputStream())
                    out.writeBytes("MMC7")
                    report("Windows接続済み・映像送信中")
                    while (running.get()) {
                        val frame = frames.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        try {
                            val bytes = ByteArrayOutputStream()
                            frame.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                            val jpeg = bytes.toByteArray()
                            out.writeInt(jpeg.size)
                            out.write(jpeg)
                            out.flush()
                        } finally { frame.recycle() }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) report("Windows転送失敗: ${e.message}")
            } finally {
                running.set(false)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                while (true) (frames.poll() ?: break).recycle()
            }
        }, "WindowsSender").also { it.start() }
    }
    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
    }
}
