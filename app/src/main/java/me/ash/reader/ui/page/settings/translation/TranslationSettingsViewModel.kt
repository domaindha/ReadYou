package me.ash.reader.ui.page.settings.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.translation.TranslationApiConfiguration
import me.ash.reader.infrastructure.translation.TranslationEngine
import me.ash.reader.infrastructure.translation.TranslationService
import me.ash.reader.infrastructure.translation.TranslationProviderDescriptor

@HiltViewModel
class TranslationSettingsViewModel @Inject constructor(
    private val translationService: TranslationService,
) : ViewModel() {
    private val _selectedProviderId = MutableStateFlow(translationService.selectedProviderId())
    val selectedProviderId = _selectedProviderId.asStateFlow()
    val availableProviders: List<TranslationProviderDescriptor> = translationService.availableProviders()

    private val _downloadedModels = MutableStateFlow<List<String>>(emptyList())
    val downloadedModels = _downloadedModels.asStateFlow()

    fun isBilingualReadingEnabled(): Boolean = translationService.isBilingualReadingEnabled()

    suspend fun clearTranslationCache() = translationService.clearTranslationCache()

    fun setBilingualReadingEnabled(enabled: Boolean) =
        translationService.setBilingualReadingEnabled(enabled)

    fun selectedTargetLanguage(): String = translationService.targetLanguage()

    fun selectedSourceLanguage(): String = translationService.sourceLanguage()

    fun saveSourceLanguage(languageTag: String) = translationService.setSourceLanguage(languageTag)

    fun loadApiConfiguration(providerId: String = _selectedProviderId.value): TranslationApiConfiguration? =
        translationService.loadApiConfiguration(providerId)

    fun selectProvider(providerId: String) {
        translationService.selectProvider(providerId)
        _selectedProviderId.value = providerId
    }

    fun selectEngine(engine: TranslationEngine) = selectProvider(engine.id)

    fun saveTargetLanguage(languageTag: String) {
        translationService.setTargetLanguage(languageTag)
    }

    fun saveApiConfiguration(configuration: TranslationApiConfiguration, providerId: String = _selectedProviderId.value) {
        translationService.saveApiConfiguration(configuration, providerId)
    }

    suspend fun testApi(
        configuration: TranslationApiConfiguration,
        targetLanguage: String,
        providerId: String = _selectedProviderId.value,
    ): String {
        translationService.saveApiConfiguration(configuration, providerId)
        return translationService.translateTextUncached(
            text = "This is a translation test from Read You.",
            sourceLanguage = "en",
            targetLanguage = targetLanguage,
            providerId = providerId,
        ).translatedText
    }

    suspend fun testProvider(
        providerId: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String = translationService.translateTextUncached(
        text = "This is a translation test from Read You.",
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        providerId = providerId,
    ).translatedText

    fun refreshDownloadedModels() {
        viewModelScope.launch {
            _downloadedModels.value =
                runCatching { translationService.getProviderModels(_selectedProviderId.value) }
                    .getOrDefault(emptyList())
        }
    }

    suspend fun downloadModel(sourceLanguage: String, targetLanguage: String) {
        translationService.downloadProviderModel(_selectedProviderId.value, sourceLanguage, targetLanguage)
        refreshDownloadedModels()
    }

    suspend fun deleteModel(languageTag: String) {
        translationService.deleteProviderModel(_selectedProviderId.value, languageTag)
        refreshDownloadedModels()
    }
}
