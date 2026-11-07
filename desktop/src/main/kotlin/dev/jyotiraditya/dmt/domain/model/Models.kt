package dev.jyotiraditya.dmt.domain.model

import androidx.compose.runtime.Immutable

enum class TrackSource { LOCAL, JELLYFIN }

@Immutable
data class Track(
    val id: Long,
    /** absolute file path (forward slashes) or http(s) url */
    val uri: String,
    val title: String,
    val artist: String,
    val albumArtist: String = "",
    val album: String,
    val path: String,
    val durationMs: Long,
    val mime: String,
    val bitrate: Int,
    val size: Long,
    val trackNumber: Int,
    val dateAdded: Long = 0L,
    val dateModified: Long = 0L,
    /** http(s) url for remote art; local art is read from the file / folder */
    val coverUri: String? = null,
    val source: TrackSource = TrackSource.LOCAL,
    val remoteId: String? = null,
    val clipStartMs: Long? = null,
    val clipEndMs: Long? = null,
)

@Immutable
data class Album(
    val name: String,
    val artist: String,
    val tracks: List<Track>,
)

@Immutable
data class Artist(
    val name: String,
    val albums: Int,
    val tracks: List<Track>,
)

@Immutable
data class Folder(
    val name: String,
    val path: String,
    val tracks: List<Track>,
)

@Immutable
data class Playlist(
    val name: String,
    val tracks: List<Track>,
)

@Immutable
data class Spec(
    val label: String,
    val value: String,
    val hot: Boolean = false,
)

@Immutable
data class LibrarySnapshot(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val folders: List<Folder> = emptyList(),
)
