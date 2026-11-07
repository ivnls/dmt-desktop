package dev.jyotiraditya.dmt.domain.usecase

import dev.jyotiraditya.dmt.data.repository.TrackMediaRepository
import dev.jyotiraditya.dmt.domain.model.Spec
import dev.jyotiraditya.dmt.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GetTrackTechUseCase(private val trackMediaRepository: TrackMediaRepository) {
    suspend operator fun invoke(track: Track?): List<Spec> =
        withContext(Dispatchers.IO) { trackMediaRepository.techSpecs(track) }
}
