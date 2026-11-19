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
    val displayName: String get() = id
    val capabilities: TranslationProviderCapabilities
    suspend fun translate(request: TranslationRequest): TranslationResponse
}

interface TranslationLanguageDetector {
    suspend fun detectLanguage(text: String): String
}

interface TranslationModelManager {
    suspend fun downloadModel(sourceLanguage: String, targetLanguage: String)
    suspend fun downloadedModels(): List<String>
    suspend fun deleteModel(languageTag: String)
}
