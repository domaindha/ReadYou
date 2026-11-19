package me.ash.reader.infrastructure.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.di.IODispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent, content-addressed cache for translated text fragments.
 * The source text, language pair, provider, and provider revision all participate in the key.
 */
@Singleton
class TranslationFileCache @Inject constructor(
    @ApplicationContext context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val directory = File(context.filesDir, "translation_cache")

    suspend fun get(key: String): String? = withContext(ioDispatcher) {
        val file = fileFor(key)
        if (!file.isFile) return@withContext null
        runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    suspend fun put(key: String, value: String): Unit = withContext(ioDispatcher) {
        if (value.length > MAX_ENTRY_CHARS) return@withContext
        if (!directory.exists() && !directory.mkdirs()) return@withContext

        val destination = fileFor(key)
        val temporary = File(directory, "${destination.name}.tmp")
        runCatching {
            temporary.writeText(value, Charsets.UTF_8)
            if (!temporary.renameTo(destination)) {
                destination.writeText(value, Charsets.UTF_8)
                temporary.delete()
            }
            pruneOldEntries()
        }
        Unit
    }

    suspend fun clear() = withContext(ioDispatcher) {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.txt")
    }

    private fun pruneOldEntries() {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "txt" } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { it.delete() }
    }

    private companion object {
        const val MAX_ENTRIES = 250
        const val MAX_ENTRY_CHARS = 250_000
    }
}
