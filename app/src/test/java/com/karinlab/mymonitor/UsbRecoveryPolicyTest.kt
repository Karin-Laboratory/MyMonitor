package com.karinlab.mymonitor

import org.junit.Assert.*
import org.junit.Test

class UsbRecoveryPolicyTest {
    @Test fun staleCallbacksMustNotChangeNewMonitorState() {
        assertTrue(UsbRecoveryPolicy.acceptEvent(5, 5))
        assertFalse(UsbRecoveryPolicy.acceptEvent(4, 5))
        assertFalse(UsbRecoveryPolicy.acceptEvent(3, 5))
    }

    @Test fun spuriousDisconnectMustNotDestroyActiveCamera() {
        assertFalse(UsbRecoveryPolicy.shouldCloseOnDisconnect(true))
        assertTrue(UsbRecoveryPolicy.shouldCloseOnDisconnect(false))
    }

    @Test fun nativeFailureRetriesAreBounded() {
        assertEquals(1500L, UsbRecoveryPolicy.retryDelayMs(1))
        assertEquals(3500L, UsbRecoveryPolicy.retryDelayMs(2))
        assertEquals(9000L, UsbRecoveryPolicy.retryDelayMs(3))
        assertEquals(9000L, UsbRecoveryPolicy.retryDelayMs(100))
    }
}
