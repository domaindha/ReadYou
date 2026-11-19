package me.ash.reader.infrastructure.translation

/** Declares what a provider can do so UI and orchestration do not infer behavior from IDs. */
data class TranslationProviderCapabilities(
    val requiresNetwork: Boolean,
    val supportsBatching: Boolean,
    val supportsLanguageDetection: Boolean = false,
    val supportsModelManagement: Boolean = false,
    val supportsCustomConfiguration: Boolean = false,
)

/** Provider contract shared by offline and remote translation engines. */
interface TranslationProvider {
    val id: String
    val capabilities: TranslationProviderCapabilities
    suspend fun translate(request: TranslationRequest): TranslationResponse
}
