package me.ash.reader.infrastructure.translation

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.Jsoup

/**
 * Entry point used by the reader. Provider selection, HTML handling, and caching stay
 * outside the UI so additional translation providers do not require reader changes.
 */
@Singleton
class TranslationService @Inject constructor(
    private val providerRegistry: TranslationProviderRegistry,
    private val cache: TranslationFileCache,
    private val configStore: TranslationApiConfigStore,
) {
    fun selectedEngine(): TranslationEngine = configStore.selectedEngine()

    fun targetLanguage(): String = configStore.targetLanguage()

    fun sourceLanguage(): String = configStore.sourceLanguage()

    fun setSourceLanguage(languageTag: String) = configStore.setSourceLanguage(languageTag)

    fun setTargetLanguage(languageTag: String) = configStore.setTargetLanguage(languageTag)

    fun selectEngine(engine: TranslationEngine) = configStore.setSelectedEngine(engine)

    suspend fun translateText(
        text: String,
        targetLanguage: String,
        sourceLanguage: String? = null,
        engine: TranslationEngine = selectedEngine(),
    ): TranslationResponse {
        if (text.isBlank()) return TranslationResponse(text, sourceLanguage, engine.id)

        val provider = providerFor(engine)
        val key = createCacheKey(
            provider.id,
            cacheRevision(provider.id),
            sourceLanguage,
            targetLanguage,
            text,
        )
        cache.get(key)?.let { return TranslationResponse(it, sourceLanguage, provider.id) }

        val response = provider.translate(
            TranslationRequest(
                text = text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )
        )
        cache.put(key, response.translatedText)
        return response
    }

    suspend fun translateHtml(
        html: String,
        targetLanguage: String,
        engine: TranslationEngine = selectedEngine(),
        sourceLanguage: String? = configStore.sourceLanguage().takeUnless { it == "auto" },
        onProgress: (Float) -> Unit = {},
    ): String {
        if (html.isBlank()) return html

        val document = Jsoup.parseBodyFragment(html)
        document.outputSettings().prettyPrint(false)
        val body = document.body()
        val textNodes = mutableListOf<TextNode>()
        collectTranslatableTextNodes(body, textNodes)
        if (textNodes.isEmpty()) return html

        val selectedProvider = providerFor(engine)
        val resolvedSourceLanguage = sourceLanguage ?: if (selectedProvider.capabilities.supportsLanguageDetection) {
            (selectedProvider as? TranslationLanguageDetector)
                ?.detectLanguage(body.text())?.takeIf { it != "und" }
        } else null

        // Keep the association between DOM nodes and their chunks to avoid moving text
        // across markup boundaries when a paragraph exceeds the engine's input limit.
        val nodeChunks = textNodes.map { node -> node to splitLongText(node.wholeText) }
        val flattenedChunks = nodeChunks.flatMap { it.second }
        val translatedChunks = translateSegments(
            segments = flattenedChunks,
            sourceLanguage = resolvedSourceLanguage,
            targetLanguage = targetLanguage,
            engine = engine,
        )

        var translatedIndex = 0
        nodeChunks.forEachIndexed { nodeIndex, (node, chunks) ->
            node.text(buildString {
                repeat(chunks.size) { append(translatedChunks[translatedIndex++]) }
            })
            onProgress((nodeIndex + 1).toFloat() / nodeChunks.size)
        }

        return body.html()
    }

    suspend fun downloadOnDeviceModel(sourceLanguage: String, targetLanguage: String) =
        modelManager().downloadModel(sourceLanguage, targetLanguage)

    suspend fun getDownloadedOnDeviceModels(): List<String> =
        modelManager().downloadedModels()

    suspend fun deleteOnDeviceModel(languageTag: String) =
        modelManager().deleteModel(languageTag)

    suspend fun clearTranslationCache() = cache.clear()

    fun saveApiConfiguration(
        configuration: TranslationApiConfiguration,
        providerId: String = TranslationEngine.HTTP_JSON.id,
    ) = configStore.saveConfiguration(configuration, providerId)

    fun loadApiConfiguration(
        providerId: String = TranslationEngine.HTTP_JSON.id,
    ): TranslationApiConfiguration? = configStore.loadConfiguration(providerId)

    private suspend fun translateSegments(
        segments: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        engine: TranslationEngine,
    ): List<String> {
        if (segments.isEmpty()) return emptyList()

        val provider = providerFor(engine)
        val revision = cacheRevision(provider.id)
        val results = MutableList<String?>(segments.size) { null }
        val missingIndices = mutableListOf<Int>()

        segments.forEachIndexed { index, segment ->
            val key = createCacheKey(
                provider.id, revision, sourceLanguage, targetLanguage, segment
            )
            val cached = cache.get(key)
            if (cached != null) results[index] = cached else missingIndices += index
        }

        suspend fun translateOne(segmentIndex: Int) {
            val segment = segments[segmentIndex]
            val response = provider.translate(
                TranslationRequest(
                    text = segment,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                )
            )
            results[segmentIndex] = response.translatedText
            cache.put(
                createCacheKey(
                    provider.id, revision, sourceLanguage, targetLanguage, segment
                ),
                response.translatedText,
            )
        }

        // Providers that do not declare batch support receive one original segment per request.
        if (!provider.capabilities.supportsBatching) {
            for (index in missingIndices) translateOne(index)
        } else {
            // Bounded batches reduce request count without creating oversized prompts.
            for (batch in buildBatches(missingIndices, segments)) {
                val markers = (0..batch.size).map(::batchMarker)
                val payload = buildString {
                    batch.forEachIndexed { index, segmentIndex ->
                        append(markers[index])
                        append(segments[segmentIndex])
                    }
                    append(markers.last())
                }

                val batchResult = try {
                    provider.translate(
                        TranslationRequest(
                            text = payload,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                        )
                    ).translatedText
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }

                val pieces = batchResult?.let { extractMarkedSegments(it, markers) }
                if (pieces != null) {
                    batch.forEachIndexed { index, segmentIndex ->
                        val value = pieces[index]
                        results[segmentIndex] = value
                        cache.put(
                            createCacheKey(
                                provider.id, revision, sourceLanguage, targetLanguage,
                                segments[segmentIndex],
                            ),
                            value,
                        )
                    }
                } else {
                    // If a service changes/removes sentinels, retry each original text node.
                    for (segmentIndex in batch) translateOne(segmentIndex)
                }
            }
        }

        return results.mapIndexed { index, value ->
            value ?: throw IllegalStateException("Translation result $index is missing.")
        }
    }

    private fun extractMarkedSegments(
        translated: String,
        markers: List<String>,
    ): List<String>? {
        if (markers.size < 2 || !translated.startsWith(markers.first())) return null

        val result = mutableListOf<String>()
        var cursor = markers.first().length
        for (markerIndex in 1 until markers.size) {
            val markerPosition = translated.indexOf(markers[markerIndex], cursor)
            if (markerPosition < 0) return null
            result += translated.substring(cursor, markerPosition)
            cursor = markerPosition + markers[markerIndex].length
        }

        if (translated.substring(cursor).isNotBlank()) return null
        return result
    }

    private fun buildBatches(indices: List<Int>, segments: List<String>): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var batch = mutableListOf<Int>()
        var batchChars = 0

        for (index in indices) {
            val length = segments[index].length
            if (batch.isNotEmpty() &&
                (batch.size >= MAX_BATCH_SEGMENTS || batchChars + length > MAX_BATCH_CHARS)
            ) {
                result += batch
                batch = mutableListOf()
                batchChars = 0
            }
            batch += index
            batchChars += length
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }

    private fun batchMarker(index: Int): String =
        "<|RY_TRANSLATION_SEGMENT_${index.toString().padStart(3, '0')}|>"

    private fun createCacheKey(
        providerId: String,
        revision: String,
        sourceLanguage: String?,
        targetLanguage: String,
        text: String,
    ): String {
        // Length-prefix each field so separator characters in article content cannot collide.
        val fields = listOf(providerId, revision, sourceLanguage.orEmpty(), targetLanguage, text)
        return fields.joinToString("") { "${it.length}:$it" }
    }

    private fun cacheRevision(providerId: String): String {
        if (providerId == TranslationEngine.ON_DEVICE.id) return PROVIDER_REVISION
        val config = configStore.loadConfiguration(providerId) ?: return "$providerId-unconfigured"
        val material = listOf(
            config.endpoint,
            config.model,
            config.apiKey,
            config.headers.toSortedMap().toString(),
            config.requestTemplate,
            config.responsePath,
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun providerFor(engine: TranslationEngine): TranslationProvider =
        providerRegistry.requireProvider(engine.id)

    private fun modelManager(): TranslationModelManager =
        providerRegistry.requireProvider(TranslationEngine.ON_DEVICE.id) as? TranslationModelManager
            ?: throw IllegalStateException("The registered on-device provider does not support model management.")

    private fun collectTranslatableTextNodes(element: Element, result: MutableList<TextNode>) {
        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> {
                    val parent = child.parent()
                    if (child.wholeText.isNotBlank() && parent != null &&
                        !isInsideIgnoredElement(parent)
                    ) {
                        result += child
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

    private fun isIgnoredElement(element: Element): Boolean =
        element.normalName() in IGNORED_TAGS ||
            element.hasAttr("contenteditable") ||
            element.hasClass("notranslate") ||
            element.attr("translate").equals("no", ignoreCase = true)

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
        const val MAX_BATCH_SEGMENTS = 8
        const val MAX_BATCH_CHARS = 6000
        val IGNORED_TAGS = setOf(
            "script", "style", "noscript", "pre", "code", "kbd", "samp",
            "svg", "math", "textarea", "input", "select", "option",
        )
    }
}
