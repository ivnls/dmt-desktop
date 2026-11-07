package dev.jyotiraditya.dmt.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.jyotiraditya.dmt.resources.Res
import dev.jyotiraditya.dmt.resources.plex_mono
import dev.jyotiraditya.dmt.resources.plex_mono_bold
import dev.jyotiraditya.dmt.resources.plex_mono_italic
import dev.jyotiraditya.dmt.resources.plex_mono_medium
import org.jetbrains.compose.resources.Font

@Composable
fun plexMono(): FontFamily {
    val regular = Font(Res.font.plex_mono, FontWeight.Normal)
    val medium = Font(Res.font.plex_mono_medium, FontWeight.Medium)
    val bold = Font(Res.font.plex_mono_bold, FontWeight.Bold)
    val italic = Font(Res.font.plex_mono_italic, FontWeight.Normal, FontStyle.Italic)
    return remember(regular, medium, bold, italic) { FontFamily(regular, medium, bold, italic) }
}

private fun mono(
    family: FontFamily,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Float = 0f,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

@Composable
fun dmtTypography(): Typography {
    val family = plexMono()
    return remember(family) {
        Typography(
            headlineSmall = mono(family, 15),
            titleLarge = mono(family, 21, FontWeight.Bold),
            titleMedium = mono(family, 15, FontWeight.Bold),
            bodyLarge = mono(family, 14),
            bodyMedium = mono(family, 13),
            bodySmall = mono(family, 11, tracking = 0.5f),
            labelLarge = mono(family, 13, FontWeight.Bold, tracking = 1f),
            labelMedium = mono(family, 12, tracking = 1.5f),
            labelSmall = mono(family, 11, FontWeight.Medium, tracking = 0.5f),
        )
    }
}
