package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resumeWithException

internal object GitHubReleaseSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val versionPattern = Regex("""\d+(?:\.\d+)+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?""")

    fun updates(client: OkHttpClient, userAgent: String): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        emit(UiState.Success(fetchLatest(client, userAgent)))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(UiState.Error(error))
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchLatest(client: OkHttpClient, userAgent: String): UpdateInfo {
        val request = Request.Builder()
            .url(AppBranding.LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", userAgent)
            .build()
        return awaitUpdate(client.newCall(request))
    }

    private fun parseResponse(response: Response): UpdateInfo {
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch update info (HTTP ${response.code})")
        }
        val release = json.decodeFromString<GitHubRelease>(response.body.string())
        require(!release.draft && !release.prerelease) { "No stable release available" }
        val version = release.tag.removePrefix("v")
        require(versionPattern.matches(version)) { "Invalid release version" }
        Instant.parse(release.publishedAt)
        val downloads = release.assets
            .filter { asset ->
                asset.state == "uploaded" && asset.name.endsWith(".apk", ignoreCase = true) &&
                    asset.size > 0 && asset.url.toHttpUrlOrNull()?.isHttps == true
            }
            .map { asset ->
                UpdateDownload(
                    name = asset.name,
                    url = asset.url,
                    size = String.format(Locale.ROOT, "%.1f MiB", asset.size / 1_048_576.0),
                )
            }
        require(downloads.isNotEmpty()) { "No downloadable APK in the latest release" }
        return UpdateInfo(version, release.publishedAt, release.body.orEmpty(), downloads)
    }

    private suspend fun awaitUpdate(call: Call): UpdateInfo = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val update = response.use(::parseResponse)
                    continuation.resumeWith(Result.success(update))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("published_at") val publishedAt: String,
    val body: String? = null,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<GitHubReleaseAsset>,
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val state: String,
)
