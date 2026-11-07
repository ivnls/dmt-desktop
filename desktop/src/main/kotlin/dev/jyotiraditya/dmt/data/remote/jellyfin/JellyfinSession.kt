package dev.jyotiraditya.dmt.data.remote.jellyfin

import java.net.InetAddress
import java.net.URI

/**
 * The jellyfin server + token currently in use. Stream and image urls no longer carry the
 * token (?api_key=... ends up in logs, caches and proxies); requests to [baseUrl] get it as a
 * header instead, from ktor and from ffmpeg alike.
 */
object JellyfinSession {
    @Volatile
    var baseUrl: String? = null
        private set

    @Volatile
    var token: String? = null
        private set

    fun set(url: String?, token: String?) {
        this.baseUrl = url?.trimEnd('/')
        this.token = token
    }

    /** Auth header for [url] if it points at the connected server, else null. */
    fun headerFor(url: String): Pair<String, String>? {
        val base = baseUrl ?: return null
        val t = token ?: return null
        return if (url == base || url.startsWith("$base/")) "X-Emby-Token" to t else null
    }
}

/** Why a server url is refused, or null when it is fine. */
enum class ServerUrlProblem { INVALID, INSECURE_PUBLIC }

object ServerUrls {

    /** "jelly.example.com" -> "https://jelly.example.com" */
    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if ("://" in trimmed) trimmed else "https://$trimmed"
    }

    fun problem(url: String): ServerUrlProblem? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ServerUrlProblem.INVALID
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return ServerUrlProblem.INVALID
        if (uri.host.isNullOrBlank()) return ServerUrlProblem.INVALID
        if (scheme == "http" && !isLocal(uri.host)) return ServerUrlProblem.INSECURE_PUBLIC
        return null
    }

    fun isPlainHttp(url: String): Boolean = url.trim().lowercase().startsWith("http://")

    /** loopback, private ranges, link-local, *.local / *.lan / *.home.arpa and bare hostnames. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") ||
            h.endsWith(".internal")
        ) {
            return true
        }
        val literal = h.all { it.isDigit() || it == '.' } || ':' in h
        if (!literal) return '.' !in h
        val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        // 100.64.0.0/10 (cgnat, tailscale) and fc00::/7 (ipv6 unique local)
        if (bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xC0) == 64) return true
        if (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC) return true
        return false
    }
}
