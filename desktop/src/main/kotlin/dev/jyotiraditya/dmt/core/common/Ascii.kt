package dev.jyotiraditya.dmt.core.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Typeface
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val RAMP =
    " .'`^\":;Il!i~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"

private const val GLYPH_SIZE = 24f
private const val BASELINE_SHIFT = 0.22f
private const val MIN_ROWS = 8
private const val MAX_ROWS = 96

private val monoTypeface: Typeface? by lazy {
    runCatching {
        val bytes = object {}.javaClass.getResourceAsStream("/fonts/plex_mono.ttf")!!.use { it.readBytes() }
        FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
    }.getOrNull()
}

private fun asciiFont(): Font = Font(monoTypeface, GLYPH_SIZE)

private val Font.cellAdvance: Float
    get() = measureTextWidth("M")

private val Font.cellAspect: Float
    get() = cellAdvance / size

private fun gammaLift(channel: Int): Int {
    val normalized = channel / 255f
    val lifted = 255f * normalized.pow(0.7f)

    return lifted.roundToInt()
}

private fun symbolFor(intensity: Float): Char {
    val lastIndex = RAMP.length - 1
    val index = (intensity * lastIndex)
        .roundToInt()
        .coerceIn(0, lastIndex)

    return RAMP[index]
}

private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

private fun hsvToColor(h: Float, s: Float, v: Float): Int {
    val c = v * s
    val hp = (h / 60f) % 6f
    val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
    val (r1, g1, b1) = when {
        hp < 1 -> Triple(c, x, 0f)
        hp < 2 -> Triple(x, c, 0f)
        hp < 3 -> Triple(0f, c, x)
        hp < 4 -> Triple(0f, x, c)
        hp < 5 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    return argb(
        ((r1 + m) * 255).roundToInt().coerceIn(0, 255),
        ((g1 + m) * 255).roundToInt().coerceIn(0, 255),
        ((b1 + m) * 255).roundToInt().coerceIn(0, 255),
    )
}

private fun renderAsciiGrid(
    font: Font,
    cols: Int,
    rows: Int,
    cell: (x: Int, y: Int, paint: Paint) -> Char,
): ImageBitmap {
    val advance = font.cellAdvance
    val lineH = font.size
    val width = (cols * advance).roundToInt().coerceAtLeast(1)
    val height = (rows * lineH).roundToInt().coerceAtLeast(1)

    val out = Bitmap()
    out.allocN32Pixels(width, height)
    out.erase(0)
    val canvas = SkCanvas(out)
    val paint = Paint().apply { isAntiAlias = true }

    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val symbol = cell(x, y, paint)
            if (symbol == ' ') continue

            val textX = x * advance
            val baselineY = (y + 1) * lineH - lineH * BASELINE_SHIFT
            canvas.drawString(symbol.toString(), textX, baselineY, font, paint)
        }
    }
    canvas.close()
    out.setImmutable()

    return out.asComposeImageBitmap()
}

private fun Image.scaledBitmap(w: Int, h: Int): Bitmap {
    val bitmap = Bitmap()
    bitmap.allocN32Pixels(w, h)
    val canvas = SkCanvas(bitmap)
    canvas.drawImageRect(
        this,
        Rect.makeWH(width.toFloat(), height.toFloat()),
        Rect.makeWH(w.toFloat(), h.toFloat()),
        SamplingMode.LINEAR,
        null,
        true,
    )
    canvas.close()
    return bitmap
}

/** Redraws [image] as coloured ascii, [cols] glyphs wide. */
fun Image.toAsciiBitmap(cols: Int = 96): ImageBitmap {
    val font = asciiFont()

    val rows = (cols.toFloat() * height / width * font.cellAspect)
        .roundToInt()
        .coerceIn(MIN_ROWS, MAX_ROWS)
    val half = Image.makeFromBitmap(scaledBitmap(cols * 2, rows * 2))
    val small = half.scaledBitmap(cols, rows)

    return renderAsciiGrid(font, cols, rows) { x, y, cellPaint ->
        val pixel = small.getColor(x, y)
        val red = gammaLift((pixel shr 16) and 0xFF)
        val green = gammaLift((pixel shr 8) and 0xFF)
        val blue = gammaLift(pixel and 0xFF)
        val lum = (0.299f * red + 0.587f * green + 0.114f * blue) / 255f
        val symbol = symbolFor(lum)

        if (symbol != ' ') {
            cellPaint.color = argb(red, green, blue)
        }

        symbol
    }
}

fun generateAsciiPlaceholder(seed: Long, cols: Int = 96): ImageBitmap {
    val font = asciiFont()
    val rows = (cols * font.cellAspect).roundToInt()

    val random = Random(seed)
    val f1 = 0.10f + random.nextFloat() * 0.25f
    val f2 = 0.10f + random.nextFloat() * 0.25f
    val f3 = 0.05f + random.nextFloat() * 0.15f
    val p1 = random.nextFloat() * 6.28f
    val p2 = random.nextFloat() * 6.28f
    val p3 = random.nextFloat() * 6.28f
    val hue = random.nextFloat() * 360f

    return renderAsciiGrid(font, cols, rows) { x, y, cellPaint ->
        val v = (
                sin(x * f1 + p1) +
                        sin(y * f2 + p2) +
                        sin((x + y) * f3 + p3) +
                        3f
                ) / 6f
        val symbol = symbolFor(v)

        if (symbol != ' ') {
            cellPaint.color = hsvToColor(hue, 0.30f, 0.20f + v * 0.45f)
        }

        symbol
    }
}

fun generateCoverPlaceholder(seed: Long, size: Int = 256): Image {
    val random = Random(seed)
    val f1 = 0.10f + random.nextFloat() * 0.25f
    val f2 = 0.10f + random.nextFloat() * 0.25f
    val f3 = 0.05f + random.nextFloat() * 0.15f
    val p1 = random.nextFloat() * 6.28f
    val p2 = random.nextFloat() * 6.28f
    val p3 = random.nextFloat() * 6.28f
    val hue = random.nextFloat() * 360f
    val cells = 32f

    val bytes = ByteArray(size * size * 4)
    for (y in 0 until size) {
        for (x in 0 until size) {
            val u = x * cells / size
            val v = y * cells / size
            val field = (
                    sin(u * f1 + p1) +
                            sin(v * f2 + p2) +
                            sin((u + v) * f3 + p3) +
                            3f
                    ) / 6f
            val color = hsvToColor(hue, 0.30f, 0.10f + field * 0.75f)
            val i = (y * size + x) * 4
            bytes[i] = (color and 0xFF).toByte()
            bytes[i + 1] = ((color shr 8) and 0xFF).toByte()
            bytes[i + 2] = ((color shr 16) and 0xFF).toByte()
            bytes[i + 3] = 0xFF.toByte()
        }
    }

    return Image.makeRaster(
        ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.PREMUL),
        bytes,
        size * 4,
    )
}

@Composable
fun AsciiCover(
    cover: ImageBitmap,
    playing: Boolean,
    modifier: Modifier = Modifier,
    wave: Boolean = true,
    fitHeight: Boolean = false,
) {
    val image = cover
    val aspect = cover.width.toFloat() / cover.height
    if (playing && wave) {
        WaveCover(image, aspect, fitHeight, modifier)
    } else {
        Canvas(modifier = modifier.fitAspect(aspect)) {
            drawImage(
                image = image,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

@Composable
private fun WaveCover(
    image: ImageBitmap,
    aspect: Float,
    fitHeight: Boolean,
    modifier: Modifier,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 5200,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Canvas(
        modifier = modifier
            .fitAspect(aspect)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
    ) {
        drawImage(
            image = image,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val cx = (-0.6f + phase * 2.8f) * size.width
        val band = size.width * 0.28f
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.3f to Color.White.copy(alpha = 0.12f),
                    0.5f to Color.White.copy(alpha = 0.55f),
                    0.7f to Color.White.copy(alpha = 0.12f),
                    1.0f to Color.Transparent,
                ),
                start = Offset(cx - band, 0f),
                end = Offset(cx + band, size.height * 0.45f),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/**
 * Like aspectRatio(), but always "contain": the cover shrinks to fit whichever side is tighter
 * and is centred in the space it was given. Desktop windows are far wider than phones, where
 * aspectRatio() would let the art spill over its neighbours.
 */
fun Modifier.fitAspect(aspect: Float): Modifier = layout { measurable, c ->
    val (w, h) = when {
        c.hasBoundedWidth && c.hasBoundedHeight -> {
            val w0 = minOf(c.maxWidth.toFloat(), c.maxHeight * aspect)
            w0 to w0 / aspect
        }

        c.hasBoundedWidth -> c.maxWidth.toFloat() to c.maxWidth / aspect
        c.hasBoundedHeight -> c.maxHeight * aspect to c.maxHeight.toFloat()
        else -> 256f to 256f / aspect
    }
    val width = w.roundToInt().coerceAtLeast(1)
    val height = h.roundToInt().coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    val outW = width.coerceIn(c.minWidth, if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE)
    val outH = height.coerceIn(c.minHeight, if (c.hasBoundedHeight) c.maxHeight else Int.MAX_VALUE)
    layout(outW, outH) {
        placeable.place((outW - width) / 2, (outH - height) / 2)
    }
}
