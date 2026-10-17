package com.karinlab.mymonitor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Post-process two independently owned media inputs:
 *  - video: original AUSBC MP4 (libuvc owns the camera; audioSource=NONE).
 *  - audio: PCM from Android AudioRecord, whose actual USB route was verified.
 *
 * No UACAudioHandler / additional libusb calls, and no second video encoder.
 * A failure NEVER alters the valid video-only original.
 */
object UsbRecordingMuxer {
    private const val TAG = "MyMonitor-UVC-MP4"
    private const val RATE = 48000
    private const val CHANNELS = 2
    private const val BYTES_PER_FRAME = 4

    /** Returns the safe original when USB audio was missing or muxing failed. */
    fun combineVideoWithPcm(video: File, pcm: File?): File {
        if (pcm == null || !pcm.isFile || pcm.length() < 4096L || !video.isFile)
            return video
        val audio = File.createTempFile("MyMonitor_uac_", ".m4a", video.parentFile)
        val merged = File(video.parentFile,
            video.nameWithoutExtension + "_usb_audio.mp4")
        try {
            if (!encodeAudio(pcm, audio)) return video
            mergeStreams(video, audio, merged)
            if (!merged.isFile || merged.length() <= 4096L)
                throw IllegalStateException("Muxer produced no usable output")
            return merged
        } catch (e: Exception) {
            Log.w(TAG, "Keeping original UVC MP4; audio merge unavailable", e)
            merged.delete()
            return video
        } finally {
            audio.delete()
            pcm.delete()
        }
    }

    private fun encodeAudio(pcm: File, output: File): Boolean {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = encoder
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val mp4 = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mp4
            val info = MediaCodec.BufferInfo()
            var track = -1
            var sourceEnded = false
            var outputEnded = false
            var sourceBytes = 0L
            var idleCycles = 0
            FileInputStream(pcm).use { input ->
                while (!outputEnded) {
                    var progressed = false
                    if (!sourceEnded) {
                        val index = encoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val buffer = encoder.getInputBuffer(index)
                                ?: throw IllegalStateException("No AAC input buffer")
                            buffer.clear()
                            val left = pcm.length() - sourceBytes
                            val pts = (sourceBytes / BYTES_PER_FRAME) * 1000000L / RATE
                            if (left <= 0L) {
                                encoder.queueInputBuffer(index, 0, 0, pts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sourceEnded = true
                            } else {
                                val count = minOf(left, buffer.remaining().toLong(), 16384L)
                                    .toInt() / BYTES_PER_FRAME * BYTES_PER_FRAME
                                if (count <= 0) throw IllegalStateException("Tiny AAC input buffer")
                                val raw = ByteArray(count)
                                var cursor = 0
                                while (cursor < count) {
                                    val n = input.read(raw, cursor, count - cursor)
                                    if (n <= 0) throw IllegalStateException("Truncated USB PCM")
                                    cursor += n
                                }
                                buffer.put(raw)
                                encoder.queueInputBuffer(index, 0, count, pts, 0)
                                sourceBytes += count
                            }
                            progressed = true
                        }
                    }
                    when (val index = encoder.dequeueOutputBuffer(info, 10000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (started) throw IllegalStateException("Repeated AAC format")
                            track = mp4.addTrack(encoder.outputFormat)
                            mp4.start()
                            started = true
                            progressed = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (index >= 0) {
                            if (info.size > 0 && (info.flags and
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (!started) throw IllegalStateException("AAC format missing")
                                val buffer = encoder.getOutputBuffer(index)
                                    ?: throw IllegalStateException("No AAC output buffer")
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                mp4.writeSampleData(track, buffer, info)
                            }
                            outputEnded = (info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(index, false)
                            progressed = true
                        }
                    }
                    idleCycles = if (progressed) 0 else idleCycles + 1
                    if (idleCycles > 250) throw IllegalStateException("AAC encoder timeout")
                }
            }
            return started && sourceBytes >= 4096L
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
        }
    }

    private fun mergeStreams(video: File, audio: File, result: File) {
        val v = MediaExtractor()
        val a = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            v.setDataSource(video.absolutePath)
            a.setDataSource(audio.absolutePath)
            fun findTrack(extractor: MediaExtractor, prefix: String): Int =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        ?.startsWith(prefix) == true
                } ?: throw IllegalStateException("MP4 track missing: $prefix")
            val videoIndex = findTrack(v, "video/")
            val audioIndex = findTrack(a, "audio/")
            val vf = v.getTrackFormat(videoIndex)
            val af = a.getTrackFormat(audioIndex)
            v.selectTrack(videoIndex)
            a.selectTrack(audioIndex)
            val out = MediaMuxer(result.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = out
            if (vf.containsKey(MediaFormat.KEY_ROTATION)) {
                val degrees = vf.getInteger(MediaFormat.KEY_ROTATION)
                if (degrees in listOf(0, 90, 180, 270)) out.setOrientationHint(degrees)
            }
            val videoTrack = out.addTrack(vf)
            val audioTrack = out.addTrack(af)
            out.start()
            started = true

            fun copyTrack(extractor: MediaExtractor, target: Int): Int {
                val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                val origin = extractor.sampleTime.coerceAtLeast(0)
                var count = 0
                var lastPts = -1L
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    if (size > buffer.capacity()) throw IllegalStateException("Oversize MP4 sample")
                    val pts = (extractor.sampleTime - origin).coerceAtLeast(lastPts + 1)
                    info.set(0, size, pts,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    buffer.position(0)
                    buffer.limit(size)
                    out.writeSampleData(target, buffer, info)
                    lastPts = pts
                    count++
                    extractor.advance()
                }
                return count
            }
            val videoCount = copyTrack(v, videoTrack)
            val audioCount = copyTrack(a, audioTrack)
            if (videoCount == 0 || audioCount == 0)
                throw IllegalStateException("Empty video/audio samples")
            Log.i(TAG, "Remuxed UVC video=$videoCount audio=$audioCount packets")
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { v.release() } catch (_: Exception) {}
            try { a.release() } catch (_: Exception) {}
        }
    }
}
