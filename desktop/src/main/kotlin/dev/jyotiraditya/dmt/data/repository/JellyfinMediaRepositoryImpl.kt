package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinApi
import dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinItem
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.domain.model.TrackSource
import dev.jyotiraditya.dmt.domain.repository.MediaRepository

private fun String.toJellyfinLong(): Long {
    val hexDigits = replace("-", "").take(16)

    return hexDigits.toULong(16).toLong()
}

class JellyfinMediaRepositoryImpl(
    private val api: JellyfinApi,
    private val settingsRepository: PreferencesRepository,
) : MediaRepository {

    override suspend fun scan(): List<Track> {
        val settings = settingsRepository.settingsNow()
        val url = settings.jellyfinUrl ?: return emptyList()
        val userId = settings.jellyfinUserId ?: return emptyList()
        val token = settings.jellyfinToken ?: return emptyList()
        dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinSession.set(url, token)

        return api.fetchAudioItems(url, userId, token).map { item ->
            item.toTrack(url, token)
        }
    }

    private fun JellyfinItem.toTrack(baseUrl: String, token: String): Track {
        val artId = if (hasOwnArt) id else albumId

        return Track(
            id = id.toJellyfinLong(),
            uri = api.streamUrl(baseUrl, id),
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            path = "",
            durationMs = durationMs,
            mime = mime,
            bitrate = bitrate,
            size = size,
            trackNumber = trackNumber,
            dateAdded = dateAdded,
            dateModified = dateAdded,
            coverUri = artId?.let { api.imageUrl(baseUrl, it) },
            source = TrackSource.JELLYFIN,
            remoteId = id,
        )
    }
}
