package top.m7a.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device visual control detection for exactly one low-impact goal:
 * main screen -> phone menu, based on M7A touch_720/screens.json.
 *
 * Model coordinates are NEVER accepted. Actual click coordinates are obtained
 * from the independently matched local bitmap and scaled to display 0.
 */
internal object SafeGameMenuDetector {
    private const val MAIN_TEMPLATE = "assets/touch_720/images/screen/main.png"
    private const val MENU_TEMPLATE = "assets/touch_720/images/screen/menu.png"

    internal data class MenuControlPoint(val x: Int, val y: Int, val score: Double)

    fun findMainMenuControl(context: Context, frame: Bitmap): MenuControlPoint? {
        if (frame.width < 300 || frame.height < 160) return null
        val template = loadTemplate(context, MAIN_TEMPLATE) ?: return null
        return try {
            // Source M7A main->menu target appears in the top-left HUD.
            // Use the image rather than assume a 1920x1080/16:9 screen.
            val region = Rect(0, 0, (frame.width * 0.16).toInt(), (frame.height * 0.30).toInt())
            val hit = findMatch(frame, template, region, threshold = 0.94) ?: return null
            val centerX = hit.x + hit.width / 2
            val centerY = hit.y + hit.height / 2
            // Never allow a matched pixel outside the expected harmless HUD region.
            if (centerX <= 1 || centerY <= 1 ||
                centerX > frame.width * 0.16 || centerY > frame.height * 0.30) null
            else MenuControlPoint(centerX, centerY, hit.score)
        } finally {
            template.recycle()
        }
    }

    fun isMenuVisible(context: Context, frame: Bitmap): Boolean {
        if (frame.width < 300 || frame.height < 160) return false
        val template = loadTemplate(context, MENU_TEMPLATE) ?: return false
        return try {
            // Post-action observer only: bounded 320px image, no target click.
            val width = min(frame.width, 320)
            val height = (frame.height.toDouble() * width / frame.width).toInt()
            val small = Bitmap.createScaledBitmap(frame, width, height, true)
            try {
                findMatch(small, template, Rect(0, 0, small.width, small.height),
                          threshold = 0.96) != null
            } finally { if (small !== frame) small.recycle() }
        } finally {
            template.recycle()
        }
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
    private data class Hit(val x: Int, val y: Int, val width: Int, val height: Int, val score: Double)

    private fun loadTemplate(context: Context, name: String): Bitmap? = runCatching {
        context.assets.open("project.zip").use { source ->
            ZipInputStream(source.buffered()).use { zip ->
                var found: Bitmap? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == name && !entry.isDirectory) {
                        val bytes = readBoundedEntry(zip)
                        if (bytes != null) found = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
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
        frame: Bitmap, source: Bitmap, region: Rect, threshold: Double,
    ): Hit? {
        val scanWidth = region.right - region.left
        val scanHeight = region.bottom - region.top
        if (scanWidth < 12 || scanHeight < 12 || source.width < 6 || source.height < 6) return null
        val framePixels = IntArray(frame.width * frame.height)
        frame.getPixels(framePixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val frameGray = IntArray(framePixels.size) { gray(framePixels[it]) }
        val baseScale = frame.height / 720.0
        var best: Hit? = null
        for (factor in listOf(1.17, 1.0, 0.82, 0.68)) {
            val tw = (source.width * baseScale * factor).toInt()
            val th = (source.height * baseScale * factor).toInt()
            if (tw !in 8..90 || th !in 8..90 || tw > scanWidth || th > scanHeight) continue
            val scaled = Bitmap.createScaledBitmap(source, tw, th, true)
            try {
                val sourcePixels = IntArray(tw * th)
                scaled.getPixels(sourcePixels, 0, tw, 0, 0, tw, th)
                val ref = IntArray(sourcePixels.size) { gray(sourcePixels[it]) }
                val step = if (scanWidth > 100) 3 else 1
                for (y in region.top..region.bottom - th step step) {
                    for (x in region.left..region.right - tw step step) {
                        val similarity = scoreAt(ref, frameGray, frame.width, x, y, tw, th)
                        if (similarity >= threshold &&
                            (best == null || similarity > best.score)) {
                            best = Hit(x, y, tw, th, similarity)
                        }
                    }
                }
            } finally { if (scaled !== source) scaled.recycle() }
        }
        return best
    }

    private fun gray(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    private fun scoreAt(ref: IntArray, pixels: IntArray, stride: Int,
                        x: Int, y: Int, width: Int, height: Int): Double {
        var sumA = 0.0; var sumB = 0.0
        var sqA = 0.0; var sqB = 0.0; var cross = 0.0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val a = ref[row * width + col].toDouble()
                val b = pixels[(y + row) * stride + x + col].toDouble()
                sumA += a; sumB += b
                sqA += a * a; sqB += b * b; cross += a * b
            }
        }
        val n = (width * height).toDouble()
        val va = n * sqA - sumA * sumA
        val vb = n * sqB - sumB * sumB
        return if (va <= 1.0 || vb <= 1.0) -1.0
            else ((n * cross - sumA * sumB) / sqrt(va * vb)).coerceIn(-1.0, 1.0)
    }

    /** Pure JVM-checkable template correlation, not an execution permit. */
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
