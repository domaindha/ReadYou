package me.ash.reader.infrastructure.translation

import java.io.IOException
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
