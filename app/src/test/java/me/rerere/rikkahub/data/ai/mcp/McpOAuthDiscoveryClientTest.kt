package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpOAuthDiscoveryClientTest {
    @Test
    fun `fallback issuer uses origin without credentials path query or fragment`() {
        assertEquals(
            "https://mcp.example.test",
            McpOAuthDiscoveryClient.serverOrigin("https://user:password@mcp.example.test:443/mcp?session=1#part"),
        )
        assertEquals(
            "http://localhost:8080",
            McpOAuthDiscoveryClient.serverOrigin("http://localhost:8080/mcp"),
        )
        assertNull(McpOAuthDiscoveryClient.serverOrigin("not a URL"))
    }

    @Test
    fun `root resource omits trailing slash and fragment`() {
        assertEquals(
            "https://mcp.example.test",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.test/#part"),
        )
    }

    @Test
    fun `resource keeps meaningful path and query while removing fragment`() {
        assertEquals(
            "https://mcp.example.test/mcp/",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.test/mcp/#part"),
        )
        assertEquals(
            "https://mcp.example.test/?session=1",
            McpOAuthDiscoveryClient.canonicalResource("https://mcp.example.test/?session=1#part"),
        )
    }

    @Test
    fun `metadata document capability is optional and parsed from server response`() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(
            json.decodeFromString<McpOAuthDiscoveryClient.AuthorizationServerMetadata>("{}").clientIdMetadataDocumentSupported,
        )
        assertTrue(
            json.decodeFromString<McpOAuthDiscoveryClient.AuthorizationServerMetadata>(
                """{"client_id_metadata_document_supported":true,"unrecognized_field":"ignored"}""",
            ).clientIdMetadataDocumentSupported,
        )
    }
}
