package dev.jyotiraditya.dmt.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumTest {

    private fun tone(hz: Double, frames: Int, rate: Int = 44_100): ByteArray {
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val s = (sin(2 * PI * hz * i / rate) * 20_000).toInt()
            for (c in 0..1) {
                out[i * 4 + c * 2] = (s and 0xFF).toByte()
                out[i * 4 + c * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    private fun loudestBand(hz: Double): Int {
        val spectrum = Spectrum()
        val pcm = tone(hz, 4096)
        spectrum.feed(pcm, pcm.size, 0, 44_100)
        val bands = FloatArray(SPECTRUM_BANDS)
        assertTrue(spectrum.at(3000, bands))
        return bands.indices.maxBy { bands[it] }
    }

    @Test
    fun lowNotesLightLowBarsAndHighNotesHighBars() {
        val low = loudestBand(100.0)
        val mid = loudestBand(1_000.0)
        val high = loudestBand(8_000.0)
        assertTrue("$low < $mid < $high", low < mid && mid < high)
    }

    @Test
    fun barsFollowThePlaybackPositionNotTheDecoder() {
        val spectrum = Spectrum()
        val pcm = tone(440.0, 1024)
        spectrum.feed(pcm, pcm.size, 10_000, 44_100) // will be heard at frame 10000
        val bands = FloatArray(SPECTRUM_BANDS)
        assertFalse("nothing is playing at 5000 yet", spectrum.at(5_000, bands))
        assertTrue(spectrum.at(10_500, bands))
        spectrum.clear()
        assertFalse(spectrum.at(10_500, bands))
    }
}
