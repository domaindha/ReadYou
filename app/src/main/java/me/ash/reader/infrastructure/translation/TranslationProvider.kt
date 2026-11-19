package me.ash.reader.infrastructure.translation

/**
 * Provider contract shared by offline and remote translation engines.
 *
 * Implementations must not mutate [TranslationRequest.text]. They should throw a
 * descriptive exception on failure rather than silently returning the source text.
 */
interface TranslationProvider {
    val id: String

    suspend fun translate(request: TranslationRequest): TranslationResponse
}
