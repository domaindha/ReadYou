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
