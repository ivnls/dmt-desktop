package dev.jyotiraditya.dmt

import dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinApi
import dev.jyotiraditya.dmt.data.remote.lrclib.LrclibApi
import dev.jyotiraditya.dmt.data.repository.CoverArtRepository
import dev.jyotiraditya.dmt.data.repository.JellyfinMediaRepositoryImpl
import dev.jyotiraditya.dmt.data.repository.LyricsRepository
import dev.jyotiraditya.dmt.data.repository.MediaRepositoryImpl
import dev.jyotiraditya.dmt.data.repository.PlaylistRepository
import dev.jyotiraditya.dmt.data.repository.PreferencesRepository
import dev.jyotiraditya.dmt.data.repository.TrackMediaRepository
import dev.jyotiraditya.dmt.domain.usecase.EmbedLyricsUseCase
import dev.jyotiraditya.dmt.domain.usecase.GetLyricsUseCase
import dev.jyotiraditya.dmt.domain.usecase.GetTrackTechUseCase
import dev.jyotiraditya.dmt.domain.usecase.JellyfinLoginUseCase
import dev.jyotiraditya.dmt.domain.usecase.MediaSourceProvider
import dev.jyotiraditya.dmt.domain.usecase.ScanLibraryUseCase
import dev.jyotiraditya.dmt.playback.AudioOutput
import dev.jyotiraditya.dmt.playback.Player
import dev.jyotiraditya.dmt.presentation.player.PlayerViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinSession

/** Hand-wired replacement for the android app's hilt graph. */
class AppGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val httpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
        }
    }

    val preferences = PreferencesRepository()
    private val jellyfinApi = JellyfinApi(httpClient)
    private val lrclibApi = LrclibApi(httpClient)
    private val output = AudioOutput()

    val player = Player(scope, output, preferences)

    private val sources = MediaSourceProvider(
        local = MediaRepositoryImpl(preferences),
        jellyfin = JellyfinMediaRepositoryImpl(jellyfinApi, preferences),
        settingsRepository = preferences,
    )

    init {
        scope.launch {
            preferences.settings.collect { JellyfinSession.set(it.jellyfinUrl, it.jellyfinToken) }
        }
    }

    val viewModel = PlayerViewModel(
        player = player,
        preferencesRepository = preferences,
        scanLibrary = ScanLibraryUseCase(sources),
        jellyfinLogin = JellyfinLoginUseCase(jellyfinApi, preferences),
        getLyrics = GetLyricsUseCase(LyricsRepository(), jellyfinApi, lrclibApi, preferences),
        embedLyrics = EmbedLyricsUseCase(),
        getTrackTech = GetTrackTechUseCase(TrackMediaRepository(output)),
        trackMediaRepository = TrackMediaRepository(output),
        coverArtRepository = CoverArtRepository(httpClient),
        playlistRepository = PlaylistRepository(),
    )

    fun shutdown() {
        runCatching { player.release() }
        runCatching { viewModel.onCleared() }
        runCatching { httpClient.close() }
    }
}
