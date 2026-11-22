package me.ash.reader.infrastructure.translation

import javax.inject.Inject
import javax.inject.Singleton

/** Stable provider metadata exposed to settings and orchestration. */
data class TranslationProviderDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: TranslationProviderCapabilities,
)

/** Provider discovery is supplied by Hilt multibindings; the registry has no vendor list. */
@Singleton
class TranslationProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TranslationProvider>,
) {
    private val providersById: Map<String, TranslationProvider> = providers.associateBy(TranslationProvider::id)

    init {
        require(providersById.size == providers.size) { "Translation provider IDs must be unique." }
    }

    fun availableProviders(): List<TranslationProvider> = providersById.values.sortedBy { it.id }

    fun descriptors(): List<TranslationProviderDescriptor> = availableProviders().map { provider ->
        TranslationProviderDescriptor(provider.id, provider.displayName, provider.capabilities)
    }

    fun capabilities(id: String): TranslationProviderCapabilities = requireProvider(id).capabilities

    fun requireProvider(id: String): TranslationProvider =
        providersById[id] ?: throw IllegalArgumentException("Translation provider '$id' is not registered.")
}
