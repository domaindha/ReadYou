package me.ash.reader.infrastructure.translation

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTranslationProviderTest {
    @Test
    fun myMemorySplitsTextWithoutBreakingUtf8CharactersOrLosingContent() {
        val original = ("Read You 翻译文章。😀 " .repeat(100))
        val chunks = MyMemoryTranslationProvider.splitMyMemoryTextByByteLimit(original)

        assertEquals(original, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 500 })
        assertTrue(chunks.size > 1)
    }

    @Test
    fun myMemoryKeepsShortTextInOneRequestChunk() {
        val text = "Hello, 世界!"
        assertEquals(listOf(text), MyMemoryTranslationProvider.splitMyMemoryTextByByteLimit(text))
    }

    private class FakeLanguageDetector(
        private val detectedLanguage: String,
    ) : TranslationLanguageDetector {
        var lastInput: String = ""
        override suspend fun detectLanguage(text: String): String {
            lastInput = text
            return detectedLanguage
        }
    }

    @Test
    fun myMemoryUsesDeviceLanguageDetectionForAutomaticSourceLanguage() = runBlocking {
        val detector = FakeLanguageDetector("zh")
        val provider = MyMemoryTranslationProvider(detector)

        assertEquals("zh-CN", provider.detectLanguage("这是用于检测语言的中文内容。"))
        assertEquals("这是用于检测语言的中文内容。", detector.lastInput)
        assertTrue(provider.capabilities.supportsLanguageDetection)
        assertEquals(false, provider.capabilities.requiresExplicitSourceLanguage)
    }

    @Test
    fun myMemoryNormalizesChineseLanguageTagsForItsApi() {
        assertEquals("zh-CN", MyMemoryTranslationProvider.normalizeSourceLanguageTag("zh"))
        assertEquals("zh-CN", MyMemoryTranslationProvider.normalizeSourceLanguageTag("zh-Hans"))
        assertEquals("zh-TW", MyMemoryTranslationProvider.normalizeSourceLanguageTag("zh-Hant"))
        assertEquals("en", MyMemoryTranslationProvider.normalizeSourceLanguageTag("en"))
    }

    @Test
    fun myMemoryParsesTranslatedTextAndDecodesHtmlEntities() {
        val json = """{"responseStatus":200,"responseData":{"translatedText":"你好 &amp; 世界"}}"""
        assertEquals("你好 & 世界", MyMemoryTranslationProvider.parseMyMemoryResponse(json))
    }

    @Test
    fun myMemoryReportsDailyQuotaExhaustionClearly() {
        val json = """{"responseStatus":403,"responseDetails":"quota exceeded","quotaFinished":true}"""
        val error = assertThrows(IOException::class.java) {
            MyMemoryTranslationProvider.parseMyMemoryResponse(json)
        }
        assertTrue(error.message.orEmpty().contains("daily quota"))
    }

    @Test
    fun myMemoryReportsApiErrors() {
        val json = """{"responseStatus":403,"responseDetails":"unsupported language pair"}"""
        val error = assertThrows(IOException::class.java) {
            MyMemoryTranslationProvider.parseMyMemoryResponse(json)
        }
        assertTrue(error.message.orEmpty().contains("unsupported language pair"))
    }

    @Test
    fun libreTranslateParsesTranslatedTextAndReportsServerErrors() {
        assertEquals(
            "你好",
            LibreTranslateProvider.parseTranslationResponse("""{"translatedText":"你好"}"""),
        )
        val error = assertThrows(IOException::class.java) {
            LibreTranslateProvider.parseTranslationResponse("""{"error":"invalid API key"}""")
        }
        assertTrue(error.message.orEmpty().contains("invalid API key"))
    }

    @Test
    fun libreTranslateBuildsTranslateEndpointForRootAndExistingPath() {
        assertEquals(
            "https://translate.example/translate",
            LibreTranslateProvider.buildTranslateUrl("https://translate.example"),
        )
        assertEquals(
            "https://translate.example/custom/translate",
            LibreTranslateProvider.buildTranslateUrl("https://translate.example/custom/translate"),
        )
    }

    @Test
    fun lingvaParsesRestTranslationAndServerErrors() {
        assertEquals(
            "你好",
            LingvaTranslationProvider.parseTranslationResponse("""{"translation":"你好"}"""),
        )
        val error = assertThrows(IOException::class.java) {
            LingvaTranslationProvider.parseTranslationResponse("""{"error":"instance unavailable"}""")
        }
        assertTrue(error.message.orEmpty().contains("instance unavailable"))
    }

    @Test
    fun lingvaGetFallbackEncodesTextAsOnePathSegment() {
        val url = LingvaTranslationProvider.buildGetUrl(
            "https://lingva.example",
            "en",
            "zh-CN",
            "hello world/你好",
        )
        assertTrue(url.startsWith("https://lingva.example/api/v1/en/zh-CN/"))
        assertTrue(url.contains("hello%20world%2F"))
    }

    @Test
    fun azureParsesTranslationAndDetectedLanguage() {
        val json = """[{"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"你好","to":"zh-Hans"}]}]"""
        val result = AzureTranslatorProvider.parseAzureTranslationResponse(json)
        assertEquals("你好", result.translatedText)
        assertEquals("en", result.detectedLanguage)
    }

    @Test
    fun azureMapsChineseLanguageTagsToServiceCodes() {
        assertEquals("zh-Hans", AzureTranslatorProvider.toAzureLanguageTag("zh-CN"))
        assertEquals("zh-Hant", AzureTranslatorProvider.toAzureLanguageTag("zh-TW"))
        assertEquals("ja", AzureTranslatorProvider.toAzureLanguageTag("ja"))
    }
}
