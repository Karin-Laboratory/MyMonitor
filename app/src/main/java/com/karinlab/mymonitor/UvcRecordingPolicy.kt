package com.karinlab.mymonitor

/**
 * Recording-only format selection. USB UVC negotiation decides whether an
 * advertised YUYV size is actually usable at the connected bus speed.
 * Do not sacrifice a Full HD recording for non-compressed 720p.
 */
internal object UvcRecordingPolicy {
    data class Size(val width: Int, val height: Int)

    fun chooseYuy2(inputWidth: Int, inputHeight: Int,
                   supported: List<Size>, highQualityEnabled: Boolean): Size? {
        if (!highQualityEnabled || inputWidth <= 0 || inputHeight <= 0) return null
        return supported.firstOrNull {
            it.width == inputWidth && it.height == inputHeight &&
                it.width >= 1280 && it.height >= 720 &&
                it.width % 2 == 0 && it.height % 2 == 0
        }
    }

    fun bitrateBps(width: Int, height: Int): Int =
        (width.toLong() * height.toLong() * 10L).coerceIn(6_000_000L, 30_000_000L).toInt()
}
