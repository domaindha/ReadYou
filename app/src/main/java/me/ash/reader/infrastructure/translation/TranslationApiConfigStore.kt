package me.ash.reader.infrastructure.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

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

    fun loadConfiguration(): TranslationApiConfiguration? {
        val endpoint = preferences.getString(KEY_ENDPOINT, null)?.trim().orEmpty()
        if (endpoint.isEmpty()) return null

        val headersJson = preferences.getString(KEY_HEADERS_ENCRYPTED, null)
            ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrDefault("{}") }
            ?: "{}"
        val headers = runCatching {
            val json = JSONObject(headersJson)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())

        val encryptedKey = preferences.getString(KEY_API_KEY, null)
        val apiKey = encryptedKey
            ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrDefault("") }
            .orEmpty()

        return TranslationApiConfiguration(
            endpoint = endpoint,
            apiKey = apiKey,
            model = preferences.getString(KEY_MODEL, "").orEmpty(),
            headers = headers,
            requestTemplate = preferences.getString(
                KEY_TEMPLATE,
                TranslationApiConfiguration.DEFAULT_REQUEST_TEMPLATE,
            ).orEmpty(),
            responsePath = preferences.getString(KEY_RESPONSE_PATH, "choices.0.message.content")
                .orEmpty(),
            timeoutSeconds = preferences.getLong(KEY_TIMEOUT, 45L).coerceIn(5L, 180L),
        )
    }

    fun saveConfiguration(configuration: TranslationApiConfiguration) {
        require(configuration.endpoint.isNotBlank()) { "API endpoint is required." }
        require(configuration.responsePath.isNotBlank()) { "Response JSON path is required." }
        require(configuration.timeoutSeconds in 5L..180L) { "Timeout must be between 5 and 180 seconds." }

        val headersJson = JSONObject().apply {
            configuration.headers.forEach { (key, value) -> put(key, value) }
        }

        preferences.edit()
            .putString(KEY_ENDPOINT, configuration.endpoint.trim())
            .putString(KEY_API_KEY, configuration.apiKey.takeIf { it.isNotEmpty() }?.let(::encrypt))
            .putString(KEY_MODEL, configuration.model)
            .putString(KEY_HEADERS_ENCRYPTED, encrypt(headersJson.toString()))
            .putString(KEY_TEMPLATE, configuration.requestTemplate)
            .putString(KEY_RESPONSE_PATH, configuration.responsePath)
            .putLong(KEY_TIMEOUT, configuration.timeoutSeconds)
            .apply()
    }

    fun clearConfiguration() {
        preferences.edit().clear().apply()
    }

    fun targetLanguage(): String =
        preferences.getString(KEY_TARGET_LANGUAGE, Locale.getDefault().toLanguageTag())
            ?.takeIf { it.isNotBlank() }
            ?: "zh-CN"

    fun setTargetLanguage(languageTag: String) {
        require(languageTag.isNotBlank()) { "Target language is required." }
        preferences.edit().putString(KEY_TARGET_LANGUAGE, languageTag.trim()).apply()
    }

    fun selectedEngine(): TranslationEngine =
        TranslationEngine.fromId(preferences.getString(KEY_ENGINE, TranslationEngine.ON_DEVICE.id))

    fun setSelectedEngine(engine: TranslationEngine) {
        preferences.edit().putString(KEY_ENGINE, engine.id).apply()
    }

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
        const val KEY_TARGET_LANGUAGE = "target_language"
    }
}
