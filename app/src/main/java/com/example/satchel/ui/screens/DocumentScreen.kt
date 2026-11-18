package com.example.satchel.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.container
import com.example.satchel.data.ApiClient
import com.example.satchel.data.DocumentFiles
import com.example.satchel.data.ApiException
import com.example.satchel.data.Document
import com.example.satchel.data.DocumentDetail
import com.example.satchel.data.Status
import com.example.satchel.ui.DocumentRow
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.FileBadge
import com.example.satchel.ui.FileKind
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.OfficialBadge
import com.example.satchel.ui.SectionTitle
import com.example.satchel.ui.StatusBadge
import com.example.satchel.ui.formatDate
import com.example.satchel.ui.formatSize
import com.example.satchel.ui.openExternally
import com.example.satchel.ui.percent
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.launch
import java.io.File

class DocumentViewModel(private val id: Int, private val api: ApiClient, private val files: DocumentFiles) : ViewModel() {
    var detail by mutableStateOf<DocumentDetail?>(null)
        private set
    var error by mutableStateOf<ApiException?>(null)
        private set
    var downloading by mutableStateOf(false)
        private set
    var downloadError by mutableStateOf<String?>(null)

    /** A downloaded file waiting to be handed to a viewer app. */
    var readyToOpen by mutableStateOf<File?>(null)

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                detail = api.document(id)
            } catch (e: ApiException) {
                error = e
            }
        }
    }

    fun open(doc: Document) {
        if (downloading) return
        downloading = true
        downloadError = null
        viewModelScope.launch {
            try {
                readyToOpen = files.fetch(doc)
            } catch (e: ApiException) {
                downloadError = e.message
            } finally {
                downloading = false
            }
        }
    }
}

@Composable
fun DocumentScreen(id: Int, onOpenDocument: (Int) -> Unit, onOpenQuiz: (Int) -> Unit, onView: (Int, String) -> Unit) {
    val vm = screenViewModel { DocumentViewModel(id, it.api, it.files) }
    val context = LocalContext.current
    val container = context.container

    vm.readyToOpen?.let { file ->
        LaunchedEffect(file) {
            vm.readyToOpen = null
            vm.downloadError = openExternally(context, container.files.shareableUri(file), file.name)
        }
    }

    val detail = vm.detail
    val error = vm.error
    when {
        // The API answers 404 both for missing documents and ones this person may not see.
        error?.status == 404 -> EmptyState("This document doesn’t exist or isn’t available to you.")
        error != null -> EmptyState(error.message ?: "Something went wrong.", actionLabel = "Try again", onAction = vm::load)
        detail == null -> Loading()
        else -> DocumentContent(detail, vm, onOpenDocument, onOpenQuiz, onView)
    }
}

@Composable
private fun DocumentContent(
    detail: DocumentDetail,
    vm: DocumentViewModel,
    onOpenDocument: (Int) -> Unit,
    onOpenQuiz: (Int) -> Unit,
    onView: (Int, String) -> Unit,
) {
    val doc = detail.document
    val kind = FileKind.of(doc.originalFilename)
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileBadge(doc.originalFilename)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.title, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OfficialBadge(doc.official)
                        if (doc.status != Status.PUBLISHED) StatusBadge(doc.status)
                    }
                }
            }
        }
        if (doc.status == Status.PENDING) item {
            Notice("Waiting for a moderator to review it. Only you and the course’s moderators can see it.", kind = "warn")
        }
        if (doc.status == Status.REJECTED) item {
            Notice("Rejected" + (doc.reviewNote?.let { ": $it" } ?: "."), kind = "error")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // Read inside Satchel; files it cannot show are opened in another app instead.
                if (kind != FileKind.Other) {
                    Button(onClick = { onView(doc.id, doc.title) }, modifier = Modifier.weight(1f)) {
                        Text("View ${kind.label}")
                    }
                } else {
                    Button(onClick = { vm.open(doc) }, enabled = !vm.downloading, modifier = Modifier.weight(1f)) {
                        Text(if (vm.downloading) "Downloading…" else "Open file")
                    }
                }
                if (doc.status == Status.PUBLISHED) {
                    FilledTonalButton(onClick = { onOpenQuiz(doc.id) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Practice quiz")
                    }
                }
            }
            if (kind != FileKind.Other) {
                TextButton(onClick = { vm.open(doc) }, enabled = !vm.downloading) {
                    if (vm.downloading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading…")
                    } else {
                        Text("Open in another app")
                    }
                }
            }
            vm.downloadError?.let { Notice(it, kind = "error", modifier = Modifier.padding(top = 8.dp)) }
        }
        item { Details(doc) }

        if (detail.duplicateFlags.isNotEmpty()) {
            item {
                SectionTitle("Possible duplicates")
                Notice("This upload looks similar to documents already in the course.", kind = "warn")
            }
            items(detail.duplicateFlags, key = { "flag-${it.matchedDocumentId}" }) { f ->
                OutlinedCard(onClick = { onOpenDocument(f.matchedDocumentId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.matchedTitle, style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (f.exactFileMatch) "Identical file" else "${percent(f.score)} similar",
                                style = MaterialTheme.typography.bodySmall)
                            StatusBadge(f.matchedStatus)
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("Related documents")
            Text(
                "Similar in content, from across the library." + when (detail.copiesHidden) {
                    0 -> ""
                    1 -> " 1 near-identical copy hidden."
                    else -> " ${detail.copiesHidden} near-identical copies hidden."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (detail.related.isEmpty()) item {
            Text("No related documents found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(detail.related, key = { "related-${it.document.id}" }) { r ->
            DocumentRow(r.document, onClick = { onOpenDocument(r.document.id) }, score = r.score, scoreLabel = "similar")
        }
    }
}

@Composable
private fun Details(doc: Document) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Detail("Course", "${doc.course.code} · ${doc.course.name}")
            Detail("Type", doc.documentType.name)
            Detail("Source", if (doc.official) "Official material" else "Community contribution")
            doc.academicYear?.let { Detail("Academic year", it) }
            Detail("Uploaded by", doc.uploader?.fullName ?: "Imported")
            Detail("Uploaded", formatDate(doc.uploadedAt))
            doc.reviewedAt?.let {
                val note = doc.reviewNote?.takeIf { doc.status == Status.PUBLISHED }?.let { n -> " · “$n”" } ?: ""
                Detail("Reviewed", formatDate(it) + note)
            }
            Detail("File", "${doc.originalFilename} · ${formatSize(doc.sizeBytes)}")
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
