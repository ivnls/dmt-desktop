package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.lyrics.LyricsParser
import dev.jyotiraditya.lyrics.LyricsTags
import dev.jyotiraditya.lyrics.Lyrics
import dev.jyotiraditya.metadata.AudioTags
import java.io.File

private val SIDECAR_EXTENSIONS = listOf("ttml", "lrc", "txt")

class LyricsRepository {

    fun lyricsFor(path: String, mime: String, preferFile: Boolean): Lyrics? =
        if (preferFile) {
            sidecarLyrics(path) ?: embeddedLyrics(path)
        } else {
            embeddedLyrics(path) ?: sidecarLyrics(path)
        }

    private fun embeddedLyrics(path: String): Lyrics? =
        LyricsTags.bestOf(AudioTags.read(path))?.let(LyricsParser::parse)

    private fun sidecarLyrics(path: String): Lyrics? {
        val track = File(path)

        return SIDECAR_EXTENSIONS
            .asSequence()
            .map { File(track.parentFile, "${track.nameWithoutExtension}.$it") }
            .mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
            .firstNotNullOfOrNull(LyricsParser::parse)
    }
}
