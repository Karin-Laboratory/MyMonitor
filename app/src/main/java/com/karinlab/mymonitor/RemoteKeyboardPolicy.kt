package com.karinlab.mymonitor

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * MMC6 v031 reuses the 17-byte reverse ACK payload without changing video or
 * audio framing. v030 receivers ignore opcodes 8/9, rather than misreading them.
 *
 * Text packets: [8, UTF8_BYTE_COUNT (1..15), bytes..., zero padding].
 * Key packets:  [9, ACTION_ID, zero padding].
 *
 * This is editable text input, not low-level HID or terminal key injection.
 */
object RemoteKeyboardPolicy {
    const val TEXT = 8
    const val KEY = 9
    const val BACKSPACE = 1
    const val DELETE = 2
    const val ENTER = 3
    const val LEFT = 4
    const val RIGHT = 5
    const val HOME = 6
    const val END = 7

    data class Edit(val replacement: String?, val caret: Int)

    fun decodeText(packet: ByteArray): String? {
        if (packet.size != 17 || (packet[0].toInt() and 255) != TEXT) return null
        val length = packet[1].toInt() and 255
        if (length !in 1..15) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(packet, 2, length)).toString()
        } catch (_: Exception) { null }
    }

    fun edit(current: String, selectionStart: Int, selectionEnd: Int,
             operation: Int, input: String = ""): Edit? {
        val a = (if (selectionStart < 0) current.length else selectionStart)
            .coerceIn(0, current.length)
        val b = (if (selectionEnd < 0) a else selectionEnd)
            .coerceIn(0, current.length)
        val from = minOf(a, b)
        val to = maxOf(a, b)
        fun replace(start: Int, finish: Int, insertion: String): Edit =
            Edit(current.substring(0, start) + insertion + current.substring(finish),
                start + insertion.length)
        return when (operation) {
            TEXT -> if (input.isNotEmpty()) replace(from, to, input) else null
            BACKSPACE -> when {
                from < to -> replace(from, to, "")
                from == 0 -> null
                else -> replace(current.offsetByCodePoints(from, -1), from, "")
            }
            DELETE -> when {
                from < to -> replace(from, to, "")
                to == current.length -> null
                else -> replace(to, current.offsetByCodePoints(to, 1), "")
            }
            ENTER -> replace(from, to, "\n")
            LEFT -> Edit(null, if (from < to) from else if (from > 0)
                current.offsetByCodePoints(from, -1) else 0)
            RIGHT -> Edit(null, if (from < to) to else if (to < current.length)
                current.offsetByCodePoints(to, 1) else current.length)
            HOME -> Edit(null, 0)
            END -> Edit(null, current.length)
            else -> null
        }
    }
}
