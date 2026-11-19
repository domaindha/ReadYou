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
                  "content": "Translate the user text from {{sourceLanguage}} to {{targetLanguage}}. Return only the translation. Preserve every tag matching <|RY_TRANSLATION_SEGMENT_NNN|> exactly, including its numeric suffix and original order. These tags are delimiters, not text to translate. Never remove, duplicate, modify, or move them."
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


/** Common language choices for the translation UI. */
data class TranslationLanguage(val tag: String, val label: String)

object TranslationLanguages {
    val supported = listOf(
        TranslationLanguage("ar", "阿拉伯语"), TranslationLanguage("bg", "保加利亚语"),
        TranslationLanguage("bn", "孟加拉语"), TranslationLanguage("ca", "加泰罗尼亚语"),
        TranslationLanguage("cs", "捷克语"), TranslationLanguage("da", "丹麦语"),
        TranslationLanguage("de", "德语"), TranslationLanguage("el", "希腊语"),
        TranslationLanguage("en", "英语"), TranslationLanguage("es", "西班牙语"),
        TranslationLanguage("et", "爱沙尼亚语"), TranslationLanguage("fa", "波斯语"),
        TranslationLanguage("fi", "芬兰语"), TranslationLanguage("fr", "法语"),
        TranslationLanguage("gl", "加利西亚语"), TranslationLanguage("gu", "古吉拉特语"),
        TranslationLanguage("he", "希伯来语"), TranslationLanguage("hi", "印地语"),
        TranslationLanguage("hr", "克罗地亚语"), TranslationLanguage("hu", "匈牙利语"),
        TranslationLanguage("id", "印尼语"), TranslationLanguage("it", "意大利语"),
        TranslationLanguage("ja", "日语"), TranslationLanguage("kn", "卡纳达语"),
        TranslationLanguage("ko", "韩语"), TranslationLanguage("lt", "立陶宛语"),
        TranslationLanguage("lv", "拉脱维亚语"), TranslationLanguage("mr", "马拉地语"),
        TranslationLanguage("ms", "马来语"), TranslationLanguage("nl", "荷兰语"),
        TranslationLanguage("no", "挪威语"), TranslationLanguage("pl", "波兰语"),
        TranslationLanguage("pt", "葡萄牙语"), TranslationLanguage("ro", "罗马尼亚语"),
        TranslationLanguage("ru", "俄语"), TranslationLanguage("sk", "斯洛伐克语"),
        TranslationLanguage("sl", "斯洛文尼亚语"), TranslationLanguage("sq", "阿尔巴尼亚语"),
        TranslationLanguage("sv", "瑞典语"), TranslationLanguage("sw", "斯瓦希里语"),
        TranslationLanguage("ta", "泰米尔语"), TranslationLanguage("te", "泰卢固语"),
        TranslationLanguage("th", "泰语"), TranslationLanguage("tr", "土耳其语"),
        TranslationLanguage("uk", "乌克兰语"), TranslationLanguage("ur", "乌尔都语"),
        TranslationLanguage("vi", "越南语"), TranslationLanguage("zh-CN", "简体中文"),
        TranslationLanguage("zh-TW", "繁体中文")
    )
}
