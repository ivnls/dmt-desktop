package dev.jyotiraditya.dmt.data.repository

import dev.jyotiraditya.dmt.data.source.local.DmtStore
import dev.jyotiraditya.dmt.data.source.local.KEY_BLOCKED_FOLDERS
import dev.jyotiraditya.dmt.data.source.local.KEY_COLS
import dev.jyotiraditya.dmt.data.source.local.KEY_JELLYFIN_TOKEN
import dev.jyotiraditya.dmt.data.source.local.KEY_JELLYFIN_URL
import dev.jyotiraditya.dmt.data.source.local.KEY_JELLYFIN_USER_ID
import dev.jyotiraditya.dmt.data.source.local.KEY_LAST_INDEX
import dev.jyotiraditya.dmt.data.source.local.KEY_LAST_POS
import dev.jyotiraditya.dmt.data.source.local.KEY_LAST_QUEUE
import dev.jyotiraditya.dmt.data.source.local.KEY_LIBRARY_SORT
import dev.jyotiraditya.dmt.data.source.local.KEY_LYRICS_FROM_FILE
import dev.jyotiraditya.dmt.data.source.local.KEY_MUSIC_FOLDERS
import dev.jyotiraditya.dmt.data.source.local.KEY_NORMALIZE
import dev.jyotiraditya.dmt.data.source.local.KEY_RAW
import dev.jyotiraditya.dmt.data.source.local.KEY_REPEAT
import dev.jyotiraditya.dmt.data.source.local.KEY_ROMANIZED_LYRICS
import dev.jyotiraditya.dmt.data.source.local.KEY_SETUP_DONE
import dev.jyotiraditya.dmt.data.source.local.KEY_SHUFFLE
import dev.jyotiraditya.dmt.data.source.local.KEY_SOURCE_MODE
import dev.jyotiraditya.dmt.data.source.local.KEY_SPECS
import dev.jyotiraditya.dmt.data.source.local.KEY_SPEED
import dev.jyotiraditya.dmt.data.source.local.KEY_STAT_COUNTS
import dev.jyotiraditya.dmt.data.source.local.KEY_STAT_TOTAL
import dev.jyotiraditya.dmt.data.source.local.KEY_STOP_ON_DISMISS
import dev.jyotiraditya.dmt.data.source.local.KEY_VOLUME
import dev.jyotiraditya.dmt.data.source.local.KEY_WAVE
import dev.jyotiraditya.dmt.data.source.local.KEY_WINDOW
import dev.jyotiraditya.dmt.data.source.local.KEY_UI_SCALE
import dev.jyotiraditya.dmt.data.source.local.encodeCounts
import dev.jyotiraditya.dmt.data.source.local.toCounts
import dev.jyotiraditya.dmt.domain.model.DmtSettings
import dev.jyotiraditya.dmt.domain.model.DmtStats
import dev.jyotiraditya.dmt.domain.model.LastSession
import dev.jyotiraditya.dmt.domain.model.LibrarySort
import dev.jyotiraditya.dmt.domain.model.SourceMode
import dev.jyotiraditya.dmt.platform.AppDirs
import dev.jyotiraditya.dmt.platform.normalPath
import dev.jyotiraditya.dmt.platform.secrets.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val COUNTED_LISTEN_MS = 30_000L

private const val SECRET_JELLYFIN_TOKEN = "jellyfin_token"

class PreferencesRepository(
    private val store: DmtStore = DmtStore(),
    secretStoreFactory: () -> SecretStore = SecretStore::create,
) {

    private val secrets: SecretStore by lazy(secretStoreFactory)

    /** The jellyfin token, kept in the os keyring; null until loaded (see [loadToken]). */
    private val token = MutableStateFlow<TokenState>(TokenState.Loading)

    private sealed interface TokenState {
        data object Loading : TokenState
        data class Ready(val value: String?) : TokenState
    }

    init {
        Thread(::loadToken, "dmt-secrets").apply { isDaemon = true }.start()
    }

    /**
     * Reads the token from the keyring, moving a plain-text token left in dmt.properties by
     * older builds into the keyring first. Only touches the keyring when jellyfin is configured,
     * so a local-only setup never triggers an unlock prompt.
     */
    private fun loadToken() {
        val prefs = store.data.value
        val legacy = prefs[KEY_JELLYFIN_TOKEN]
        val value = runCatching {
            when {
                legacy != null -> {
                    secrets.put(SECRET_JELLYFIN_TOKEN, legacy)
                    kotlinx.coroutines.runBlocking { store.edit { it.remove(KEY_JELLYFIN_TOKEN) } }
                    legacy
                }

                prefs[KEY_JELLYFIN_URL] != null -> secrets.get(SECRET_JELLYFIN_TOKEN)
                else -> null
            }
        }.getOrNull()
        token.value = TokenState.Ready(value)
    }

    val settings: Flow<DmtSettings> = combine(
        store.data,
        token.filterIsInstance<TokenState.Ready>(),
    ) { prefs, tokenState ->
        DmtSettings(
            wave = prefs[KEY_WAVE] ?: true,
            normalizeVolume = prefs[KEY_NORMALIZE] ?: false,
            cols = prefs[KEY_COLS] ?: 96,
            listSpecs = prefs[KEY_SPECS] ?: true,
            romanizedLyrics = prefs[KEY_ROMANIZED_LYRICS] ?: false,
            rawArt = prefs[KEY_RAW] ?: false,
            lyricsFromFile = prefs[KEY_LYRICS_FROM_FILE] ?: false,
            stopOnDismiss = prefs[KEY_STOP_ON_DISMISS] ?: false,
            uiScale = prefs[KEY_UI_SCALE] ?: 0,
            setupDone = prefs[KEY_SETUP_DONE] ?: false,
            blockedFolders = prefs[KEY_BLOCKED_FOLDERS]?.toSet() ?: emptySet(),
            musicFolders = prefs[KEY_MUSIC_FOLDERS]
                ?: listOfNotNull(AppDirs.defaultMusicDir()?.normalPath()),
            sourceMode = SourceMode.entries[(prefs[KEY_SOURCE_MODE] ?: 0).mod(SourceMode.entries.size)],
            librarySort = LibrarySort.entries[(prefs[KEY_LIBRARY_SORT] ?: 0).mod(LibrarySort.entries.size)],
            jellyfinUrl = prefs[KEY_JELLYFIN_URL],
            jellyfinUserId = prefs[KEY_JELLYFIN_USER_ID],
            jellyfinToken = tokenState.value,
        )
    }.distinctUntilChanged()

    /** Synchronous read for code that cannot suspend (window setup, tray). */
    suspend fun settingsNow(): DmtSettings = settings.first()

    suspend fun save(settings: DmtSettings) {
        store.edit {
            it[KEY_WAVE] = settings.wave
            it[KEY_NORMALIZE] = settings.normalizeVolume
            it[KEY_COLS] = settings.cols
            it[KEY_SPECS] = settings.listSpecs
            it[KEY_ROMANIZED_LYRICS] = settings.romanizedLyrics
            it[KEY_RAW] = settings.rawArt
            it[KEY_LYRICS_FROM_FILE] = settings.lyricsFromFile
            it[KEY_STOP_ON_DISMISS] = settings.stopOnDismiss
            it[KEY_UI_SCALE] = settings.uiScale
            it[KEY_SETUP_DONE] = settings.setupDone
            it[KEY_BLOCKED_FOLDERS] = settings.blockedFolders.sorted()
            it[KEY_MUSIC_FOLDERS] = settings.musicFolders
            it[KEY_SOURCE_MODE] = settings.sourceMode.ordinal
            it[KEY_LIBRARY_SORT] = settings.librarySort.ordinal
            settings.jellyfinUrl?.let { url -> it[KEY_JELLYFIN_URL] = url } ?: it.remove(KEY_JELLYFIN_URL)
            settings.jellyfinUserId?.let { id -> it[KEY_JELLYFIN_USER_ID] = id } ?: it.remove(KEY_JELLYFIN_USER_ID)
            it.remove(KEY_JELLYFIN_TOKEN)
        }
        val current = (token.value as? TokenState.Ready)?.value
        if (settings.jellyfinToken != current) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val t = settings.jellyfinToken
                    if (t == null) secrets.delete(SECRET_JELLYFIN_TOKEN) else secrets.put(SECRET_JELLYFIN_TOKEN, t)
                }
            }
            token.value = TokenState.Ready(settings.jellyfinToken)
        }
    }

    /** Which store holds credentials, for the sources page. */
    val secretStoreName: String get() = runCatching { secrets.name }.getOrDefault("file")

    fun savedSpeed(): Float = store.data.value[KEY_SPEED] ?: 1f

    suspend fun saveSpeed(speed: Float) = store.edit { it[KEY_SPEED] = speed }

    fun savedVolume(): Float = store.data.value[KEY_VOLUME] ?: 1f

    suspend fun saveVolume(volume: Float) = store.edit { it[KEY_VOLUME] = volume }

    fun savedShuffle(): Boolean = store.data.value[KEY_SHUFFLE] ?: false

    fun savedRepeat(): Int = store.data.value[KEY_REPEAT] ?: 0

    suspend fun saveModes(shuffle: Boolean, repeat: Int) = store.edit {
        it[KEY_SHUFFLE] = shuffle
        it[KEY_REPEAT] = repeat
    }

    fun windowState(): String? = store.data.value[KEY_WINDOW]

    suspend fun saveWindowState(value: String) = store.edit { it[KEY_WINDOW] = value }

    fun lastSession(): LastSession? {
        val prefs = store.data.value
        val ids = (prefs[KEY_LAST_QUEUE] ?: "").split(',').mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return null
        return LastSession(
            queueIds = ids,
            index = prefs[KEY_LAST_INDEX] ?: 0,
            positionMs = prefs[KEY_LAST_POS] ?: 0L,
        )
    }

    suspend fun saveSession(session: LastSession) {
        store.edit { prefs ->
            prefs[KEY_LAST_QUEUE] = session.queueIds.joinToString(",")
            prefs[KEY_LAST_INDEX] = session.index
            prefs[KEY_LAST_POS] = session.positionMs
        }
    }

    suspend fun recordPlayback(playedMs: Long, trackId: Long?) {
        store.edit { prefs ->
            prefs[KEY_STAT_TOTAL] = (prefs[KEY_STAT_TOTAL] ?: 0L) + playedMs
            if (playedMs >= COUNTED_LISTEN_MS && trackId != null) {
                val counts = (prefs[KEY_STAT_COUNTS] ?: "").toCounts().toMutableMap()
                counts[trackId] = (counts[trackId] ?: 0) + 1
                prefs[KEY_STAT_COUNTS] = counts.encodeCounts()
            }
        }
    }

    val stats: Flow<DmtStats> = store.data.map { prefs ->
        DmtStats(
            totalMs = prefs[KEY_STAT_TOTAL] ?: 0L,
            counts = (prefs[KEY_STAT_COUNTS] ?: "").toCounts(),
        )
    }.distinctUntilChanged()
}
