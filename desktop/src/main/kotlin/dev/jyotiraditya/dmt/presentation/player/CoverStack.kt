package dev.jyotiraditya.dmt.presentation.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.jyotiraditya.dmt.core.common.AsciiCover
import dev.jyotiraditya.dmt.core.common.TuiPanel
import dev.jyotiraditya.dmt.domain.model.Track

/** Small (home-shelf sized) art for any track; provided by the screen from the view model. */
val LocalTrackArt = staticCompositionLocalOf<(suspend (Track) -> ImageBitmap)?> { null }

/** How many upcoming covers peek out under the current one. */
private const val STACK_DEPTH = 3
private const val RISE_MS = 560
private val RiseEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Remembers what the stack looked like for the previous track, to tell "next" from a jump. */
private class StackHistory {
    var id: String? = null
    var upNext: List<Track> = emptyList()
    var lastMain: ImageBitmap? = null
    var lastMainRaw = false
    var outgoing: ImageBitmap? = null
    var outgoingRaw = false
    var rise = false
}

/**
 * The current cover with the next tracks of the queue stacked underneath it: each one a bit
 * smaller, lower and darker. When playback moves on to the next track the whole stack rises:
 * the old cover lifts away and fades, every card climbs one step and a new one fades in at the
 * back. [main] draws the full-quality current cover.
 */
@Composable
fun CoverStack(
    state: DmtState,
    modifier: Modifier = Modifier,
    main: @Composable (Modifier) -> Unit,
) {
    val art = LocalTrackArt.current
    val upNext = state.upNext.take(STACK_DEPTH)
    val currentId = state.nowPlayingId
    val rawMode = state.settings.rawArt
    val current = remember(currentId, state.tracks, upNext) {
        currentId?.let { id -> state.tracks.find { it.id.toString() == id } }
    }

    // small art for the cards behind (and for the current one until its full cover is loaded)
    val images = remember(rawMode) { mutableStateMapOf<Long, ImageBitmap>() }
    val wanted = listOfNotNull(current) + upNext
    LaunchedEffect(wanted.map { it.id }, rawMode, art) {
        if (art == null) return@LaunchedEffect
        wanted.forEach { track ->
            if (track.id !in images) runCatching { art(track) }.getOrNull()?.let { images[track.id] = it }
        }
    }

    val history = remember { StackHistory() }
    if (history.id != currentId) {
        history.rise = history.id != null && history.upNext.firstOrNull()?.id?.toString() == currentId
        history.outgoing = history.lastMain
        history.outgoingRaw = history.lastMainRaw
        history.id = currentId
    }
    history.upNext = upNext

    val shift = remember { Animatable(0f) }
    LaunchedEffect(currentId) {
        if (history.rise) {
            shift.snapTo(1f)
            shift.animateTo(0f, tween(RISE_MS, easing = RiseEasing))
        } else {
            shift.snapTo(0f)
        }
        history.rise = false
        history.outgoing = null
    }

    val coverReady = state.coverId != null && state.coverId == currentId
    val mainImage: ImageBitmap?
    val mainRaw: Boolean
    if (coverReady && rawMode && state.artRaw != null) {
        mainImage = state.artRaw
        mainRaw = true
    } else if (coverReady && state.cover != null) {
        mainImage = state.cover
        mainRaw = false
    } else {
        mainImage = current?.let { images[it.id] }
        mainRaw = rawMode
    }
    if (mainImage != null) {
        history.lastMain = mainImage
        history.lastMainRaw = mainRaw
    }

    BoxWithConstraints(contentAlignment = Alignment.TopCenter, modifier = modifier.clipToBounds()) {
        val peek = (maxHeight * 0.05f).coerceIn(10.dp, 28.dp)
        val reserve = peek * STACK_DEPTH
        val side = minOf(maxWidth, maxHeight - reserve).coerceAtLeast(48.dp)
        val peekPx = with(LocalDensity.current) { peek.toPx() }
        val sidePx = with(LocalDensity.current) { side.toPx() }

        Box(modifier = Modifier.width(side).height(side + reserve)) {
            val s = shift.value

            // back to front: upcoming cards (depth 1..3, plus the one fading in during a rise)
            upNext.forEachIndexed { i, track ->
                val image = images[track.id]
                StackLayer(depth = i + 1 + s, peekPx = peekPx, sidePx = sidePx, side = side) {
                    StackCard(image, rawMode)
                }
            }

            // the current cover
            StackLayer(depth = s, peekPx = peekPx, sidePx = sidePx, side = side) {
                if (coverReady && s == 0f) {
                    main(Modifier.fillMaxSize())
                } else {
                    StackCard(mainImage, mainRaw)
                }
            }

            // the previous cover, lifting off the top of the stack
            val outgoing = history.outgoing
            if (s > 0f && outgoing != null) {
                StackLayer(depth = s - 1f, peekPx = peekPx, sidePx = sidePx, side = side) {
                    StackCard(outgoing, history.outgoingRaw)
                }
            }
        }
    }
}

/**
 * One card at a (fractional) [depth]: 0 is the current cover, 1..3 the cards behind it,
 * negative the old cover leaving upwards, above 3 a new card fading in at the back.
 */
@Composable
private fun StackLayer(
    depth: Float,
    peekPx: Float,
    sidePx: Float,
    side: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val alpha = when {
        depth < 0f -> (1f + depth * 1.6f).coerceIn(0f, 1f)
        depth > STACK_DEPTH -> (STACK_DEPTH + 1f - depth).coerceIn(0f, 1f)
        else -> 1f
    }
    if (alpha <= 0f) return
    // 0 -> 0, 1 -> 0.4, 2 -> 0.6, 3 -> 0.8: each card further back is clearly dimmer
    val darkness = (if (depth <= 1f) depth * 0.4f else 0.4f + (depth - 1f) * 0.2f).coerceIn(0f, 0.8f)
    Box(
        modifier = Modifier
            .size(side)
            .zIndex(10f - depth)
            .graphicsLayer {
                transformOrigin = TransformOrigin(0.5f, 1f)
                val scale = if (depth >= 0f) 1f - 0.07f * depth else 1f - 0.04f * depth
                scaleX = scale
                scaleY = scale
                translationY = if (depth >= 0f) depth * peekPx else depth * sidePx * 0.25f
                this.alpha = alpha
            }
            .drawWithContent {
                drawContent()
                if (darkness > 0f) drawRect(Color.Black.copy(alpha = darkness))
            },
    ) {
        content()
    }
}

/** Same frame as the cover panel, with a still (no wave) image. */
@Composable
private fun StackCard(image: ImageBitmap?, raw: Boolean) {
    TuiPanel(modifier = Modifier.fillMaxSize()) {
        when {
            image == null -> Unit
            raw -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .aspectRatio(image.width.toFloat() / image.height),
            )

            else -> AsciiCover(
                cover = image,
                playing = false,
                wave = false,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
