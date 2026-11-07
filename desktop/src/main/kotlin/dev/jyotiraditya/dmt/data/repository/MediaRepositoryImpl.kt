package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.dmt.data.ffmpeg.Ffmpeg
import dev.jyotiraditya.dmt.data.ffmpeg.tag
import dev.jyotiraditya.dmt.data.source.local.cue.CueLibrary
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.domain.model.TrackSource
import dev.jyotiraditya.dmt.domain.repository.MediaRepository
import dev.jyotiraditya.dmt.platform.AppDirs
import dev.jyotiraditya.dmt.platform.normalPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

val AUDIO_EXTENSIONS = setOf(
    "mp3", "mp2", "flac", "m4a", "m4b", "mp4", "aac", "adts", "ogg", "oga", "opus", "wav", "wave",
    "wv", "ape", "aif", "aiff", "aifc", "wma", "alac", "dsf", "dff", "mka", "mpc", "tta", "ac3",
    "eac3", "ec3", "dts", "amr", "3gp", "caf", "spx", "tak", "w64", "webm", "ac4",
)

private const val CACHE_VERSION = 3
private const val ID_MASK = 0x000F_FFFF_FFFF_FFFFL

/** Stable id for a path: 52 bits of fnv-1a so cue virtual ids (id * 1000 + n) never overflow. */
fun stableId(path: String): Long {
    var hash = -0x340d631b7bdddcdbL
    for (ch in path) {
        hash = hash xor ch.code.toLong()
        hash *= 0x100000001b3L
    }
    return (hash and ID_MASK).takeIf { it != 0L } ?: 1L
}

private data class CachedEntry(val size: Long, val mtime: Long, val firstSeen: Long, val track: Track)

/**
 * Local source for desktop: walks the configured music folders (there is no MediaStore),
 * reads tags + stream info with ffmpeg and keeps a cache keyed by path, size and mtime so
 * rescans only touch new or changed files.
 */
class MediaRepositoryImpl(
    private val settingsRepository: PreferencesRepository,
    private val cacheFile: File = File(AppDirs.cache, "library.json"),
) : MediaRepository {

    override suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settingsNow()
        val blocked = settings.blockedFolders
        val roots = settings.musicFolders.map(::File).filter { it.isDirectory }
        val files = roots.flatMap(::audioFilesIn).distinctBy { it.normalPath() }
        val cache = readCache()
        val now = System.currentTimeMillis() / 1000

        val tracks = coroutineScope {
            files.chunked(32).map { chunk ->
                async(Dispatchers.IO) {
                    chunk.mapNotNull { file ->
                        val path = file.normalPath()
                        val size = file.length()
                        val mtime = file.lastModified()
                        val hit = cache[path]
                        if (hit != null && hit.size == size && hit.mtime == mtime) {
                            hit
                        } else {
                            val firstSeen = hit?.firstSeen ?: creationTime(file) ?: now
                            probeTrack(file, path, size, mtime, firstSeen)
                                ?.let { CachedEntry(size, mtime, firstSeen, it) }
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        writeCache(tracks)

        val base = tracks.map { it.track }.filter { it.path.substringBeforeLast('/') !in blocked }
        CueLibrary.expand(base).sortedBy { it.title.lowercase() }
    }

    private fun audioFilesIn(root: File): List<File> {
        val out = ArrayList<File>()
        runCatching {
            Files.walkFileTree(
                root.toPath(),
                EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                64,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = dir.fileName?.toString().orEmpty()
                        return if (dir != root.toPath() && name.startsWith(".")) {
                            FileVisitResult.SKIP_SUBTREE
                        } else {
                            FileVisitResult.CONTINUE
                        }
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName.toString()
                        if (attrs.isRegularFile && !name.startsWith("._") &&
                            name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
                        ) {
                            out += file.toFile()
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        }
        return out
    }

    private fun creationTime(file: File): Long? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            .creationTime().toMillis() / 1000
    }.getOrNull()?.takeIf { it > 0 }

    private fun probeTrack(file: File, path: String, size: Long, mtime: Long, firstSeen: Long): Track? {
        val probe = Ffmpeg.probe(path, deep = false) ?: return null
        if (!probe.hasAudio) return null
        val tags = probe.tags
        val trackNo = tags.tag("track", "tracknumber", "trck")?.leadingInt() ?: 0
        val discNo = tags.tag("disc", "discnumber", "tpos")?.leadingInt() ?: 0
        return Track(
            id = stableId(path),
            uri = path,
            title = tags.tag("title").orUnknown(file.nameWithoutExtension),
            artist = tags.tag("artist", "author").orUnknown("unknown artist"),
            albumArtist = tags.tag("album_artist", "albumartist", "album artist", "tpe2").orEmpty(),
            album = tags.tag("album").orUnknown(file.parentFile?.name ?: "unknown album"),
            path = path,
            durationMs = probe.durationMs,
            mime = "audio/" + probe.codec.ifEmpty { file.extension.lowercase() },
            bitrate = probe.bitrate,
            size = size,
            trackNumber = if (discNo > 0) discNo * 1000 + trackNo else trackNo,
            dateAdded = firstSeen,
            dateModified = mtime / 1000,
            coverUri = null,
            source = TrackSource.LOCAL,
        )
    }

    private fun String.leadingInt(): Int? = trim().takeWhile { it.isDigit() }.toIntOrNull()

    private fun readCache(): Map<String, CachedEntry> = runCatching {
        val root = JSONObject(cacheFile.readText())
        if (root.optInt("version") != CACHE_VERSION) return emptyMap()
        val items = root.getJSONArray("tracks")
        (0 until items.length()).associate { index ->
            val o = items.getJSONObject(index)
            val path = o.getString("path")
            path to CachedEntry(
                size = o.getLong("size"),
                mtime = o.getLong("mtime"),
                firstSeen = o.getLong("firstSeen"),
                track = Track(
                    id = stableId(path),
                    uri = path,
                    title = o.getString("title"),
                    artist = o.getString("artist"),
                    albumArtist = o.optString("albumArtist"),
                    album = o.getString("album"),
                    path = path,
                    durationMs = o.getLong("durationMs"),
                    mime = o.getString("mime"),
                    bitrate = o.getInt("bitrate"),
                    size = o.getLong("size"),
                    trackNumber = o.getInt("trackNumber"),
                    dateAdded = o.getLong("firstSeen"),
                    dateModified = o.getLong("mtime") / 1000,
                ),
            )
        }
    }.getOrDefault(emptyMap())

    private fun writeCache(entries: List<CachedEntry>) {
        runCatching {
            val items = JSONArray()
            entries.forEach { entry ->
                val t = entry.track
                items.put(
                    JSONObject()
                        .put("path", t.path)
                        .put("size", entry.size)
                        .put("mtime", entry.mtime)
                        .put("firstSeen", entry.firstSeen)
                        .put("title", t.title)
                        .put("artist", t.artist)
                        .put("albumArtist", t.albumArtist)
                        .put("album", t.album)
                        .put("durationMs", t.durationMs)
                        .put("mime", t.mime)
                        .put("bitrate", t.bitrate)
                        .put("trackNumber", t.trackNumber),
                )
            }
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(JSONObject().put("version", CACHE_VERSION).put("tracks", items).toString())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        }
    }
}

fun String?.orUnknown(fallback: String): String =
    if (isNullOrBlank() || this == "<unknown>") fallback else this
