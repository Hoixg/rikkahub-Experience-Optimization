package me.rerere.rikkahub.data.ai.tools.local

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentUriSafetyGuardTest {
    @Test
    fun `content uri routing only accepts content scheme`() {
        assertTrue(ContentUriSafetyGuard.isContentUri("content://provider/tree/root"))
        assertEquals(false, ContentUriSafetyGuard.isContentUri("file:///sdcard/file.txt"))
    }

    @Test
    fun `structural validation rejects malformed uri`() {
        assertNotNull(ContentUriSafetyGuard.check(null))
        assertNotNull(ContentUriSafetyGuard.check("content:///tree/root"))
        assertNotNull(ContentUriSafetyGuard.check("content://provider"))
    }

    @Test
    fun `structural validation permits any granted provider`() {
        assertNull(ContentUriSafetyGuard.check("content://example.provider/tree/root"))
    }

    @Test
    fun `limited read reports truncation without reading the full input`() {
        val (bytes, truncated) = readLimited(
            input = ByteArrayInputStream(ByteArray(32) { it.toByte() }),
            limit = 8,
        )

        assertEquals(8, bytes.size)
        assertTrue(truncated)
    }
}
