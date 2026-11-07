package dev.jyotiraditya.dmt.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val SPECTRUM_BANDS = 32
private const val FFT_SIZE = 1024
private const val RING = 128
private const val MIN_HZ = 50.0
private const val MAX_HZ = 16_000.0
private const val FLOOR_DB = -52f

/**
 * Spectrum of what is about to be heard. The decoder feeds every chunk it writes ([feed]),
 * tagged with the line frame position where that chunk will play; the ui asks for the bands
 * at the line's current position ([at]), so the bars follow the speakers, not the decoder,
 * which runs ~200ms ahead.
 */
class Spectrum {
    private val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val pending = FloatArray(FFT_SIZE)
    private var filled = 0
    private var pendingStart = 0L

    private val positions = LongArray(RING)
    private val frames = Array(RING) { FloatArray(SPECTRUM_BANDS) }
    private var head = 0
    private var count = 0
    private var sampleRate = 44_100
    private var edges = bandEdges(sampleRate)
    private val lock = Any()

    private fun bandEdges(rate: Int): IntArray {
        val nyquist = rate / 2.0
        val top = min(MAX_HZ, nyquist * 0.95)
        return IntArray(SPECTRUM_BANDS + 1) { i ->
            val hz = MIN_HZ * (top / MIN_HZ).pow(i / SPECTRUM_BANDS.toDouble())
            (hz / nyquist * (FFT_SIZE / 2)).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
        }
    }

    /** Interleaved 16-bit stereo [pcm] (first [length] bytes) that starts playing at [linePos]. */
    fun feed(pcm: ByteArray, length: Int, linePos: Long, rate: Int) {
        if (rate != sampleRate) {
            sampleRate = rate
            edges = bandEdges(rate)
        }
        var i = 0
        var frame = 0L
        while (i + 3 < length) {
            if (filled == 0) pendingStart = linePos + frame
            val l = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val r = (pcm[i + 2].toInt() and 0xFF) or (pcm[i + 3].toInt() shl 8)
            pending[filled++] = (l.toShort() + r.toShort()) / 65_536f
            if (filled == FFT_SIZE) {
                analyse(pendingStart)
                filled = 0
            }
            i += 4
            frame++
        }
    }

    private fun analyse(pos: Long) {
        for (k in 0 until FFT_SIZE) {
            re[k] = pending[k] * window[k]
            im[k] = 0f
        }
        fft(re, im)
        val out = FloatArray(SPECTRUM_BANDS)
        for (b in 0 until SPECTRUM_BANDS) {
            val from = edges[b]
            val to = max(edges[b + 1], from + 1)
            var peak = 0f
            for (k in from until to) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                if (m > peak) peak = m
            }
            // magnitude -> dB -> 0..1, with a gentle tilt so the highs are not always flat
            val db = 20f * log10(peak / (FFT_SIZE / 4f) + 1e-9f) + b * 0.45f
            out[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
        synchronized(lock) {
            positions[head] = pos
            out.copyInto(frames[head])
            head = (head + 1) % RING
            if (count < RING) count++
        }
    }

    /** Bands playing at line frame [linePos], or null when nothing recent matches. */
    fun at(linePos: Long, into: FloatArray): Boolean = synchronized(lock) {
        var best = -1
        var bestPos = Long.MIN_VALUE
        for (n in 0 until count) {
            val idx = (head - 1 - n + RING) % RING
            val p = positions[idx]
            if (p <= linePos && p > bestPos) {
                bestPos = p
                best = idx
            }
        }
        if (best < 0 || linePos - bestPos > FFT_SIZE * 4) return false
        frames[best].copyInto(into)
        true
    }

    /** After a flush / seek the queued analysis no longer matches what will play. */
    fun clear() = synchronized(lock) {
        count = 0
        head = 0
        filled = 0
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
