package dev.jyotiraditya.dmt.core.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Size of the window content in dp, provided by the root of the app. */
val LocalWindowDpSize = compositionLocalOf { DpSize(1100.dp, 720.dp) }

@Composable
fun windowDpSize(): DpSize = LocalWindowDpSize.current

@Composable
fun fitScaleFor(designHeightDp: Float, minScale: Float): Float =
    (windowDpSize().height.value / designHeightDp).coerceIn(minScale, 1f)

/** Desktop windows are "landscape" whenever they are wider than tall (the side-rail layout). */
@Composable
fun isLandscapeWindow(): Boolean {
    val size = windowDpSize()
    return size.width > size.height
}

@Composable
fun isCompactWindow(): Boolean {
    val size = windowDpSize()
    return size.height < 480.dp && size.width < 840.dp
}
