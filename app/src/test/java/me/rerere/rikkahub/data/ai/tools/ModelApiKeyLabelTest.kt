package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.provider.ApiKeyInfo
import me.rerere.rikkahub.ui.pages.setting.formatModelApiKeyLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelApiKeyLabelTest {
    @Test
    fun `shows only key name and multiplier`() {
        assertEquals("Production · x1", formatModelApiKeyLabel(ApiKeyInfo(key = "secret", name = "Production", multiplier = 1f)))
        assertEquals("Backup · x1.5", formatModelApiKeyLabel(ApiKeyInfo(key = "another-secret", name = "Backup", multiplier = 1.5f)))
    }

    @Test
    fun `uses a fallback name when metadata name is blank`() {
        assertEquals("Key · x2", formatModelApiKeyLabel(ApiKeyInfo(key = "secret", name = " ", multiplier = 2f)))
    }
}
