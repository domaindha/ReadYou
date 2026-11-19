package me.ash.reader.infrastructure.translation

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.whenever

class TranslationServiceTest {
    @Test
    fun duplicatedBatchMarkerFallsBackToTranslatingEachOriginalSegment() = runBlocking {
        val provider = DuplicateMarkerProvider()
        val registry = TranslationProviderRegistry(setOf(provider))
        val cache = Mockito.mock(TranslationFileCache::class.java)
        whenever(cache.get(Mockito.anyString())).thenReturn(null)
        val configStore = Mockito.mock(TranslationApiConfigStore::class.java)
        val service = TranslationService(registry, cache, configStore)

        val translated = service.translateHtml(
            html = "<p>alpha</p><p>beta</p>",
            targetLanguage = "zh-CN",
            providerId = provider.id,
            sourceLanguage = "en",
        )

        val paragraphs = Jsoup.parseBodyFragment(translated).select("p").map { it.text() }
        assertEquals(listOf("translated:alpha", "translated:beta"), paragraphs)
        assertEquals(3, provider.requests.size)
        assertEquals("alpha", provider.requests[1])
        assertEquals("beta", provider.requests[2])
    }


    @Test
    fun successfulTranslationIsStoredAndReusedFromCache() = runBlocking {
        val provider = CountingProvider("provider_cached")
        val registry = TranslationProviderRegistry(setOf(provider))
        val cache = Mockito.mock(TranslationFileCache::class.java)
        val values = mutableMapOf<String, String>()
        Mockito.doAnswer { invocation ->
            values[invocation.getArgument<String>(0)]
        }.`when`(cache).get(Mockito.anyString())
        Mockito.doAnswer { invocation ->
            values[invocation.getArgument<String>(0)] = invocation.getArgument(1)
            null
        }.`when`(cache).put(Mockito.anyString(), Mockito.anyString())
        val configStore = Mockito.mock(TranslationApiConfigStore::class.java)
        whenever(configStore.loadConfiguration(provider.id)).thenReturn(
            testConfiguration(endpoint = "https://cache.example/translate")
        )
        val service = TranslationService(registry, cache, configStore)

        val first = service.translateText("hello", "zh-CN", "en", providerId = provider.id)
        val second = service.translateText("hello", "zh-CN", "en", providerId = provider.id)

        assertEquals("translated:hello", first.translatedText)
        assertEquals(first.translatedText, second.translatedText)
        assertEquals("A repeated translation should be served from cache.", 1, provider.requestCount)
        assertEquals(1, values.size)
    }

    @Test
    fun htmlTranslationLeavesCodePreScriptAndExplicitNoTranslateContentUntouched() = runBlocking {
        val provider = CountingProvider("provider_html")
        val registry = TranslationProviderRegistry(setOf(provider))
        val cache = Mockito.mock(TranslationFileCache::class.java)
        whenever(cache.get(Mockito.anyString())).thenReturn(null)
        val configStore = Mockito.mock(TranslationApiConfigStore::class.java)
        val service = TranslationService(registry, cache, configStore)

        val translated = service.translateHtml(
            html = """
                <p>Hello <strong>world</strong></p>
                <pre>fun main() = Unit</pre>
                <p>Keep <code>sourceCode()</code> intact</p>
                <p class="notranslate">Do not translate this</p>
                <script>const secret = "unchanged";</script>
                <style>.sample { display: none; }</style>
            """.trimIndent(),
            targetLanguage = "zh-CN",
            providerId = provider.id,
            sourceLanguage = "en",
        )
        val document = Jsoup.parseBodyFragment(translated)

        assertEquals("translated:Hello translated:world", document.selectFirst("p")?.text())
        assertEquals("fun main() = Unit", document.selectFirst("pre")?.text())
        assertEquals("sourceCode()", document.selectFirst("code")?.text())
        assertEquals(
            "translated:Keep sourceCode() translated:intact",
            document.select("p").getOrNull(1)?.text(),
        )
        assertEquals("Do not translate this", document.selectFirst(".notranslate")?.text())
        assertEquals("const secret = \"unchanged\";", document.selectFirst("script")?.data())
        assertEquals(".sample { display: none; }", document.selectFirst("style")?.data())
        assertEquals(4, provider.requestCount)
        assertEquals(listOf("Hello", "world", "Keep", "intact"), provider.requests)
    }

    @Test
    fun cacheKeysAreIsolatedByProviderAndProviderConfigurationRevision() = runBlocking {
        val providerA = CountingProvider("provider_a")
        val providerB = CountingProvider("provider_b")
        val registry = TranslationProviderRegistry(setOf(providerA, providerB))
        val cache = Mockito.mock(TranslationFileCache::class.java)
        val cacheKeys = mutableListOf<String>()
        Mockito.doAnswer { invocation ->
            cacheKeys += invocation.getArgument<String>(0)
            null
        }.`when`(cache).get(Mockito.anyString())
        val configStore = Mockito.mock(TranslationApiConfigStore::class.java)
        val initialA = testConfiguration(endpoint = "https://a.example/translate", apiKey = "key-a")
        val changedA = initialA.copy(model = "model-v2")
        val configB = testConfiguration(endpoint = "https://b.example/translate", apiKey = "key-b")
        whenever(configStore.loadConfiguration("provider_a")).thenReturn(initialA, changedA)
        whenever(configStore.loadConfiguration("provider_b")).thenReturn(configB)
        val service = TranslationService(registry, cache, configStore)

        service.translateText("same article text", "zh-CN", "en", providerId = "provider_a")
        service.translateText("same article text", "zh-CN", "en", providerId = "provider_b")
        service.translateText("same article text", "zh-CN", "en", providerId = "provider_a")

        assertEquals(3, cacheKeys.size)
        assertEquals(3, cacheKeys.distinct().size)
        assertEquals(3, providerA.requestCount + providerB.requestCount)
    }

    @Test
    fun cacheKeyIncludesSourceAndTargetLanguagesButIgnoresTimeoutOnlyChanges() = runBlocking {
        val provider = CountingProvider("provider_languages")
        val registry = TranslationProviderRegistry(setOf(provider))
        val cache = Mockito.mock(TranslationFileCache::class.java)
        val cacheKeys = mutableListOf<String>()
        Mockito.doAnswer { invocation ->
            cacheKeys += invocation.getArgument<String>(0)
            null
        }.`when`(cache).get(Mockito.anyString())
        val configStore = Mockito.mock(TranslationApiConfigStore::class.java)
        val base = testConfiguration(endpoint = "https://api.example/translate")
        whenever(configStore.loadConfiguration(provider.id)).thenReturn(
            base,
            base.copy(timeoutSeconds = 90L),
            base,
            base,
        )
        val service = TranslationService(registry, cache, configStore)

        service.translateText("hello", "zh-CN", "en", providerId = provider.id)
        service.translateText("hello", "zh-CN", "en", providerId = provider.id)
        service.translateText("hello", "ja", "en", providerId = provider.id)
        service.translateText("hello", "zh-CN", "ja", providerId = provider.id)

        assertEquals(4, cacheKeys.size)
        assertEquals(3, cacheKeys.distinct().size)
    }

    private fun testConfiguration(
        endpoint: String,
        apiKey: String = "",
    ) = TranslationApiConfiguration(
        endpoint = endpoint,
        apiKey = apiKey,
        requestTemplate = """{"text":"{{text}}"}""",
        responsePath = "text",
    )

    private class CountingProvider(override val id: String) : TranslationProvider {
        override val capabilities = TranslationProviderCapabilities(
            requiresNetwork = true,
            supportsBatching = false,
        )
        var requestCount = 0
        val requests = mutableListOf<String>()

        override suspend fun translate(request: TranslationRequest): TranslationResponse {
            requestCount++
            requests += request.text
            return TranslationResponse("translated:${request.text}", request.sourceLanguage, id)
        }
    }

    private class DuplicateMarkerProvider : TranslationProvider {
        override val id = "test_batch"
        override val displayName = "Test Batch"
        override val capabilities = TranslationProviderCapabilities(
            requiresNetwork = true,
            supportsBatching = true,
        )
        val requests = mutableListOf<String>()

        override suspend fun translate(request: TranslationRequest): TranslationResponse {
            requests += request.text
            val markers = MARKER.findAll(request.text).map { it.value }.toList()
            val result = if (markers.size == 3) {
                markers[0] + "translated alpha" + markers[1] +
                    markers[1] + "translated beta" + markers[2]
            } else {
                "translated:" + request.text
            }
            return TranslationResponse(result, request.sourceLanguage, id)
        }

        private companion object {
            val MARKER = Regex("<\\|RY_TRANSLATION_SEGMENT_[^>]*\\|>")
        }
    }
}
