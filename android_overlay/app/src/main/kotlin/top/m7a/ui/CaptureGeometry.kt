package top.m7a.ui

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure capture output sizing, independent of M7A's 1280x720 *game* virtual display. */
internal data class CaptureDimensions(val width: Int, val height: Int)

/** Preserve orientation and aspect ratio; bound raw ImageReader memory on high-DPI tablets. */
internal fun captureDimensions(width: Int, height: Int, maxLongEdge: Int = 1280): CaptureDimensions {
    require(width > 0 && height > 0 && maxLongEdge > 0) { "Invalid captured content size" }
    val longest = max(width, height)
    if (longest <= maxLongEdge) return CaptureDimensions(width, height)
    val scale = maxLongEdge.toDouble() / longest
    return CaptureDimensions(
        max(1, (width * scale).roundToInt()),
        max(1, (height * scale).roundToInt()),
    )
}
