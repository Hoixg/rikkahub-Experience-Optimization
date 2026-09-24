package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

private data class GrantedFolderRow(
    val uri: String,
    val displayName: String,
    val displayPath: String,
)

@Composable
fun GrantedFoldersPage(
    grantStore: StorageVolumeGrantStore = koinInject(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var folders by remember { mutableStateOf<List<GrantedFolderRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var pendingRevoke by remember { mutableStateOf<GrantedFolderRow?>(null) }

    val refresh: () -> Unit = {
        scope.launch {
            loading = true
            loadFailed = false
            runCatching {
                withContext(Dispatchers.IO) {
                    val storedGrants = grantStore.reconcile().associateBy { it.contentUri }
                    context.contentResolver.persistedUriPermissions
                        .filter { DocumentsContract.isTreeUri(it.uri) }
                        .distinctBy { it.uri.toString() }
                        .map { permission ->
                            val uri = permission.uri
                            val storedName = storedGrants[uri.toString()]?.displayName
                            val documentName = runCatching {
                                DocumentFile.fromTreeUri(context, uri)?.name
                            }.getOrNull()
                            val name = storedName?.takeIf { it.isNotBlank() }
                                ?: documentName?.takeIf { it.isNotBlank() }
                                ?: uri.lastPathSegment
                                ?: uri.toString()
                            GrantedFolderRow(
                                uri = uri.toString(),
                                displayName = name,
                                displayPath = localStoragePath(context, uri) ?: uri.toString(),
                            )
                        }
                        .sortedBy { it.displayName.lowercase() }
                }
            }.onSuccess {
                folders = it
            }.onFailure {
                loadFailed = true
            }
            loading = false
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        context.contentResolver.takePersistableUriPermission(uri, flags)
                        val name = DocumentFile.fromTreeUri(context, uri)?.name
                            ?.takeIf { it.isNotBlank() }
                            ?: uri.lastPathSegment
                            ?: uri.toString()
                        grantStore.add(
                            StorageVolumeGrantStore.Grant(
                                contentUri = uri.toString(),
                                displayName = name,
                                authority = uri.authority ?: "unknown",
                            )
                        )
                    }
                }
                if (result.isFailure) {
                    toaster.show(
                        message = context.getString(R.string.assistant_page_local_tools_granted_folders_add_failed),
                        type = ToastType.Error,
                    )
                }
                refresh()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_page_local_tools_granted_folders_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = { treePicker.launch(null) }) {
                        Text(stringResource(R.string.assistant_page_local_tools_granted_folders_add))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading && folders.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }

            loadFailed && folders.isEmpty() -> BoxMessage(
                text = stringResource(R.string.assistant_page_local_tools_granted_folders_load_failed),
                padding = innerPadding,
            )

            folders.isEmpty() -> BoxMessage(
                text = stringResource(R.string.assistant_page_local_tools_granted_folders_empty),
                padding = innerPadding,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    end = 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(folders, key = { it.uri }) { folder ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CustomColors.cardColorsOnSurfaceContainer,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    folder.displayName,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                IconButton(onClick = { pendingRevoke = folder }) {
                                    Icon(
                                        imageVector = HugeIcons.Delete01,
                                        contentDescription = stringResource(R.string.assistant_page_local_tools_granted_folders_revoke),
                                    )
                                }
                            }
                            Text(
                                text = folder.displayPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                overflow = TextOverflow.Clip,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingRevoke?.let { folder ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text(stringResource(R.string.assistant_page_local_tools_granted_folders_revoke_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.assistant_page_local_tools_granted_folders_revoke_message,
                        folder.displayName,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRevoke = null
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val uri = Uri.parse(folder.uri)
                                    val permission = context.contentResolver.persistedUriPermissions
                                        .firstOrNull { it.uri == uri }
                                    if (permission != null) {
                                        var flags = 0
                                        if (permission.isReadPermission) {
                                            flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        }
                                        if (permission.isWritePermission) {
                                            flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        }
                                        if (flags != 0) {
                                            context.contentResolver.releasePersistableUriPermission(uri, flags)
                                        }
                                    }
                                    grantStore.remove(folder.uri)
                                }
                            }
                            if (result.isFailure) {
                                toaster.show(
                                    message = context.getString(R.string.assistant_page_local_tools_granted_folders_revoke_failed),
                                    type = ToastType.Error,
                                )
                            }
                            refresh()
                        }
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_local_tools_granted_folders_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun BoxMessage(text: String, padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun localStoragePath(context: Context, uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val separator = documentId.indexOf(':')
    if (separator <= 0) return null

    val volumeId = documentId.substring(0, separator)
    val relativePath = documentId.substring(separator + 1).trim('/')
    val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return null
        storageManager.storageVolumes.firstOrNull { volume ->
            if (volume.isPrimary) volumeId.equals("primary", ignoreCase = true)
            else volume.uuid?.equals(volumeId, ignoreCase = true) == true
        }?.directory?.absolutePath
    } else if (volumeId.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0"
    } else {
        null
    } ?: return null

    return if (relativePath.isEmpty()) root else "$root/$relativePath"
}
