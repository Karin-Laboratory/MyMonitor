package com.karinlab.mymonitor

import org.junit.Assert.*
import org.junit.Test

class UacPcmAdapterTest {
    @Test fun stereo48k16PassesAndPreservesChannels() {
        val pcm = byteArrayOf(0, 64, 0, -64, -1, 127, 0, -128)
        assertArrayEquals(pcm, UacPcmAdapter.toStereo48k(pcm, 48000, 2, 16))
    }
    @Test fun mono48kIsDuplicated() {
        val pcm = byteArrayOf(0, 64, -1, 127)
        assertArrayEquals(byteArrayOf(0, 64, 0, 64, -1, 127, -1, 127),
            UacPcmAdapter.toStereo48k(pcm, 48000, 1, 16))
    }
    @Test fun rateAndEightBitConvertTo48kStereo() {
        val raw = ByteArray(441) { (it % 15).toByte() }
        assertEquals(1920, UacPcmAdapter.toStereo48k(raw, 44100, 1, 8)!!.size)
    }
    @Test fun rejectsUnknownDeviceFormat() {
        assertNull(UacPcmAdapter.toStereo48k(byteArrayOf(1,2), 0, 1, 16))
        assertNull(UacPcmAdapter.toStereo48k(byteArrayOf(1,2), 48000, 6, 16))
        assertNull(UacPcmAdapter.toStereo48k(byteArrayOf(1,2), 48000, 1, 24))
    }
}
