package com.karinlab.mymonitor

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Never rotate HDMI/UVC frame pixels with phone orientation.
 * Compute a centered, aspect-preserving preview inside the actual viewport.
 */
object PreviewGeometry {
    data class Size(val width: Int, val height: Int)
    fun fit(sourceWidth: Int, sourceHeight: Int, viewportWidth: Int, viewportHeight: Int): Size {
        require(sourceWidth > 0 && sourceHeight > 0 && viewportWidth > 0 && viewportHeight > 0)
        val ratio = min(viewportWidth.toDouble() / sourceWidth,
            viewportHeight.toDouble() / sourceHeight)
        return Size((sourceWidth * ratio).roundToInt().coerceIn(1, viewportWidth),
            (sourceHeight * ratio).roundToInt().coerceIn(1, viewportHeight))
    }
}
