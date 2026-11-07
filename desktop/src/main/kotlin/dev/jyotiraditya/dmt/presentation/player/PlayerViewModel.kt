package dev.jyotiraditya.dmt.presentation.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.jyotiraditya.dmt.resources.*
import org.jetbrains.compose.resources.getString
import dev.jyotiraditya.dmt.core.base.BaseViewModel
import dev.jyotiraditya.dmt.core.common.generateAsciiPlaceholder
import dev.jyotiraditya.dmt.core.common.toAsciiBitmap
import dev.jyotiraditya.dmt.data.repository.CoverArtRepository
import dev.jyotiraditya.dmt.data.repository.PlaylistRepository
import dev.jyotiraditya.dmt.data.repository.PreferencesRepository
import dev.jyotiraditya.dmt.data.repository.TrackMediaRepository
import dev.jyotiraditya.dmt.domain.model.Album
import dev.jyotiraditya.dmt.domain.model.Artist
import dev.jyotiraditya.dmt.domain.model.Folder
import dev.jyotiraditya.dmt.domain.model.LibrarySort
import dev.jyotiraditya.dmt.domain.model.SourceMode
import dev.jyotiraditya.dmt.domain.model.Track
import dev.jyotiraditya.dmt.domain.usecase.EmbedLyricsUseCase
import dev.jyotiraditya.dmt.domain.usecase.GetLyricsUseCase
import dev.jyotiraditya.dmt.domain.usecase.GetTrackTechUseCase
import dev.jyotiraditya.dmt.domain.usecase.JellyfinLoginUseCase
import dev.jyotiraditya.dmt.domain.usecase.ScanLibraryUseCase
import dev.jyotiraditya.dmt.playback.Player
import dev.jyotiraditya.dmt.playback.PlayerListener
import dev.jyotiraditya.dmt.util.queueWithPosition
import dev.jyotiraditya.dmt.util.resolveQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
private val SLEEP_STEPS = listOf(0, 15, 30, 60)
private const val HOME_ART_COLS = 48
private const val HOME_ART_CACHE_ENTRIES = 256

private data class FilteredLibrary(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val folders: List<Folder>,
)

class PlayerViewModel(
    private val player: Player,
    private val preferencesRepository: PreferencesRepository,
    private val scanLibrary: ScanLibraryUseCase,
    private val jellyfinLogin: JellyfinLoginUseCase,
    private val getLyrics: GetLyricsUseCase,
    private val embedLyrics: EmbedLyricsUseCase,
    private val getTrackTech: GetTrackTechUseCase,
    private val trackMediaRepository: TrackMediaRepository,
    private val coverArtRepository: CoverArtRepository,
    private val playlistRepository: PlaylistRepository,
) : BaseViewModel<DmtAction, DmtState, PlayerEffect>(
    DmtState(hasPermission = true),
) {

    private var controller: Player? = null
    private val homeArtCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > HOME_ART_CACHE_ENTRIES
    }
    private var noticeJob: Job? = null
    private var coverJob: Job? = null
    private var techJob: Job? = null
    private var lyricsJob: Job? = null
    private var sleepEndAt: Long? = null
    private var sessionRestored = false

    init {
        viewModelScope.launch {
            val settings = preferencesRepository.settings.first()
            player.normalizeVolume = settings.normalizeVolume
            reduce {
                it.copy(
                    settings = settings,
                    settingsLoaded = true,
                    hasPermission = settings.musicFolders.isNotEmpty(),
                    volume = player.volume,
                )
            }
            scan()
        }
        viewModelScope.launch {
            preferencesRepository.stats.collect { stats ->
                reduce { if (it.stats == stats) it else it.copy(stats = stats) }
            }
        }
        viewModelScope.launch {
            trackMediaRepository.routeSpecs()
                .distinctUntilChanged()
                .flowOn(Dispatchers.IO)
                .collect { route ->
                    reduce { if (it.route == route) it else it.copy(route = route) }
                }
        }
        connect()
    }

    private fun <T> List<T>.matching(query: String, fields: (T) -> List<String>): List<T> =
        if (query.isBlank()) {
            this
        } else {
            filter { item -> fields(item).any { it.contains(query, true) } }
        }

    private fun filter(tracks: List<Track>, query: String, sort: LibrarySort): List<Track> =
        tracks
            .matching(query) { listOf(it.title, it.artist, it.album) }
            .sortedWith(sort.comparator)

    private fun filterAlbums(albums: List<Album>, query: String): List<Album> =
        albums.matching(query) { listOf(it.name, it.artist) }

    private fun filterArtists(artists: List<Artist>, query: String): List<Artist> =
        artists.matching(query) { listOf(it.name) }

    private fun filterFolders(folders: List<Folder>, query: String): List<Folder> =
        folders.matching(query) { listOf(it.name) }

    private fun mutatePlaylists(block: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            block()
            val playlists = playlistRepository.load(currentState.tracks)
            reduce { it.copy(playlists = playlists) }
        }
    }

    override fun onIntent(intent: DmtAction) {
        val c = controller
        when (intent) {
            DmtAction.Rescan -> scan()
            is DmtAction.Query -> {
                reduce { it.copy(query = intent.value) }
                val query = intent.value
                val tracks = currentState.tracks
                val albums = currentState.albums
                val artists = currentState.artists
                val folders = currentState.folders
                val sort = currentState.settings.librarySort
                viewModelScope.launch {
                    val (filteredTracks, filteredAlbums, filteredArtists, filteredFolders) =
                        withContext(Dispatchers.Default) {
                            FilteredLibrary(
                                tracks = filter(tracks, query, sort),
                                albums = filterAlbums(albums, query),
                                artists = filterArtists(artists, query),
                                folders = filterFolders(folders, query),
                            )
                        }
                    if (currentState.query == query) {
                        reduce {
                            it.copy(
                                filtered = filteredTracks,
                                filteredAlbums = filteredAlbums,
                                filteredArtists = filteredArtists,
                                filteredFolders = filteredFolders,
                            )
                        }
                    }
                }
            }

            is DmtAction.Show -> {
                reduce { it.copy(view = intent.view, error = null) }
            }

            is DmtAction.OpenAlbum -> reduce { it.copy(openAlbum = intent.name) }
            is DmtAction.OpenArtist -> reduce { it.copy(openArtist = intent.name) }
            is DmtAction.OpenFolder -> reduce { it.copy(openFolder = intent.path) }
            is DmtAction.OpenPlaylist -> reduce { it.copy(openPlaylist = intent.name) }

            is DmtAction.CreatePlaylist -> mutatePlaylists {
                playlistRepository.create(intent.name)
            }

            is DmtAction.DeletePlaylist -> {
                reduce { it.copy(openPlaylist = null) }
                mutatePlaylists { playlistRepository.delete(intent.name) }
            }

            is DmtAction.AddToPlaylist -> mutatePlaylists {
                playlistRepository.addTrack(intent.name, intent.track)
            }

            is DmtAction.RemoveFromPlaylist -> mutatePlaylists {
                playlistRepository.removeTrack(intent.name, intent.path)
            }

            is DmtAction.PlayAt -> c?.run {
                reduce { it.copy(error = null) }
                setMediaItems(intent.list, intent.index, 0L)
                play()
            }

            is DmtAction.Enqueue -> c?.run {
                addMediaItems(intent.list)
                notifyRes(Res.string.queued, intent.label)
            }

            is DmtAction.Jump -> c?.run {
                seekTo(intent.index, 0L)
                play()
            }

            DmtAction.TogglePlay -> c?.togglePlayPause()
            DmtAction.Next -> c?.seekToNext()
            DmtAction.Prev -> c?.seekToPrevious()
            DmtAction.ToggleShuffle -> c?.run { shuffleModeEnabled = !shuffleModeEnabled }
            DmtAction.CycleRepeat -> c?.cycleRepeat()

            is DmtAction.Seek -> c?.run {
                val duration = currentState.durationMs
                if (duration > 0) {
                    val target = (intent.fraction * duration).toLong()
                    seekTo(target)
                    reduce { it.copy(positionMs = target) }
                }
            }

            is DmtAction.SeekBy -> c?.run {
                if (mediaItemCount == 0) return@run
                val duration = currentState.durationMs
                val target = (currentPosition + intent.deltaMs)
                    .coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
                seekTo(target)
                reduce { it.copy(positionMs = target) }
            }

            is DmtAction.Volume -> c?.run {
                volume = ((volume + intent.delta) * 20).roundToInt() / 20f
                reduce { it.copy(volume = volume) }
                viewModelScope.launch { preferencesRepository.saveVolume(volume) }
                notify("vol ${(volume * 100).roundToInt()}%")
            }

            is DmtAction.Expand -> reduce { it.copy(expanded = intent.value) }

            is DmtAction.RemoveAt -> c?.run {
                if (intent.index in 0 until mediaItemCount) removeMediaItem(intent.index)
            }

            DmtAction.FetchLyrics -> fetchOnlineLyrics()
            DmtAction.CycleSleep -> cycleSleep()
            DmtAction.CycleSpeed -> cycleSpeed()
            DmtAction.OpenEqualizer -> notifyRes(Res.string.no_eq)

            is DmtAction.Config -> {
                val old = currentState.settings
                reduce {
                    it.copy(
                        settings = intent.settings,
                        hasPermission = intent.settings.musicFolders.isNotEmpty(),
                    )
                }
                player.normalizeVolume = intent.settings.normalizeVolume
                if (old.librarySort != intent.settings.librarySort) {
                    reduce {
                        it.copy(
                            filtered = filter(it.tracks, it.query, intent.settings.librarySort),
                        )
                    }
                }
                if (old.sourceMode != intent.settings.sourceMode) {
                    c?.clearMediaItems()
                }
                viewModelScope.launch {
                    preferencesRepository.save(intent.settings)
                    if (old.sourceMode != intent.settings.sourceMode ||
                        old.blockedFolders != intent.settings.blockedFolders ||
                        old.musicFolders != intent.settings.musicFolders
                    ) {
                        scan()
                    }
                }
                if (old.cols != intent.settings.cols) loadCover(c?.currentTrack)
                if (old.lyricsFromFile != intent.settings.lyricsFromFile) {
                    loadLyrics(c?.currentTrack)
                }
            }

            is DmtAction.ShowLogin ->
                reduce {
                    it.copy(
                        view = DmtView.SOURCE_LOGIN,
                        loginSource = intent.mode,
                        error = null,
                    )
                }

            is DmtAction.SourceLogin -> when (intent.mode) {
                SourceMode.JELLYFIN -> loginToJellyfin(intent)
                SourceMode.LOCAL -> Unit
            }
        }
    }

    private fun loginToJellyfin(intent: DmtAction.SourceLogin) =
        viewModelScope.launch {
            reduce { it.copy(scanning = true, error = null) }
            jellyfinLogin(intent.url, intent.username, intent.password)
                .onSuccess {
                    val settings = preferencesRepository.settings.first()
                    reduce { it.copy(settings = settings, view = DmtView.LIBRARY) }
                    scan()
                }
                .onFailure {
                    val message = getString(Res.string.source_login_failed)
                    reduce {
                        it.copy(
                            scanning = false,
                            error = message,
                        )
                    }
                }
        }

    private fun connect() =
        viewModelScope.launch {
            val c = player
            controller = c
            c.addListener(listener)
            syncFrom(c)
            restoreSleep(c)
            loadCover(c.currentTrack)
            loadTech(c.currentTrack)
            loadLyrics(c.currentTrack)
            restoreSession()
            while (isActive) {
                val position = c.currentPosition.coerceAtLeast(0L)
                val duration = c.duration.coerceAtLeast(0L)
                val index = c.currentMediaItemIndex
                val sleepLeft = sleepEndAt?.let { end ->
                    (end - System.currentTimeMillis()).coerceAtLeast(0L)
                } ?: 0L
                val sleepExpired = sleepEndAt != null && sleepLeft == 0L
                if (sleepExpired) sleepEndAt = null
                reduce {
                    if (it.positionMs == position &&
                        it.durationMs == duration &&
                        it.queueIndex == index &&
                        it.sleepLeftMs == sleepLeft &&
                        !sleepExpired
                    ) {
                        it
                    } else {
                        it.copy(
                            positionMs = position,
                            durationMs = duration,
                            queueIndex = index,
                            queuePosition = if (index == it.queueIndex) {
                                it.queuePosition
                            } else {
                                it.queue.indexOfFirst { entry -> entry.index == index }
                            },
                            sleepLeftMs = sleepLeft,
                            sleepMinutes = if (sleepExpired) 0 else it.sleepMinutes,
                        )
                    }
                }
                delay((if (c.isPlaying) 250 else 1000).milliseconds)
            }
        }

    private val listener = object : PlayerListener {
        override fun onMediaItemTransition(track: Track?) {
            reduce {
                it.copy(
                    nowPlayingId = track?.id?.toString(),
                    title = track?.title ?: "unknown",
                    artist = track?.artist ?: "unknown artist",
                    album = track?.album.orEmpty(),
                    durationMs = track?.durationMs ?: 0L,
                    positionMs = 0L,
                    lyrics = null,
                    fault = null,
                    error = null,
                )
            }
            loadCover(track)
            loadTech(track)
            loadLyrics(track)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            reduce { it.copy(isPlaying = isPlaying) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            controller?.let { c ->
                val (queue, queuePosition) = c.queueWithPosition()
                reduce {
                    it.copy(
                        shuffle = shuffleModeEnabled,
                        queue = queue,
                        queuePosition = queuePosition,
                    )
                }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            reduce { it.copy(repeat = repeatMode) }
        }

        override fun onPlaybackSpeedChanged(speed: Float) {
            reduce { it.copy(speed = speed) }
        }

        override fun onTimelineChanged() {
            controller?.let { c ->
                val (queue, queuePosition) = c.queueWithPosition()
                reduce { it.copy(queue = queue, queuePosition = queuePosition) }
            }
        }

        override fun onUnsupported() {
            viewModelScope.launch {
                val format = currentState.tech
                    .firstOrNull { spec -> spec.label == "FMT" }
                    ?.value
                    ?.lowercase()
                    .orEmpty()
                val message = getString(Res.string.playback_unsupported, format)
                reduce { it.copy(fault = message) }
            }
        }

        override fun onPlayerError(code: String) {
            viewModelScope.launch {
                val message = getString(Res.string.playback_error, code.lowercase())
                reduce { it.copy(fault = message) }
            }
        }
    }

    private fun syncFrom(c: Player) {
        val (queue, queuePosition) = c.queueWithPosition()
        val track = c.currentTrack
        reduce {
            it.copy(
                nowPlayingId = track?.id?.toString(),
                title = track?.title ?: "unknown",
                artist = track?.artist ?: "unknown artist",
                isPlaying = c.isPlaying,
                shuffle = c.shuffleModeEnabled,
                repeat = c.repeatMode,
                album = track?.album.orEmpty(),
                speed = c.playbackSpeed,
                queue = queue,
                queuePosition = queuePosition,
            )
        }
    }

    private fun scan() =
        viewModelScope.launch {
            reduce { it.copy(scanning = true) }
            val query = currentState.query
            val scanFailed = getString(Res.string.scan_failed, currentState.settings.sourceMode.label)
            val library = runCatching { scanLibrary() }.getOrElse {
                reduce { state ->
                    state.copy(
                        scanning = false,
                        tracks = emptyList(),
                        albums = emptyList(),
                        artists = emptyList(),
                        folders = emptyList(),
                        filtered = emptyList(),
                        filteredAlbums = emptyList(),
                        filteredArtists = emptyList(),
                        filteredFolders = emptyList(),
                        error = scanFailed,
                    )
                }
                return@launch
            }
            val (filteredTracks, filteredAlbums, filteredArtists, filteredFolders) = withContext(
                Dispatchers.Default,
            ) {
                FilteredLibrary(
                    tracks = filter(library.tracks, query, currentState.settings.librarySort),
                    albums = filterAlbums(library.albums, query),
                    artists = filterArtists(library.artists, query),
                    folders = filterFolders(library.folders, query),
                )
            }
            reduce {
                it.copy(
                    scanning = false,
                    tracks = library.tracks,
                    albums = library.albums,
                    artists = library.artists,
                    folders = library.folders,
                    filtered = filteredTracks,
                    filteredAlbums = filteredAlbums,
                    filteredArtists = filteredArtists,
                    filteredFolders = filteredFolders,
                    error = null,
                )
            }
            mutatePlaylists()
            restoreSession()
        }

    private fun restoreSession() {
        if (sessionRestored) return
        val c = controller ?: return
        val tracks = currentState.tracks
        if (tracks.isEmpty()) return
        if (c.mediaItemCount > 0) {
            sessionRestored = true
            return
        }
        sessionRestored = true
        viewModelScope.launch {
            val session = preferencesRepository.lastSession() ?: return@launch
            val (existing, index, position) = session.resolveQueue(tracks) ?: return@launch
            c.setMediaItems(existing, index, position)
        }
    }

    private fun fetchOnlineLyrics() {
        val id = currentState.nowPlayingId ?: return
        if (currentState.lyricsFetching || currentState.lyrics != null) return
        val track = currentState.tracks.find { it.id.toString() == id } ?: return

        reduce { it.copy(lyricsFetching = true) }
        viewModelScope.launch {
            val text = getLyrics.onlineText(track)
            val lyrics = text?.let { getLyrics.parse(it) }

            reduce {
                if (it.nowPlayingId != id) {
                    it.copy(lyricsFetching = false)
                } else {
                    it.copy(lyricsFetching = false, lyrics = lyrics)
                }
            }
            if (lyrics == null || text == null) {
                notifyRes(Res.string.no_lyrics_found)
                return@launch
            }

            if (!embedLyrics.canWrite(track)) {
                notifyRes(Res.string.lyrics_embed_unsupported)
                return@launch
            }
            val done = embedLyrics(track, text)
            notifyRes(if (done) Res.string.lyrics_embedded else Res.string.lyrics_embed_failed)
        }
    }

    private fun loadLyrics(track: Track?) {
        val forId = track?.id?.toString()
        reduce { it.copy(lyricsFetching = true) }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val known = currentState.tracks.find { it.id.toString() == forId } ?: track
            val lyrics = known?.let { getLyrics(it) }
            reduce {
                if (it.nowPlayingId != forId) {
                    it
                } else {
                    it.copy(lyrics = lyrics, lyricsFetching = false)
                }
            }
        }
    }

    suspend fun homeArt(track: Track): ImageBitmap {
        val rawMode = currentState.settings.rawArt
        val key = "${track.id}:$rawMode"
        homeArtCache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val raw = runCatching { coverArtRepository.loadArt(track) }.getOrNull()
            val art = when {
                raw != null && rawMode -> raw.toComposeImageBitmap()
                raw != null ->
                    runCatching { raw.toAsciiBitmap(HOME_ART_COLS) }.getOrNull()
                        ?: generateAsciiPlaceholder(track.id, HOME_ART_COLS)

                else -> generateAsciiPlaceholder(track.id, HOME_ART_COLS)
            }
            withContext(Dispatchers.Main) { homeArtCache[key] = art }
            art
        }
    }

    private fun loadCover(track: Track?) {
        val forId = track?.id?.toString()
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) {
                track?.let { runCatching { coverArtRepository.loadArt(it) }.getOrNull() }
            }
            val cols = currentState.settings.cols
            val cover = withContext(Dispatchers.IO) {
                raw?.let { art ->
                    runCatching { art.toAsciiBitmap(cols) }.getOrNull()
                } ?: track?.let {
                    generateAsciiPlaceholder(seed = it.id, cols = cols)
                }
            }
            val rawBitmap = raw?.let { runCatching { it.toComposeImageBitmap() }.getOrNull() }
            reduce {
                if (it.nowPlayingId != forId) it else it.copy(cover = cover, artRaw = rawBitmap)
            }
        }
    }

    private fun loadTech(track: Track?) {
        val id = track?.id?.toString()
        techJob?.cancel()
        techJob = viewModelScope.launch {
            val known = currentState.tracks.find { t -> t.id.toString() == id } ?: track
            val tech = known?.let { getTrackTech(it) }.orEmpty()
            reduce {
                if (it.nowPlayingId != id) it else it.copy(tech = tech)
            }
        }
    }

    private fun cycleSpeed() {
        val c = controller ?: return
        val currentIndex = SPEED_STEPS.indexOfFirst { abs(it - currentState.speed) < 0.01f }
        val next = SPEED_STEPS[(currentIndex + 1).mod(SPEED_STEPS.size)]
        c.setPlaybackSpeed(next)
    }

    private fun cycleSleep() {
        val c = controller ?: return
        val currentIndex = SLEEP_STEPS.indexOf(currentState.sleepMinutes)
        val next = SLEEP_STEPS[(currentIndex + 1).mod(SLEEP_STEPS.size)]
        val endAt = if (next == 0) 0L else System.currentTimeMillis() + next * 60_000L
        c.scheduleSleep(endAt)
        sleepEndAt = endAt.takeIf { it > 0L }
        reduce {
            it.copy(
                sleepMinutes = next,
                sleepLeftMs = if (next == 0) 0L else next * 60_000L,
            )
        }
    }

    private fun restoreSleep(c: Player) {
        val endAt = c.sleepEndAt ?: return
        if (endAt > System.currentTimeMillis()) {
            sleepEndAt = endAt
            val left = endAt - System.currentTimeMillis()
            val step = when {
                left <= 15 * 60_000L -> 15
                left <= 30 * 60_000L -> 30
                else -> 60
            }
            reduce { it.copy(sleepMinutes = step, sleepLeftMs = left) }
        }
    }

    fun notify(message: String) {
        noticeJob?.cancel()
        reduce { it.copy(notice = message) }
        noticeJob = viewModelScope.launch {
            delay(2.seconds)
            reduce { it.copy(notice = null) }
        }
    }

    private fun notifyRes(res: org.jetbrains.compose.resources.StringResource, vararg args: Any) {
        viewModelScope.launch { notify(getString(res, *args)) }
    }

    override fun onCleared() {
        controller = null
        super.onCleared()
    }
}
