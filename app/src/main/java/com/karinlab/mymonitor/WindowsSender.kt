package com.karinlab.mymonitor

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MJPEG-first stream. At most one JPEG is in flight, and pending frames are
 * replaced rather than queued. Transient Wi-Fi/TCP/ACK errors trigger an
 * automatic retry; only explicit stop() / USB detach ends the retry loop.
 */
class WindowsSender(private val report: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private data class Frame(
        val bytes: ByteArray, val width: Int, val height: Int,
        val jpeg: Boolean, val capturedAtMs: Long
    )
    private val frames = ArrayBlockingQueue<Frame>(1)
    private var lastOffered = 0L
    @Volatile private var lastMjpeg = 0L
    @Volatile private var worker: Thread? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var awaitingAckSince = 0L
    @Volatile private var connected = false
    @Volatile private var transportConnected = false

    private fun readControl(input: java.io.InputStream) {
        val packet = ByteArray(9)
        var offset = 0
        while (offset < packet.size) {
            val n = input.read(packet,offset,packet.size-offset)
            if (n < 0) throw java.io.EOFException("control ACK incomplete")
            offset += n
        }
        if (packet[0].toInt() != 0) RemoteControlService.receive(packet)
    }
    fun isRunning() = running.get()
    /** Clear a UVC JPEG queued before the screen source became active, or vice versa. */
    @Synchronized fun discardPending() {
        frames.clear()
        lastMjpeg = 0L
        lastOffered = 0L
    }
    /** True only after the remote receiver ACKed an actual video frame. */
    fun isConnected() = connected
    /** TCP socket is actually established even when UVC has not sent first frame. */
    fun hasTransportConnection() = transportConnected
    fun awaitStopped(timeoutMs: Long = 2500) {
        val thread = worker
        if (thread != null && thread !== Thread.currentThread())
            try { thread.join(timeoutMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Native UVC owns the ByteBuffer; copy before the callback returns. */
    @Synchronized fun offerJpeg(buffer: ByteBuffer) {
        if (!running.get()) return
        val length = buffer.remaining()
        if (length < 100 || length > 8 * 1024 * 1024) return
        val pos = buffer.position()
        if (buffer.get(pos).toInt() and 255 != 0xff ||
            buffer.get(pos + 1).toInt() and 255 != 0xd8) return
        val now = SystemClock.elapsedRealtime()
        lastMjpeg = now
        if (now - lastOffered < 33L) return
        val data = ByteArray(length)
        buffer.get(data)
        lastOffered = now
        frames.poll()
        frames.offer(Frame(data, 0, 0, true, now))
    }

    /** Fallback if USB device isn't supplying valid compressed MJPEG. */
    @Synchronized fun offer(data: ByteArray, width: Int, height: Int) {
        if (!running.get() || width <= 0 || height <= 0 || width > 4096 || height > 4096) return
        if (data.size != width * height * 3 / 2) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMjpeg < 1500L || now - lastOffered < 33L) return
        lastOffered = now
        frames.poll()
        frames.offer(Frame(data.copyOf(), width, height, false, now))
    }

    @Synchronized fun start(host: String, port: Int) {
        if (worker?.isAlive == true) {
            report(if (running.get()) "すでに転送中です" else "前の転送を終了中です。再試行してください")
            return
        }
        if (!running.compareAndSet(false, true)) return
        lastMjpeg = 0L
        lastOffered = 0L
        connected = false
        transportConnected = false
        frames.clear()
        worker = Thread({
            var failures = 0
            try {
                while (running.get()) {
                    val s = Socket()
                    socket = s
                    var connected = false
                    try {
                        s.sendBufferSize = 64 * 1024
                        // This applies to ACK reads, not socket writes.
                        s.soTimeout = 5000
                        s.connect(InetSocketAddress(host, port), 3000)
                        transportConnected = true
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        connected = true
                        failures = 0
                        s.use {
                            val out = DataOutputStream(s.getOutputStream())
                            val input = s.getInputStream()
                            out.writeBytes("MMC5")
                            report("受信機へTCP接続済み。映像フレーム待機中")
                            awaitingAckSince = 0L
                            var firstFrame = true
                            var lastSentAt = SystemClock.elapsedRealtime()
                            var sampleWindowMs = lastSentAt
                            var samples = 0
                            var sumQueueAge = 0L
                            var sumWriteMs = 0L
                            var sumAckWaitMs = 0L
                            var maxAckWaitMs = 0L
                            // SO_TIMEOUT covers ACK reads but NOT writes. A stalled Wi-Fi
                            // TCP write may otherwise block forever. Close that socket
                            // after 8s so this worker can automatically reconnect.
                            Thread({
                                while (running.get() && socket === s && !s.isClosed) {
                                    val started = awaitingAckSince
                                    if (started != 0L && SystemClock.elapsedRealtime() - started > 8000L) {
                                        try { s.close() } catch (_: Exception) {}
                                        break
                                    }
                                    try { Thread.sleep(400) } catch (_: InterruptedException) { break }
                                }
                            }, "WindowsSendWatchdog").apply { isDaemon = true; start() }
                            while (running.get()) {
                                val polled = frames.poll(500, TimeUnit.MILLISECONDS)
                                if (polled == null) {
                                    // A static Android screen may deliver no new ImageReader
                                    // buffers for >5s. Heartbeat prevents false disconnects.
                                    if (SystemClock.elapsedRealtime() - lastSentAt >= 1500L) {
                                        awaitingAckSince = SystemClock.elapsedRealtime()
                                        try {
                                            out.writeInt(0) // MMC9 v014 heartbeat, no image
                                            out.flush()
                                            check(input.read() == 0x06) { "ハートビート応答なし" }
                                            readControl(input)
                                            lastSentAt = SystemClock.elapsedRealtime()
                                        } finally { awaitingAckSince = 0L }
                                    }
                                    continue
                                }
                                var frame = polled
                                // Discard stale frames accumulated while the preceding JPEG was in flight.
                                while (true) { frame = frames.poll() ?: break }
                                val jpeg = if (frame.jpeg) frame.bytes else {
                                    val bytes = ByteArrayOutputStream()
                                    check(YuvImage(frame.bytes, ImageFormat.NV21, frame.width, frame.height, null)
                                        .compressToJpeg(Rect(0, 0, frame.width, frame.height), 75, bytes))
                                    bytes.toByteArray()
                                }
                                val writeStarted = SystemClock.elapsedRealtime()
                                awaitingAckSince = writeStarted
                                try {
                                    out.writeInt(jpeg.size)
                                    out.write(jpeg)
                                    out.flush()
                                    val afterWrite = SystemClock.elapsedRealtime()
                                    check(input.read() == 0x06) { "ACK不一致または接続切断" }
                                    readControl(input)
                                    lastSentAt = SystemClock.elapsedRealtime()
                                    connected = true
                                    if (firstFrame) {
                                        firstFrame = false
                                        // v015 ACK means complete packet received, not decoder/render success.
                                        report("Windowsが映像パケットを受領: " + host)
                                    }
                                    // Diagnostic breakdown on the SENDER only.
                                    // The Windows title shows separate JPEG decode / GDI paint timings.
                                    samples++
                                    sumQueueAge += (writeStarted - frame.capturedAtMs).coerceAtLeast(0L)
                                    sumWriteMs += (afterWrite - writeStarted).coerceAtLeast(0L)
                                    val wait = (lastSentAt - afterWrite).coerceAtLeast(0L)
                                    sumAckWaitMs += wait
                                    maxAckWaitMs = maxOf(maxAckWaitMs, wait)
                                    if (lastSentAt - sampleWindowMs >= 5000L && samples > 0) {
                                        Log.i("MyMonitorLatency", "v015 host=$host frames=$samples " +
                                            "avg_queue_ms=${sumQueueAge / samples} " +
                                            "avg_write_ms=${sumWriteMs / samples} " +
                                            "avg_ack_wait_ms=${sumAckWaitMs / samples} " +
                                            "max_ack_wait_ms=$maxAckWaitMs")
                                        sampleWindowMs = lastSentAt
                                        samples = 0
                                        sumQueueAge = 0L
                                        sumWriteMs = 0L
                                        sumAckWaitMs = 0L
                                        maxAckWaitMs = 0L
                                    }
                                } finally {
                                    awaitingAckSince = 0L
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            failures++
                            // Report every first loss, then sparingly on repeated failed attempts.
                            if (connected || failures == 1 || failures % 10 == 0) {
                                report("接続が途切れました。自動再接続中: " + (e.message ?: e.javaClass.simpleName))
                            }
                        }
                    } finally {
                        connected = false
                        StreamHub.disarmControl()
                        transportConnected = false
                        awaitingAckSince = 0L
                        try { s.close() } catch (_: Exception) {}
                        if (socket === s) socket = null
                        frames.clear()
                    }
                    if (!running.get()) break
                    val backoff = when {
                        failures <= 1 -> 250L
                        failures <= 3 -> 500L
                        failures <= 7 -> 1000L
                        else -> 2000L
                    }
                    try { Thread.sleep(backoff) } catch (_: InterruptedException) {
                        if (!running.get()) break
                    }
                }
            } finally {
                connected = false
                transportConnected = false
                running.set(false)
                socket = null
                frames.clear()
            }
        }, "WindowsSender").also { it.start() }
    }

    /** A deliberate stop must never be undone by the automatic reconnect. */
    fun stop() {
        connected = false
        transportConnected = false
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        worker?.interrupt()
    }
}
