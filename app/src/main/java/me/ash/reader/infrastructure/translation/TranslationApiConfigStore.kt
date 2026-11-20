package me.ash.reader.infrastructure.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores custom HTTP translation settings locally. The API key is encrypted with an
 * AES-GCM key held by Android Keystore; the key itself is never written to preferences.
 */
@Singleton
class TranslationApiConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("translation_api_settings", Context.MODE_PRIVATE)

    private val _articleMetadataTranslationEnabled = MutableStateFlow(
        preferences.getBoolean(KEY_ARTICLE_METADATA_TRANSLATION_ENABLED, false)
    )
    val articleMetadataTranslationEnabled = _articleMetadataTranslationEnabled.asStateFlow()

    fun loadConfiguration(providerId: String = TranslationEngine.HTTP_JSON.id): TranslationApiConfiguration? {
        val endpoint = preferences.getString(storageKey(KEY_ENDPOINT, providerId), null)?.trim().orEmpty()
        if (endpoint.isEmpty()) return null

        val headersJson = preferences.getString(storageKey(KEY_HEADERS_ENCRYPTED, providerId), null)
            ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrDefault("{}") }
            ?: "{}"
        val headers = runCatching {
            val json = JSONObject(headersJson)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())

        val encryptedKey = preferences.getString(storageKey(KEY_API_KEY, providerId), null)
        val apiKey = encryptedKey
            ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrDefault("") }
            .orEmpty()

        return TranslationApiConfiguration(
            endpoint = endpoint,
            apiKey = apiKey,
            model = preferences.getString(storageKey(KEY_MODEL, providerId), "").orEmpty(),
            headers = headers,
            requestTemplate = preferences.getString(
                storageKey(KEY_TEMPLATE, providerId),
                TranslationApiConfiguration.DEFAULT_REQUEST_TEMPLATE,
            ).orEmpty(),
            responsePath = preferences.getString(storageKey(KEY_RESPONSE_PATH, providerId), "choices.0.message.content")
                .orEmpty(),
            timeoutSeconds = preferences.getLong(storageKey(KEY_TIMEOUT, providerId), 45L).coerceIn(5L, 180L),
        )
    }

    fun saveConfiguration(
        configuration: TranslationApiConfiguration,
        providerId: String = TranslationEngine.HTTP_JSON.id,
    ) {
        require(configuration.endpoint.isNotBlank()) { "API endpoint is required." }
        require(configuration.responsePath.isNotBlank()) { "Response JSON path is required." }
        require(configuration.timeoutSeconds in 5L..180L) { "Timeout must be between 5 and 180 seconds." }

        val headersJson = JSONObject().apply {
            configuration.headers.forEach { (key, value) -> put(key, value) }
        }

        preferences.edit()
            .putString(storageKey(KEY_ENDPOINT, providerId), configuration.endpoint.trim())
            .putString(storageKey(KEY_API_KEY, providerId), configuration.apiKey.takeIf { it.isNotEmpty() }?.let(::encrypt))
            .putString(storageKey(KEY_MODEL, providerId), configuration.model)
            .putString(storageKey(KEY_HEADERS_ENCRYPTED, providerId), encrypt(headersJson.toString()))
            .putString(storageKey(KEY_TEMPLATE, providerId), configuration.requestTemplate)
            .putString(storageKey(KEY_RESPONSE_PATH, providerId), configuration.responsePath)
            .putLong(storageKey(KEY_TIMEOUT, providerId), configuration.timeoutSeconds)
            .apply()
    }

    fun clearConfiguration(providerId: String = TranslationEngine.HTTP_JSON.id) {
        val keys = listOf(KEY_ENDPOINT, KEY_API_KEY, KEY_MODEL, KEY_HEADERS_ENCRYPTED, KEY_TEMPLATE, KEY_RESPONSE_PATH, KEY_TIMEOUT)
        preferences.edit().also { editor ->
            keys.forEach { editor.remove(storageKey(it, providerId)) }
        }.apply()
    }

    fun targetLanguage(): String =
        preferences.getString(KEY_TARGET_LANGUAGE, "zh-CN")
            ?.takeIf { it.isNotBlank() }
            ?: "zh-CN"

    fun setTargetLanguage(languageTag: String) {
        require(languageTag.isNotBlank()) { "Target language is required." }
        preferences.edit().putString(KEY_TARGET_LANGUAGE, languageTag.trim()).apply()
    }

    fun sourceLanguage(): String = preferences.getString(KEY_SOURCE_LANGUAGE, "auto")?.takeIf { it.isNotBlank() } ?: "auto"

    fun setSourceLanguage(languageTag: String) { preferences.edit().putString(KEY_SOURCE_LANGUAGE, languageTag.trim()).apply() }

    fun selectedProviderId(): String =
        preferences.getString(KEY_PROVIDER, null)
            ?: preferences.getString(KEY_ENGINE, TranslationEngine.ON_DEVICE.id)
            ?: TranslationEngine.ON_DEVICE.id

    fun setSelectedProviderId(providerId: String) {
        require(providerId.isNotBlank()) { "Translation provider ID is required." }
        preferences.edit().putString(KEY_PROVIDER, providerId.trim()).apply()
    }

    fun isBilingualReadingEnabled(): Boolean =
        preferences.getBoolean(KEY_BILINGUAL_READING, false)

    fun setBilingualReadingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_BILINGUAL_READING, enabled).apply()
    }

    fun setArticleMetadataTranslationEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ARTICLE_METADATA_TRANSLATION_ENABLED, enabled).apply()
        _articleMetadataTranslationEnabled.value = enabled
    }

    fun isAutoTranslationEnabled(feedId: String): Boolean {
        require(feedId.isNotBlank()) { "Feed ID is required." }
        return preferences.getBoolean(autoTranslationKey(feedId), false)
    }

    fun setAutoTranslationEnabled(feedId: String, enabled: Boolean) {
        require(feedId.isNotBlank()) { "Feed ID is required." }
        preferences.edit().putBoolean(autoTranslationKey(feedId), enabled).apply()
    }

    /**
     * Returns null when a group has no automatic-translation default configured.
     * A stored false is an explicit opt-out for future subscriptions in that group.
     */
    fun groupAutoTranslationDefault(groupId: String): Boolean? {
        require(groupId.isNotBlank()) { "Group ID is required." }
        val key = groupAutoTranslationDefaultKey(groupId)
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    fun setGroupAutoTranslationDefault(groupId: String, enabled: Boolean) {
        require(groupId.isNotBlank()) { "Group ID is required." }
        preferences.edit().putBoolean(groupAutoTranslationDefaultKey(groupId), enabled).apply()
    }

    fun selectedEngine(): TranslationEngine = TranslationEngine.fromId(selectedProviderId())

    fun setSelectedEngine(engine: TranslationEngine) = setSelectedProviderId(engine.id)

    private fun storageKey(key: String, providerId: String): String =
        if (providerId == TranslationEngine.HTTP_JSON.id) key else "${providerId}_$key"

    private fun autoTranslationKey(feedId: String): String = "auto_translate_feed_$feedId"

    private fun groupAutoTranslationDefaultKey(groupId: String): String =
        "$KEY_AUTO_TRANSLATE_GROUP_PREFIX$groupId"

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_LENGTH) { "Invalid encrypted API key." }
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = payload.copyOfRange(GCM_IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "readyou_translation_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_API_KEY = "api_key_encrypted"
        const val KEY_MODEL = "model"
        const val KEY_HEADERS_ENCRYPTED = "headers_encrypted"
        const val KEY_TEMPLATE = "request_template"
        const val KEY_RESPONSE_PATH = "response_path"
        const val KEY_TIMEOUT = "timeout_seconds"
        const val KEY_ENGINE = "selected_engine"
        const val KEY_PROVIDER = "selected_provider"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_SOURCE_LANGUAGE = "source_language"
        const val KEY_BILINGUAL_READING = "bilingual_reading_enabled"
        const val KEY_ARTICLE_METADATA_TRANSLATION_ENABLED = "article_metadata_translation_enabled"
        const val KEY_AUTO_TRANSLATE_GROUP_PREFIX = "auto_translate_group_default_"
    }
}
