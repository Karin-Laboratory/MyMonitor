package com.karinlab.mymonitor

import org.junit.Assert.*
import org.junit.Test

class RemoteKeyboardPolicyTest {
    private fun payload(text: String): ByteArray {
        val chars = text.toByteArray(Charsets.UTF_8)
        return ByteArray(17).also {
            it[0] = RemoteKeyboardPolicy.TEXT.toByte()
            it[1] = chars.size.toByte()
            chars.copyInto(it, 2)
        }
    }

    @Test fun acceptsJapaneseCommittedUtf8AndRejectsBrokenFraming() {
        assertEquals("東京駅", RemoteKeyboardPolicy.decodeText(payload("東京駅")))
        assertNull(RemoteKeyboardPolicy.decodeText(ByteArray(17)))
        assertNull(RemoteKeyboardPolicy.decodeText(payload("X").apply { this[1] = 16 }))
        assertNull(RemoteKeyboardPolicy.decodeText(payload("X").apply {
            this[2] = 0xc3.toByte()
        }))
    }

    @Test fun insertAtCaretOrReplaceSelection() {
        assertEquals(RemoteKeyboardPolicy.Edit("a東京b", 3),
            RemoteKeyboardPolicy.edit("ab", 1, 1, RemoteKeyboardPolicy.TEXT, "東京"))
        assertEquals(RemoteKeyboardPolicy.Edit("abc", 2),
            RemoteKeyboardPolicy.edit("aXc", 1, 2, RemoteKeyboardPolicy.TEXT, "b"))
    }

    @Test fun backspaceDeletesCodePointNotHalfOfEmoji() {
        val input = "a😀b"
        assertEquals("ab", RemoteKeyboardPolicy.edit(input, 3, 3,
            RemoteKeyboardPolicy.BACKSPACE)?.replacement)
        assertEquals("a😀", RemoteKeyboardPolicy.edit(input, 3, 3,
            RemoteKeyboardPolicy.DELETE)?.replacement)
    }

    @Test fun caretMovementAndEnter() {
        assertEquals(1, RemoteKeyboardPolicy.edit("abc", 2, 2,
            RemoteKeyboardPolicy.LEFT)?.caret)
        assertEquals(3, RemoteKeyboardPolicy.edit("abc", 2, 2,
            RemoteKeyboardPolicy.RIGHT)?.caret)
        assertEquals("a\nb", RemoteKeyboardPolicy.edit("ab", 1, 1,
            RemoteKeyboardPolicy.ENTER)?.replacement)
    }
}
