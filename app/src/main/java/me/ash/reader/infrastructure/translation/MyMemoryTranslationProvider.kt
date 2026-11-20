package me.ash.reader.infrastructure.translation

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.parser.Parser
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Public MyMemory adapter.
 *
 * MyMemory limits each query to 500 UTF-8 bytes and anonymous use to 5,000 characters/day.
 * This provider deliberately sends one short segment per request and never batches paragraphs.
 */
@Singleton
class MyMemoryTranslationProvider @Inject constructor() : TranslationProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "MyMemory（免费公共接口）"
    override val capabilities = TranslationProviderCapabilities(
        requiresNetwork = true,
        supportsBatching = false,
        requiresExplicitSourceLanguage = true,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val requestMutex = Mutex()
    private var lastRequestStartedAtNanos: Long = 0L

    override suspend fun translate(request: TranslationRequest): TranslationResponse {
        require(request.text.isNotBlank()) { "There is no text to translate." }
        val sourceLanguage = request.sourceLanguage
            ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "MyMemory does not detect the source language. Select the article's original language first."
            )
        require(!request.targetLanguage.equals("auto", ignoreCase = true)) {
            "Select a target language before using MyMemory."
        }

        val translated = StringBuilder()
        splitMyMemoryTextByByteLimit(request.text).forEach { chunk ->
            translated.append(
                requestMutex.withLock {
                    waitForRequestSlot()
                    lastRequestStartedAtNanos = System.nanoTime()
                    translateChunk(chunk, sourceLanguage, request.targetLanguage)
                }
            )
        }

        return TranslationResponse(
            translatedText = translated.toString(),
            sourceLanguage = sourceLanguage,
            providerId = id,
        )
    }

    private suspend fun waitForRequestSlot() {
        if (lastRequestStartedAtNanos == 0L) return
        val elapsedMillis = (System.nanoTime() - lastRequestStartedAtNanos) / NANOS_PER_MILLISECOND
        val remainingMillis = MIN_REQUEST_INTERVAL_MILLIS - elapsedMillis
        if (remainingMillis > 0) delay(remainingMillis)
    }

    private suspend fun translateChunk(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): String {
        val url = ENDPOINT.newBuilder()
            .addQueryParameter("q", text)
            .addQueryParameter("langpair", sourceLanguage + "|" + targetLanguage)
            .addQueryParameter("mt", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()
        val responseBody = client.newCall(request).executeCancellable()
        return parseMyMemoryResponse(responseBody)
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
                                val retryAfter = it.header("Retry-After")
                                val retryMessage = retryAfter?.let { value -> " Retry after " + value + " seconds." }.orEmpty()
                                throw IOException(
                                    "MyMemory returned HTTP " + it.code + ":" + retryMessage + " " +
                                        responseText.take(400)
                                )
                            }
                            if (responseText.isBlank()) {
                                throw IOException("MyMemory returned an empty response.")
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
        const val PROVIDER_ID = "mymemory"
        private const val MAX_QUERY_BYTES = 500
        private const val MIN_REQUEST_INTERVAL_MILLIS = 350L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val ENDPOINT = "https://api.mymemory.translated.net/get".toHttpUrl()

        internal fun splitMyMemoryTextByByteLimit(
            text: String,
            maxBytes: Int = MAX_QUERY_BYTES,
        ): List<String> {
            require(maxBytes > 0) { "The MyMemory byte limit must be positive." }
            if (text.isEmpty()) return emptyList()

            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = start
                var byteCount = 0
                var preferredEnd = -1
                var preferredByteCount = 0

                while (end < text.length) {
                    val codePoint = text.codePointAt(end)
                    val codePointBytes = when {
                        codePoint <= 0x7F -> 1
                        codePoint <= 0x7FF -> 2
                        codePoint <= 0xFFFF -> 3
                        else -> 4
                    }
                    if (byteCount + codePointBytes > maxBytes) break

                    byteCount += codePointBytes
                    end += Character.charCount(codePoint)
                    if (Character.isWhitespace(codePoint) || isSentenceBoundary(codePoint)) {
                        preferredEnd = end
                        preferredByteCount = byteCount
                    }
                }

                if (end == start) {
                    throw IllegalArgumentException("A character exceeds MyMemory's 500-byte query limit.")
                }
                if (end < text.length && preferredEnd > start && preferredByteCount >= maxBytes / 2) {
                    end = preferredEnd
                }
                chunks += text.substring(start, end)
                start = end
            }
            return chunks
        }

        private fun isSentenceBoundary(codePoint: Int): Boolean =
            codePoint <= Char.MAX_VALUE.code &&
                codePoint.toChar() in ".!?;:,。！？；，、："

        internal fun parseMyMemoryResponse(responseBody: String): String {
            val json = runCatching { JSONObject(responseBody) }
                .getOrElse { throw IOException("MyMemory returned invalid JSON.", it) }

            val details = json.optString("responseDetails")
                .takeIf { it.isNotBlank() && it != "null" }
            if (json.optBoolean("quotaFinished")) {
                throw IOException("MyMemory's anonymous daily quota has been exhausted (5,000 characters/day).")
            }

            val responseStatus = json.optInt("responseStatus", 0)
            if (responseStatus != 200) {
                throw IOException(details ?: "MyMemory translation failed (status " + responseStatus + ").")
            }

            val translatedText = json.optJSONObject("responseData")
                ?.optString("translatedText")
                .orEmpty()
            if (translatedText.isBlank()) {
                throw IOException(details ?: "MyMemory response did not contain translated text.")
            }
            return Parser.unescapeEntities(translatedText, false)
        }
    }
}
