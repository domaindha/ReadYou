package me.ash.reader.infrastructure.translation

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Experimental adapter for Lingva REST v1 instances.
 * POST is preferred for long chunks; older instances may fall back to GET.
 */
@Singleton
class LingvaTranslationProvider @Inject constructor(
    private val configStore: TranslationApiConfigStore,
) : TranslationProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "Lingva（实验性公共 / 自托管实例）"
    override val capabilities = TranslationProviderCapabilities(
        requiresNetwork = true,
        supportsBatching = false,
        supportsCustomConfiguration = true,
    )

    private val baseClient = OkHttpClient.Builder().build()

    override suspend fun translate(request: TranslationRequest): TranslationResponse {
        require(request.text.isNotBlank()) { "There is no text to translate." }
        val config = configStore.loadConfiguration(id)
            ?: throw IllegalStateException("请先配置 Lingva 实例地址。")
        val endpoint = config.endpoint.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Lingva 实例地址不是有效的 HTTP URL。")
        require(endpoint.username.isBlank() && endpoint.password.isBlank()) {
            "请勿把账号或密码直接写入实例 URL。"
        }

        val source = request.sourceLanguage?.takeIf { it.isNotBlank() } ?: "auto"
        val apiClient = baseClient.newBuilder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val postRequest = Request.Builder()
            .url(buildPostUrl(endpoint, source, request.targetLanguage))
            .post(JSONObject().put("query", request.text).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json; charset=utf-8")
            .build()
        val postResponse = apiClient.newCall(postRequest).executeCancellable()
        val responseBody = when (postResponse.code) {
            in 200..299 -> postResponse.body
            404, 405 -> {
                val getRequest = Request.Builder()
                    .url(buildGetUrl(endpoint, source, request.targetLanguage, request.text))
                    .get()
                    .header("Accept", "application/json")
                    .build()
                val getResponse = apiClient.newCall(getRequest).executeCancellable()
                if (getResponse.code !in 200..299) {
                    throw IOException(
                        "Lingva returned HTTP " + getResponse.code + ": " + getResponse.body.take(300)
                    )
                }
                getResponse.body
            }
            else -> throw IOException(
                "Lingva returned HTTP " + postResponse.code + ": " + postResponse.body.take(300)
            )
        }
        return TranslationResponse(
            translatedText = parseTranslationResponse(responseBody),
            sourceLanguage = request.sourceLanguage,
            providerId = id,
        )
    }

    private suspend fun Call.executeCancellable(): LingvaHttpResult =
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
                            if (continuation.isActive) {
                                continuation.resume(LingvaHttpResult(it.code, body))
                            }
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    companion object {
        const val PROVIDER_ID = "lingva"
        const val DEFAULT_ENDPOINT = "https://lingva.ml"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun buildPostUrl(endpoint: HttpUrl, source: String, target: String): HttpUrl {
            val builder = apiBaseBuilder(endpoint)
            builder.addPathSegment(source).addPathSegment(target)
            return builder.build()
        }

        internal fun buildGetUrl(endpoint: HttpUrl, source: String, target: String, text: String): HttpUrl {
            val builder = apiBaseBuilder(endpoint)
            builder.addPathSegment(source).addPathSegment(target).addPathSegment(text)
            return builder.build()
        }

        internal fun buildGetUrl(
            endpoint: String,
            source: String,
            target: String,
            text: String,
        ): String {
            val parsed = endpoint.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid Lingva endpoint.")
            return buildGetUrl(parsed, source, target, text).toString()
        }

        private fun apiBaseBuilder(endpoint: HttpUrl): HttpUrl.Builder {
            val builder = endpoint.newBuilder()
            if (!endpoint.encodedPath.trimEnd('/').endsWith("/api/v1")) {
                builder.addPathSegment("api").addPathSegment("v1")
            }
            return builder
        }

        internal fun parseTranslationResponse(responseBody: String): String {
            val json = runCatching { JSONObject(responseBody) }
                .getOrElse { throw IOException("Lingva returned invalid JSON.", it) }
            val error = sequenceOf("error", "errorMsg")
                .map { json.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" }
            if (error != null) throw IOException("Lingva: $error")
            val translated = json.optString("translation").takeIf { it.isNotBlank() && it != "null" }
                ?: json.optJSONObject("target")?.optString("text")
                    ?.takeIf { it.isNotBlank() && it != "null" }
            return translated
                ?: throw IOException("Lingva response did not contain a translation.")
        }
    }
}

private data class LingvaHttpResult(
    val code: Int,
    val body: String,
)
