package dev.jyotiraditya.dmt.playback

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

const val OUT_CHANNELS = 2
const val OUT_BYTES_PER_FRAME = OUT_CHANNELS * 2
private const val BUFFER_MS = 200

data class RouteInfo(
    val api: String,
    val bits: Int,
    val sampleRate: Int?,
    val bufferFrames: Int?,
    val flags: List<String>,
    val device: String,
    val deviceDescription: String?,
    val channels: Int?,
)

/**
 * One shared java sound line. Consecutive tracks at the same sample rate keep writing into
 * the same open line, which is what makes track changes (almost) gapless.
 */
class AudioOutput {

    private var line: SourceDataLine? = null
    private var rate: Int = 0

    @Volatile
    var sourceRate: Int? = null

    @Volatile
    var effects: List<String> = emptyList()

    val spectrum = Spectrum()

    /** Bands for what the speakers are playing right now; false when silent / nothing matches. */
    fun currentSpectrum(into: FloatArray): Boolean {
        val l = line ?: return false
        if (!l.isOpen || !l.isActive) return false
        return spectrum.at(l.longFramePosition, into)
    }

    @Synchronized
    fun acquire(preferredRate: Int): Pair<SourceDataLine, Int> {
        val current = line
        if (current != null && current.isOpen && rate == preferredRate) return current to rate
        current?.let {
            runCatching { it.drain() }
            runCatching { it.close() }
        }
        line = null
        val candidates = listOf(preferredRate, 48_000, 44_100).filter { it in 8_000..384_000 }.distinct()
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val format = AudioFormat(candidate.toFloat(), 16, OUT_CHANNELS, true, false)
            try {
                val info = DataLine.Info(SourceDataLine::class.java, format)
                val opened = AudioSystem.getLine(info) as SourceDataLine
                opened.open(format, candidate / 1000 * BUFFER_MS * OUT_BYTES_PER_FRAME)
                opened.start()
                line = opened
                rate = candidate
                return opened to candidate
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("audio output unavailable", lastError)
    }

    @Synchronized
    fun flush() {
        spectrum.clear()
        line?.let { runCatching { it.flush() } }
    }

    @Synchronized
    fun close() {
        spectrum.clear()
        line?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        line = null
    }

    fun describe(): RouteInfo {
        val current = line
        val mixer = runCatching { AudioSystem.getMixer(null).mixerInfo }.getOrNull()
        val flags = buildList {
            val source = sourceRate
            if (current != null && source != null && source != rate) add("RESAMPLED")
            addAll(effects)
            add("SHARED")
        }
        return RouteInfo(
            api = "JAVA SOUND",
            bits = 16,
            sampleRate = current?.takeIf { it.isOpen }?.let { rate },
            bufferFrames = current?.takeIf { it.isOpen }?.bufferSize?.div(OUT_BYTES_PER_FRAME),
            flags = flags,
            device = mixer?.name?.substringBefore(" [")?.ifBlank { null } ?: "DEFAULT",
            deviceDescription = mixer?.description,
            channels = OUT_CHANNELS,
        )
    }
}
