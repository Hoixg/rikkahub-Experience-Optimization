package me.rerere.rikkahub.data.ai.tools.local

import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentUriSafetyGuardTest {
    @Test
    fun `file tools accept the grant tool content_uri result`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A%E6%B5%8B%E8%AF%95"
        val args = Json.parseToJsonElement("{\"content_uri\":\"$uri\"}").jsonObject

        assertEquals(uri, contentUriArg(args))
    }

    @Test
    fun `file tools fall back across compatible directory argument names`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A%E6%B5%8B%E8%AF%95"
        val args = Json.parseToJsonElement("{\"path\":\"/sdcard\",\"root\":\"$uri\"}").jsonObject

        assertEquals(uri, contentUriArg(args))
    }

    @Test
    fun `file tools accept every compatible directory uri alias`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A%E6%B5%8B%E8%AF%95"
        listOf("path", "root", "content_uri", "directory_uri", "uri").forEach { key ->
            val args = Json.parseToJsonElement("{\"$key\":\"$uri\"}").jsonObject
            assertEquals(uri, contentUriArg(args))
        }
    }

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
    fun `child names reject traversal and separators`() {
        assertEquals("notes.txt", safeChildName(" notes.txt "))
        assertNull(safeChildName("."))
        assertNull(safeChildName(".."))
        assertNull(safeChildName("folder/file.txt"))
        assertNull(safeChildName("folder\\file.txt"))
        assertNull(safeChildName("bad\u0000name"))
    }

    @Test
    fun `tree root detection protects both SAF root URI forms`() {
        assertTrue(ContentUriResolver.isTreeRootUri(
            "content://com.android.externalstorage.documents/tree/primary%3A%E6%B5%8B%E8%AF%95"
        ))
        assertTrue(ContentUriResolver.isTreeRootUri(
            "content://com.android.externalstorage.documents/tree/primary%3A%E6%B5%8B%E8%AF%95/document/primary%3A%E6%B5%8B%E8%AF%95"
        ))
        assertEquals(false, ContentUriResolver.isTreeRootUri(
            "content://com.android.externalstorage.documents/tree/primary%3A%E6%B5%8B%E8%AF%95/document/primary%3A%E6%B5%8B%E8%AF%95%2Fchild"
        ))
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
