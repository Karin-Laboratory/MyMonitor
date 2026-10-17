package com.karinlab.mymonitor

import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recording-only sink for PCM received from Android's ALREADY-ROUTED USB
 * AudioRecord. It never opens a second USB interface or audio recorder.
 *
 * Capture is decoupled from flash writes. Bounded queue avoids stalling the
 * 48kHz USB producer. If a queue overflow happens, abandon the sound take
 * rather than produce a falsely synchronized MP4.
 */
class UsbAudioTake(cacheDir: File) {
    val file = File.createTempFile("MyMonitor_UVC_", ".pcm", cacheDir)
    private val chunks = ArrayBlockingQueue<ByteArray>(64)
    private val open = AtomicBoolean(true)
    private val failed = AtomicBoolean(false)
    @Volatile private var bytesWritten = 0L
    val startedNs = SystemClock.elapsedRealtimeNanos()
    private val writer = Thread({
        try {
            BufferedOutputStream(FileOutputStream(file), 65536).use { out ->
                while (open.get() || chunks.isNotEmpty()) {
                    val pcm = chunks.poll(150, TimeUnit.MILLISECONDS) ?: continue
                    out.write(pcm)
                    bytesWritten += pcm.size
                }
            }
        } catch (e: Exception) {
            failed.set(true)
            Log.w("MyMonitor-USB-Take", "USB sound temp write failed", e)
        }
    }, "MyMonitorUsbSoundFile").apply { isDaemon = true; start() }

    /** PCM is 48kHz 16-bit stereo: each frame is exactly 4 bytes. */
    fun offer(pcm: ByteArray) {
        if (!open.get() || failed.get() || pcm.isEmpty() || pcm.size % 4 != 0) return
        if (!chunks.offer(pcm)) failed.set(true)
    }

    /** Called outside the USB capture thread on video completion. */
    fun finish(): File? {
        open.set(false)
        try { writer.join(5000) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return if (!writer.isAlive && !failed.get() && bytesWritten >= 4096 &&
            file.isFile && file.length() == bytesWritten) file else null
    }

    fun discard() {
        open.set(false)
        writer.interrupt()
        // The writer owns this file until finished. Deletion on recording
        // failure is performed by the caller after the worker finishes.
    }
}
