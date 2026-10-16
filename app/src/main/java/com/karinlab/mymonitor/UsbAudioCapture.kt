package com.karinlab.mymonitor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.jiangdg.uac.UACAudioCallBack
import com.jiangdg.uac.UACAudioHandler
import com.jiangdg.usb.USBMonitor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v034 HDMI capture sound: read the actual UAC interface via the pinned AUSBC
 * native library, NOT the phone microphone. The existing 48k stereo MMA1 TCP
 * receiver plays this unchanged, and an optional local AudioTrack monitors it.
 * One producer owns the UAC interface; stop this before AUSBC MP4 recording,
 * which independently owns the same UAC device microphone.
 */
class UsbAudioCapture(private val context: Context,
                      private val onState: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private val speakerQueue = ArrayBlockingQueue<ByteArray>(5)
    @Volatile private var handler: UACAudioHandler? = null
    @Volatile private var callback: UACAudioCallBack? = null
    @Volatile private var player: Thread? = null
    @Volatile private var playerTrack: AudioTrack? = null
    @Volatile private var generation = 0

    fun isRunning() = running.get()

    @Synchronized fun start(block: USBMonitor.UsbControlBlock) {
        if (!running.compareAndSet(false, true)) return
        val epoch = ++generation
        val monitor = context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            .getBoolean("uvcAudioMonitor", true)
        speakerQueue.clear()
        if (monitor) startSpeaker(epoch)
        Thread({
            var instance: UACAudioHandler? = null
            try {
                instance = UACAudioHandler.createHandler(block)
                    ?: throw IllegalStateException("UAC handler unavailable")
                if (!running.get() || generation != epoch) return@Thread
                handler = instance
                instance.initAudioRecord()
                // The native UAC handler initializes asynchronously on its
                // own Looper; getSampleRate waits for init with a timeout.
                val rate = instance.sampleRate
                if (rate !in 8000..96000) throw IllegalStateException("UAC sample rate $rate")
                val channels = instance.channelCount
                val bits = instance.bitResolution
                if (channels !in 1..2 || bits !in listOf(8, 16))
                    throw IllegalStateException("UAC format $rate/$channels/$bits")
                val hasAudio = AtomicBoolean(false)
                val sink = UACAudioCallBack { pcm ->
                    if (!running.get() || generation != epoch || !StreamHub.uvcReady) return@UACAudioCallBack
                    val output = UacPcmAdapter.toStereo48k(pcm, rate, channels, bits)
                        ?: return@UACAudioCallBack
                    // The network sender has a 38,400 byte maximum per
                    // packet. Always split at 16-bit stereo frame boundaries.
                    var offset = 0
                    while (offset < output.size) {
                        val count = minOf(19200, output.size - offset)
                        val chunk = output.copyOfRange(offset, offset + count)
                        StreamHub.offerUvcAudio(chunk, count)
                        if (monitor) {
                            if (!speakerQueue.offer(chunk)) {
                                speakerQueue.poll()
                                speakerQueue.offer(chunk)
                            }
                        }
                        offset += count
                    }
                }
                callback = sink
                instance.addDataCallBack(sink)
                if (!running.get() || generation != epoch) return@Thread
                instance.startRecording()
                onState("USBキャプチャの音声を利用しています")
                Log.i("MyMonitor-UAC", "UAC capture started: $rate Hz, $channels channels, $bits bits")
            } catch (e: Exception) {
                if (running.get() && generation == epoch) {
                    onState("USB音声を開始できません。映像のみ利用できます")
                    Log.w("MyMonitor-UAC", "USB audio start failed", e)
                }
                if (generation == epoch) stop()
            }
        }, "MyMonitorUacStart").apply { isDaemon = true; start() }
    }

    private fun startSpeaker(epoch: Int) {
        player = Thread({
            var track: AudioTrack? = null
            try {
                val format = AudioFormat.Builder()
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                val min = AudioTrack.getMinBufferSize(48000,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) return@Thread
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(min, 19200))
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) return@Thread
                playerTrack = track
                track.play()
                while (running.get() && generation == epoch) {
                    val pcm = speakerQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (!running.get() || generation != epoch) break
                    track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                }
            } catch (e: Exception) {
                Log.w("MyMonitor-UAC", "Local audio monitoring unavailable", e)
            } finally {
                try { track?.pause() } catch (_: Exception) {}
                try { track?.flush() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                if (playerTrack === track) playerTrack = null
            }
        }, "MyMonitorUacSpeaker").also { it.isDaemon = true; it.start() }
    }

    @Synchronized fun stop() {
        if (!running.getAndSet(false)) return
        ++generation
        speakerQueue.clear()
        val previous = handler
        handler = null
        val sink = callback
        callback = null
        try { if (sink != null) previous?.removeDataCallBack(sink) } catch (_: Exception) {}
        try { previous?.stopRecording() } catch (_: Exception) {}
        try { previous?.releaseAudioRecord() } catch (_: Exception) {}
        player?.interrupt()
        player = null
    }
}
