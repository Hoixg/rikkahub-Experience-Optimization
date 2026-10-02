package me.rerere.rikkahub.ui.components.richtext

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageModelResolverTest {
    @Test
    fun `resolves existing absolute file paths including spaces`() {
        val directory = Files.createTempDirectory("generated image ").toFile()
        val image = File(directory, "generated image.png").apply { writeBytes(byteArrayOf(1)) }

        try {
            val resolved = resolveChatImageModel(image.absolutePath)

            assertEquals(image, resolved)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preserves URI and network image models`() {
        listOf(
            "https://example.com/image.png",
            "http://example.com/image.png",
            "file:///data/user/0/app/files/image.png",
            "content://media/external/images/1",
            "data:image/png;base64,AAAA",
        ).forEach { model ->
            assertEquals(model, resolveChatImageModel(model))
        }
    }

    @Test
    fun `preserves missing absolute paths and relative paths`() {
        val directory = Files.createTempDirectory("missing generated image ").toFile()
        val missingPath = File(directory, "missing image.png").absolutePath
        val relativePath = "generated images/image.png"

        try {
            assertEquals(missingPath, resolveChatImageModel(missingPath))
            assertEquals(relativePath, resolveChatImageModel(relativePath))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `preserves null image model`() {
        assertNull(resolveChatImageModel(null))
    }
}
