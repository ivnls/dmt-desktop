package dev.jyotiraditya.dmt.platform

import java.awt.GraphicsEnvironment
import java.util.concurrent.TimeUnit

/**
 * How much bigger the desktop wants things drawn than java already does.
 *
 * Java honours the windows display scale, but on linux it usually ignores fractional scaling
 * (gnome / kde at 125%, 150%...), so we look at the usual hints ourselves.
 */
object DisplayScale {

    /** java's own scale for the default screen (already applied by compose). */
    private val javaScale: Float by lazy {
        runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .defaultConfiguration.defaultTransform.scaleX.toFloat()
        }.getOrDefault(1f).coerceAtLeast(1f)
    }

    /** Extra factor to apply on top of java's scale. */
    val extra: Float by lazy {
        if (!Platform.isLinux) return@lazy 1f
        val hints = listOfNotNull(
            gdkScale(),
            System.getenv("QT_SCALE_FACTOR")?.toFloatOrNull(),
            xftDpi()?.div(96f),
            gnomeTextScale(),
        ).filter { it in 0.5f..4f }
        val wanted = hints.maxOrNull() ?: 1f
        (wanted / javaScale).coerceIn(1f, 3f)
    }

    private fun gdkScale(): Float? {
        val scale = System.getenv("GDK_SCALE")?.toFloatOrNull()
        val dpi = System.getenv("GDK_DPI_SCALE")?.toFloatOrNull()
        if (scale == null && dpi == null) return null
        return (scale ?: 1f) * (dpi ?: 1f)
    }

    private fun xftDpi(): Float? =
        run("xrdb", "-query")
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("Xft.dpi:") }
            ?.substringAfter(':')?.trim()?.toFloatOrNull()

    private fun gnomeTextScale(): Float? =
        run("gsettings", "get", "org.gnome.desktop.interface", "text-scaling-factor")?.trim()?.toFloatOrNull()

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        if (process.exitValue() != 0) null else process.inputStream.bufferedReader().readText()
    }.getOrNull()
}

/** The phone layout was designed for roughly this many dp of height. */
private const val DESIGN_HEIGHT_DP = 820f
private const val MAX_AUTO_FIT = 1.5f

/**
 * Final zoom for the ui. [percent] 0 = auto: system scale, then grow with tall windows so a
 * maximised window on a big screen is not a small phone ui floating in empty space.
 */
fun uiZoom(percent: Int, windowHeightDp: Float): Float {
    if (percent > 0) return percent / 100f
    val system = DisplayScale.extra
    val fit = (windowHeightDp / system / DESIGN_HEIGHT_DP).coerceIn(1f, MAX_AUTO_FIT)
    return system * fit
}
