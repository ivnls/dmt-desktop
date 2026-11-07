package dev.jyotiraditya.dmt.platform

import java.io.File

object Platform {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows: Boolean = osName.contains("win")
    val isMac: Boolean = osName.contains("mac")
    val isLinux: Boolean = !isWindows && !isMac

    val version: String by lazy {
        runCatching {
            Platform::class.java.getResourceAsStream("/dmt.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "dev"
    }
}

/** Per-OS locations for config, cache and data (XDG on linux, %APPDATA% on windows). */
object AppDirs {
    private val home: File = File(System.getProperty("user.home"))

    private fun env(name: String): File? =
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let(::File)

    val config: File by lazy {
        when {
            Platform.isWindows -> File(env("APPDATA") ?: File(home, "AppData/Roaming"), "dmt")
            Platform.isMac -> File(home, "Library/Application Support/dmt")
            else -> File(env("XDG_CONFIG_HOME") ?: File(home, ".config"), "dmt")
        }.apply { mkdirs() }
    }

    val data: File by lazy {
        when {
            Platform.isWindows -> File(env("APPDATA") ?: File(home, "AppData/Roaming"), "dmt")
            Platform.isMac -> File(home, "Library/Application Support/dmt")
            else -> File(env("XDG_DATA_HOME") ?: File(home, ".local/share"), "dmt")
        }.apply { mkdirs() }
    }

    val cache: File by lazy {
        when {
            Platform.isWindows -> File(env("LOCALAPPDATA") ?: File(home, "AppData/Local"), "dmt/cache")
            Platform.isMac -> File(home, "Library/Caches/dmt")
            else -> File(env("XDG_CACHE_HOME") ?: File(home, ".cache"), "dmt")
        }.apply { mkdirs() }
    }

    val playlists: File
        get() = File(data, "playlists").apply { mkdirs() }

    /** The user's music directory: XDG_MUSIC_DIR on linux, ~/Music elsewhere. */
    fun defaultMusicDir(): File? {
        if (Platform.isLinux) {
            val userDirs = File(env("XDG_CONFIG_HOME") ?: File(home, ".config"), "user-dirs.dirs")
            runCatching {
                userDirs.readLines()
                    .firstOrNull { it.trim().startsWith("XDG_MUSIC_DIR=") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.trim('"')
                    ?.replace("\$HOME", home.path)
                    ?.let(::File)
                    ?.takeIf { it.isDirectory && it != home }
            }.getOrNull()?.let { return it }
        }
        return File(home, "Music").takeIf { it.isDirectory }
    }
}

/** Paths are kept with forward slashes everywhere so folder grouping works the same on windows. */
fun File.normalPath(): String = absolutePath.replace('\\', '/')
