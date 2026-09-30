package me.rerere.rikkahub.data.ai.image

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/** Final history files are independent of the managed copy attached to a chat. */
class GeneratedImageStorage(
    private val directory: () -> File,
    private val insertHistory: suspend (GenMediaEntity) -> Unit,
) {
    suspend fun save(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File {
        require(!item.partial) { "Image previews cannot be saved to history" }
        currentCoroutineContext().ensureActive()
        val extension = imageFileExtension(item.mimeType)
        val file = File(directory().apply { mkdirs() }, "${Uuid.random()}.$extension")
        try {
            file.writeBytes(Base64.decode(item.data.substringAfter("base64,", item.data)))
            currentCoroutineContext().ensureActive()
            // Finish the short file/database commit together even if generation is stopped.
            withContext(NonCancellable) {
                insertHistory(GenMediaEntity(
                    path = "images/${file.name}",
                    prompt = prompt,
                    modelId = modelName,
                    createAt = System.currentTimeMillis(),
                    type = type,
                    sourcePaths = sourcePaths,
                ))
            }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
}

internal fun imageFileExtension(mimeType: String): String = when (mimeType.substringBefore(';').lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    else -> "png"
}
