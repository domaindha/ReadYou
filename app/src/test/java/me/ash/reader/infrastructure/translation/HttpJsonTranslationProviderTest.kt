package me.ash.reader.infrastructure.translation

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever

class HttpJsonTranslationProviderTest {
    @Test
    fun requestPlaceholdersAreJsonEscapedAndResponsePathSupportsArrays() {
        val response = """{"data":{"translations":[{"text":"你好，世界"}]}}"""
        LocalJsonServer(200, response).use { server ->
            val config = TranslationApiConfiguration(
                endpoint = server.url,
                model = "test-model",
                headers = mapOf("X-Client" to "ReadYou"),
                requestTemplate = """{"q":"{{text}}","source":"{{sourceLanguage}}","target":"{{targetLanguage}}","model":"{{model}}"}""",
                responsePath = "data.translations.0.text",
            )
            val provider = providerWith(config)

            val translated = runBlocking {
                provider.translate(
                    TranslationRequest(
                        text = "A quote: \"hello\"\nand a second line",
                        sourceLanguage = "en",
                        targetLanguage = "zh-CN",
                    )
                )
            }

            server.awaitRequest()
            assertEquals("你好，世界", translated.translatedText)
            val requestJson = JSONObject(server.requestBody)
            assertEquals("A quote: \"hello\"\nand a second line", requestJson.getString("q"))
            assertEquals("en", requestJson.getString("source"))
            assertEquals("zh-CN", requestJson.getString("target"))
            assertEquals("test-model", requestJson.getString("model"))
            assertTrue(server.requestHeaders.contains("X-Client: ReadYou"))
        }
    }

    @Test
    fun nonSuccessfulHttpResponseIsReportedAsIOException() {
        LocalJsonServer(429, """{"error":"rate limited"}""").use { server ->
            val provider = providerWith(
                TranslationApiConfiguration(
                    endpoint = server.url,
                    requestTemplate = """{"text":"{{text}}"}""",
                    responsePath = "text",
                )
            )

            val error = org.junit.Assert.assertThrows(IOException::class.java) {
                runBlocking {
                    provider.translate(TranslationRequest("hello", "en", "zh-CN"))
                }
            }

            server.awaitRequest()
            assertTrue(error.message.orEmpty().contains("HTTP 429"))
        }
    }

    @Test
    fun credentialsAreRejectedForPlainHttpEndpoints() {
        val provider = providerWith(
            TranslationApiConfiguration(
                endpoint = "http://127.0.0.1:1/translate",
                apiKey = "must-not-leak",
            )
        )

        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                provider.translate(TranslationRequest("hello", "en", "zh-CN"))
            }
        }

        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun missingResponseJsonPathProducesAnExplicitError() {
        LocalJsonServer(200, """{"result":{"text":"translated"}}""").use { server ->
            val provider = providerWith(
                TranslationApiConfiguration(
                    endpoint = server.url,
                    requestTemplate = """{"text":"{{text}}"}""",
                    responsePath = "result.translation",
                )
            )

            val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    provider.translate(TranslationRequest("hello", "en", "zh-CN"))
                }
            }

            server.awaitRequest()
            assertTrue(error.message.orEmpty().contains("was not found"))
        }
    }

    private fun providerWith(configuration: TranslationApiConfiguration): HttpJsonTranslationProvider {
        val configStore = Mockito.mock(TranslationApiConfigStore::class.java)
        whenever(configStore.loadConfiguration(TranslationEngine.HTTP_JSON.id)).thenReturn(configuration)
        return HttpJsonTranslationProvider(configStore)
    }

    /** Loopback HTTP server: exercises OkHttp without external access or another dependency. */
    private class LocalJsonServer(
        private val responseCode: Int,
        private val responseBody: String,
    ) : Closeable {
        private val socket = ServerSocket(0)
        val url: String = "http://127.0.0.1:" + socket.localPort + "/translate"

        @Volatile
        var requestHeaders: String = ""
            private set

        @Volatile
        var requestBody: String = ""
            private set

        @Volatile
        private var serverFailure: Throwable? = null

        private val worker = Thread {
            try {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    val headerBytes = ByteArrayOutputStream()
                    val delimiter = byteArrayOf(13, 10, 13, 10)
                    var matched = 0
                    while (matched < delimiter.size) {
                        val value = input.read()
                        if (value < 0) throw EOFException("Client closed before sending HTTP headers.")
                        headerBytes.write(value)
                        matched = if (value == delimiter[matched].toInt()) matched + 1 else 0
                    }
                    requestHeaders = headerBytes.toString(StandardCharsets.ISO_8859_1.name())
                    val contentLength = requestHeaders.lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                    val bodyBytes = ByteArray(contentLength)
                    var offset = 0
                    while (offset < bodyBytes.size) {
                        val count = input.read(bodyBytes, offset, bodyBytes.size - offset)
                        if (count < 0) throw EOFException("Client closed before sending the HTTP body.")
                        offset += count
                    }
                    requestBody = String(bodyBytes, StandardCharsets.UTF_8)

                    val responseBytes = responseBody.toByteArray(StandardCharsets.UTF_8)
                    val reason = when (responseCode) {
                        200 -> "OK"
                        429 -> "Too Many Requests"
                        else -> "Test Response"
                    }
                    val header = "HTTP/1.1 " + responseCode + " " + reason + "\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: " + responseBytes.size + "\r\n" +
                        "Connection: close\r\n\r\n"
                    client.getOutputStream().apply {
                        write(header.toByteArray(StandardCharsets.US_ASCII))
                        write(responseBytes)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                serverFailure = error
            }
        }.apply { isDaemon = true }

        init {
            worker.start()
        }

        fun awaitRequest() {
            worker.join(3_000)
            if (worker.isAlive) throw AssertionError("Local HTTP server did not finish.")
            serverFailure?.let { throw AssertionError("Local HTTP server failed.", it) }
        }

        override fun close() {
            runCatching { socket.close() }
            worker.join(3_000)
        }
    }
}
