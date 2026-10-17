package com.karinlab.mymonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v035: UVC stays the sole owner of libusb/UsbControlBlock.
 * USB sound is captured ONLY through Android's AudioRecord public API,
 * explicitly routed to a unique USB audio input. If no OS USB route is
 * available, preserve UVC video and use SILENCE (never phone microphone).
 *
 * The v034 direct UACAudioHandler reused the video camera's UsbControlBlock
 * on multiple asynchronous native threads, with a potential unplug/use-after-
 * free race. No native USB audio JNI functions are called in this class.
 */
class UsbAudioCapture(private val context: Context,
                      private val onState: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    private val speakerQueue = ArrayBlockingQueue<ByteArray>(5)
    @Volatile private var generation = 0
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var speakerWorker: Thread? = null

    fun isRunning() = running.get()

    companion object {
        fun usbInputs(context: Context): List<AudioDeviceInfo> {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return emptyList()
            return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
                it.isSource && (it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
            }
        }
        fun hasUsbInput(context: Context) = usbInputs(context).size == 1
    }

    @Synchronized fun start() {
        if (running.get()) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return
        val candidates = usbInputs(context)
        if (candidates.size != 1) {
            onState(if (candidates.isEmpty())
                "AndroidがUSB音声入力を認識していません。映像はそのまま利用できます"
            else "USB音声入力が複数あります。誤録音防止のため音声を停止しています")
            return
        }
        val device = candidates.single()
        val localMonitor = context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            .getBoolean("uvcAudioMonitor", true)
        val epoch = ++generation
        running.set(true)
        speakerQueue.clear()
        worker = Thread({
            var capture: AudioRecord? = null
            try {
                // Android USB audio endpoints may expose only mono, stereo or
                // selected rates. Try several formats without falling back to
                // the Android built-in microphone.
                val formats = listOf(
                    48000 to 2, 48000 to 1, 44100 to 2, 44100 to 1,
                    32000 to 2, 32000 to 1, 16000 to 1)
                var rate = 0
                var channels = 0
                for ((candidateRate, candidateChannels) in formats) {
                    if (!running.get() || generation != epoch) return@Thread
                    val config = if (candidateChannels == 2)
                        AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                    val min = AudioRecord.getMinBufferSize(candidateRate, config,
                        AudioFormat.ENCODING_PCM_16BIT)
                    if (min <= 0) continue
                    val proposed = try {
                        AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.MIC)
                            .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(candidateRate)
                                .setChannelMask(config).build())
                            .setBufferSizeInBytes(maxOf(min * 2, candidateRate / 10 *
                                candidateChannels * 2))
                            .build()
                    } catch (e: Exception) {
                        Log.w("MyMonitor-USB-Audio", "Unsupported USB audio format", e)
                        continue
                    }
                    if (proposed.state != AudioRecord.STATE_INITIALIZED ||
                        !proposed.setPreferredDevice(device)) {
                        proposed.release()
                        continue
                    }
                    capture = proposed
                    rate = candidateRate
                    channels = candidateChannels
                    break
                }
                val record = capture ?: run {
                    onState("USB音声入力を開けません。映像のみ利用できます")
                    return@Thread
                }
                if (!running.get() || generation != epoch) return@Thread
                recorder = record
                record.startRecording()
                // Crucial privacy/safety gate: Android can silently fall back
                // to the built-in microphone despite setPreferredDevice().
                // Do NOT read, play or transmit any samples until an actual USB
                // route is confirmed. A missing route means video-only.
                val routed = record.routedDevice
                if (routed == null || routed.id != device.id || !routed.isSource) {
                    onState("USB音声への接続を確認できません。映像のみ利用できます")
                    return@Thread
                }
                if (localMonitor) startSpeaker(epoch)
                onState("USBキャプチャの音声入力を開始しました")
                val data = ByteArray(4096 * channels * 2)
                while (running.get() && generation == epoch &&
                    StreamHub.source() == CapturePolicy.Source.UVC) {
                    val activeRoute = record.routedDevice
                    if (activeRoute?.id != device.id) {
                        onState("USB音声が切断されました。映像の転送は継続します")
                        break
                    }
                    val n = record.read(data, 0, data.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    if (!running.get() || generation != epoch ||
                        record.routedDevice?.id != device.id) break
                    val pcm = UacPcmAdapter.toStereo48k(data.copyOf(n), rate, channels, 16)
                        ?: continue
                    for (offset in pcm.indices step 19200) {
                        val chunk = pcm.copyOfRange(offset, minOf(offset + 19200, pcm.size))
                        StreamHub.offerUvcAudio(chunk, chunk.size)
                        if (localMonitor && !speakerQueue.offer(chunk)) {
                            speakerQueue.poll()
                            speakerQueue.offer(chunk)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get() && generation == epoch) {
                    Log.w("MyMonitor-USB-Audio", "Android USB audio capture unavailable", e)
                    onState("USB音声を取得できません。映像は継続します")
                }
            } finally {
                try { capture?.stop() } catch (_: Exception) {}
                try { capture?.release() } catch (_: Exception) {}
                if (recorder === capture) recorder = null
                if (generation == epoch) running.set(false)
                speakerQueue.clear()
            }
        }, "MyMonitorAndroidUsbAudio").apply { isDaemon = true; start() }
    }

    private fun startSpeaker(epoch: Int) {
        speakerWorker = Thread({
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
                    .setAudioFormat(format).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(min, 19200)).build()
                if (track.state != AudioTrack.STATE_INITIALIZED) return@Thread
                track.play()
                while (running.get() && generation == epoch) {
                    val pcm = speakerQueue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    if (!running.get() || generation != epoch) break
                    track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                }
            } catch (e: Exception) {
                Log.w("MyMonitor-USB-Audio", "Phone audio monitoring unavailable", e)
            } finally {
                try { track?.pause() } catch (_: Exception) {}
                try { track?.flush() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }, "MyMonitorUsbSpeaker").also { it.isDaemon = true; it.start() }
    }

    @Synchronized fun stop() {
        running.set(false)
        ++generation
        speakerQueue.clear()
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.interrupt()
        speakerWorker?.interrupt()
    }
}
