package me.rerere.rikkahub.data.ai.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeneratedImageStorageTest {
    @get:Rule val folder = TemporaryFolder()
    private val item = ImageGenerationItem("aW1hZ2U=", "image/webp")

    @Test fun `final image history uses mime extension and persists approved prompt`() = runBlocking {
        val records = mutableListOf<GenMediaEntity>()
        val images = folder.newFolder("images")
        val storage = GeneratedImageStorage({ images }, { records += it })
        val file = storage.save(item, "Approved prompt", "Model / name", GenMediaEntity.TYPE_IMAGE_EDIT, "file:///reference.png")
        assertEquals("webp", file.extension)
        assertEquals("image", file.readText())
        assertEquals("Approved prompt", records.single().prompt)
        assertEquals("images/${file.name}", records.single().path)
        assertEquals(GenMediaEntity.TYPE_IMAGE_EDIT, records.single().type)
        assertEquals("file:///reference.png", records.single().sourcePaths)
    }

    @Test fun `failed or cancelled history saves remove incomplete files`() = runBlocking {
        val images = folder.newFolder("images")
        val failing = GeneratedImageStorage({ images }, { error("Database failed") })
        assertTrue(runCatching { failing.save(item, "Prompt", "Model") }.isFailure)
        assertEquals(0, images.listFiles()!!.size)
        val cancelled = GeneratedImageStorage({ images }, { throw CancellationException("Stopped") })
        assertTrue(runCatching { cancelled.save(item, "Prompt", "Model") }.exceptionOrNull() is CancellationException)
        assertEquals(0, images.listFiles()!!.size)
    }

    @Test fun `previews never enter image history`() = runBlocking {
        var inserts = 0
        val images = folder.newFolder("images")
        val storage = GeneratedImageStorage({ images }, { inserts++ })
        assertTrue(runCatching { storage.save(item.copy(partial = true), "Prompt", "Model") }.isFailure)
        assertEquals(0, inserts)
        assertEquals(0, images.listFiles()!!.size)
    }

    @Test fun `history and chat attachment bytes have independent lifetimes`() = runBlocking {
        val images = folder.newFolder("images")
        val storage = GeneratedImageStorage({ images }, {})
        val history = storage.save(item, "Prompt", "Model")
        val chat = folder.newFile("chat.webp").apply { writeBytes(history.readBytes()) }
        assertTrue(history.delete())
        assertEquals("image", chat.readText())
        val secondHistory = storage.save(item, "Prompt", "Model")
        assertTrue(chat.delete())
        assertEquals("image", secondHistory.readText())
    }

    @Test fun `custom fields cannot override approved parameters but remain unchanged for instant generation`() {
        val fields = listOf("prompt", "model", "n", "size", "image", "quality").map { CustomBody(it, JsonPrimitive("custom")) }
        assertEquals(listOf("quality"), imageRequestCustomBody(fields, true).map { it.key })
        assertEquals(fields, imageRequestCustomBody(fields, false))
    }

    @Test fun `stopping during history commit leaves a complete record and file together`() = runBlocking {
        val images = folder.newFolder("images")
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val records = mutableListOf<GenMediaEntity>()
        val storage = GeneratedImageStorage({ images }, {
            started.complete(Unit)
            finish.await()
            records += it
        })
        val job = launch { storage.save(item, "Prompt", "Model") }
        started.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertEquals(1, records.size)
        assertEquals(1, images.listFiles()!!.size)
        assertEquals("image", images.listFiles()!!.single().readText())
    }
}
