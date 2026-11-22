package me.ash.reader.infrastructure.translation

import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRetryPolicyTest {
    @Test
    fun retriesRateLimitsAndServerErrorsButNotPermanentClientErrors() {
        assertTrue(TranslationRetryPolicy.shouldRetry(IOExceptionForTest("HTTP 429"), true))
        assertTrue(TranslationRetryPolicy.shouldRetry(IOExceptionForTest("HTTP 503"), true))
        assertTrue(TranslationRetryPolicy.shouldRetry(IOExceptionForTest("HTTP 408"), true))
        assertFalse(TranslationRetryPolicy.shouldRetry(IOExceptionForTest("HTTP 401"), true))
        assertFalse(TranslationRetryPolicy.shouldRetry(IOExceptionForTest("HTTP 404"), true))
        assertFalse(TranslationRetryPolicy.shouldRetry(IOExceptionForTest("HTTP 503"), false))
    }

    @Test
    fun retriesSocketTimeoutsButNotCertificateFailures() {
        assertTrue(TranslationRetryPolicy.shouldRetry(SocketTimeoutException("read timed out"), true))
        assertFalse(TranslationRetryPolicy.shouldRetry(SSLException("certificate rejected"), true))
    }

    @Test
    fun exponentialBackoffIsBoundedAndRetryAfterIsHonoured() {
        assertEquals(1_000L, TranslationRetryPolicy.retryDelayMillis(IOExceptionForTest("temporary"), 0))
        assertEquals(2_000L, TranslationRetryPolicy.retryDelayMillis(IOExceptionForTest("temporary"), 1))
        assertEquals(8_000L, TranslationRetryPolicy.retryDelayMillis(IOExceptionForTest("temporary"), 3))
        assertEquals(3_000L, TranslationRetryPolicy.retryDelayMillis(IOExceptionForTest("HTTP 429 Retry after 3 seconds"), 0))
        assertEquals(30_000L, TranslationRetryPolicy.retryDelayMillis(IOExceptionForTest("Retry-After: 99"), 0))
    }

    private class IOExceptionForTest(message: String) : java.io.IOException(message)
}
