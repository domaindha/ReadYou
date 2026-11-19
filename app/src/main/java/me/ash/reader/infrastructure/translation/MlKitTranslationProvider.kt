package me.ash.reader.infrastructure.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device translation provider. Models are downloaded on demand and all subsequent
 * translation work is performed by ML Kit on the device.
 */
@Singleton
class MlKitTranslationProvider @Inject constructor() : TranslationProvider {
    override val id: String = TranslationEngine.ON_DEVICE.id

    private val modelManager = RemoteModelManager.getInstance()
    private val languageIdentifier = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()

    override suspend fun translate(request: TranslationRequest): TranslationResponse {
        require(request.text.isNotBlank()) { "There is no text to translate." }

        val sourceTag = request.sourceLanguage
            ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            ?: detectLanguage(request.text)

        if (sourceTag == "und") {
            throw IllegalArgumentException("Could not identify the source language.")
        }

        val source = toMlKitLanguage(sourceTag)
            ?: throw IllegalArgumentException("ML Kit does not support the source language: $sourceTag")
        val target = toMlKitLanguage(request.targetLanguage)
            ?: throw IllegalArgumentException("ML Kit does not support the target language: ${request.targetLanguage}")

        if (source == target) {
            return TranslationResponse(request.text, sourceTag, id)
        }

        val translator = getTranslator(source, target)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
        val translated = translator.translate(request.text).awaitTask()
        return TranslationResponse(translated, sourceTag, id)
    }

    suspend fun detectLanguage(text: String): String {
        if (text.isBlank()) return "und"
        return languageIdentifier.identifyLanguage(text.take(LANGUAGE_SAMPLE_CHARS)).awaitTask()
    }

    suspend fun downloadModel(sourceLanguage: String, targetLanguage: String) {
        val source = toMlKitLanguage(sourceLanguage)
            ?: throw IllegalArgumentException("Unsupported source language: $sourceLanguage")
        val target = toMlKitLanguage(targetLanguage)
            ?: throw IllegalArgumentException("Unsupported target language: $targetLanguage")
        getTranslator(source, target)
            .downloadModelIfNeeded(DownloadConditions.Builder().build())
            .awaitTask()
    }

    suspend fun downloadedModels(): List<String> =
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .awaitTask()
            .map { it.language }
            .sorted()

    suspend fun deleteModel(languageTag: String) {
        val model = TranslateRemoteModel.Builder(languageTag).build()
        modelManager.deleteDownloadedModel(model).awaitTask()
    }

    @Synchronized
    private fun getTranslator(source: String, target: String): Translator {
        val key = "$source:$target"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
            Translation.getClient(options)
        }
    }

    private fun toMlKitLanguage(tag: String): String? {
        val normalized = tag.replace('_', '-')
        return TranslateLanguage.fromLanguageTag(normalized)
            ?: TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(normalized).language)
    }

    fun close() {
        synchronized(this) {
            translators.values.forEach { it.close() }
            translators.clear()
        }
        languageIdentifier.close()
    }

    private suspend fun <T> Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { value ->
                if (continuation.isActive) continuation.resume(value)
            }
            addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private companion object {
        const val LANGUAGE_SAMPLE_CHARS = 700
    }
}
