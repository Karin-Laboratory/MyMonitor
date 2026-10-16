package com.karinlab.mymonitor

import kotlin.math.roundToInt

/** AUSBC/UAC native PCM is hardware-dependent; MyCast MMA1 is always
 * 48,000 Hz, 16-bit little-endian, stereo. Never reinterpret USB mic as
 * Android's built-in microphone. */
object UacPcmAdapter {
    fun toStereo48k(data: ByteArray, rate: Int, channels: Int, bits: Int): ByteArray? {
        if (rate !in 8000..96000 || channels !in 1..2 || bits !in listOf(8, 16)) return null
        val bytesPerSample = bits / 8
        val stride = channels * bytesPerSample
        val frames = data.size / stride
        if (frames <= 0 || frames > 9600) return null
        val outFrames = ((frames.toLong() * 48000 + rate / 2) / rate).toInt()
        if (outFrames <= 0 || outFrames > 19200) return null
        val out = ByteArray(outFrames * 4)
        fun sample(frame: Int, channel: Int): Int {
            val p = (frame * channels + channel.coerceAtMost(channels - 1)) * bytesPerSample
            return if (bits == 16) {
                ((data[p].toInt() and 0xff) or (data[p + 1].toInt() shl 8)).toShort().toInt()
            } else data[p].toInt() shl 8 // UAC signed 8-bit PCM
        }
        for (i in 0 until outFrames) {
            val source = i.toDouble() * rate / 48000.0
            val first = source.toInt().coerceAtMost(frames - 1)
            val second = (first + 1).coerceAtMost(frames - 1)
            val fraction = source - first
            for (c in 0..1) {
                val v = (sample(first, c) * (1.0 - fraction) +
                    sample(second, c) * fraction).roundToInt().coerceIn(-32768, 32767)
                val p = i * 4 + c * 2
                out[p] = v.toByte()
                out[p + 1] = (v shr 8).toByte()
            }
        }
        return out
    }
}
