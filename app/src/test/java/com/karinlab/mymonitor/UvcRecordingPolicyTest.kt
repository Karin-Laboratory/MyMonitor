package com.karinlab.mymonitor

import org.junit.Assert.*
import org.junit.Test

class UvcRecordingPolicyTest {
    private val sizes = listOf(UvcRecordingPolicy.Size(640,480),
        UvcRecordingPolicy.Size(1280,720), UvcRecordingPolicy.Size(1920,1080))

    @Test fun fullHdUncompressedOnlyWhenExactAdvertised() {
        assertEquals(UvcRecordingPolicy.Size(1920,1080),
            UvcRecordingPolicy.chooseYuy2(1920,1080,sizes,true))
        assertNull(UvcRecordingPolicy.chooseYuy2(1920,1200,sizes,true))
    }
    @Test fun neverReduceFullHdToYuyv720() {
        assertNull(UvcRecordingPolicy.chooseYuy2(1920,1080,
            listOf(UvcRecordingPolicy.Size(1280,720)),true))
        assertEquals(UvcRecordingPolicy.Size(1280,720),
            UvcRecordingPolicy.chooseYuy2(1280,720,sizes,true))
    }
    @Test fun disabledSettingAndVgaStayWithSafeMjpeg() {
        assertNull(UvcRecordingPolicy.chooseYuy2(1920,1080,sizes,false))
        assertNull(UvcRecordingPolicy.chooseYuy2(640,480,sizes,true))
    }
    @Test fun bitrateScalesAndIsBounded() {
        assertEquals(20_736_000, UvcRecordingPolicy.bitrateBps(1920,1080))
        assertEquals(9_216_000, UvcRecordingPolicy.bitrateBps(1280,720))
        assertEquals(30_000_000, UvcRecordingPolicy.bitrateBps(3840,2160))
    }
}
