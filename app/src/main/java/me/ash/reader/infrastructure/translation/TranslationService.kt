package me.ash.reader.infrastructure.translation

import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Entry point used by the reader. Keeps provider selection, HTML handling, and caching
 * outside the UI so additional translation providers do not require reader changes.
 */
@Singleton
class TranslationService @Inject constructor(
    private val onDeviceProvider: MlKitTranslationProvider,
    private val httpJsonProvider: HttpJsonTranslationProvider,
    private val cache: TranslationFileCache,
    private val configStore: TranslationApiConfigStore,
) {
    fun selectedEngine(): TranslationEngine = configStore.selectedEngine()

    fun selectEngine(engine: TranslationEngine) = configStore.setSelectedEngine(engine)

    suspend fun translateText(
        text: String,
        targetLanguage: String,
        sourceLanguage: String? = null,
        engine: TranslationEngine = selectedEngine(),
    ): TranslationResponse {
        if (text.isBlank()) {
            return TranslationResponse(text, sourceLanguage, engine.id)
        }

        val provider = providerFor(engine)
        val cacheKey = listOf(
            provider.id,
            PROVIDER_REVISION,
            sourceLanguage.orEmpty(),
            targetLanguage,
            text,
        ).joinToString("|")

        cache.get(cacheKey)?.let {
            return TranslationResponse(it, sourceLanguage, provider.id)
        }

        val result = provider.translate(
            TranslationRequest(
                text = text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
        )
        cache.put(cacheKey, result.translatedText)
        return result
    }

    suspend fun translateHtml(
        html: String,
        targetLanguage: String,
        engine: TranslationEngine = selectedEngine(),
        onProgress: (Float) -> Unit = {},
    ): String {
        if (html.isBlank()) return html

        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        val body = document.body()
        val textNodes = mutableListOf<TextNode>()
        collectTranslatableTextNodes(body, textNodes)
        if (textNodes.isEmpty()) return html

        val sourceLanguage = if (engine == TranslationEngine.ON_DEVICE) {
            onDeviceProvider.detectLanguage(body.text())
                .takeIf { it != "und" }
        } else {
            null
        }

        val total = textNodes.size
        textNodes.forEachIndexed { index, node ->
            val originalText = node.wholeText
            val chunks = splitLongText(originalText)
            val translated = buildString {
                chunks.forEach { chunk ->
                    append(
                        translateText(
                            text = chunk,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            engine = engine,
                        ).translatedText
                    )
                }
            }
            node.text(translated)
            onProgress((index + 1).toFloat() / total)
        }

        return body.html()
    }

    suspend fun downloadOnDeviceModel(sourceLanguage: String, targetLanguage: String) =
        onDeviceProvider.downloadModel(sourceLanguage, targetLanguage)

    suspend fun getDownloadedOnDeviceModels(): List<String> =
        onDeviceProvider.downloadedModels()

    suspend fun deleteOnDeviceModel(languageTag: String) =
        onDeviceProvider.deleteModel(languageTag)

    suspend fun clearTranslationCache() = cache.clear()

    fun saveApiConfiguration(configuration: TranslationApiConfiguration) =
        configStore.saveConfiguration(configuration)

    fun loadApiConfiguration(): TranslationApiConfiguration? =
        configStore.loadConfiguration()

    private fun providerFor(engine: TranslationEngine): TranslationProvider =
        when (engine) {
            TranslationEngine.ON_DEVICE -> onDeviceProvider
            TranslationEngine.HTTP_JSON -> httpJsonProvider
        }

    private fun collectTranslatableTextNodes(element: Element, result: MutableList<TextNode>) {
        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> {
                    val parent = child.parent()
                    if (child.wholeText.isNotBlank() && parent != null && !isInsideIgnoredElement(parent)) {
                        result.add(child)
                    }
                }
                is Element -> if (!isIgnoredElement(child)) {
                    collectTranslatableTextNodes(child, result)
                }
            }
        }
    }

    private fun isInsideIgnoredElement(element: Element): Boolean {
        var current: Element? = element
        while (current != null) {
            if (isIgnoredElement(current)) return true
            current = current.parent()
        }
        return false
    }

    private fun isIgnoredElement(element: Element): Boolean {
        val tag = element.normalName()
        return tag in IGNORED_TAGS ||
            element.hasAttr("contenteditable") ||
            element.hasClass("notranslate") ||
            element.attr("translate").equals("no", ignoreCase = true)
    }

    /**
     * Keep chunk boundaries at whitespace where possible. Chunks are concatenated
     * without injecting extra punctuation or markup.
     */
    private fun splitLongText(text: String, maxChars: Int = MAX_CHUNK_CHARS): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), end - 1)
                if (boundary > start + maxChars / 2) end = boundary + 1
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    private companion object {
        const val PROVIDER_REVISION = "1"
        const val MAX_CHUNK_CHARS = 3500
        val IGNORED_TAGS = setOf(
            "script", "style", "noscript", "pre", "code", "kbd", "samp",
            "svg", "math", "textarea", "input", "select", "option",
        )
    }
}
