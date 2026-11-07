package dev.jyotiraditya.dmt.domain.usecase

import dev.jyotiraditya.dmt.data.repository.PreferencesRepository
import dev.jyotiraditya.dmt.domain.model.SourceMode
import dev.jyotiraditya.dmt.domain.repository.MediaRepository

class MediaSourceProvider(
    private val local: MediaRepository,
    private val jellyfin: MediaRepository,
    private val settingsRepository: PreferencesRepository,
) {
    suspend fun current(): MediaRepository =
        when (settingsRepository.settingsNow().sourceMode) {
            SourceMode.LOCAL -> local
            SourceMode.JELLYFIN -> jellyfin
        }
}
