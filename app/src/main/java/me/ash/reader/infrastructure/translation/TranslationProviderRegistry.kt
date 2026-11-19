package me.ash.reader.infrastructure.translation

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central registry for translation backends.
 *
 * The reader-facing service depends on this registry rather than knowing concrete
 * provider classes. Adding a cloud backend later only requires registering its
 * provider here; article parsing, caching, and reader UI remain unchanged.
 */
@Singleton
class TranslationProviderRegistry @Inject constructor(
    val onDeviceProvider: MlKitTranslationProvider,
    httpJsonProvider: HttpJsonTranslationProvider,
) {
    private val providers: Map<String, TranslationProvider> = listOf(
        onDeviceProvider,
        httpJsonProvider,
    ).associateBy(TranslationProvider::id)

    fun availableProviders(): List<TranslationProvider> = providers.values.toList()

    fun requireProvider(id: String): TranslationProvider =
        providers[id] ?: throw IllegalArgumentException("Translation provider '$id' is not registered.")
}
