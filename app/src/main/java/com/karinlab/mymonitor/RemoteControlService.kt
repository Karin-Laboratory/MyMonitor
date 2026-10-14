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

/** User-enabled accessibility service. Only accepts controls during a live screen stream. */
class RemoteControlService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteControlService? = null
        private val main = Handler(Looper.getMainLooper())
        fun receive(packet: ByteArray) {
            val service = instance ?: return
            if (packet.size != 9 || !StreamHub.enabled || StreamHub.source() != CapturePolicy.Source.SCREEN ||
                !StreamHub.controlArmed || !StreamHub.sender.hasTransportConnection() || !ScreenStreamService.projectionActive) return
            val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguard.isKeyguardLocked) return
            val op = packet[0].toInt() and 255
            if (op !in 1..5) return
            fun word(i: Int) = ((packet[i].toInt() and 255) shl 8) or (packet[i+1].toInt() and 255)
            val x1 = word(1); val y1 = word(3); val x2 = word(5); val y2 = word(7)
            if (listOf(x1,y1,x2,y2).any { it > 65535 }) return
            main.post {
                if (instance !== service || !StreamHub.enabled || !StreamHub.controlArmed ||
                    StreamHub.source() != CapturePolicy.Source.SCREEN || !ScreenStreamService.projectionActive ||
                    keyguard.isKeyguardLocked) return@post
                val metrics = service.resources.displayMetrics
                fun x(v: Int) = (v / 65535f * (metrics.widthPixels-1)).coerceIn(0f,metrics.widthPixels.toFloat()-1)
                fun y(v: Int) = (v / 65535f * (metrics.heightPixels-1)).coerceIn(0f,metrics.heightPixels.toFloat()-1)
                val builder = GestureDescription.Builder()
                fun stroke(ax: Int, ay: Int, bx: Int, by: Int, duration: Long) {
                    val path = Path().apply { moveTo(x(ax), y(ay)); lineTo(x(bx), y(by)) }
                    builder.addStroke(GestureDescription.StrokeDescription(path,0,duration))
                }
                when (op) {
                    1 -> stroke(x1,y1,x1,y1,80)
                    2 -> stroke(x1,y1,x1,y1,700)
                    3 -> stroke(x1,y1,x2,y2,350)
                    4 -> { stroke(x1,y1,x2,y2,400)
                           stroke(65535-x1,65535-y1,65535-x2,65535-y2,400) }
                    5 -> { service.performGlobalAction(GLOBAL_ACTION_BACK); return@post }
                }
                service.dispatchGesture(builder.build(),null,null)
            }
        }
        fun active() = instance != null
    }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; Log.i("MyMonitor-Control","Accessibility enabled") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
}
