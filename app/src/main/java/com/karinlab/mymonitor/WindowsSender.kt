package com.karinlab.mymonitor

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** One in-flight frame, no growing latency queue. Wire: MMC9 + uint32 JPEG length + JPEG; receiver ACK 0x06 before next frame. */
class WindowsSender(private val report: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private data class Frame(val data: ByteArray, val width: Int, val height: Int)
    private val frames = ArrayBlockingQueue<Frame>(1)
    private var lastSent = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    fun isRunning() = running.get()
    @Synchronized fun offer(data: ByteArray, width: Int, height: Int) {
        if (!running.get() || width <= 0 || height <= 0 || width > 4096 || height > 4096) return
        if (data.size != width * height * 3 / 2) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSent < 66L) return
        lastSent = now
        // Replace an older waiting frame rather than preserving its latency.
        frames.poll()
        frames.offer(Frame(data.copyOf(), width, height))
    }
    @Synchronized fun start(host: String, port: Int) {
        if (worker?.isAlive == true) { report("前の転送を終了中です。少し待って再試行してください"); return }
        if (!running.compareAndSet(false, true)) return
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
                    report("Windows接続済み・映像送信中")
                    while (running.get()) {
                        val frame = frames.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        run {
                            val bytes = ByteArrayOutputStream()
                            check(YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null)
                                .compressToJpeg(Rect(0, 0, frame.width, frame.height), 75, bytes))
                            val jpeg = bytes.toByteArray()
                            out.writeInt(jpeg.size)
                            out.write(jpeg)
                            out.flush()
                            // TCP write completion only means queued locally. Wait until Windows
                            // has decoded this frame: at most ONE JPEG may be in flight.
                            check(input.read() == 0x06) { "受信exeを009に更新してください／接続切断" }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) report("Windows転送失敗: ${e.message}")
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
