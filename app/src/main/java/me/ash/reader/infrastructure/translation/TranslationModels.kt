package me.ash.reader.infrastructure.translation

/**
 * Stable identifiers for translation backends. New cloud providers should implement
 * [TranslationProvider] and register a new value here without changing the reader UI.
 */
enum class TranslationEngine(val id: String) {
    ON_DEVICE("on_device"),
    HTTP_JSON("http_json");

    companion object {
        fun fromId(id: String?): TranslationEngine =
            entries.firstOrNull { it.id == id } ?: ON_DEVICE
    }
}

data class TranslationRequest(
    val text: String,
    val sourceLanguage: String? = null,
    val targetLanguage: String,
)

data class TranslationResponse(
    val translatedText: String,
    val sourceLanguage: String?,
    val providerId: String,
)

data class TranslationApiConfiguration(
    val endpoint: String,
    val apiKey: String = "",
    val model: String = "",
    val headers: Map<String, String> = emptyMap(),
    /**
     * A JSON object template. String values may contain {{text}}, {{sourceLanguage}},
     * {{targetLanguage}}, and {{model}} placeholders. Values are JSON-escaped safely.
     */
    val requestTemplate: String = DEFAULT_REQUEST_TEMPLATE,
    /** Dot path into the JSON response, with numeric array indexes, e.g. choices.0.message.content. */
    val responsePath: String = "choices.0.message.content",
    val timeoutSeconds: Long = 45L,
) {
    companion object {
        val DEFAULT_REQUEST_TEMPLATE = """
            {
              "model": "{{model}}",
              "messages": [
                {
                  "role": "system",
                  "content": "Translate the user text from {{sourceLanguage}} to {{targetLanguage}}. Return only the translation."
                },
                {
                  "role": "user",
                  "content": "{{text}}"
                }
              ],
              "temperature": 0
            }
        """.trimIndent()
    }
}
