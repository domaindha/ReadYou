package me.ash.reader.ui.page.adaptive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ArticleMetadataSourceSignatureTest {
    @Test
    fun identicalTitleAndSummaryProduceStableSignature() {
        val first = articleMetadataSourceSignature("Original title", "Original summary")
        val second = articleMetadataSourceSignature("Original title", "Original summary")

        assertEquals(first, second)
    }

    @Test
    fun changingTitleInvalidatesMetadataTranslation() {
        val original = articleMetadataSourceSignature("Original title", "Same summary")
        val updated = articleMetadataSourceSignature("Updated title", "Same summary")

        assertNotEquals(original, updated)
    }

    @Test
    fun changingSummaryInvalidatesMetadataTranslation() {
        val original = articleMetadataSourceSignature("Same title", "Original summary")
        val updated = articleMetadataSourceSignature("Same title", "Updated summary")

        assertNotEquals(original, updated)
    }

    @Test
    fun titleAndSummaryBoundariesCannotCollide() {
        val first = articleMetadataSourceSignature("ab", "c")
        val second = articleMetadataSourceSignature("a", "bc")

        assertNotEquals(first, second)
    }
}
