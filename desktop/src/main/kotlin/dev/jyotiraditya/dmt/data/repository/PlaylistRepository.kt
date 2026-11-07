package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.dmt.domain.model.Playlist
import dev.jyotiraditya.dmt.domain.model.Track
import java.io.File
import dev.jyotiraditya.dmt.platform.AppDirs

private const val M3U_HEADER = "#EXTM3U"
private const val M3U_EXTENSION = ".m3u8"

class PlaylistRepository {

    private val dir: File
        get() = AppDirs.playlists

    fun load(tracks: List<Track>): List<Playlist> {
        val byPath = tracks.associateBy { it.path }

        return dir.listFiles { file -> file.extension == "m3u8" }
            .orEmpty()
            .sortedBy { it.nameWithoutExtension.lowercase() }
            .map { file ->
                Playlist(
                    name = file.nameWithoutExtension,
                    tracks = entriesOf(file).mapNotNull { byPath[it] },
                )
            }
    }

    fun create(name: String): Boolean {
        val file = fileFor(name) ?: return false
        if (file.exists()) return false

        return runCatching { file.writeText(M3U_HEADER + "\n") }.isSuccess
    }

    fun delete(name: String) {
        fileFor(name)?.delete()
    }

    fun addTrack(name: String, track: Track): Boolean {
        val file = fileFor(name) ?: return false
        if (!file.exists() || track.path.isEmpty()) return false
        if (track.path in entriesOf(file)) return false

        return runCatching { file.appendText(track.path + "\n") }.isSuccess
    }

    fun removeTrack(name: String, path: String) {
        val file = fileFor(name) ?: return
        if (!file.exists()) return

        val kept = file.readLines().filterNot { it.trim() == path }
        runCatching { file.writeText(kept.joinToString("\n", postfix = "\n")) }
    }

    private fun entriesOf(file: File): List<String> =
        runCatching { file.readLines() }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun fileFor(name: String): File? {
        val safe = name.trim().replace(Regex("""[/\\:*?"<>|]"""), "")
        if (safe.isEmpty()) return null

        return File(dir, safe + M3U_EXTENSION)
    }
}