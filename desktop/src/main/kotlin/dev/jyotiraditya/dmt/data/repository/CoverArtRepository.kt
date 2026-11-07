package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.dmt.data.ffmpeg.Ffmpeg
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.platform.AppDirs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest

private const val DISK_CACHE_MAX_BYTES = 50L * 1024 * 1024
private val FOLDER_ART_NAMES = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall", "artwork")
private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

/** Embedded art (via ffmpeg), then folder.jpg style files; remote art goes through a disk cache. */
class CoverArtRepository(private val client: HttpClient) {

    private val diskDir = File(AppDirs.cache, "cover_art").apply { mkdirs() }

    suspend fun loadArt(track: Track): Image? {
        val remote = track.coverUri
        return if (remote != null && (remote.startsWith("http://") || remote.startsWith("https://"))) {
            loadRemote(remote)
        } else if (track.path.isNotEmpty()) {
            embeddedArt(track.path) ?: folderArt(track.path)
        } else {
            null
        }
    }

    private fun decode(bytes: ByteArray?): Image? =
        bytes?.takeIf { it.isNotEmpty() }?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }

    private fun embeddedArt(path: String): Image? =
        decode(Ffmpeg.probe(path, withPicture = true, deep = false)?.picture)

    private fun folderArt(path: String): Image? {
        val dir = File(path).parentFile ?: return null
        val images = dir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (images.isEmpty()) return null
        val stem = File(path).nameWithoutExtension.lowercase()
        val best = images.firstOrNull { it.nameWithoutExtension.lowercase() == stem }
            ?: images.firstOrNull { it.nameWithoutExtension.lowercase() in FOLDER_ART_NAMES }
            ?: images.firstOrNull { f -> FOLDER_ART_NAMES.any { f.nameWithoutExtension.lowercase().startsWith(it) } }
            ?: images.first()
        return decode(runCatching { best.readBytes() }.getOrNull())
    }

    private suspend fun loadRemote(url: String): Image? {
        val key = sha1(url.replace(Regex("""[?&]api_key=[^&]*"""), ""))
        val file = File(diskDir, key)
        if (file.isFile) {
            decode(runCatching { file.readBytes() }.getOrNull())?.let { return it }
        }
        val bytes = runCatching {
            val response = client.get(url)
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.getOrNull() ?: return null
        val image = decode(bytes) ?: return null
        runCatching {
            file.writeBytes(bytes)
            trimDiskCache()
        }
        return image
    }

    private fun trimDiskCache() {
        val files = diskDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_CACHE_MAX_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
