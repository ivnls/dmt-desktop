package dev.jyotiraditya.dmt.util

import dev.jyotiraditya.dmt.domain.model.LastSession
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.domain.model.asCredit
import dev.jyotiraditya.dmt.playback.Player

fun LastSession.resolveQueue(tracks: List<Track>): Triple<List<Track>, Int, Long>? {
    val byId = tracks.associateBy { it.id }
    val existing = queueIds.mapNotNull { byId[it] }
    if (existing.isEmpty()) return null

    val savedCurrentId = queueIds.getOrNull(index)
    var startIndex = existing.indexOfFirst { it.id == savedCurrentId }
    var position = positionMs
    if (startIndex < 0) {
        startIndex = 0
        position = 0L
    }
    return Triple(existing, startIndex, position)
}

data class QueueEntry(val index: Int, val label: String)

fun Player.queueEntries(): List<QueueEntry> =
    order.mapNotNull { index ->
        queue.getOrNull(index)?.let { QueueEntry(index, "${it.title} · ${it.artist.asCredit()}") }
    }

fun Player.queueWithPosition(): Pair<List<QueueEntry>, Int> {
    val entries = queueEntries()
    return entries to entries.indexOfFirst { it.index == currentMediaItemIndex }
}

fun Long.asTime(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds < 10) "$minutes:0$seconds" else "$minutes:$seconds"
}

fun String.codecLabel(): String =
    when {
        contains("flac", true) -> "FLAC"
        contains("alac", true) -> "ALAC"
        contains("mpeg", true) || contains("mp3", true) -> "MP3"
        contains("mp4a", true) || contains("aac", true) || contains("mp4", true) -> "AAC"
        contains("opus", true) -> "OPUS"
        contains("vorbis", true) -> "VORBIS"
        contains("ogg", true) -> "OGG"
        contains("wavpack", true) -> "WAVPACK"
        contains("wav", true) -> "WAV"
        contains("pcm", true) || contains("raw", true) -> "PCM"
        contains("aiff", true) -> "AIFF"
        contains("dsd", true) -> "DSD"
        contains("ape", true) -> "APE"
        contains("wma", true) -> "WMA"
        contains("eac3", true) -> "E-AC-3"
        contains("ac3", true) -> "AC-3"
        contains("dts", true) -> "DTS"
        contains("tta", true) -> "TTA"
        else -> substringAfterLast('/').uppercase().take(8)
    }

fun Int.asKHz(): String {
    if (this % 1000 == 0) return "${this / 1000}K"
    val tenths = Math.round(this / 100.0)
    return "${tenths / 10}.${tenths % 10}K"
}

fun Long.asMB(): String {
    val tenths = Math.round(this / 1048576.0 * 10)
    return "${tenths / 10}.${tenths % 10}MB"
}
