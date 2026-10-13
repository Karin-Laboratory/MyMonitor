package com.karinlab.mymonitor

import org.junit.Assert.*
import org.junit.Test

/** Regression matrix for v016 without a physical Pixel/UVC grabber. */
class CapturePolicyTest {
    @Test fun initialScreenNeedsExplicitConsent() {
        assertEquals(CapturePolicy.Source.AWAITING_SCREEN_PERMISSION,
            CapturePolicy.source(uvcReady = false, projectionReady = false))
        assertTrue(CapturePolicy.needsConsent(true, true, false, false))
        assertFalse(CapturePolicy.needsConsent(false, true, false, false))
        assertFalse(CapturePolicy.needsConsent(true, false, false, false))
    }
    @Test fun usbHasPriorityOverAlreadyConsentedScreen() {
        assertEquals(CapturePolicy.Source.SCREEN, CapturePolicy.source(false, true))
        assertEquals(CapturePolicy.Source.UVC, CapturePolicy.source(true, true))
        assertEquals(CapturePolicy.Source.SCREEN, CapturePolicy.source(false, true))
        assertTrue(CapturePolicy.returnToHomeOnDetach(true, false, true))
        assertFalse(CapturePolicy.returnToHomeOnDetach(false, false, true))
        assertFalse(CapturePolicy.returnToHomeOnDetach(true, true, true))
    }
    @Test fun unplugWithoutExistingScreenMustAskAgain() {
        assertEquals(CapturePolicy.Source.UVC, CapturePolicy.source(true, false))
        assertEquals(CapturePolicy.Source.AWAITING_SCREEN_PERMISSION,
            CapturePolicy.source(false, false))
        assertTrue(CapturePolicy.needsConsent(true, true, false, false))
        assertFalse(CapturePolicy.returnToHomeOnDetach(true, false, false))
    }
    @Test fun screenPermissionCanExpireDuringUvcSession() {
        assertEquals(CapturePolicy.Source.UVC, CapturePolicy.source(true, false))
        assertTrue(CapturePolicy.needsConsent(true, true, false, false))
    }
    private data class Host(val address: String)
    @Test fun receiverSelectionIsDeterministic() {
        val one = listOf(Host("192.168.0.27"))
        assertEquals(one[0], CapturePolicy.choose(one, null) { it.address })
        val many = listOf(Host("192.168.0.27"), Host("192.168.0.88"))
        assertNull(CapturePolicy.choose(many, null) { it.address })
        assertEquals(many[0], CapturePolicy.choose(many, "192.168.0.27") { it.address })
        assertNull(CapturePolicy.choose(many, "192.168.0.99") { it.address })
        assertNull(CapturePolicy.choose(emptyList<Host>(), "192.168.0.27") { it.address })
    }
}
