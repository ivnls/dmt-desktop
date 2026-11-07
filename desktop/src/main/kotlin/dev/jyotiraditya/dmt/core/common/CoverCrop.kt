package dev.jyotiraditya.dmt.core.common

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val SAMPLE = 192
/** a border line may differ this much (per channel) from the outermost line */
private const val EDGE_TOLERANCE = 28
/** ...and be at most this noisy itself */
private const val LINE_MAX_STDDEV = 14.0
/** never trim more than this share of a side */
private const val MAX_TRIM = 0.3f
/** close enough to square: leave alone */
private const val SQUARE_SLACK = 0.03f

/**
 * Album art as a square: embedded covers are often not. Video thumbnails come as 16:9 with the
 * art in the middle, scans have black or white margins. First trims flat bars (letterbox,
 * pillarbox, margins), then centre-crops what is left to a square.
 */
fun Image.squareCover(): Image {
    if (width <= 0 || height <= 0) return this
    val trimmed = runCatching { trimFlatBorders() }.getOrNull() ?: Rect.makeWH(width.toFloat(), height.toFloat())
    val w = trimmed.width
    val h = trimmed.height
    val side = min(w, h)
    val crop = Rect.makeXYWH(
        trimmed.left + (w - side) / 2f,
        trimmed.top + (h - side) / 2f,
        side,
        side,
    )
    if (abs(crop.width - width) < 1f && abs(crop.height - height) < 1f) return this
    if (abs(w / h - 1f) < SQUARE_SLACK && crop.width >= width - 1f && crop.height >= height - 1f) return this
    val px = side.roundToInt().coerceAtLeast(1)
    val out = Bitmap()
    out.allocN32Pixels(px, px)
    Canvas(out).use { canvas ->
        canvas.drawImageRect(this, crop, Rect.makeWH(px.toFloat(), px.toFloat()), SamplingMode.LINEAR, null, true)
    }
    out.setImmutable()
    return Image.makeFromBitmap(out)
}

/** The image area without flat bars, in source pixels. */
private fun Image.trimFlatBorders(): Rect {
    val scale = min(1f, SAMPLE.toFloat() / maxOf(width, height))
    val sw = (width * scale).roundToInt().coerceAtLeast(1)
    val sh = (height * scale).roundToInt().coerceAtLeast(1)
    val bmp = Bitmap()
    bmp.allocN32Pixels(sw, sh)
    Canvas(bmp).use { it.drawImageRect(this, Rect.makeWH(width.toFloat(), height.toFloat()), Rect.makeWH(sw.toFloat(), sh.toFloat()), SamplingMode.LINEAR, null, true) }

    fun px(x: Int, y: Int): Int = bmp.getColor(x, y)
    fun row(y: Int) = IntArray(sw) { px(it, y) }
    fun col(x: Int) = IntArray(sh) { px(x, it) }

    val maxX = (sw * MAX_TRIM).toInt()
    val maxY = (sh * MAX_TRIM).toInt()
    val top = flatRun(maxY) { row(it) }
    val bottom = flatRun(maxY) { row(sh - 1 - it) }
    val left = flatRun(maxX) { col(it) }
    val right = flatRun(maxX) { col(sw - 1 - it) }

    val inv = 1f / scale
    return Rect.makeLTRB(
        left * inv,
        top * inv,
        width - right * inv,
        height - bottom * inv,
    )
}

/** How many lines from an edge are flat and match the outermost one. */
private inline fun flatRun(limit: Int, line: (Int) -> IntArray): Int {
    val edge = line(0)
    if (!isFlat(edge)) return 0
    val ref = mean(edge)
    var n = 1
    while (n < limit) {
        val l = line(n)
        if (!isFlat(l)) break
        val m = mean(l)
        if ((0..2).any { abs(m[it] - ref[it]) > EDGE_TOLERANCE }) break
        n++
    }
    return if (n <= 1) 0 else n
}

private fun channel(c: Int, i: Int): Int = when (i) {
    0 -> (c shr 16) and 0xFF
    1 -> (c shr 8) and 0xFF
    else -> c and 0xFF
}

private fun mean(line: IntArray): DoubleArray =
    DoubleArray(3) { ch -> line.sumOf { channel(it, ch) }.toDouble() / line.size }

private fun isFlat(line: IntArray): Boolean {
    val m = mean(line)
    return (0..2).all { ch ->
        val variance = line.sumOf { val d = channel(it, ch) - m[ch]; d * d } / line.size
        sqrt(variance) <= LINE_MAX_STDDEV
    }
}
