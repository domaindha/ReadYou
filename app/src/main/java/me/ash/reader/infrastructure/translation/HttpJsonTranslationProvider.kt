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

/**
 * Configurable HTTP + JSON provider. It is intentionally not tied to a vendor API:
 * request JSON, headers, and response JSON path are all user-configurable.
 */
@Singleton
class HttpJsonTranslationProvider @Inject constructor(
    private val configStore: TranslationApiConfigStore,
) : TranslationProvider {
    override val id: String = TranslationEngine.HTTP_JSON.id

    override suspend fun translate(request: TranslationRequest): TranslationResponse {
        require(request.text.isNotBlank()) { "There is no text to translate." }
        val config = configStore.loadConfiguration()
            ?: throw IllegalStateException("Configure a translation API before selecting this engine.")

        val endpoint = config.endpoint.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("The translation API endpoint is not a valid HTTP URL.")
        if (endpoint.scheme != "https" && config.apiKey.isNotBlank()) {
            throw IllegalArgumentException("An API key may only be sent to an HTTPS endpoint.")
        }

        val requestJson = runCatching { JSONObject(config.requestTemplate) }
            .getOrElse { throw IllegalArgumentException("The request template must be a valid JSON object.", it) }

        val values = mapOf(
            "text" to request.text,
            "sourceLanguage" to (request.sourceLanguage ?: "auto"),
            "targetLanguage" to request.targetLanguage,
            "model" to config.model,
        )
        replacePlaceholders(requestJson, values)

        val builder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
        config.headers.forEach { (name, value) ->
            builder.header(name, replaceHeaderPlaceholders(value, config.apiKey))
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .build()

        val body = client.newCall(builder.build()).executeCancellable()
        val translated = extractJsonPath(body, config.responsePath)
        if (translated.isBlank()) {
            throw IllegalStateException("The API response did not contain translated text at '${config.responsePath}'.")
        }

        return TranslationResponse(
            translatedText = translated,
            sourceLanguage = request.sourceLanguage,
            providerId = id,
        )
    }

    private fun replacePlaceholders(value: Any, values: Map<String, String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().toList()
                keys.forEach { key ->
                    val child = value.get(key)
                    when (child) {
                        is JSONObject, is JSONArray -> replacePlaceholders(child, values)
                        is String -> value.put(key, replaceTokens(child, values))
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    val child = value.get(index)
                    when (child) {
                        is JSONObject, is JSONArray -> replacePlaceholders(child, values)
                        is String -> value.put(index, replaceTokens(child, values))
                    }
                }
            }
        }
    }

    private fun replaceTokens(value: String, values: Map<String, String>): String =
        values.entries.fold(value) { current, (key, replacement) ->
            current.replace("{{$key}}", replacement)
        }

    private fun replaceHeaderPlaceholders(value: String, apiKey: String): String =
        value.replace("{{apiKey}}", apiKey)

    private fun extractJsonPath(json: String, path: String): String {
        var current: Any = JSONObject(json)
        for (part in path.split('.').filter { it.isNotEmpty() }) {
            current = when (current) {
                is JSONObject -> current.opt(part)
                is JSONArray -> part.toIntOrNull()?.let(current::opt)
                    ?: throw IllegalArgumentException("Invalid array index '$part' in response path.")
                else -> throw IllegalArgumentException("Response path '$path' does not resolve to a JSON value.")
            }
            if (current == null || current == JSONObject.NULL) {
                throw IllegalArgumentException("Response JSON path '$path' was not found.")
            }
        }
        return when (current) {
            is String -> current
            is Number, is Boolean -> current.toString()
            else -> throw IllegalArgumentException("Response path '$path' must resolve to a string or scalar.")
        }
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
                                    "Translation API returned HTTP ${it.code}: ${responseText.take(400)}"
                                )
                            }
                            if (responseText.isBlank()) {
                                throw IOException("The translation API returned an empty response.")
                            }
                            if (continuation.isActive) continuation.resume(responseText)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
