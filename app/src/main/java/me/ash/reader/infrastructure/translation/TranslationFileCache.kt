package me.ash.reader.infrastructure.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val mutex = Mutex()
    private var initialPruneDone = false

    suspend fun get(key: String): String? = withContext(ioDispatcher) {
        mutex.withLock {
            val file = fileFor(key)
            if (!file.isFile) {
                pruneOldEntriesIfNeeded()
                return@withLock null
            }
            val value = runCatching {
                file.setLastModified(System.currentTimeMillis())
                file.readText(Charsets.UTF_8).takeIf { it.isNotEmpty() }
            }.getOrNull()
            pruneOldEntriesIfNeeded()
            value
        }
    }

    suspend fun put(key: String, value: String): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            if (value.isEmpty() || value.length > MAX_ENTRY_CHARS) return@withLock
            if (!directory.exists() && !directory.mkdirs()) return@withLock

            val destination = fileFor(key)
            val temporary = runCatching {
                File.createTempFile("${destination.name}.", ".tmp", directory)
            }.getOrNull() ?: return@withLock

            try {
                temporary.writeText(value, Charsets.UTF_8)
                if (!temporary.renameTo(destination)) {
                    // Cache writes are best-effort; retain a fallback for filesystems where
                    // rename cannot replace an existing destination.
                    destination.writeText(value, Charsets.UTF_8)
                }
                pruneOldEntries()
                initialPruneDone = true
            } catch (_: Exception) {
                // A cache failure must never fail an otherwise successful translation.
            } finally {
                temporary.delete()
            }
        }
        Unit
    }

    suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            directory.listFiles()?.forEach { it.delete() }
            initialPruneDone = true
        }
    }

    suspend fun stats(): TranslationCacheStats = withContext(ioDispatcher) {
        mutex.withLock {
            pruneOldEntries()
            initialPruneDone = true
            val files = cacheFiles()
            TranslationCacheStats(
                entryCount = files.size,
                sizeBytes = files.sumOf { it.length() },
            )
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.txt")
    }

    private fun pruneOldEntriesIfNeeded() {
        if (initialPruneDone) return
        pruneOldEntries()
        initialPruneDone = true
    }

    private fun cacheFiles(): List<File> =
        directory.listFiles()?.filter { it.isFile && it.extension == "txt" }.orEmpty()

    private fun pruneOldEntries() {
        val files = cacheFiles()
        var remainingEntries = files.size
        var remainingBytes = files.sumOf { it.length() }
        if (remainingEntries <= MAX_ENTRIES && remainingBytes <= MAX_CACHE_BYTES) return

        for (file in files.sortedBy { it.lastModified() }) {
            if (remainingEntries <= MAX_ENTRIES && remainingBytes <= MAX_CACHE_BYTES) break
            val length = file.length()
            if (file.delete()) {
                remainingEntries--
                remainingBytes -= length
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 250
        const val MAX_ENTRY_CHARS = 250_000
        const val MAX_CACHE_BYTES = 32L * 1024L * 1024L
    }
}

data class TranslationCacheStats(
    val entryCount: Int,
    val sizeBytes: Long,
)
