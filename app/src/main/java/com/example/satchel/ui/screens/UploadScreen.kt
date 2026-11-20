package com.example.satchel.ui.screens

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.HierarchyCache
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.Hierarchy
import com.example.satchel.data.UploadResponse
import com.example.satchel.data.UploadSource
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.FileBadge
import com.example.satchel.ui.FileKind
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.SectionTitle
import com.example.satchel.ui.formatSize
import com.example.satchel.ui.isAcademicYear
import com.example.satchel.ui.mimeTypeFor
import com.example.satchel.ui.percent
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.launch

private const val MAX_MB = 25
private val ACCEPTED_TYPES = arrayOf(
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

class UploadViewModel(private val api: ApiClient, private val cache: HierarchyCache) : ViewModel() {
    var hierarchy by mutableStateOf<Hierarchy?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    var file by mutableStateOf<UploadSource?>(null)
        private set
    var title by mutableStateOf("")
    var courseId by mutableStateOf<Int?>(null)
    var typeId by mutableStateOf<Int?>(null)
    var academicYear by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var result by mutableStateOf<UploadResponse?>(null)
        private set

    /** Called after a successful upload, so other screens can refresh. */
    var onUploaded: () -> Unit = {}

    init {
        loadHierarchy()
    }

    fun loadHierarchy() {
        loadError = null
        viewModelScope.launch {
            try {
                hierarchy = cache.get()
            } catch (e: ApiException) {
                loadError = e.message
            }
        }
    }

    fun pick(source: UploadSource?) {
        error = null
        if (source == null) {
            file = null
            return
        }
        if (FileKind.of(source.name) == FileKind.Other) {
            error = "Only PDF, DOCX and PPTX files can be uploaded."
            return
        }
        file = source
        if (title.isBlank()) title = source.name.substringBeforeLast('.').replace(Regex("[_-]+"), " ").trim()
    }

    fun submit() {
        val source = file
        error = when {
            source == null -> "Choose a file to upload."
            source.size > MAX_MB * 1024L * 1024L -> "Files can be at most $MAX_MB MB."
            title.isBlank() -> "Give the document a title."
            courseId == null -> "Choose a course."
            typeId == null -> "Choose a document type."
            academicYear.isNotBlank() && !isAcademicYear(academicYear.trim()) -> "Academic year looks like 2025/2026."
            else -> null
        }
        if (error != null || source == null) return
        busy = true
        viewModelScope.launch {
            try {
                result = api.upload(source, title.trim(), courseId!!, typeId!!, academicYear.trim().ifEmpty { null })
                cache.clear()   // course document counts have changed
                onUploaded()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun reset() {
        result = null
        file = null
        title = ""
        error = null
    }
}

@Composable
fun UploadScreen(isStudent: Boolean, onOpenDocument: (Int) -> Unit, onUploaded: () -> Unit) {
    val vm = screenViewModel { UploadViewModel(it.api, it.hierarchy) }
    vm.onUploaded = onUploaded
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pick(describe(resolver, uri))
    }

    val h = vm.hierarchy
    val result = vm.result
    when {
        result != null -> UploadResult(result, onOpenDocument, vm::reset)
        vm.loadError != null -> EmptyState(vm.loadError!!, actionLabel = "Try again", onAction = vm::loadHierarchy)
        h == null -> Loading()
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New upload", style = MaterialTheme.typography.headlineSmall)
            Text("Each upload is checked against its course for duplicates before it is saved.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val file = vm.file
            if (file == null) {
                OutlinedButton(onClick = { picker.launch(ACCEPTED_TYPES) }, modifier = Modifier.fillMaxWidth().height(96.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("Choose a file")
                        Text("PDF, Word (.docx) or PowerPoint (.pptx), up to $MAX_MB MB",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileBadge(file.name)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall)
                            Text(formatSize(file.size), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.pick(null) }, enabled = !vm.busy) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove file")
                        }
                    }
                }
            }

            OutlinedTextField(value = vm.title, onValueChange = { vm.title = it.take(255) }, label = { Text("Title") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Picker(
                label = "Course",
                selected = vm.courseId,
                // The API refuses student uploads outside their own courses, so only offer those.
                options = h.programmes.flatMap { p ->
                    p.courses.filter { !isStudent || !h.hasOwnCourses || h.isMine(it.id) }
                        .map { c -> c.id to (if (h.programmes.size > 1) "${p.name}: " else "") + "${c.code} · ${c.name}" }
                },
                onSelect = { vm.courseId = it },
            )
            Picker("Document type", vm.typeId, h.documentTypes.map { it.id to it.name }) { vm.typeId = it }
            OutlinedTextField(
                value = vm.academicYear, onValueChange = { vm.academicYear = it.take(9) },
                label = { Text("Academic year (optional)") }, placeholder = { Text("2025/2026") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            vm.error?.let { Notice(it, kind = "error") }
            Button(onClick = vm::submit, enabled = !vm.busy && file != null, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Uploading and checking…")
                } else Text("Upload")
            }
        }
    }
}

@Composable
private fun UploadResult(result: UploadResponse, onOpenDocument: (Int) -> Unit, onAnother: () -> Unit) {
    val doc = result.document
    val check = result.duplicateCheck
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (result.published) "Published" else "Uploaded: waiting for review", style = MaterialTheme.typography.headlineSmall)
        Notice(
            if (result.published) "“${doc.title}” is now in the library."
            else "“${doc.title}” will appear once a moderator approves it. You can follow its status under My uploads.",
            kind = if (result.published) "ok" else "warn",
        )
        if (check.matches.isEmpty()) {
            Text("No close matches found in this course.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            SectionTitle("Similar documents already in this course")
            Notice(
                (if (check.status == "POTENTIAL DUPLICATE") "This may be a duplicate of an existing document."
                else "Related documents were found.") + " Moderators will see these matches too.",
                kind = "warn",
            )
            check.matches.forEach { m ->
                OutlinedCard(onClick = { onOpenDocument(m.documentId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            (if (m.exactFileMatch) "Identical file" else "${percent(m.score)} similar") + " · " + m.label.lowercase(),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onOpenDocument(doc.id) }) { Text("View document") }
            OutlinedButton(onClick = onAnother) { Text("Upload another") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, selected: Int?, options: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Choose…") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(id); open = false })
            }
        }
    }
}

/** The chosen file's name and size, and a way to read it when the upload is sent. */
private fun describe(resolver: ContentResolver, uri: Uri): UploadSource? {
    var name = "upload"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size < 0) return null
    return UploadSource(name, size, mimeTypeFor(name)) {
        resolver.openInputStream(uri) ?: throw java.io.IOException("Could not read the file")
    }
}
