package com.karinlab.mymonitor

/**
 * The application has ONE implicit input, not a user-visible mode switch.
 * Keep this pure: capture source arbitration can be unit-tested without Android
 * hardware, projection permissions or a Windows machine.
 */
object CapturePolicy {
    enum class Source { UVC, SCREEN, AWAITING_SCREEN_PERMISSION }
    enum class Event { UVC_ATTACHED, UVC_REMOVED, PROJECTION_GRANTED, PROJECTION_STOPPED }

    fun source(uvcReady: Boolean, projectionReady: Boolean): Source = when {
        uvcReady -> Source.UVC
        projectionReady -> Source.SCREEN
        else -> Source.AWAITING_SCREEN_PERMISSION
    }

    /** Only ask for consent when no real UVC preview or projection is available. */
    fun needsConsent(receiverSelected: Boolean, streamEnabled: Boolean,
                     uvcReady: Boolean, projectionReady: Boolean): Boolean =
        streamEnabled && receiverSelected &&
            source(uvcReady, projectionReady) == Source.AWAITING_SCREEN_PERMISSION

    /** A UVC unplug only needs to surface the user's existing Android screen. */
    fun returnToHomeOnDetach(hadUvc: Boolean, physicalUvcPresent: Boolean,
                             projectionReady: Boolean): Boolean =
        hadUvc && !physicalUvcPresent && projectionReady

    /** Pick the previously verified receiver only when it still passes verification. */
    fun <T> choose(receivers: List<T>, saved: String?, address: (T) -> String): T? =
        when {
            receivers.size == 1 -> receivers.first()
            else -> receivers.firstOrNull { saved != null && address(it) == saved }
        }
}
