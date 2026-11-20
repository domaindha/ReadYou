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
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapter for LibreTranslate-compatible instances.
 *
 * Self-hosted servers can run without an API key; managed instances may require one.
 */
@Singleton
class LibreTranslateProvider @Inject constructor(
    private val configStore: TranslationApiConfigStore,
) : TranslationProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "LibreTranslate（自托管 / 公共实例）"
    override val capabilities = TranslationProviderCapabilities(
        requiresNetwork = true,
        supportsBatching = true,
        supportsCustomConfiguration = true,
    )

    private val baseClient = OkHttpClient.Builder().build()

    override suspend fun translate(request: TranslationRequest): TranslationResponse {
        require(request.text.isNotBlank()) { "There is no text to translate." }
        val config = configStore.loadConfiguration(id)
            ?: throw IllegalStateException("请先配置 LibreTranslate 实例地址。")
        val endpoint = config.endpoint.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("LibreTranslate 实例地址不是有效的 HTTP URL。")
        require(endpoint.username.isBlank() && endpoint.password.isBlank()) {
            "请勿把账号或密码直接写入实例 URL。"
        }
        if (config.apiKey.isNotBlank()) {
            require(endpoint.scheme == "https") {
                "LibreTranslate API Key 只能发送到 HTTPS 实例。"
            }
        }

        val payload = JSONObject()
            .put("q", request.text)
            .put("source", request.sourceLanguage?.takeIf { it.isNotBlank() } ?: "auto")
            .put("target", request.targetLanguage)
            .put("format", "text")
            .apply {
                if (config.apiKey.isNotBlank()) put("api_key", config.apiKey)
            }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(buildTranslateUrl(endpoint))
            .post(payload)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .build()
        val client = baseClient.newBuilder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val responseBody = client.newCall(httpRequest).executeCancellable()
        return TranslationResponse(
            translatedText = parseTranslationResponse(responseBody),
            sourceLanguage = request.sourceLanguage,
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
                            val body = it.body?.string().orEmpty()
                            if (!it.isSuccessful) {
                                val detail = runCatching {
                                    JSONObject(body).optString("error").takeIf { message -> message.isNotBlank() }
                                }.getOrNull()
                                throw IOException(
                                    "LibreTranslate returned HTTP " + it.code +
                                        (detail?.let { message -> ": $message" } ?: ": " + body.take(300))
                                )
                            }
                            if (continuation.isActive) continuation.resume(body)
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    companion object {
        const val PROVIDER_ID = "libre_translate"
        const val DEFAULT_ENDPOINT = "https://libretranslate.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun parseTranslationResponse(responseBody: String): String {
            val json = runCatching { JSONObject(responseBody) }
                .getOrElse { throw IOException("LibreTranslate returned invalid JSON.", it) }
            val error = json.optString("error").takeIf { it.isNotBlank() && it != "null" }
            if (error != null) throw IOException("LibreTranslate: $error")
            return json.optString("translatedText")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: throw IOException("LibreTranslate response did not contain translatedText.")
        }

        internal fun buildTranslateUrl(endpoint: String): String {
            val parsed = endpoint.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid LibreTranslate endpoint.")
            return buildTranslateUrl(parsed).toString()
        }

        private fun buildTranslateUrl(endpoint: okhttp3.HttpUrl): okhttp3.HttpUrl {
            val builder = endpoint.newBuilder()
            if (!endpoint.encodedPath.trimEnd('/').endsWith("/translate")) {
                builder.addPathSegment("translate")
            }
            return builder.build()
        }
    }
}
