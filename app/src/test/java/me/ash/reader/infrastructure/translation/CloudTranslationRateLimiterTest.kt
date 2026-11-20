package me.ash.reader.infrastructure.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTranslationRateLimiterTest {
    @Test
    fun cloudRequestsForTheSameProviderRespectMinimumInterval() = runBlocking {
        val limiter = CloudTranslationRateLimiter(minimumIntervalMillis = 60L)
        val provider = TestRateLimitProvider(requiresNetwork = true)

        val startedAt = System.nanoTime()
        limiter.awaitIfNeeded(provider)
        limiter.awaitIfNeeded(provider)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

        assertTrue(
            "The second cloud request should wait for the provider interval.",
            elapsedMillis >= 50L,
        )
    }

    @Test
    fun localProviderDoesNotWaitForCloudRateLimit() = runBlocking {
        val limiter = CloudTranslationRateLimiter(minimumIntervalMillis = 500L)
        val provider = TestRateLimitProvider(requiresNetwork = false)

        val startedAt = System.nanoTime()
        limiter.awaitIfNeeded(provider)
        limiter.awaitIfNeeded(provider)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L

        assertTrue(
            "On-device translation must not be throttled by the cloud limiter.",
            elapsedMillis < 100L,
        )
    }

    private class TestRateLimitProvider(
        requiresNetwork: Boolean,
    ) : TranslationProvider {
        override val id = if (requiresNetwork) "cloud_rate_test" else "local_rate_test"
        override val capabilities = TranslationProviderCapabilities(
            requiresNetwork = requiresNetwork,
            supportsBatching = false,
        )

        override suspend fun translate(request: TranslationRequest): TranslationResponse =
            TranslationResponse(request.text, request.sourceLanguage, id)
    }
}
