package com.karinlab.mymonitor

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Never rotate HDMI/UVC frame pixels with phone orientation.
 * Compute a centered, aspect-preserving preview inside the actual viewport.
 */
object PreviewGeometry {
    data class Size(val width: Int, val height: Int)
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int)
    fun fit(sourceWidth: Int, sourceHeight: Int, viewportWidth: Int, viewportHeight: Int): Size {
        require(sourceWidth > 0 && sourceHeight > 0 && viewportWidth > 0 && viewportHeight > 0)
        val ratio = min(viewportWidth.toDouble() / sourceWidth,
            viewportHeight.toDouble() / sourceHeight)
        return Size((sourceWidth * ratio).roundToInt().coerceIn(1, viewportWidth),
            (sourceHeight * ratio).roundToInt().coerceIn(1, viewportHeight))
    }
    /** Pure geometry regression: no aspect correction must occur a second time. */
    fun centered(sourceWidth: Int, sourceHeight: Int,
                 viewportWidth: Int, viewportHeight: Int): Rect {
        val size = fit(sourceWidth, sourceHeight, viewportWidth, viewportHeight)
        return Rect((viewportWidth - size.width) / 2,
            (viewportHeight - size.height) / 2, size.width, size.height)
    }
}
