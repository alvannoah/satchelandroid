package com.example.satchel.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.satchel.container
import com.example.satchel.data.ApiException
import com.example.satchel.data.Download
import com.example.satchel.data.DocumentFiles
import com.example.satchel.ui.DocumentRow
import com.example.satchel.ui.LayoutIcons
import com.example.satchel.ui.SelectionBar
import com.example.satchel.ui.formatSize
import com.example.satchel.ui.plural
import com.example.satchel.ui.screenViewModel
import com.example.satchel.ui.shareFiles
import kotlinx.coroutines.launch

class DownloadsViewModel(private val files: DocumentFiles) : ViewModel() {
    val downloads = files.downloads

    /** Selected downloads by document id. */
    val selection = mutableStateMapOf<Int, Download>()
    val selecting: Boolean get() = selection.isNotEmpty()

    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch { files.loadDownloads() }
    }

    fun toggle(d: Download) {
        if (selection.remove(d.document.id) == null) selection[d.document.id] = d
    }

    fun selectAll() = downloads.value.forEach { selection[it.document.id] = it }

    fun clearSelection() = selection.clear()

    fun removeSelected() {
        val ids = selection.keys.toList()
        busy = true
        viewModelScope.launch {
            files.removeDownloads(ids)
            message = "Removed ${plural(ids.size, "download")}."
            clearSelection()
            busy = false
        }
    }

    /** Copies the selected files into the phone's Downloads/Satchel folder, for other apps. */
    fun saveSelectedToPhone() {
        val docs = selection.values.map { it.document }
        busy = true
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            docs.forEach { doc ->
                try {
                    files.saveToDownloads(doc)
                } catch (e: ApiException) {
                    failed += "“${doc.title}”"
                }
            }
            message = if (failed.isEmpty()) "Saved ${plural(docs.size, "file")} to the phone’s Downloads/Satchel folder."
            else "Could not save ${failed.joinToString(", ")}."
            if (failed.isEmpty()) clearSelection()
            busy = false
        }
    }
}

@Composable
fun DownloadsScreen(onView: (Int, String) -> Unit) {
    val vm = screenViewModel { DownloadsViewModel(it.files) }
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }

    BackHandler(enabled = vm.selecting) { vm.clearSelection() }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        vm.message = null
        scope.launch { snackbar.showSnackbar(text, withDismissAction = true) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (vm.selecting) {
                SelectionBar(
                    count = vm.selection.size,
                    busy = vm.busy,
                    onClose = vm::clearSelection,
                    onSelectAll = if (vm.selection.size < downloads.size) vm::selectAll else null,
                ) {
                    IconButton(onClick = {
                        val uris = vm.selection.values.map { context.container.files.shareableUri(it.file) }
                        if (shareFiles(context, uris)) vm.clearSelection()
                        else vm.message = "No app on this phone can receive these files."
                    }, enabled = !vm.busy) {
                        Icon(Icons.Filled.Share, contentDescription = "Share selected")
                    }
                    IconButton(onClick = vm::saveSelectedToPhone, enabled = !vm.busy) {
                        Icon(LayoutIcons.Download, contentDescription = "Save selected to the phone’s Downloads folder")
                    }
                    IconButton(onClick = { confirmRemove = true }, enabled = !vm.busy) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove selected downloads")
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!vm.selecting) item {
                    Column {
                        Text("Downloads", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (downloads.isEmpty()) "Documents you download can be read here without internet."
                            else "${plural(downloads.size, "document")} · " +
                                "${formatSize(downloads.sumOf { it.file.length() })} on this phone · readable offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (downloads.isEmpty()) item {
                    Text(
                        "Nothing downloaded yet. Use Download on a document’s page, or long-press documents " +
                            "in the Library to download several at once.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
                items(downloads, key = { it.document.id }) { d ->
                    val selected = if (vm.selecting) d.document.id in vm.selection else null
                    DocumentRow(
                        d.document,
                        onClick = { if (vm.selecting) vm.toggle(d) else onView(d.document.id, d.document.title) },
                        selected = selected,
                        onLongClick = { vm.toggle(d) },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (confirmRemove) {
        val n = vm.selection.size
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${plural(n, "download")}?") },
            text = { Text("They will no longer be readable offline. You can download them again from the Library.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; vm.removeSelected() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
