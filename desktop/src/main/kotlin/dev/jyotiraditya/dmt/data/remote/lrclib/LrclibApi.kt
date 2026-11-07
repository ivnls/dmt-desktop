package dev.jyotiraditya.dmt.data.remote.lrclib

import dev.jyotiraditya.dmt.platform.Platform
import dev.jyotiraditya.dmt.domain.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import org.json.JSONObject

private const val BASE_URL = "https://lrclib.net/api/get"
private val USER_AGENT =
    "dmt-desktop v${Platform.version} (https://github.com/imjyotiraditya/dmt)"

private fun known(value: String, placeholder: String): String? =
    value.takeIf { it.isNotBlank() && it != placeholder }

class LrclibApi(
    private val client: HttpClient,
) {

    suspend fun fetchLyrics(track: Track): String? {
        val title = known(track.title, "unknown title") ?: return null
        val artist = known(track.artist, "unknown artist") ?: return null
        val album = known(track.album, "unknown album")

        val response = client.get(BASE_URL) {
            url {
                parameters.append("track_name", title)
                parameters.append("artist_name", artist)
                album?.let { parameters.append("album_name", it) }
                parameters.append("duration", "${track.durationMs / 1000}")
            }
            header("User-Agent", USER_AGENT)
        }
        if (!response.status.isSuccess()) return null
        val json = JSONObject(response.bodyAsText())

        if (json.optBoolean("instrumental")) return null

        return json.optString("syncedLyrics")
            .ifBlank { json.optString("plainLyrics") }
            .ifBlank { null }
    }
}
