package dev.jyotiraditya.dmt.playback

import dev.jyotiraditya.dmt.data.ffmpeg.Ffmpeg
import dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinSession
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.File
import java.nio.ShortBuffer
import javax.sound.sampled.SourceDataLine

private const val CHUNK_FRAMES = 1024
/** A write that makes no progress for this long means the output is gone, not slow. */
private const val STALL_LIMIT_MS = 3_000L
/** How far decoding may run ahead of what the device actually played. */
private const val MAX_AHEAD_MS = 1_500L

/** Why a track could not be played; becomes "playback error: <code>" on the player. */
class PlaybackFailure(val code: String, val unsupported: Boolean = false) : Exception(code)

interface SessionListener {
    fun onReady(session: PlaybackSession, durationMs: Long)
    fun onEnded(session: PlaybackSession)
    fun onFailed(session: PlaybackSession, failure: PlaybackFailure)
}

/**
 * Decodes one track with ffmpeg on its own thread and feeds 16-bit stereo pcm into the
 * shared [AudioOutput] line. Commands (pause, seek, speed, gain) are picked up between chunks.
 */
class PlaybackSession(
    private val uri: String,
    private val clipStartMs: Long?,
    private val clipEndMs: Long?,
    startPositionMs: Long,
    playWhenReady: Boolean,
    speed: Float,
    gain: Float,
    private val output: AudioOutput,
    private val listener: SessionListener,
    private val flushOnStart: Boolean,
    private val previous: PlaybackSession?,
) {
    private val lock = Object()
    private val thread = Thread(::run, "tmpl-decoder").apply { isDaemon = true }

    @Volatile private var stopped = false
    @Volatile private var playing = playWhenReady
    @Volatile private var pendingSeekMs: Long? = startPositionMs.takeIf { it > 0 }
    @Volatile private var pendingSpeed: Float? = null
    @Volatile var speed: Float = speed
        private set
    @Volatile var gain: Float = gain

    @Volatile private var basePositionMs: Long = startPositionMs.coerceAtLeast(0)
    @Volatile private var framesAtBase: Long = 0
    @Volatile private var outRate: Int = 0
    @Volatile private var line: SourceDataLine? = null
    @Volatile var durationMs: Long = 0
        private set
    @Volatile var finished = false
        private set

    /** frames handed to the line since [framesAtBase] was taken */
    @Volatile private var framesWritten: Long = 0

    val positionMs: Long
        get() {
            val l = line ?: return basePositionMs
            val rate = outRate.takeIf { it > 0 } ?: return basePositionMs
            val played = (l.longFramePosition - framesAtBase).coerceAtLeast(0)
            val pos = basePositionMs + (played * 1000 / rate * speed).toLong()
            return if (durationMs > 0) pos.coerceAtMost(durationMs) else pos
        }

    fun start() = thread.start()

    fun setPlaying(value: Boolean) {
        playing = value
        synchronized(lock) { lock.notifyAll() }
    }

    fun seekTo(positionMs: Long) {
        basePositionMs = positionMs.coerceAtLeast(0)
        pendingSeekMs = basePositionMs
        synchronized(lock) { lock.notifyAll() }
    }

    fun setSpeed(value: Float) {
        pendingSpeed = value
        synchronized(lock) { lock.notifyAll() }
    }

    /** Stops decoding; with [cut] the audio still sitting in the line buffer is dropped too. */
    fun stop(cut: Boolean) {
        stopped = true
        if (cut) output.flush()
        synchronized(lock) { lock.notifyAll() }
    }

    fun join(timeoutMs: Long) = runCatching { thread.join(timeoutMs) }

    private fun open(): FFmpegFrameGrabber {
        val local = !uri.startsWith("http://") && !uri.startsWith("https://")
        if (local && !File(uri).isFile) throw PlaybackFailure("io_file_not_found")
        if (!Ffmpeg.ensureLoaded()) throw PlaybackFailure("decoder_init_failed")
        val grabber = FFmpegFrameGrabber(uri)
        grabber.sampleMode = FrameGrabber.SampleMode.SHORT
        grabber.audioChannels = OUT_CHANNELS
        // Only the protocols this source needs. Without a whitelist a crafted file (playlist,
        // concat list, hls) could make ffmpeg open other local files or reach out to the network.
        grabber.setOption("protocol_whitelist", Ffmpeg.protocolsFor(uri))
        if (!local) {
            grabber.setOption("rw_timeout", "15000000")
            grabber.setOption("reconnect", "1")
            JellyfinSession.headerFor(uri)?.let { (name, value) ->
                grabber.setOption("headers", "$name: $value\r\n")
            }
        }
        try {
            grabber.start()
        } catch (t: Throwable) {
            runCatching { grabber.release() }
            throw PlaybackFailure(if (local) "decoding_format_unsupported" else "io_network_connection_failed")
        }
        if (grabber.audioStream < 0 || grabber.audioChannels <= 0) {
            runCatching { grabber.release() }
            throw PlaybackFailure("decoding_format_unsupported", unsupported = true)
        }
        return grabber
    }

    private fun buildFilter(rate: Int, value: Float): FFmpegFrameFilter? {
        if (kotlin.math.abs(value - 1f) < 0.01f) return null
        return FFmpegFrameFilter("atempo=%.3f".format(java.util.Locale.ROOT, value), OUT_CHANNELS).apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            sampleRate = rate
            start()
        }
    }

    private fun run() {
        var grabber: FFmpegFrameGrabber? = null
        var filter: FFmpegFrameFilter? = null
        try {
            grabber = open()
            val sourceRate = grabber.sampleRate.takeIf { it > 0 } ?: 48_000
            previous?.join(1_000)
            if (stopped) return
            if (flushOnStart) output.flush()
            val (l, rate) = output.acquire(sourceRate)
            output.sourceRate = sourceRate
            line = l
            // The line is shared: a previous session that was paused left it stop()ped. Writing
            // into a stopped line returns without playing anything, which used to make the
            // decoder race through the file and skip from track to track on its own.
            if (playing) l.start()
            outRate = rate
            if (rate != sourceRate) grabber.sampleRate = rate

            val fileMs = (grabber.lengthInTime / 1000).coerceAtLeast(0)
            val clipStart = clipStartMs ?: 0L
            val clipEnd = clipEndMs?.takeIf { it > clipStart } ?: fileMs
            durationMs = (clipEnd - clipStart).coerceAtLeast(0)
            if (clipStart > 0 && pendingSeekMs == null) pendingSeekMs = 0
            framesAtBase = l.longFramePosition
            framesWritten = 0
            listener.onReady(this, durationMs)

            filter = buildFilter(rate, speed)
            val buffer = ByteArray(CHUNK_FRAMES * OUT_BYTES_PER_FRAME)

            while (!stopped) {
                if (!playing) {
                    runCatching { l.stop() }
                    synchronized(lock) {
                        while (!playing && !stopped && pendingSeekMs == null && pendingSpeed == null) lock.wait(250)
                    }
                    if (stopped) break
                    if (playing) l.start()
                }
                pendingSpeed?.let { value ->
                    pendingSpeed = null
                    val pos = positionMs
                    speed = value
                    filter?.let { runCatching { it.stop(); it.release() } }
                    filter = buildFilter(rate, value)
                    pendingSeekMs = pendingSeekMs ?: pos
                }
                pendingSeekMs?.let { target ->
                    pendingSeekMs = null
                    val clamped = if (durationMs > 0) target.coerceIn(0, durationMs) else target.coerceAtLeast(0)
                    runCatching { grabber.setTimestamp((clipStart + clamped) * 1000L) }
                    output.flush()
                    basePositionMs = clamped
                    framesAtBase = l.longFramePosition
                    framesWritten = 0
                    filter?.let { runCatching { it.stop(); it.release() } }
                    filter = buildFilter(rate, speed)
                }
                if (!playing) continue

                val frame: Frame = try {
                    grabber.grabSamples()
                } catch (t: Throwable) {
                    null
                } ?: break
                if (frame.samples == null) continue
                if (clipEndMs != null && frame.timestamp / 1000 >= clipEnd) break

                val f = filter
                if (f == null) {
                    write(l, frame, buffer)
                } else {
                    f.push(frame)
                    while (!stopped) {
                        val out = f.pullSamples() ?: break
                        write(l, out, buffer)
                    }
                }
            }
            if (!stopped) {
                finished = true
                listener.onEnded(this)
            }
        } catch (failure: PlaybackFailure) {
            // a dead line must not be handed to the next track
            if (failure.code.startsWith("audio_")) output.close()
            if (!stopped) listener.onFailed(this, failure)
        } catch (t: Throwable) {
            if (!stopped) {
                val code = if (t.message?.contains("audio output") == true) "audio_track_init_failed" else "decoding_failed"
                listener.onFailed(this, PlaybackFailure(code))
            }
        } finally {
            filter?.let { runCatching { it.stop(); it.release() } }
            grabber?.let { runCatching { it.stop(); it.release() } }
        }
    }

    private fun write(l: SourceDataLine, frame: Frame, buffer: ByteArray) {
        val samples = frame.samples?.getOrNull(0) as? ShortBuffer ?: return
        val g = gain
        val apply = kotlin.math.abs(g - 1f) > 0.001f
        while (samples.hasRemaining() && !stopped) {
            if (pendingSeekMs != null || pendingSpeed != null) return
            if (!playing) {
                runCatching { l.stop() }
                synchronized(lock) {
                    while (!playing && !stopped && pendingSeekMs == null) lock.wait(250)
                }
                if (stopped || pendingSeekMs != null) return
                l.start()
            }
            if (!l.isActive && !l.isRunning) l.start()
            val count = minOf(samples.remaining(), buffer.size / 2)
            var o = 0
            repeat(count) {
                var s = samples.get().toInt()
                if (apply) s = (s * g).toInt().coerceIn(-32768, 32767)
                buffer[o++] = (s and 0xFF).toByte()
                buffer[o++] = ((s shr 8) and 0xFF).toByte()
            }
            writeFully(l, buffer, o)
        }
    }

    /**
     * SourceDataLine.write normally blocks until the device takes the data. When the device
     * goes away (pipewire / pulse restart, bluetooth drop, alsa xrun) it can return at once
     * with 0 bytes, and the decoder would then race through the file and "end" every track
     * in a row. Insist on progress, and give up loudly instead.
     */
    private fun writeFully(l: SourceDataLine, buffer: ByteArray, length: Int) {
        // tag the chunk with the line position where it will be heard
        output.spectrum.feed(buffer, length, framesAtBase + framesWritten, outRate)
        var offset = 0
        var stalledSince = 0L
        while (offset < length && !stopped) {
            if (!l.isOpen) throw PlaybackFailure("audio_output_closed")
            val n = l.write(buffer, offset, length - offset)
            if (n > 0) {
                offset += n
                stalledSince = 0
                continue
            }
            if (!playing || pendingSeekMs != null) return
            val now = System.currentTimeMillis()
            if (stalledSince == 0L) stalledSince = now
            if (now - stalledSince > STALL_LIMIT_MS) throw PlaybackFailure("audio_output_stalled")
            Thread.sleep(5)
        }
        framesWritten += length / OUT_BYTES_PER_FRAME
        // never run more than a moment ahead of the device, whatever write() claims
        val rate = outRate.takeIf { it > 0 } ?: return
        var waitedSince = 0L
        while (!stopped && playing && pendingSeekMs == null) {
            val played = l.longFramePosition - framesAtBase
            val aheadMs = (framesWritten - played) * 1000 / rate
            if (aheadMs <= MAX_AHEAD_MS) break
            val now = System.currentTimeMillis()
            if (waitedSince == 0L) waitedSince = now
            if (now - waitedSince > STALL_LIMIT_MS) throw PlaybackFailure("audio_output_stalled")
            Thread.sleep(10)
        }
    }
}
