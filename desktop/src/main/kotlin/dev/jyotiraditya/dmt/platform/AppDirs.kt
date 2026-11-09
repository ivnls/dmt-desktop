package dev.jyotiraditya.dmt.platform

import java.io.File

object Platform {
    private val osName = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows: Boolean = osName.contains("win")
    val isMac: Boolean = osName.contains("mac")
    val isLinux: Boolean = !isWindows && !isMac

    val version: String by lazy {
        runCatching {
            Platform::class.java.getResourceAsStream("/tmpl.properties")?.use { stream ->
                java.util.Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "dev"
    }
}

private const val APP = "tmpl"
/** the project was called dmt before; its folders are migrated once */
private const val LEGACY_APP = "dmt"

/** Per-OS locations for config, cache and data (XDG on linux, %APPDATA% on windows). */
object AppDirs {
    private val home: File = File(System.getProperty("user.home"))

    private fun env(name: String): File? =
        System.getenv(name)?.takeIf { it.isNotBlank() }?.let(::File)

    val config: File by lazy {
        when {
            Platform.isWindows -> File(env("APPDATA") ?: File(home, "AppData/Roaming"), APP)
            Platform.isMac -> File(home, "Library/Application Support/$APP")
            else -> File(env("XDG_CONFIG_HOME") ?: File(home, ".config"), APP)
        }.also(::migrateLegacy).apply { mkdirs() }
    }

    val data: File by lazy {
        when {
            Platform.isWindows -> File(env("APPDATA") ?: File(home, "AppData/Roaming"), APP)
            Platform.isMac -> File(home, "Library/Application Support/$APP")
            else -> File(env("XDG_DATA_HOME") ?: File(home, ".local/share"), APP)
        }.also(::migrateLegacy).apply { mkdirs() }
    }

    val cache: File by lazy {
        when {
            Platform.isWindows -> File(env("LOCALAPPDATA") ?: File(home, "AppData/Local"), "$APP/cache")
            Platform.isMac -> File(home, "Library/Caches/$APP")
            else -> File(env("XDG_CACHE_HOME") ?: File(home, ".cache"), APP)
        }.apply { mkdirs() }
    }

    /**
     * First start after the rename: copy the old dmt folder (settings, playlists, secrets file)
     * to the new tmpl one. The old folder is left in place.
     */
    private fun migrateLegacy(target: File) {
        if (target.exists()) return
        val legacy = File(target.parentFile, if (target.name == APP) LEGACY_APP else return)
        if (!legacy.isDirectory) return
        runCatching {
            legacy.copyRecursively(target, overwrite = false)
            File(target, "$LEGACY_APP.properties").takeIf { it.isFile }
                ?.renameTo(File(target, "$APP.properties"))
        }
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
