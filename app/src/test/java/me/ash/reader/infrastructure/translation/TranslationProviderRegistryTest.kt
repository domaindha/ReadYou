package me.ash.reader.infrastructure.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class TranslationProviderRegistryTest {
    private class FakeProvider(
        override val id: String,
        override val displayName: String,
        override val capabilities: TranslationProviderCapabilities,
    ) : TranslationProvider {
        override suspend fun translate(request: TranslationRequest): TranslationResponse =
            TranslationResponse(request.text, request.sourceLanguage, id)
    }

    @Test
    fun descriptorsExposeProviderMetadataInStableOrder() {
        val remote = FakeProvider(
            id = "remote_z",
            displayName = "Remote Z",
            capabilities = TranslationProviderCapabilities(
                requiresNetwork = true,
                supportsBatching = true,
                supportsCustomConfiguration = true,
            ),
        )
        val local = FakeProvider(
            id = "local_a",
            displayName = "Local A",
            capabilities = TranslationProviderCapabilities(
                requiresNetwork = false,
                supportsBatching = false,
                supportsLanguageDetection = true,
            ),
        )

        val descriptors = TranslationProviderRegistry(setOf(remote, local)).descriptors()

        assertEquals(listOf("local_a", "remote_z"), descriptors.map { it.id })
        assertEquals("Local A", descriptors.first().displayName)
        assertEquals(false, descriptors.first().capabilities.requiresNetwork)
        assertEquals(true, descriptors.last().capabilities.supportsCustomConfiguration)
    }

    @Test
    fun requireProviderReturnsRegisteredInstance() {
        val provider = FakeProvider(
            id = "test",
            displayName = "Test",
            capabilities = TranslationProviderCapabilities(false, false),
        )

        assertSame(provider, TranslationProviderRegistry(setOf(provider)).requireProvider("test"))
    }

    @Test
    fun requireProviderRejectsUnknownId() {
        val registry = TranslationProviderRegistry(emptySet())

        assertThrows(IllegalArgumentException::class.java) {
            registry.requireProvider("missing")
        }
    }

    @Test
    fun duplicateProviderIdsAreRejected() {
        val first = FakeProvider("duplicate", "First", TranslationProviderCapabilities(false, false))
        val second = FakeProvider("duplicate", "Second", TranslationProviderCapabilities(false, false))

        assertThrows(IllegalArgumentException::class.java) {
            TranslationProviderRegistry(setOf(first, second))
        }
    }
}
