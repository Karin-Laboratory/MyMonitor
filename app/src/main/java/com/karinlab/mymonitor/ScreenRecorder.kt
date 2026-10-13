package com.karinlab.mymonitor

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Asynchronous MP4 recorder (H.264/AAC) driven by the screen ImageReader and AudioRecord.
 * CPU conversion is isolated from the low-latency network producer. */
class ScreenRecorder(private val context: Context, private val finished: (File?, String?) -> Unit) {
    private data class Frame(val bitmap: Bitmap, val pts: Long)
    private data class Audio(val data: ByteArray, val pts: Long)
    private val frames = ArrayBlockingQueue<Frame>(2)
    private val sounds = ArrayBlockingQueue<Audio>(24)
    private val running = AtomicBoolean(false)
    private val startNs = SystemClock.elapsedRealtimeNanos()
    private var worker: Thread? = null
    private lateinit var file: File
    private var width = 0
    private var height = 0

    fun isRecording() = running.get()
    fun start(w: Int, h: Int): Boolean {
        if (!running.compareAndSet(false, true)) return false
        width = w and -2; height = h and -2
        if (width < 16 || height < 16) { running.set(false); return false }
        file = File(context.getExternalFilesDir(null) ?: context.filesDir,
            "MyMonitor_" + System.currentTimeMillis() + "_screen.mp4")
        worker = Thread({ runEncoder() }, "ScreenRecorder").also { it.start() }
        return true
    }
    fun offerFrame(bitmap: Bitmap) {
        if (!running.get() || bitmap.width != width || bitmap.height != height) return
        val copied = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val item = Frame(copied, (SystemClock.elapsedRealtimeNanos() - startNs) / 1000)
        if (frames.remainingCapacity() == 0) frames.poll()?.bitmap?.recycle()
        if (!frames.offer(item)) copied.recycle()
    }
    fun offerAudio(data: ByteArray, count: Int) {
        if (!running.get() || count <= 0) return
        val item = Audio(data.copyOf(count), (SystemClock.elapsedRealtimeNanos() - startNs) / 1000)
        if (sounds.remainingCapacity() == 0) sounds.poll()
        sounds.offer(item)
    }
    fun stop() { running.set(false); worker?.interrupt() }

    private fun runEncoder() {
        var v: MediaCodec? = null
        var a: MediaCodec? = null
        var mux: MediaMuxer? = null
        var started = false
        var vTrack = -1
        var aTrack = -1
        var error: String? = null
        try {
            val vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            vFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            vFmt.setInteger(MediaFormat.KEY_BIT_RATE, 7_000_000)
            vFmt.setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            vFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val vc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            v = vc
            vc.configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            vc.start()
            val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2)
            aFmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            aFmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            val ac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            a = ac
            ac.configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            val mm = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = mm
            val info = MediaCodec.BufferInfo()
            fun drain(codec: MediaCodec, video: Boolean) {
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, 0)
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (video) vTrack = mm.addTrack(codec.outputFormat)
                        else aTrack = mm.addTrack(codec.outputFormat)
                        if (!started && vTrack >= 0 && aTrack >= 0) {
                            mm.start(); started = true
                        }
                        continue
                    }
                    if (idx < 0) continue
                    if (started && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val buffer = codec.getOutputBuffer(idx)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        mm.writeSampleData(if (video) vTrack else aTrack, buffer, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
            }
            var lastPts = -1L
            while (running.get() || frames.isNotEmpty() || sounds.isNotEmpty()) {
                val frame = frames.poll()
                if (frame != null) {
                    val idx = vc.dequeueInputBuffer(0)
                    if (idx >= 0) {
                        val img = vc.getInputImage(idx)
                        if (img != null) {
                            writeYuv(frame.bitmap, img.planes)
                            img.close()
                            val pts = maxOf(frame.pts, lastPts + 1)
                            lastPts = pts
                            vc.queueInputBuffer(idx, 0, width * height * 3 / 2, pts, 0)
                        } else {
                            vc.queueInputBuffer(idx, 0, 0, frame.pts, 0)
                        }
                    }
                    frame.bitmap.recycle()
                }
                repeat(5) {
                    val sound = sounds.poll() ?: return@repeat
                    val idx = ac.dequeueInputBuffer(0)
                    if (idx >= 0) {
                        val buffer = ac.getInputBuffer(idx)!!
                        buffer.clear()
                        val count = min(sound.data.size, buffer.remaining())
                        buffer.put(sound.data, 0, count)
                        ac.queueInputBuffer(idx, 0, count, sound.pts, 0)
                    }
                }
                drain(vc, true); drain(ac, false)
                if (frame == null && sounds.isEmpty())
                    try { Thread.sleep(8) } catch (_: InterruptedException) { }
            }
            repeat(12) { drain(vc, true); drain(ac, false); Thread.sleep(8) }
            if (!started) error = "MP4作成に必要な映像・音声トラックがありません"
        } catch (e: Exception) {
            error = "録画失敗: " + e.message
        } finally {
            frames.forEach { it.bitmap.recycle() }; frames.clear(); sounds.clear()
            try { v?.stop() } catch (_: Exception) { }
            try { v?.release() } catch (_: Exception) { }
            try { a?.stop() } catch (_: Exception) { }
            try { a?.release() } catch (_: Exception) { }
            try { if (started) mux?.stop() } catch (e: Exception) { error = e.message }
            try { mux?.release() } catch (_: Exception) { }
            finished(if (error == null && file.length() > 0L) file else null, error)
        }
    }

    private fun writeYuv(bitmap: Bitmap, planes: Array<Image.Plane>) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels[y * width + x]
            val r = (color shr 16) and 255
            val g = (color shr 8) and 255
            val b = color and 255
            val luma = ((66*r + 129*g + 25*b + 128) shr 8) + 16
            yBuffer.put(y*yPlane.rowStride + x*yPlane.pixelStride, luma.coerceIn(0,255).toByte())
            if (x % 2 == 0 && y % 2 == 0) {
                val u = ((-38*r - 74*g + 112*b + 128) shr 8) + 128
                val v = ((112*r - 94*g - 18*b + 128) shr 8) + 128
                uBuffer.put((y/2)*uPlane.rowStride + (x/2)*uPlane.pixelStride, u.coerceIn(0,255).toByte())
                vBuffer.put((y/2)*vPlane.rowStride + (x/2)*vPlane.pixelStride, v.coerceIn(0,255).toByte())
            }
        }
    }
}
