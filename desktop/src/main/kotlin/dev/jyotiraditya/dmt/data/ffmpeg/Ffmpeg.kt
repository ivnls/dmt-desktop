package dev.jyotiraditya.dmt.data.ffmpeg

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer

/** What ffprobe would tell us about one file. */
data class Probe(
    val durationMs: Long,
    val container: String,
    val codec: String,
    val codecLong: String,
    val profile: String?,
    val sampleRate: Int,
    val channels: Int,
    val bits: Int,
    val bitrate: Int,
    val tags: Map<String, String>,
    val picture: ByteArray?,
    val hasAudio: Boolean,
)

object Ffmpeg {

    @Volatile
    private var ready = false

    /** Loads the native libs once and silences ffmpeg's stderr chatter. */
    fun ensureLoaded(): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            ready = runCatching {
                avutil.av_log_set_level(avutil.AV_LOG_QUIET)
                avformat.avformat_network_init()
                true
            }.getOrElse { false }
        }
        return ready
    }

    val version: String by lazy {
        runCatching {
            ensureLoaded()
            avutil.av_version_info().string
        }.getOrDefault("?")
    }

    fun probe(path: String, withPicture: Boolean = false, deep: Boolean = true): Probe? {
        if (!ensureLoaded()) return null
        val ctx = AVFormatContext(null)
        val url = BytePointer(path, "UTF-8")
        try {
            if (avformat.avformat_open_input(ctx, url, null, null) < 0) return null
            try {
                if (deep || ctx.duration() <= 0) {
                    avformat.avformat_find_stream_info(ctx, null as PointerPointer<*>?)
                }
                val tags = LinkedHashMap<String, String>()
                ctx.metadata().collectInto(tags)

                var audioIndex = -1
                var picture: ByteArray? = null
                for (i in 0 until ctx.nb_streams()) {
                    val stream = ctx.streams(i)
                    val par = stream.codecpar()
                    val attached = stream.disposition() and avformat.AV_DISPOSITION_ATTACHED_PIC != 0
                    if (attached) {
                        if (withPicture && picture == null) {
                            val pkt = stream.attached_pic()
                            val size = pkt.size()
                            if (size > 0 && pkt.data() != null) {
                                picture = ByteArray(size).also { pkt.data().capacity(size.toLong()).get(it) }
                            }
                        }
                    } else if (audioIndex < 0 && par.codec_type() == avutil.AVMEDIA_TYPE_AUDIO) {
                        audioIndex = i
                        stream.metadata().collectInto(tags)
                    }
                }

                val container = ctx.iformat()?.name()?.string.orEmpty()
                if (audioIndex < 0) {
                    return Probe(
                        durationMs = 0, container = container, codec = "", codecLong = "",
                        profile = null, sampleRate = 0, channels = 0, bits = 0, bitrate = 0,
                        tags = tags, picture = picture, hasAudio = false,
                    )
                }
                val stream = ctx.streams(audioIndex)
                val par = stream.codecpar()
                val descriptor = avcodec.avcodec_descriptor_get(par.codec_id())
                val profile = runCatching {
                    avcodec.avcodec_profile_name(par.codec_id(), par.profile())?.string
                }.getOrNull()
                val durationMs = when {
                    ctx.duration() > 0 -> ctx.duration() / 1000
                    stream.duration() > 0 -> {
                        val tb = stream.time_base()
                        stream.duration() * 1000 * tb.num() / tb.den().coerceAtLeast(1)
                    }

                    else -> 0L
                }
                val bits = par.bits_per_raw_sample().takeIf { it > 0 }
                    ?: par.bits_per_coded_sample().takeIf { it > 0 && descriptor?.props()?.let { p -> p and avcodec.AV_CODEC_PROP_LOSSLESS != 0 } == true }
                    ?: 0
                val bitrate = par.bit_rate().takeIf { it > 0 } ?: ctx.bit_rate()
                return Probe(
                    durationMs = durationMs,
                    container = container,
                    codec = descriptor?.name()?.string.orEmpty(),
                    codecLong = descriptor?.long_name()?.string.orEmpty(),
                    profile = profile,
                    sampleRate = par.sample_rate(),
                    channels = par.ch_layout().nb_channels(),
                    bits = bits,
                    bitrate = bitrate.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    tags = tags,
                    picture = picture,
                    hasAudio = true,
                )
            } finally {
                avformat.avformat_close_input(ctx)
            }
        } catch (_: Throwable) {
            return null
        } finally {
            url.close()
        }
    }

    private fun AVDictionary?.collectInto(into: MutableMap<String, String>) {
        if (this == null || this.isNull) return
        var entry: AVDictionaryEntry? = null
        while (true) {
            entry = avutil.av_dict_get(this, "", entry, avutil.AV_DICT_IGNORE_SUFFIX) ?: break
            if (entry.isNull) break
            val key = entry.key()?.getString("UTF-8")?.lowercase() ?: continue
            val value = entry.value()?.getString("UTF-8") ?: continue
            if (key !in into) into[key] = value
        }
    }
}

/** Case-insensitive tag lookup over the few spellings containers use. */
fun Map<String, String>.tag(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it.isNotBlank() } }
