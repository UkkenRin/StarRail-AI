package top.m7a.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Local template verification for exactly one low-risk game UI control:
 * opening the phone/menu from the overworld main screen.
 *
 * Reference: M7A touch_720/screens.json main -> menu and main.png. Pixels
 * come only from a CURRENT local MediaProjection frame, never a model answer.
 * Fail-closed on unexpected aspect ratio or missing assets. The detector
 * intentionally does not accept model-selected coordinates.
 */
internal object SafeGameMenuDetector {
    private const val MAIN_TEMPLATE = "assets/touch_720/images/screen/main.png"
    private const val MENU_TEMPLATE = "assets/touch_720/images/screen/menu.png"

    fun isOverworldMenuControlVisible(context: Context, frame: Bitmap): Boolean {
        if (!validAspect(frame)) return false
        // Source M7A guide: template is found in 18..90 x 60..138 on 1920x1080.
        val left = (frame.width * 18.0 / 1920).toInt()
        val top = (frame.height * 60.0 / 1080).toInt()
        val right = (frame.width * 90.0 / 1920).toInt().coerceAtMost(frame.width)
        val bottom = (frame.height * 138.0 / 1080).toInt().coerceAtMost(frame.height)
        val template = loadTemplate(context, MAIN_TEMPLATE) ?: return false
        return try {
            findMatch(frame, template, left, top, right, bottom, threshold = 0.94)
        } finally { template.recycle() }
    }

    fun isMenuVisible(context: Context, frame: Bitmap): Boolean {
        if (!validAspect(frame)) return false
        val template = loadTemplate(context, MENU_TEMPLATE) ?: return false
        return try {
            // Menu has no defined source crop; only useful as a POST-action
            // additional clue; never authorize an input from this check.
            findMatch(frame, template, 0, 0, frame.width, frame.height, threshold = 0.96)
        } finally { template.recycle() }
    }

    private fun validAspect(frame: Bitmap): Boolean =
        frame.width >= 300 && frame.height >= 160 &&
            frame.width.toDouble() / frame.height in 1.72..1.83

    private fun loadTemplate(context: Context, name: String): Bitmap? =
        runCatching {
            context.assets.open("project.zip").use { source ->
                ZipInputStream(source.buffered()).use { zip ->
                    var found: Bitmap? = null
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == name && !entry.isDirectory) {
                            val bytes = readBoundedEntry(zip)
                            if (bytes != null) {
                                found = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            break
                        }
                        zip.closeEntry()
                    }
                    found
                }
            }
        }.getOrNull()

    private fun readBoundedEntry(zip: ZipInputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = zip.read(buffer)
            if (n <= 0) return output.toByteArray()
            if (output.size() + n > 256_000) return null
            output.write(buffer, 0, n)
        }
    }

    private fun findMatch(
        frame: Bitmap, source: Bitmap,
        left: Int, top: Int, right: Int, bottom: Int, threshold: Double,
    ): Boolean {
        val areaWidth = right - left
        val areaHeight = bottom - top
        if (areaWidth < 10 || areaHeight < 10 || source.width < 6 || source.height < 6) return false
        // Smaller templates reduce CPU cost. Different scales fail closed
        // rather than treating mismatched game resolutions as trusted input.
        val baseScale = min(areaWidth.toDouble() / source.width,
                            areaHeight.toDouble() / source.height)
        for (factor in listOf(1.0, 0.82, 0.68)) {
            val tw = (source.width * baseScale * factor).toInt()
            val th = (source.height * baseScale * factor).toInt()
            if (tw !in 8..90 || th !in 8..90 || tw > areaWidth || th > areaHeight) continue
            val scaled = Bitmap.createScaledBitmap(source, tw, th, true)
            try {
                val templatePixels = IntArray(tw * th)
                scaled.getPixels(templatePixels, 0, tw, 0, 0, tw, th)
                val signature = IntArray(tw * th) { n -> grayscale(templatePixels[n]) }
                // Bound full screen scan: sample at 2px. Main control scans
                // the tiny expected top-left region at single-pixel precision.
                val stride = if (areaWidth > 100) 3 else 1
                for (y in top..bottom - th step stride) {
                    for (x in left..right - tw step stride) {
                        val observed = IntArray(tw * th)
                        for (row in 0 until th) {
                            for (col in 0 until tw) {
                                observed[row * tw + col] = grayscale(frame.getPixel(x + col, y + row))
                            }
                        }
                        if (normalizedCorrelation(signature, observed) >= threshold) return true
                    }
                }
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        }
        return false
    }

    private fun grayscale(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    /** Pure algorithm for deterministic JVM tests without an Android bitmap. */
    internal fun normalizedCorrelation(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.size < 16) return -1.0
        val meanA = a.average()
        val meanB = b.average()
        var cross = 0.0
        var varA = 0.0
        var varB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            cross += da * db
            varA += da * da
            varB += db * db
        }
        return if (varA < 0.01 || varB < 0.01) -1.0
            else (cross / sqrt(varA * varB)).coerceIn(-1.0, 1.0)
    }
}
