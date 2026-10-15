package com.karinlab.mymonitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import android.view.WindowManager
import android.graphics.Point
import java.util.ArrayDeque

/**
 * Explicitly armed remote control of the PHONE SCREEN only.
 * dispatchGesture() cancels any gesture still in progress. v029 serializes
 * incoming packets via GestureResultCallback instead of overlapping strokes.
 */
class RemoteControlService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteControlService? = null
        private val main = Handler(Looper.getMainLooper())
        // Only accessed on the main looper, including gesture callbacks.
        private val pending = ArrayDeque<ByteArray>()
        private var gestureRunning = false
        private var gestureSerial = 0L

        private fun resetQueued() {
            pending.clear()
            gestureRunning = false
            gestureSerial++ // invalidate callbacks belonging to a previous service
        }

        private fun canControl(service: RemoteControlService): Boolean {
            if (instance !== service || !StreamHub.enabled ||
                StreamHub.source() != CapturePolicy.Source.SCREEN ||
                !StreamHub.controlArmed || !StreamHub.sender.hasTransportConnection() ||
                !ScreenStreamService.projectionActive) return false
            val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return !keyguard.isKeyguardLocked
        }

        fun receive(packet: ByteArray) {
            val service = instance ?: return
            if (packet.size != 17 || !canControl(service)) return
            val op = packet[0].toInt() and 255
            if (op !in 1..7) return
            main.post {
                if (!canControl(service)) {
                    resetQueued()
                    return@post
                }
                // Bound latency when a high-resolution wheel generates a storm of
                // events. Windows already merges detents into timed pinch packets.
                // If still overloaded, discard the oldest WAITING pinch; never
                // interrupt the in-flight gesture or drop a pending button event.
                if (op == 4 && pending.count { (it[0].toInt() and 255) == 4 } >= 8) {
                    val it = pending.iterator()
                    while (it.hasNext()) {
                        if ((it.next()[0].toInt() and 255) == 4) {
                            it.remove()
                            break
                        }
                    }
                }
                if (pending.size >= 32) {
                    Log.w("MyMonitor-Control", "Remote input queue full, dropping newest")
                    return@post
                }
                pending.addLast(packet.copyOf())
                pump(service)
            }
        }

        private fun pump(service: RemoteControlService) {
            if (gestureRunning) return
            while (pending.isNotEmpty()) {
                if (!canControl(service)) {
                    resetQueued()
                    return
                }
                val packet = pending.removeFirst()
                val op = packet[0].toInt() and 255
                if (op in 5..7) {
                    service.performGlobalAction(
                        if (op == 6) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK
                    )
                    continue
                }
                fun word(i: Int) = ((packet[i].toInt() and 255) shl 8) or
                    (packet[i + 1].toInt() and 255)
                val x1 = word(1); val y1 = word(3)
                val x2 = word(5); val y2 = word(7)
                val x3 = word(9); val y3 = word(11)
                val x4 = word(13); val y4 = word(15)

                // Use the FULL physical display including navigation/status areas.
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val size = Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(size)
                val fullW = size.x.coerceAtLeast(1)
                val fullH = size.y.coerceAtLeast(1)
                fun x(v: Int) = (v / 65535f * (fullW - 1)).coerceIn(0f, (fullW - 1).toFloat())
                fun y(v: Int) = (v / 65535f * (fullH - 1)).coerceIn(0f, (fullH - 1).toFloat())
                val builder = GestureDescription.Builder()
                fun stroke(ax: Int, ay: Int, bx: Int, by: Int, duration: Long) {
                    val path = Path().apply { moveTo(x(ax), y(ay)); lineTo(x(bx), y(by)) }
                    builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                }
                when (op) {
                    1 -> stroke(x1, y1, x1, y1, 80)
                    2 -> stroke(x1, y1, x1, y1, 700)
                    3 -> stroke(x1, y1, x2, y2, 350)
                    4 -> {
                        // Same start time/duration for both fingers. Short enough
                        // for responsive map zoom, but never overlap the next pinch.
                        stroke(x1, y1, x2, y2, 220)
                        stroke(x3, y3, x4, y4, 220)
                    }
                    else -> continue
                }
                gestureRunning = true
                val serial = ++gestureSerial
                val accepted = service.dispatchGesture(builder.build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gesture: GestureDescription?) = finish()
                        override fun onCancelled(gesture: GestureDescription?) = finish()
                        private fun finish() {
                            if (instance !== service || gestureSerial != serial) return
                            gestureRunning = false
                            pump(service)
                        }
                    }, main)
                if (!accepted) {
                    // Accessibility service can reject a gesture. Do not wedge
                    // future mouse commands behind an unsignaled callback.
                    gestureRunning = false
                    Log.w("MyMonitor-Control", "dispatchGesture rejected op=$op")
                    continue
                }
                return // Wait for onCompleted/onCancelled before dispatching again.
            }
        }

        fun active() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        resetQueued()
        instance = this
        Log.i("MyMonitor-Control", "Accessibility enabled")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (instance === this) {
            instance = null
            resetQueued()
        }
        super.onDestroy()
    }
}
