package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.dmt.data.ffmpeg.Ffmpeg
import dev.jyotiraditya.dmt.domain.model.Spec
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.domain.model.TrackSource
import dev.jyotiraditya.dmt.playback.AudioOutput
import dev.jyotiraditya.dmt.util.asKHz
import dev.jyotiraditya.dmt.util.asMB
import dev.jyotiraditya.dmt.util.codecLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.seconds

/** "info" and "chain" tabs: what the file is, and where the sound goes. */
class TrackMediaRepository(private val output: AudioOutput) {

    fun techSpecs(track: Track?): List<Spec> {
        track ?: return emptyList()
        if (track.source == TrackSource.JELLYFIN) {
            return buildList {
                if (track.mime.isNotEmpty()) add(Spec(label = "FMT", value = track.mime.codecLabel()))
                if (track.bitrate > 0) add(Spec(label = "KBPS", value = "${track.bitrate / 1000}", hot = true))
                track.size.takeIf { it > 0 }?.let { add(Spec(label = "SIZE", value = it.asMB())) }
                addAll(decoderSpecs(track.mime.substringAfter('/')))
            }
        }
        val probe = Ffmpeg.probe(track.path, deep = true)
        val codec = probe?.codec.orEmpty()
        val fmt = when {
            probe == null -> track.mime.codecLabel()
            codec == "aac" && probe.profile?.contains("HE-AACv2", true) == true -> "HE-AACv2"
            codec == "aac" && probe.profile?.contains("HE", true) == true -> "HE-AAC"
            else -> "audio/$codec".codecLabel()
        }
        val vbr = probe != null && codec in setOf("mp3", "aac", "vorbis", "opus") && isVbr(probe.tags, probe.bitrate)
        val bitrate = probe?.bitrate?.takeIf { it > 0 } ?: track.bitrate
        return buildList {
            add(Spec(label = "FMT", value = fmt))
            probe?.bits?.takeIf { it > 0 }?.let { add(Spec(label = "BIT", value = "$it")) }
            probe?.sampleRate?.takeIf { it > 0 }?.let { add(Spec(label = "RATE", value = it.asKHz())) }
            probe?.channels?.takeIf { it > 0 }?.let { add(Spec(label = "CH", value = if (it == 2) "ST" else "$it")) }
            if (bitrate > 0) add(Spec(label = if (vbr) "VBR" else "KBPS", value = "${bitrate / 1000}", hot = true))
            probe?.container?.takeIf { it.isNotEmpty() }?.let {
                add(Spec(label = "CONT", value = it.substringBefore(',').uppercase()))
            }
            track.size.takeIf { it > 0 }?.let { add(Spec(label = "SIZE", value = it.asMB())) }
            addAll(decoderSpecs(codec))
        }
    }

    private fun isVbr(tags: Map<String, String>, bitrate: Int): Boolean =
        tags.keys.any { it.contains("vbr", true) || it == "xing" } || bitrate % 1000 != 0

    private fun decoderSpecs(codec: String): List<Spec> = buildList {
        if (!Ffmpeg.ensureLoaded()) {
            add(Spec(label = "DEC", value = "NONE", hot = true))
            return@buildList
        }
        add(Spec(label = "DEC", value = "FFMPEG ${codec.uppercase()}".trim()))
        add(Spec(label = "HW", value = "NO"))
        add(Spec(label = "IMPL", value = "BUNDLED ${Ffmpeg.version}"))
    }

    fun routeSpecs(): Flow<List<Spec>> = flow {
        while (true) {
            emit(currentRouteSpecs())
            delay(3.seconds)
        }
    }

    private fun currentRouteSpecs(): List<Spec> {
        val route = output.describe()
        return buildList {
            add(Spec(label = "API", value = route.api))
            add(Spec(label = "BIT", value = "${route.bits}"))
            route.sampleRate?.let { add(Spec(label = "RATE", value = it.asKHz())) }
            if (route.bufferFrames != null && route.sampleRate != null) {
                val bufMs = route.bufferFrames * 1000f / route.sampleRate
                add(Spec(label = "BUF", value = "${route.bufferFrames} FRAMES / %.1fMS".format(bufMs)))
            }
            add(Spec(label = "FLAGS", value = route.flags.ifEmpty { listOf("NONE") }.joinToString(" ")))
            add(Spec(label = "VIA", value = route.device.uppercase()))
            route.deviceDescription?.takeIf { it.isNotBlank() }?.let { add(Spec(label = "NAME", value = it)) }
            route.channels?.let { add(Spec(label = "CH", value = "$it")) }
        }
    }
}
