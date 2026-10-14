package com.karinlab.mymonitor

/**
 * Pure USB hotplug policy. Android USBMonitor callbacks can outlive their
 * registrations, so only events from the current generation may change state.
 */
internal object UsbRecoveryPolicy {
    fun acceptEvent(callbackEpoch: Int, activeEpoch: Int): Boolean =
        callbackEpoch == activeEpoch

    fun shouldCloseOnDisconnect(physicalDevicePresent: Boolean): Boolean =
        !physicalDevicePresent

    fun retryDelayMs(failures: Int): Long = when {
        failures <= 1 -> 1_500L
        failures == 2 -> 3_500L
        else -> 9_000L
    }
}
