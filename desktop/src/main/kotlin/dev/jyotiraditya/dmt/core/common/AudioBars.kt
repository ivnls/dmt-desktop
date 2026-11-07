package dev.jyotiraditya.dmt.core.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.jyotiraditya.dmt.playback.SPECTRUM_BANDS
import kotlin.math.max
import kotlin.math.min

/** Fills the array with the bands being heard now; false when nothing is playing. */
val LocalSpectrum = staticCompositionLocalOf<((FloatArray) -> Boolean)?> { null }

private val BarTop = Color(0xFFFFC46B)
private val BarMid = Color(0xFFE8A33D)
private val BarLow = Color(0xFF8A3E12)

private const val ATTACK = 0.55f
private const val RELEASE_PER_SECOND = 2.4f
private const val MIN_BAR = 0.04f

/**
 * Bars that follow the music, amber at the base fading to light gold at the top. Rises fast,
 * falls slowly; settles to a low line when paused.
 */
@Composable
fun AudioBars(playing: Boolean, modifier: Modifier = Modifier) {
    val source = LocalSpectrum.current
    val isPlaying by rememberUpdatedState(playing)
    val target = remember { FloatArray(SPECTRUM_BANDS) }
    val shown = remember { FloatArray(SPECTRUM_BANDS) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(source) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                val live = isPlaying && source?.invoke(target) == true
                if (!live) target.fill(0f)
                for (i in shown.indices) {
                    val t = target[i]
                    shown[i] = if (t > shown[i]) {
                        shown[i] + (t - shown[i]) * ATTACK
                    } else {
                        max(t, shown[i] - RELEASE_PER_SECOND * dt * max(shown[i], 0.25f))
                    }
                }
                frame = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame // redraw every frame
        val n = SPECTRUM_BANDS
        val gap = min(4f, size.width / n * 0.35f)
        val barW = (size.width - gap * (n - 1)) / n
        if (barW <= 0f) return@Canvas
        val brush = Brush.verticalGradient(
            0f to BarTop,
            0.45f to BarMid,
            1f to BarLow,
            startY = 0f,
            endY = size.height,
        )
        val radius = CornerRadius(min(barW / 2, 2f))
        for (i in 0 until n) {
            val h = max(MIN_BAR, shown[i]) * size.height
            drawRoundRect(
                brush = brush,
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = radius,
            )
        }
    }
}
