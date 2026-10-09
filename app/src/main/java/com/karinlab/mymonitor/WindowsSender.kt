package com.karinlab.mymonitor

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** MMC9 wire protocol, one in-flight frame. Native MJPEG preferred over NV21 fallback. */
class WindowsSender(private val report: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private data class Frame(val bytes: ByteArray, val width: Int, val height: Int, val jpeg: Boolean)
    private val frames = ArrayBlockingQueue<Frame>(1)
    private var lastOffered = 0L
    @Volatile private var lastMjpeg = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null

    fun isRunning() = running.get()

    /** Copy synchronously: native DirectByteBuffer is invalid once callback returns. */
    @Synchronized fun offerJpeg(buffer: ByteBuffer) {
        if (!running.get()) return
        val length = buffer.remaining()
        if (length < 100 || length > 8 * 1024 * 1024) return
        val now = SystemClock.elapsedRealtime()
        val pos = buffer.position()
        if (buffer.get(pos).toInt() and 255 != 0xff ||
            buffer.get(pos + 1).toInt() and 255 != 0xd8) return
        lastMjpeg = now
        if (now - lastOffered < 33L) return
        val data = ByteArray(length)
        buffer.get(data)
        lastOffered = now
        frames.poll()
        frames.offer(Frame(data, 0, 0, true))
    }

    /** Fallback when USB device does not send MJPEG. */
    @Synchronized fun offer(data: ByteArray, width: Int, height: Int) {
        if (!running.get() || width <= 0 || height <= 0 || width > 4096 || height > 4096) return
        if (data.size != width * height * 3 / 2) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMjpeg < 1500L || now - lastOffered < 33L) return
        lastOffered = now
        frames.poll()
        frames.offer(Frame(data.copyOf(), width, height, false))
    }

    @Synchronized fun start(host: String, port: Int) {
        if (worker?.isAlive == true) { report("前の転送を終了中です"); return }
        if (!running.compareAndSet(false, true)) return
        lastMjpeg = 0L
        lastOffered = 0L
        frames.clear()
        worker = Thread({
            try {
                val s = Socket()
                socket = s
                s.sendBufferSize = 64 * 1024
                s.soTimeout = 2000
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                s.use {
                    val out = DataOutputStream(s.getOutputStream())
                    val input = s.getInputStream()
                    out.writeBytes("MMC9")
                    report("Windows接続済み・映像送信中（MJPEG優先）")
                    while (running.get()) {
                        var frame = frames.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        while (true) { frame = frames.poll() ?: break }
                        val jpeg = if (frame.jpeg) frame.bytes else {
                            val bytes = ByteArrayOutputStream()
                            check(YuvImage(frame.bytes, ImageFormat.NV21, frame.width, frame.height, null)
                                .compressToJpeg(Rect(0, 0, frame.width, frame.height), 75, bytes))
                            bytes.toByteArray()
                        }
                        out.writeInt(jpeg.size)
                        out.write(jpeg)
                        out.flush()
                        check(input.read() == 0x06) { "受信機との通信が切断されました" }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) report("Windows転送失敗: " + e.message)
            } finally {
                running.set(false)
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                frames.clear()
            }
        }, "WindowsSender").also { it.start() }
    }
    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
    }
}
