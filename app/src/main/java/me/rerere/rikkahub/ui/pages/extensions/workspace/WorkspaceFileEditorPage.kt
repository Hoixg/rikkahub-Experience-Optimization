package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.rerere.document.DocxParser
import me.rerere.document.PptxParser
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownImageResolver
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import kotlin.math.roundToInt

/**
 * 工作区文本文件编辑/预览页.
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }
    val extension = fileName.substringAfterLast('.', "").lowercase()
    val isMarkdown = extension in setOf("md", "markdown")
    val isDocument = extension in setOf("pdf", "ppt", "pptx", "doc", "docx")
    val supportsWebPreview = extension in setOf("html", "htm", "svg")

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var pdfFilePath by remember(id, area, path) { mutableStateOf<String?>(null) }
    var pdfPageCount by remember(id, area, path) { mutableStateOf(0) }
    var pdfZoom by rememberSaveable(id, area, path) { mutableStateOf(1f) }
    var showPdfPageDialog by rememberSaveable(id, area, path) { mutableStateOf(false) }
    var pdfPageInput by rememberSaveable(id, area, path) { mutableStateOf("1") }
    var pdfSelectedPage by remember { mutableStateOf<Int?>(null) }
    val pdfListState = rememberLazyListState()
    val pdfHorizontalScrollState = rememberScrollState()
    val pdfRenderCoordinator = remember(pdfFilePath) {
        pdfFilePath?.let(::PdfRenderCoordinator)
    }
    DisposableEffect(pdfRenderCoordinator) {
        onDispose { pdfRenderCoordinator?.close() }
    }
    val currentPdfPage by remember {
        derivedStateOf { (pdfListState.firstVisibleItemIndex + 1).coerceAtLeast(1) }
    }
    var markdownPreview by rememberSaveable(id, area, path) { mutableStateOf(true) }
    var webPreview by rememberSaveable(id, area, path) { mutableStateOf(supportsWebPreview) }
    val imageResolver: MarkdownImageResolver = remember(id, area, path, repository) {
        { source ->
            when (val resolved = resolveWorkspaceMarkdownImagePath(path, source)) {
                null -> null
                is WorkspaceMarkdownImagePath.Network -> resolved.url
                is WorkspaceMarkdownImagePath.Local -> runCatching {
                    repository.resolvePreviewFile(id, area, resolved.path).absolutePath
                }.getOrNull()
            }
        }
    }

    LaunchedEffect(id, area, path) {
        loading = true
        loadError = null
        if (extension == "pdf") {
            runCatching {
                val file = repository.resolvePreviewFile(id, area, path)
                val pageCount = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val document = PDFDocument.openDocument(file.absolutePath).asPDF()
                    try {
                        document.countPages()
                    } finally {
                        document.destroy()
                    }
                }
                file.absolutePath to pageCount
            }.onSuccess { (filePath, pageCount) ->
                pdfFilePath = filePath
                pdfPageCount = pageCount
                loading = false
            }.onFailure {
                loadError = it.message ?: context.getString(R.string.workspace_file_read_failed)
                loading = false
            }
            return@LaunchedEffect
        }

        runCatching {
            if (isDocument) {
                val file = repository.resolvePreviewFile(id, area, path)
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    when (extension) {
                        "pptx" -> PptxParser.parse(file)
                        "docx" -> DocxParser.parse(file)
                        else -> "暂不支持此格式的应用内内容解析，请使用系统查看器打开。"
                    }
                }
            } else {
                repository.readTextForPreview(id, area, path)
            }
        }.onSuccess { content ->
            textState.setTextAndPlaceCursorAtEnd(content)
            loading = false
        }.onFailure {
            loadError = it.message ?: context.getString(R.string.workspace_file_read_failed)
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (supportsWebPreview && !loading && loadError == null) {
                        TextButton(onClick = { webPreview = !webPreview }) {
                            Text(
                                stringResource(
                                    if (webPreview) R.string.workspace_file_source else R.string.workspace_file_preview
                                )
                            )
                        }
                    }
                    if (editable && !isDocument && !loading && loadError == null) {
                        TextButton(
                            onClick = {
                                if (saving) return@TextButton
                                saving = true
                                scope.launch {
                                    runCatching {
                                        repository.writeText(
                                            id = id,
                                            path = path,
                                            text = textState.text.toString(),
                                            overwrite = true,
                                        )
                                    }.onSuccess {
                                        toaster.show(
                                            context.getString(R.string.workspace_file_saved),
                                            type = ToastType.Success,
                                        )
                                    }.onFailure {
                                        toaster.show(
                                            it.message ?: context.getString(R.string.workspace_file_save_failed),
                                            type = ToastType.Error,
                                        )
                                    }
                                    saving = false
                                }
                            },
                            enabled = !saving,
                        ) {
                            Text(stringResource(R.string.common_save))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            supportsWebPreview && webPreview -> WorkspaceWebPreview(
                content = textState.text.toString(),
                isSvg = extension == "svg",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (extension == "pdf") {
                    PdfPreviewControls(
                        zoom = pdfZoom,
                        currentPage = currentPdfPage,
                        pageCount = pdfPageCount,
                        onZoomOut = { pdfZoom = (pdfZoom - 0.25f).coerceAtLeast(0.5f) },
                        onZoomIn = { pdfZoom = (pdfZoom + 0.25f).coerceAtMost(3f) },
                        onFitWidth = { pdfZoom = 1f },
                        onPageClick = {
                            pdfPageInput = currentPdfPage.toString()
                            showPdfPageDialog = true
                        },
                    )
                    PdfPageList(
                        filePath = pdfFilePath,
                        pageCount = pdfPageCount,
                        zoom = pdfZoom,
                        renderCoordinator = pdfRenderCoordinator,
                        listState = pdfListState,
                        horizontalScrollState = pdfHorizontalScrollState,
                        onPageClick = { pdfSelectedPage = it },
                        modifier = Modifier.weight(1f),
                    )
                } else if (isMarkdown && !isDocument) {
                    val modes = listOf(
                        true to stringResource(R.string.workspace_file_preview),
                        false to stringResource(R.string.workspace_file_source),
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        modes.forEachIndexed { index, (preview, label) ->
                            SegmentedButton(
                                selected = markdownPreview == preview,
                                onClick = { markdownPreview = preview },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            ) {
                                Text(label)
                            }
                        }
                    }
                }

                if (extension != "pdf" && ((isMarkdown && markdownPreview) || isDocument)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SelectionContainer {
                            MarkdownBlock(
                                content = textState.text.toString(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                imageResolver = imageResolver,
                            )
                        }
                    }
                } else if (extension != "pdf") {
                    TextField(
                        state = textState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .imePadding(),
                        readOnly = !editable,
                        lineLimits = TextFieldLineLimits.MultiLine(),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = JetbrainsMono,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                    )
                }
            }
        }
    }

    if (showPdfPageDialog) {
        AlertDialog(
            onDismissRequest = { showPdfPageDialog = false },
            title = { Text(stringResource(R.string.workspace_pdf_jump_to_page)) },
            text = {
                OutlinedTextField(
                    value = pdfPageInput,
                    onValueChange = { pdfPageInput = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.workspace_pdf_page_number)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val page = pdfPageInput.toIntOrNull()?.coerceIn(1, pdfPageCount)
                        if (page != null && pdfPageCount > 0) {
                            showPdfPageDialog = false
                            scope.launch { pdfListState.animateScrollToItem(page - 1) }
                        }
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPdfPageDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    pdfSelectedPage?.let { pageIndex ->
        PdfPageZoomDialog(
            filePath = pdfFilePath,
            pageIndex = pageIndex,
            renderCoordinator = pdfRenderCoordinator,
            onDismissRequest = { pdfSelectedPage = null },
        )
    }
}

@Composable
private fun PdfPreviewControls(
    zoom: Float,
    currentPage: Int,
    pageCount: Int,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFitWidth: () -> Unit,
    onPageClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onZoomOut, enabled = zoom > 0.5f) { Text("-") }
        TextButton(onClick = onFitWidth) { Text((zoom * 100).roundToInt().toString() + "%") }
        TextButton(onClick = onZoomIn, enabled = zoom < 3f) { Text("+") }
        TextButton(onClick = onPageClick, enabled = pageCount > 0) {
            Text(currentPage.toString() + " / " + pageCount.toString())
        }
    }
}

@Composable
private fun PdfPageList(
    filePath: String?,
    pageCount: Int,
    zoom: Float,
    renderCoordinator: PdfRenderCoordinator?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    horizontalScrollState: androidx.compose.foundation.ScrollState,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
    ) {
        val pageWidth = maxWidth * zoom
        val targetWidth = with(LocalDensity.current) {
            pageWidth.roundToPx().coerceIn(480, 2400)
        }
        LaunchedEffect(renderCoordinator, targetWidth) {
            renderCoordinator?.discardOtherWidths(targetWidth)
        }
        LaunchedEffect(renderCoordinator, listState, pageCount, targetWidth) {
            if (renderCoordinator == null) return@LaunchedEffect
            snapshotFlow {
                val visible = listState.layoutInfo.visibleItemsInfo
                if (visible.isEmpty()) null else visible.first().index to visible.last().index
            }
                .distinctUntilChanged()
                .collectLatest { visibleRange ->
                    if (visibleRange == null || pageCount == 0) return@collectLatest
                    val first = (visibleRange.first - 1).coerceAtLeast(0)
                    val last = (visibleRange.second + 1).coerceAtMost(pageCount - 1)
                    coroutineScope {
                        (first..last).map { pageIndex ->
                            launch { renderCoordinator.prefetch(pageIndex, targetWidth) }
                        }.joinAll()
                    }
                }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(horizontalScrollState),
            contentAlignment = Alignment.TopStart,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(pageWidth)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(pageCount, key = { it }) { index ->
                    PdfLazyPage(
                        filePath = filePath,
                        pageIndex = index,
                        targetWidth = targetWidth,
                        renderCoordinator = renderCoordinator,
                        onClick = { onPageClick(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfLazyPage(
    filePath: String?,
    pageIndex: Int,
    targetWidth: Int,
    renderCoordinator: PdfRenderCoordinator?,
    onClick: () -> Unit,
) {
    var lease by remember(filePath, pageIndex, targetWidth, renderCoordinator) {
        mutableStateOf<PdfBitmapLease?>(null)
    }
    var error by remember(filePath, pageIndex, targetWidth) { mutableStateOf<String?>(null) }
    LaunchedEffect(filePath, pageIndex, targetWidth, renderCoordinator) {
        lease = null
        error = null
        if (filePath == null || renderCoordinator == null) return@LaunchedEffect
        try {
            val newLease = renderCoordinator.acquire(pageIndex, targetWidth)
            try {
                currentCoroutineContext().ensureActive()
                lease = newLease
            } catch (cancelled: CancellationException) {
                newLease.release()
                throw cancelled
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message
        }
    }
    DisposableEffect(lease) {
        onDispose { lease?.release() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = lease != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            lease != null -> Image(
                bitmap = lease!!.bitmap.asImageBitmap(),
                contentDescription = "PDF page " + (pageIndex + 1),
                modifier = Modifier.fillMaxWidth(),
            )
            error != null -> Text(error ?: "")
            else -> CircularProgressIndicator(modifier = Modifier.padding(32.dp))
        }
    }
}

private fun renderPdfPage(filePath: String, pageIndex: Int, targetWidth: Int): Bitmap {
    val document = PDFDocument.openDocument(filePath).asPDF()
    try {
        val page = document.loadPage(pageIndex)
        try {
            return AndroidDrawDevice.drawPageFitWidth(page, targetWidth)
        } finally {
            page.destroy()
        }
    } finally {
        document.destroy()
    }
}

@Composable
private fun PdfPageZoomDialog(
    filePath: String?,
    pageIndex: Int,
    renderCoordinator: PdfRenderCoordinator?,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    var lease by remember(filePath, pageIndex, renderCoordinator) {
        mutableStateOf<PdfBitmapLease?>(null)
    }
    var error by remember(filePath, pageIndex) { mutableStateOf<String?>(null) }
    val pagerState = rememberZoomablePagerState { 1 }
    val targetWidth = (context.resources.displayMetrics.widthPixels * 2f)
        .toInt()
        .coerceIn(720, 3000)
    LaunchedEffect(filePath, pageIndex, targetWidth, renderCoordinator) {
        lease = null
        error = null
        if (filePath == null || renderCoordinator == null) return@LaunchedEffect
        try {
            val newLease = renderCoordinator.acquire(pageIndex, targetWidth)
            try {
                currentCoroutineContext().ensureActive()
                lease = newLease
            } catch (cancelled: CancellationException) {
                newLease.release()
                throw cancelled
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.message
        }
    }
    DisposableEffect(lease) {
        onDispose { lease?.release() }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (lease == null && error == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (lease != null) {
                ImagePager(
                    modifier = Modifier.fillMaxSize(),
                    pagerState = pagerState,
                    imageLoader = {
                        val painter = BitmapPainter(lease!!.bitmap.asImageBitmap())
                        Pair(painter, painter.intrinsicSize)
                    },
                )
            } else {
                Text(
                    text = error ?: context.getString(R.string.workspace_file_read_failed),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(
                onClick = onDismissRequest,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}

private data class PdfPageKey(
    val pageIndex: Int,
    val targetWidth: Int,
)

private class CachedPdfBitmap(
    val bitmap: Bitmap,
    var references: Int,
) {
    val bytes: Long
        get() = bitmap.allocationByteCount.toLong()
}

private class PdfBitmapLease(
    private val coordinator: PdfRenderCoordinator,
    private val key: PdfPageKey,
    val bitmap: Bitmap,
) {
    private var released = false

    fun release() {
        if (released) return
        released = true
        coordinator.release(key)
    }
}

/** Coordinates page decoding for one PDF and keeps only a small, referenced-safe cache. */
private class PdfRenderCoordinator(
    private val filePath: String,
) {
    private companion object {
        const val MAX_CACHE_ENTRIES = 4
        const val MAX_CACHE_BYTES = 48L * 1024L * 1024L
        const val MAX_RENDER_WIDTH = 2400
        const val MAX_CONCURRENT_RENDERS = 2
    }

    private val semaphore = Semaphore(MAX_CONCURRENT_RENDERS)
    private val cache = LinkedHashMap<PdfPageKey, CachedPdfBitmap>(
        MAX_CACHE_ENTRIES,
        0.75f,
        true,
    )
    private var cacheBytes = 0L
    private var closed = false

    suspend fun acquire(pageIndex: Int, requestedWidth: Int): PdfBitmapLease {
        val key = PdfPageKey(pageIndex, requestedWidth.coerceIn(480, MAX_RENDER_WIDTH))
        synchronized(this) {
            check(!closed) { "PDF renderer is closed" }
            cache[key]?.let {
                it.references++
                return PdfBitmapLease(this, key, it.bitmap)
            }
        }

        var rendered: Bitmap? = null
        try {
            rendered = semaphore.withPermit {
                withContext(Dispatchers.IO) {
                    renderPdfPage(filePath, pageIndex, key.targetWidth)
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                check(!closed) { "PDF renderer is closed" }
                val cached = cache[key]
                if (cached != null) {
                    cached.references++
                    rendered.recycle()
                    rendered = null
                    return PdfBitmapLease(this, key, cached.bitmap)
                }
                val bitmap = rendered
                cache[key] = CachedPdfBitmap(bitmap, references = 1)
                cacheBytes += bitmap.allocationByteCount.toLong()
                rendered = null
                trimCacheLocked().forEach { it.recycle() }
                return PdfBitmapLease(this, key, bitmap)
            }
        } finally {
            rendered?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    suspend fun prefetch(pageIndex: Int, targetWidth: Int) {
        try {
            acquire(pageIndex, targetWidth).release()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Prefetch must never surface a background page error in the preview.
        }
    }

    fun discardOtherWidths(keepWidth: Int) {
        val normalizedWidth = keepWidth.coerceIn(480, MAX_RENDER_WIDTH)
        val toRecycle = synchronized(this) {
            val keys = cache.entries
                .filter { it.key.targetWidth != normalizedWidth && it.value.references == 0 }
                .map { it.key }
            keys.mapNotNull { key -> cache.remove(key)?.also { cacheBytes -= it.bytes }?.bitmap }
        }
        toRecycle.forEach { it.recycle() }
    }

    fun release(key: PdfPageKey) {
        val toRecycle = synchronized(this) {
            val entry = cache[key]
            if (entry == null) {
                emptyList()
            } else {
                entry.references = (entry.references - 1).coerceAtLeast(0)
                if (closed && entry.references == 0) {
                    cache.remove(key)
                    cacheBytes -= entry.bytes
                    listOf(entry.bitmap)
                } else {
                    trimCacheLocked()
                }
            }
        }
        toRecycle.forEach { it.recycle() }
    }

    fun close() {
        val toRecycle = synchronized(this) {
            closed = true
            val keys = cache.entries.filter { it.value.references == 0 }.map { it.key }
            keys.mapNotNull { key -> cache.remove(key)?.also { cacheBytes -= it.bytes }?.bitmap }
        }
        toRecycle.forEach { it.recycle() }
    }

    private fun trimCacheLocked(): List<Bitmap> {
        val toRecycle = mutableListOf<Bitmap>()
        val iterator = cache.entries.iterator()
        while (
            (cache.size > MAX_CACHE_ENTRIES || cacheBytes > MAX_CACHE_BYTES) &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            if (entry.value.references == 0) {
                iterator.remove()
                cacheBytes -= entry.value.bytes
                toRecycle += entry.value.bitmap
            }
        }
        return toRecycle
    }
}

@Composable
private fun WorkspaceWebPreview(
    content: String,
    isSvg: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = rememberWebViewState(
        data = content,
        baseUrl = "https://workspace-preview.invalid/",
        mimeType = if (isSvg) "image/svg+xml" else "text/html",
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )
    WebView(state = state, modifier = modifier)
}
