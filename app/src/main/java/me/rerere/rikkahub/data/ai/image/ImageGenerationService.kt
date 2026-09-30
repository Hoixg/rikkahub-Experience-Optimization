package me.rerere.rikkahub.data.ai.image

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.common.http.await
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ImageToolRequest
import me.rerere.rikkahub.data.ai.tools.local.validateImageToolTarget
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findRequestProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

class ImageGenerationService(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    private val client: OkHttpClient,
) {
    private val storage = GeneratedImageStorage(filesManager::getImagesDir, genMediaRepository::insertMedia)

    fun generate(
        settings: Settings,
        modelId: Uuid,
        prompt: String,
        count: Int,
        size: String,
        references: List<String> = emptyList(),
        protectApprovedParameters: Boolean = false,
    ): Flow<ImageGenerationItem> = flow {
        val model = settings.findModelById(modelId)
            ?: error(context.getString(R.string.chat_image_generation_model_missing))
        val provider = model.findRequestProvider(settings.providers)
            ?: error(context.getString(R.string.chat_image_generation_model_missing))
        require(provider.enabled) { context.getString(R.string.chat_image_generation_model_missing) }
        require(provider is ProviderSetting.OpenAI) { context.getString(R.string.chat_image_generation_unsupported) }
        val temporaryFiles = mutableListOf<File>()
        try {
            val paths = references.map { materializeReference(it, temporaryFiles).absolutePath }
            val implementation = providerManager.getProviderByType(provider)
            emitAll(requestProviderImages(implementation, provider, model, prompt, count, size, paths, protectApprovedParameters))
        } finally {
            temporaryFiles.forEach { it.delete() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun saveToHistory(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File = withContext(Dispatchers.IO) {
        storage.save(item, prompt, modelName, type, sourcePaths)
    }

    suspend fun generateForChat(settings: Settings, request: ImageToolRequest): List<UIMessagePart> {
        val images = mutableListOf<UIMessagePart.Image>()
        try {
            return withContext(Dispatchers.IO) {
                validateImageToolTarget(request, settings)
                var failure: String? = null
                try {
                    generate(
                        settings, request.target.modelId, request.prompt, request.count, request.size,
                        request.references.map { it.url }, protectApprovedParameters = true,
                    ).collect { item ->
                        if (!item.partial) {
                            val historyFile = saveToHistory(
                                item, request.prompt, request.target.modelName,
                                if (request.references.isEmpty()) GenMediaEntity.TYPE_IMAGE_GENERATION else GenMediaEntity.TYPE_IMAGE_EDIT,
                                request.references.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.url },
                            )
                            // A separate managed attachment preserves each view's existing delete rules.
                            withContext(NonCancellable) {
                                val attachment = filesManager.saveManagedFromBytes(
                                    folder = me.rerere.rikkahub.data.files.FileFolders.UPLOAD,
                                    bytes = historyFile.readBytes(),
                                    displayName = historyFile.name,
                                    mimeType = item.mimeType,
                                )
                                images += UIMessagePart.Image(filesManager.getFile(attachment).toUri().toString())
                            }
                        }
                    }
                    if (images.isEmpty()) failure = context.getString(R.string.chat_image_generation_no_result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e.message ?: context.getString(R.string.chat_image_generation_failed)
                }
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", failure == null)
                    put("image_count", images.size)
                    put("prompt", request.prompt)
                    put("model", request.target.modelName)
                    failure?.let { put("error", it) }
                }.toString())) + images
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                filesManager.deleteChatFiles(images.map { it.url.toUri() })
            }
            throw e
        }
    }

    private suspend fun materializeReference(url: String, temporaryFiles: MutableList<File>): File {
        currentCoroutineContext().ensureActive()
        if (url.startsWith("file:") || url.startsWith("/")) {
            return File(if (url.startsWith("file:")) requireNotNull(url.toUri().path) else url).also {
                require(it.isFile) { context.getString(R.string.chat_image_generation_reference_missing) }
            }
        }
        val mimeType = when {
            url.startsWith("data:image/") -> url.substringAfter("data:").substringBefore(';')
            url.startsWith("content:") -> context.contentResolver.getType(url.toUri()).orEmpty()
            else -> "image/png"
        }
        val file = File(context.appTempFolder, "image_reference_${Uuid.random()}.${imageFileExtension(mimeType)}")
        temporaryFiles += file
        when {
            url.startsWith("data:image/") -> file.writeBytes(Base64.decode(url.substringAfter("base64,")))
            url.startsWith("content:") -> {
                val input = context.contentResolver.openInputStream(url.toUri())
                    ?: error(context.getString(R.string.chat_image_generation_reference_missing))
                input.use { source -> file.outputStream().use { source.copyTo(it) } }
            }
            url.startsWith("https://") || url.startsWith("http://") -> {
                client.newCall(Request.Builder().url(url).build()).await().use { response ->
                    check(response.isSuccessful) { "Reference image download failed: ${response.code}" }
                    response.body.byteStream().use { source -> file.outputStream().use { source.copyTo(it) } }
                }
            }
            else -> error(context.getString(R.string.chat_image_generation_reference_missing))
        }
        return file
    }
}

internal fun imageRequestCustomBody(fields: List<CustomBody>, protectApprovedParameters: Boolean): List<CustomBody> =
    if (protectApprovedParameters) fields.filterNot {
        it.key in setOf("model", "prompt", "n", "size", "image", "image[]")
    } else fields

internal suspend fun requestProviderImages(
    implementation: Provider<*>,
    provider: ProviderSetting,
    model: Model,
    prompt: String,
    count: Int,
    size: String,
    references: List<String>,
    protectApprovedParameters: Boolean,
): Flow<ImageGenerationItem> {
    val customBody = imageRequestCustomBody(model.customBodies, protectApprovedParameters)
    return if (references.isEmpty()) {
        implementation.generateImage(provider, ImageGenerationParams(
            model = model, prompt = prompt, numOfImages = count, size = size,
            customHeaders = model.customHeaders, customBody = customBody,
        ))
    } else {
        implementation.editImage(provider, ImageEditParams(
            model = model, prompt = prompt, images = references, numOfImages = count, size = size,
            customHeaders = model.customHeaders, customBody = customBody,
        ))
    }
}
