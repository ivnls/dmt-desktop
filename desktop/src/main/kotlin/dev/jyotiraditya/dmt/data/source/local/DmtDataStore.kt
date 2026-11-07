package dev.jyotiraditya.dmt.data.source.local

import dev.jyotiraditya.dmt.platform.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Tiny replacement for androidx datastore-preferences: a properties file with a
 * StateFlow in front of it. Values are stored as strings and typed by [Key].
 */
class DmtStore(private val file: File = File(AppDirs.config, "dmt.properties")) {

    private val mutex = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<Prefs> = _data.asStateFlow()

    private fun load(): Prefs =
        runCatching {
            val props = Properties().apply { file.inputStream().use(::load) }
            Prefs(props.stringPropertyNames().associateWith { props.getProperty(it) })
        }.getOrDefault(Prefs(emptyMap()))

    suspend fun edit(block: (MutablePrefs) -> Unit) {
        mutex.withLock {
            val next = MutablePrefs(_data.value.values.toMutableMap()).apply(block)
            val snapshot = Prefs(next.values.toMap())
            if (snapshot.values == _data.value.values) return
            _data.value = snapshot
            withContext(Dispatchers.IO) { persist(snapshot) }
        }
    }

    private fun persist(prefs: Prefs) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            val props = Properties().apply { prefs.values.forEach { (k, v) -> setProperty(k, v) } }
            tmp.outputStream().use { props.store(it, "dmt") }
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            runCatching {
                val props = Properties().apply { prefs.values.forEach { (k, v) -> setProperty(k, v) } }
                file.outputStream().use { props.store(it, "dmt") }
            }
        }
    }
}

sealed class Key<T>(val name: String) {
    abstract fun decode(raw: String): T?
    abstract fun encode(value: T): String
}

class BoolKey(name: String) : Key<Boolean>(name) {
    override fun decode(raw: String) = raw.toBooleanStrictOrNull()
    override fun encode(value: Boolean) = value.toString()
}

class IntKey(name: String) : Key<Int>(name) {
    override fun decode(raw: String) = raw.toIntOrNull()
    override fun encode(value: Int) = value.toString()
}

class LongKey(name: String) : Key<Long>(name) {
    override fun decode(raw: String) = raw.toLongOrNull()
    override fun encode(value: Long) = value.toString()
}

class FloatKey(name: String) : Key<Float>(name) {
    override fun decode(raw: String) = raw.toFloatOrNull()
    override fun encode(value: Float) = value.toString()
}

class StringKey(name: String) : Key<String>(name) {
    override fun decode(raw: String) = raw
    override fun encode(value: String) = value
}

/** Set / list of strings, stored one per line. */
class StringListKey(name: String) : Key<List<String>>(name) {
    override fun decode(raw: String) = raw.split('\n').filter { it.isNotEmpty() }
    override fun encode(value: List<String>) = value.joinToString("\n")
}

open class Prefs(open val values: Map<String, String>) {
    operator fun <T> get(key: Key<T>): T? = values[key.name]?.let(key::decode)
}

class MutablePrefs(override val values: MutableMap<String, String>) : Prefs(values) {
    operator fun <T> set(key: Key<T>, value: T) {
        values[key.name] = key.encode(value)
    }

    fun remove(key: Key<*>) {
        values.remove(key.name)
    }
}

val KEY_WAVE = BoolKey("wave")
val KEY_NORMALIZE = BoolKey("normalize_volume")
val KEY_COLS = IntKey("cols")
val KEY_SPECS = BoolKey("specs")
val KEY_RAW = BoolKey("raw_art")
val KEY_LYRICS_FROM_FILE = BoolKey("lyrics_from_file")
val KEY_ROMANIZED_LYRICS = BoolKey("romanized_lyrics")
val KEY_STOP_ON_DISMISS = BoolKey("stop_on_dismiss")
val KEY_SETUP_DONE = BoolKey("setup_done")
val KEY_BLOCKED_FOLDERS = StringListKey("blocked_folders")
val KEY_MUSIC_FOLDERS = StringListKey("music_folders")
val KEY_SPEED = FloatKey("speed")
val KEY_VOLUME = FloatKey("volume")
val KEY_STAT_TOTAL = LongKey("stat_total_ms")
val KEY_STAT_COUNTS = StringKey("stat_counts")
val KEY_LAST_QUEUE = StringKey("last_queue")
val KEY_LAST_INDEX = IntKey("last_index")
val KEY_LAST_POS = LongKey("last_pos")
val KEY_SHUFFLE = BoolKey("shuffle")
val KEY_REPEAT = IntKey("repeat")
val KEY_SOURCE_MODE = IntKey("source_mode")
val KEY_LIBRARY_SORT = IntKey("library_sort")
val KEY_JELLYFIN_URL = StringKey("jellyfin_url")
val KEY_JELLYFIN_USER_ID = StringKey("jellyfin_user_id")
val KEY_JELLYFIN_TOKEN = StringKey("jellyfin_token")
val KEY_WINDOW = StringKey("window")

fun String.toCounts(): Map<Long, Int> =
    split(';')
        .mapNotNull { entry ->
            val parts = entry.split(':')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val count = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            id to count
        }
        .toMap()

fun Map<Long, Int>.encodeCounts(): String = entries.joinToString(";") { "${it.key}:${it.value}" }
