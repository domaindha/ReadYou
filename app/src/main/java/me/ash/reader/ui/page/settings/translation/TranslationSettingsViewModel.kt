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

@HiltViewModel
class TranslationSettingsViewModel @Inject constructor(
    private val translationService: TranslationService,
) : ViewModel() {
    private val _selectedEngine = MutableStateFlow(translationService.selectedEngine())
    val selectedEngine = _selectedEngine.asStateFlow()

    private val _downloadedModels = MutableStateFlow<List<String>>(emptyList())
    val downloadedModels = _downloadedModels.asStateFlow()

    fun selectedTargetLanguage(): String = translationService.targetLanguage()

    fun selectedSourceLanguage(): String = translationService.sourceLanguage()

    fun saveSourceLanguage(languageTag: String) = translationService.setSourceLanguage(languageTag)

    fun loadApiConfiguration(): TranslationApiConfiguration? =
        translationService.loadApiConfiguration()

    fun selectEngine(engine: TranslationEngine) {
        translationService.selectEngine(engine)
        _selectedEngine.value = engine
    }

    fun saveTargetLanguage(languageTag: String) {
        translationService.setTargetLanguage(languageTag)
    }

    fun saveApiConfiguration(configuration: TranslationApiConfiguration) {
        translationService.saveApiConfiguration(configuration)
    }

    suspend fun testApi(
        configuration: TranslationApiConfiguration,
        targetLanguage: String,
    ): String {
        translationService.saveApiConfiguration(configuration)
        return translationService.translateText(
            text = "This is a translation test from Read You.",
            sourceLanguage = "en",
            targetLanguage = targetLanguage,
            engine = TranslationEngine.HTTP_JSON,
        ).translatedText
    }

    fun refreshDownloadedModels() {
        viewModelScope.launch {
            _downloadedModels.value =
                runCatching { translationService.getDownloadedOnDeviceModels() }
                    .getOrDefault(emptyList())
        }
    }

    suspend fun downloadModel(sourceLanguage: String, targetLanguage: String) {
        translationService.downloadOnDeviceModel(sourceLanguage, targetLanguage)
        refreshDownloadedModels()
    }

    suspend fun deleteModel(languageTag: String) {
        translationService.deleteOnDeviceModel(languageTag)
        refreshDownloadedModels()
    }
}
