package dev.jyotiraditya.dmt.domain.usecase

import dev.jyotiraditya.dmt.data.remote.jellyfin.ServerUrls

import dev.jyotiraditya.dmt.data.remote.jellyfin.JellyfinApi
import dev.jyotiraditya.dmt.domain.model.SourceMode
import dev.jyotiraditya.dmt.data.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JellyfinLoginUseCase(
    private val api: JellyfinApi,
    private val settingsRepository: PreferencesRepository,
) {
    suspend operator fun invoke(url: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // the ui already asks; this is the backstop: never send a password over plain
                // http to a host outside the local network
                require(ServerUrls.problem(url) == null) { "refusing insecure server url" }
                val auth = api.authenticate(url, username, password)
                val settings = settingsRepository.settingsNow()

                settingsRepository.save(
                    settings.copy(
                        sourceMode = SourceMode.JELLYFIN,
                        jellyfinUrl = url,
                        jellyfinUserId = auth.userId,
                        jellyfinToken = auth.token,
                    ),
                )
            }
        }
}
