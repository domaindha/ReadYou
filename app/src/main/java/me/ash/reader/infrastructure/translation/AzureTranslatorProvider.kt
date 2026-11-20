package me.ash.reader.infrastructure.translation

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Azure Translator v3 adapter. Credentials are loaded from the encrypted provider store. */
@Singleton
class AzureTranslatorProvider @Inject constructor(
    private val configStore: TranslationApiConfigStore,
) : TranslationProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "Azure Translator"
    override val capabilities = TranslationProviderCapabilities(
        requiresNetwork = true,
        supportsBatching = false,
        supportsCustomConfiguration = true,
    )

    private val baseClient = OkHttpClient.Builder().build()

    override suspend fun translate(request: TranslationRequest): TranslationResponse {
        require(request.text.isNotBlank()) { "There is no text to translate." }
        val characterCount = request.text.codePointCount(0, request.text.length)
        require(characterCount <= MAX_CHARACTERS_PER_REQUEST) {
            "Azure Translator accepts at most 50,000 characters per request."
        }

        val config = configStore.loadConfiguration(id)
            ?: throw IllegalStateException("Configure your Azure Translator endpoint and key first.")
        require(config.apiKey.isNotBlank()) { "Azure Translator subscription key is required." }

        val endpoint = config.endpoint.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("The Azure Translator endpoint is not a valid HTTPS URL.")
        require(endpoint.scheme == "https") {
            "Azure Translator credentials may only be sent to an HTTPS endpoint."
        }

        val urlBuilder = endpoint.newBuilder()
            .removeAllQueryParameters("api-version")
            .removeAllQueryParameters("from")
            .removeAllQueryParameters("to")
        if (!endpoint.encodedPath.trimEnd('/').endsWith("/translate")) {
            urlBuilder.addPathSegment("translate")
        }
        urlBuilder.addQueryParameter("api-version", API_VERSION)
            .addQueryParameter("to", toAzureLanguageTag(request.targetLanguage))
        request.sourceLanguage
            ?.takeUnless { it.equals("auto", ignoreCase = true) || it.isBlank() }
            ?.let { urlBuilder.addQueryParameter("from", toAzureLanguageTag(it)) }

        val requestBody = JSONArray()
            .put(JSONObject().put("Text", request.text))
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .post(requestBody)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=UTF-8")

        config.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        requestBuilder.header(SUBSCRIPTION_KEY_HEADER, config.apiKey)

        val client = baseClient.newBuilder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .build()

        val responseBody = client.newCall(requestBuilder.build()).executeCancellable()
        val result = parseAzureTranslationResponse(responseBody)
        return TranslationResponse(
            translatedText = result.translatedText,
            sourceLanguage = request.sourceLanguage
                ?.takeUnless { it.equals("auto", ignoreCase = true) }
                ?: result.detectedLanguage,
            providerId = id,
        )
    }

    private suspend fun Call.executeCancellable(): String =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val responseText = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                throw IOException(
                                    "Azure Translator returned HTTP " + it.code + ": " + responseText.take(400)
                                )
                            }
                            if (responseText.isBlank()) {
                                throw IOException("Azure Translator returned an empty response.")
                            }
                            if (continuation.isActive) continuation.resume(responseText)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    companion object {
        const val PROVIDER_ID = "azure_translator"
        const val DEFAULT_ENDPOINT = "https://api.cognitive.microsofttranslator.com"
        private const val API_VERSION = "3.0"
        private const val MAX_CHARACTERS_PER_REQUEST = 50_000
        private const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun toAzureLanguageTag(languageTag: String): String =
            when (languageTag.lowercase()) {
                "zh-cn", "zh-hans" -> "zh-Hans"
                "zh-tw", "zh-hant" -> "zh-Hant"
                else -> languageTag
            }

        internal fun parseAzureTranslationResponse(responseBody: String): AzureTranslationResult {
            val first = runCatching { JSONArray(responseBody).optJSONObject(0) }
                .getOrNull()
                ?: throw IOException("Azure Translator returned an invalid JSON response.")
            val translatedText = first.optJSONArray("translations")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
            if (translatedText.isBlank()) {
                throw IOException("Azure Translator response did not contain translated text.")
            }
            val detectedLanguage = first.optJSONObject("detectedLanguage")
                ?.optString("language")
                ?.takeIf { it.isNotBlank() }
            return AzureTranslationResult(translatedText, detectedLanguage)
        }
    }
}

internal data class AzureTranslationResult(
    val translatedText: String,
    val detectedLanguage: String?,
)
