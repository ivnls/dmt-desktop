package dev.jyotiraditya.dmt.domain.usecase

import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.domain.model.TrackSource
import dev.jyotiraditya.dmt.platform.AppDirs
import dev.jyotiraditya.metadata.AudioTags
import dev.jyotiraditya.metadata.TagKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** On desktop there is no write-permission dialog: if the file is writable, we write. */
class EmbedLyricsUseCase {

    suspend fun canWrite(track: Track): Boolean =
        withContext(Dispatchers.IO) {
            track.source == TrackSource.LOCAL &&
                track.clipStartMs == null && track.clipEndMs == null &&
                File(track.path).canWrite() &&
                AudioTags.canWrite(track.path)
        }

    suspend operator fun invoke(track: Track, text: String): Boolean =
        withContext(Dispatchers.IO) {
            AudioTags.write(
                file = File(track.path),
                tempDir = File(AppDirs.cache, "tmp").apply { mkdirs() },
                tags = mapOf(TagKey.LYRICS to text),
            )
        }
}
