package dev.jyotiraditya.dmt.core

import dev.jyotiraditya.dmt.core.common.squareCover
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CoverCropTest {

    private fun image(w: Int, h: Int, draw: Canvas.() -> Unit): Image {
        val b = Bitmap()
        b.allocN32Pixels(w, h)
        Canvas(b).draw()
        b.setImmutable()
        return Image.makeFromBitmap(b)
    }

    private fun Canvas.fill(color: Int, r: Rect) = drawRect(r, Paint().apply { this.color = color })

    /** a "real" cover: noisy stripes, so it is never mistaken for a flat bar */
    private fun Canvas.art(x: Float, y: Float, side: Float) {
        val n = 16
        for (i in 0 until n) for (j in 0 until n) {
            val c = if ((i + j) % 2 == 0) 0xFFE8A33D.toInt() else 0xFF2050A0.toInt()
            fill(c, Rect.makeXYWH(x + i * side / n, y + j * side / n, side / n, side / n))
        }
    }

    private fun centerColor(img: Image): Int {
        val b = Bitmap()
        b.allocN32Pixels(img.width, img.height)
        Canvas(b).drawImage(img, 0f, 0f)
        return b.getColor(img.width / 2 + 3, img.height / 2 + 3)
    }

    @Test
    fun squareStaysAsIs() {
        val img = image(300, 300) { art(0f, 0f, 300f) }
        assertSame(img, img.squareCover())
    }

    @Test
    fun pillarboxedVideoThumbnailBecomesTheArt() {
        // 1280x720 with black bars left/right and the 720px art in the middle
        val img = image(1280, 720) {
            fill(0xFF000000.toInt(), Rect.makeWH(1280f, 720f))
            art(280f, 0f, 720f)
        }
        val out = img.squareCover()
        assertEquals(out.width, out.height)
        assertTrue("side ${out.width}", abs(out.width - 720) <= 8)
    }

    @Test
    fun wideImageWithoutBarsIsCenterCropped() {
        val img = image(1600, 900) { art(0f, 0f, 1600f) }
        val out = img.squareCover()
        assertEquals(900, out.width)
        assertEquals(900, out.height)
    }

    @Test
    fun whiteMarginsOfAScanAreTrimmed() {
        val img = image(1000, 1200) {
            fill(0xFFFFFFFF.toInt(), Rect.makeWH(1000f, 1200f))
            art(100f, 200f, 800f)
        }
        val out = img.squareCover()
        assertTrue("side ${out.width}", abs(out.width - 800) <= 12)
        assertTrue(centerColor(out) != 0xFFFFFFFF.toInt())
    }
}
