package com.karinlab.mymonitor

import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** AudioPlaybackCapture PCM16LE stereo at 48kHz over TCP 57009.
 * Lossy queue deliberately drops OLD chunks to bound latency. */
class PcmSender {
    private val running = AtomicBoolean(false)
    private val chunks = ArrayBlockingQueue<ByteArray>(3)
    @Volatile private var socket: Socket? = null
    @Volatile private var thread: Thread? = null
    fun isRunning() = running.get()
    fun offer(bytes: ByteArray, size: Int) {
        if (!running.get() || size <= 0 || size > 38400 || size % 4 != 0) return
        if (chunks.remainingCapacity() == 0) chunks.poll()
        chunks.offer(bytes.copyOf(size))
    }
    fun start(host: String) {
        if (!running.compareAndSet(false, true)) return
        chunks.clear()
        thread = Thread({
            try {
                while (running.get()) {
                    try {
                        val s = Socket()
                        socket = s
                        s.tcpNoDelay = true
                        s.connect(InetSocketAddress(host, 57009), 2500)
                        s.use {
                            val out = DataOutputStream(s.getOutputStream())
                            out.writeBytes("MMA1")
                            while (running.get()) {
                                val pcm = chunks.poll(500, TimeUnit.MILLISECONDS) ?: continue
                                out.writeInt(pcm.size)
                                out.write(pcm)
                                // Don't flush after every sample, just each 20ms chunk.
                                out.flush()
                            }
                        }
                    } catch (_: Exception) {
                        if (running.get()) try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    } finally {
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                        chunks.clear()
                    }
                }
            } finally {
                running.set(false)
            }
        }, "MyMonitorAudio").also { it.isDaemon = true; it.start() }
    }
    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        thread?.interrupt()
        chunks.clear()
    }
}
