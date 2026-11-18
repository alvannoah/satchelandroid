package com.example.satchel.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.example.satchel.container
import com.example.satchel.data.DocumentFiles
import com.example.satchel.ui.LayoutIcons
import java.io.File
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.example.satchel.Prefs
import com.example.satchel.ui.CompactDocumentRow
import com.example.satchel.ui.DocLayout
import com.example.satchel.ui.DocumentTile
import com.example.satchel.ui.LayoutMenu
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.HierarchyCache
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.CourseNode
import com.example.satchel.data.Document
import com.example.satchel.data.Filters
import com.example.satchel.data.Hierarchy
import com.example.satchel.data.SearchResults
import com.example.satchel.ui.DocumentRow
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.plural
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.launch

/** Browsing and search share one screen: with a query, results are ranked by content; without, they are listed. */
/** A bulk action in progress: "Downloading", 3 of 7 done. */
data class BulkProgress(val verb: String, val done: Int, val total: Int)

class LibraryViewModel(
    private val api: ApiClient,
    private val cache: HierarchyCache,
    private val prefs: Prefs,
    private val files: DocumentFiles,
) : ViewModel() {
    // ------------------------------ selection ------------------------------ //

    /** Selected documents by id. Kept across searches and filters, so a selection can span courses. */
    val selection = mutableStateMapOf<Int, Document>()
    val selecting: Boolean get() = selection.isNotEmpty()

    var bulk by mutableStateOf<BulkProgress?>(null)
        private set

    /** A one-off message for the snackbar. */
    var message by mutableStateOf<String?>(null)

    /** Downloaded files waiting for the screen to open the share sheet. */
    var readyToShare by mutableStateOf<List<File>?>(null)

    fun toggle(doc: Document) {
        if (selection.remove(doc.id) == null) selection[doc.id] = doc
    }

    /** Selects everything listed now: the search results, or the documents loaded so far. */
    fun selectAll() {
        val shown = if (query.isNotEmpty()) results?.results.orEmpty().map { it.document } else items
        shown.forEach { selection[it.id] = it }
    }

    val allShownSelected: Boolean
        get() {
            val shown = if (query.isNotEmpty()) results?.results.orEmpty().map { it.document } else items
            return shown.isNotEmpty() && shown.all { it.id in selection }
        }

    fun clearSelection() = selection.clear()

    fun downloadSelected() = runBulk("Downloading", files::saveToDownloads) { saved, failed ->
        message = buildString {
            if (saved.isNotEmpty()) append("Saved ${plural(saved.size, "file")} to Downloads/Satchel.")
            if (failed.isNotEmpty()) append(" Could not save: ${failed.joinToString(", ")}.")
        }.trim()
        if (failed.isEmpty()) clearSelection()
    }

    fun shareSelected() = runBulk("Preparing", files::fetch) { ready, failed ->
        if (failed.isNotEmpty()) message = "Could not download: ${failed.joinToString(", ")}."
        if (ready.isNotEmpty()) readyToShare = ready
    }

    /**
     * Runs [each] on the selected documents one at a time, counting progress, then
     * calls [finish] with the results and the titles of any that failed.
     */
    private fun <T> runBulk(verb: String, each: suspend (Document) -> T, finish: (List<T>, List<String>) -> Unit) {
        if (bulk != null) return
        val docs = selection.values.toList()
        if (docs.isEmpty()) return
        bulk = BulkProgress(verb, 0, docs.size)
        viewModelScope.launch {
            val done = mutableListOf<T>()
            val failed = mutableListOf<String>()
            try {
                docs.forEachIndexed { i, doc ->
                    try {
                        done += each(doc)
                    } catch (e: ApiException) {
                        failed += "“${doc.title}”"
                        if (e.status == 401) return@launch   // logged out: the login screen takes over
                    }
                    bulk = BulkProgress(verb, i + 1, docs.size)
                }
                finish(done, failed)
            } finally {
                bulk = null
            }
        }
    }

    // ------------------------------- listing ------------------------------- //

    var layout by mutableStateOf(DocLayout.from(prefs.libraryLayout))
        private set

    fun changeLayout(new: DocLayout) {
        layout = new
        prefs.libraryLayout = new.name
    }

    var hierarchy by mutableStateOf<Hierarchy?>(null)
        private set
    var filters by mutableStateOf(Filters())
        private set
    var queryText by mutableStateOf("")
    var query by mutableStateOf("")          // the submitted search, empty while browsing
        private set
    var method by mutableStateOf("tfidf")    // "tfidf" (the engine) or "keyword" (the baseline)
        private set

    var items by mutableStateOf<List<Document>>(emptyList())
        private set
    var total by mutableStateOf(0)
        private set
    var results by mutableStateOf<SearchResults?>(null)
        private set

    var loading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var page = 0
    private var generation = 0   // a newer load makes older answers irrelevant

    init {
        load()
    }

    private fun load(refreshHierarchy: Boolean = false) {
        val mine = ++generation
        viewModelScope.launch {
            try {
                val h = cache.get(refreshHierarchy)
                if (query.isNotEmpty()) {
                    val r = api.search(query, filters, method)
                    if (mine != generation) return@launch
                    results = r
                } else {
                    val p = api.documents(filters, 1, PAGE_SIZE)
                    if (mine != generation) return@launch
                    items = p.items
                    total = p.total
                    page = 1
                }
                hierarchy = h
                error = null
            } catch (e: ApiException) {
                if (mine == generation) error = e.message
            } finally {
                if (mine == generation) {
                    loading = false
                    refreshing = false
                }
            }
        }
    }

    fun loadMore() {
        if (query.isNotEmpty() || loading || loadingMore || items.size >= total) return
        val mine = generation
        loadingMore = true
        viewModelScope.launch {
            try {
                val p = api.documents(filters, page + 1, PAGE_SIZE)
                if (mine != generation) return@launch
                items = items + p.items.filter { new -> items.none { it.id == new.id } }
                total = p.total
                page = p.page
            } catch (e: ApiException) {
                if (mine == generation) error = e.message
            } finally {
                loadingMore = false
            }
        }
    }

    private fun reload() {
        loading = true
        load()
    }

    fun search() {
        val q = queryText.trim()
        if (q == query) return
        query = q
        results = null
        reload()
    }

    fun clearSearch() {
        queryText = ""
        if (query.isEmpty()) return
        query = ""
        results = null
        reload()
    }

    fun updateFilters(new: Filters) {
        if (new == filters) return
        filters = new
        reload()
    }

    fun changeMethod(new: String) {
        if (new == method) return
        method = new
        reload()
    }

    fun refresh() {
        refreshing = true
        load(refreshHierarchy = true)
    }

    fun retry() = reload()

    companion object {
        const val PAGE_SIZE = 25
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenDocument: (Int) -> Unit) {
    val vm = screenViewModel { LibraryViewModel(it.api, it.hierarchy, it.prefs, it.files) }
    val focus = LocalFocusManager.current
    val listState = rememberLazyGridState()
    val hierarchy = vm.hierarchy

    // Browsing loads page after page as you scroll; search returns its best matches at once.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, vm.items.size) { if (nearEnd) vm.loadMore() }

    // Back leaves selection mode before it leaves the screen.
    BackHandler(enabled = vm.selecting) { vm.clearSelection() }

    val context = LocalContext.current
    vm.readyToShare?.let { ready ->
        LaunchedEffect(ready) {
            vm.readyToShare = null
            if (shareFiles(context, ready.map(context.container.files::shareableUri))) vm.clearSelection()
            else vm.message = "No app on this phone can receive these files."
        }
    }
    // Shown from the screen's own scope: clearing vm.message would cancel an effect keyed on it.
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        vm.message = null
        scope.launch { snackbar.showSnackbar(text, withDismissAction = true) }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (vm.selecting) SelectionBar(vm) else OutlinedTextField(
            value = vm.queryText,
            onValueChange = { vm.queryText = it },
            placeholder = { Text("Search the library") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (vm.queryText.isNotEmpty() || vm.query.isNotEmpty()) {
                    IconButton(onClick = { vm.clearSearch(); focus.clearFocus() }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        vm.bulk?.let { b ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("${b.verb} ${(b.done + 1).coerceAtMost(b.total)} of ${b.total}…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { b.done / b.total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        if (hierarchy != null) FilterRow(hierarchy, vm.filters, vm::updateFilters)

        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            when {
                vm.loading -> Loading()
                vm.error != null && hierarchy == null -> EmptyState(vm.error!!, actionLabel = "Try again", onAction = vm::retry)
                else -> DocumentGrid(vm, hierarchy, listState, onOpenDocument)
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Replaces the search field while documents are selected. */
@Composable
private fun SelectionBar(vm: LibraryViewModel) {
    val busy = vm.bulk != null
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = vm::clearSelection, enabled = !busy) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
        }
        Text("${vm.selection.size} selected", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
        if (!vm.allShownSelected) TextButton(onClick = vm::selectAll, enabled = !busy) { Text("Select all") }
        IconButton(onClick = vm::shareSelected, enabled = !busy) {
            Icon(Icons.Filled.Share, contentDescription = "Share selected")
        }
        IconButton(onClick = vm::downloadSelected, enabled = !busy) {
            Icon(LayoutIcons.Download, contentDescription = "Save selected to Downloads")
        }
    }
}

/** Opens the share sheet with the files. False if no app can take them. */
private fun shareFiles(context: Context, uris: List<Uri>): Boolean {
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = "*/*"
    // Grant the receiving app read access to every file, not only the first.
    send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context.packageManager.queryIntentActivities(send, 0).isEmpty()) return false
    return try {
        context.startActivity(Intent.createChooser(send, "Share ${plural(uris.size, "document")}"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/**
 * One grid for every layout: in Grid, documents share rows (two on a phone, more
 * on a tablet); in Cards and Compact, and for headings, an item spans the full row.
 */
@Composable
private fun DocumentGrid(
    vm: LibraryViewModel,
    hierarchy: Hierarchy?,
    state: LazyGridState,
    onOpenDocument: (Int) -> Unit,
) {
    val layout = vm.layout
    val full: LazyGridItemSpanScope.(Any?) -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    val docSpan: (LazyGridItemSpanScope.(Any?) -> GridItemSpan)? = if (layout == DocLayout.Grid) null else full
    val wide: LazyGridScope.(@Composable () -> Unit) -> Unit = { content -> item(span = { GridItemSpan(maxLineSpan) }) { content() } }

    // While selecting, a tap selects; otherwise it opens. A long-press always selects.
    @Composable
    fun Doc(doc: Document, score: Double?) {
        val selected = if (vm.selecting) doc.id in vm.selection else null
        val click = { if (vm.selecting) vm.toggle(doc) else onOpenDocument(doc.id) }
        val longClick = { vm.toggle(doc) }
        when (layout) {
            DocLayout.Cards -> DocumentRow(doc, click, score = score, selected = selected, onLongClick = longClick)
            DocLayout.Compact -> CompactDocumentRow(doc, click, score = score, selected = selected, onLongClick = longClick)
            DocLayout.Grid -> DocumentTile(doc, click, score = score, selected = selected, onLongClick = longClick)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        state = state,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (layout == DocLayout.Compact) 0.dp else 10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        vm.error?.let { wide { Notice(it, kind = "error") } }
        if (vm.query.isEmpty() && vm.filters.courseId == null && hierarchy != null) {
            val courses = hierarchy.programmes
                .filter { vm.filters.programmeId == null || it.id == vm.filters.programmeId }
                .flatMap { it.courses }
            if (courses.isNotEmpty()) wide {
                CourseStrip(courses) { vm.updateFilters(vm.filters.copy(courseId = it)) }
            }
        }
        wide { ListHeading(vm, hierarchy) }

        if (vm.query.isNotEmpty()) {
            val r = vm.results
            if (r != null && r.results.isEmpty()) wide {
                EmptyHint("No documents match “${r.query}”. Try different words or fewer filters.")
            }
            items(r?.results.orEmpty(), key = { it.document.id }, span = docSpan) { hit -> Doc(hit.document, hit.score) }
        } else {
            if (vm.items.isEmpty()) wide {
                EmptyHint(if (vm.filters.isEmpty) "Nothing here yet." else "No documents match these filters.")
            }
            items(vm.items, key = { it.id }, span = docSpan) { doc -> Doc(doc, null) }
            if (vm.loadingMore) wide {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun ListHeading(vm: LibraryViewModel, hierarchy: Hierarchy?) {
    val course = hierarchy?.courses?.find { it.id == vm.filters.courseId }
    val r = vm.results
    Column(Modifier.padding(bottom = if (vm.layout == DocLayout.Compact) 4.dp else 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    vm.query.isNotEmpty() -> "Search results"
                    course != null -> course.name
                    else -> "All documents"
                },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            LayoutMenu(vm.layout, vm::changeLayout)
        }
        val subtitle = if (vm.query.isNotEmpty()) {
            r?.let {
                plural(it.results.size, "result") + " for “${it.query}”" +
                    if (it.matchedTerms.isNotEmpty()) " · matched ${it.matchedTerms.joinToString(", ")}" else ""
            }
        } else {
            (course?.let { "${it.code} · Year ${it.yearOfStudy}, semester ${it.semester} · " } ?: "") +
                plural(vm.total, "document")
        }
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vm.query.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(selected = vm.method == "tfidf", onClick = { vm.changeMethod("tfidf") },
                    label = { Text("Ranked by content") })
                FilterChip(selected = vm.method == "keyword", onClick = { vm.changeMethod("keyword") },
                    label = { Text("Keyword count") })
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 32.dp),
    )
}

@Composable
private fun CourseStrip(courses: List<CourseNode>, onPick: (Int) -> Unit) {
    Column {
        Text("Browse by course", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(courses, key = { it.id }) { c ->
                Card(onClick = { onPick(c.id) }, modifier = Modifier.width(180.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.code, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(40.dp))
                        Text(
                            "Year ${c.yearOfStudy} · Sem ${c.semester} · ${plural(c.publishedDocuments, "doc")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Filters as chips; a chip is highlighted while it narrows the results. */
@Composable
private fun FilterRow(h: Hierarchy, f: Filters, onChange: (Filters) -> Unit) {
    val courses = h.programmes.filter { f.programmeId == null || it.id == f.programmeId }.flatMap { it.courses }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (h.programmes.size > 1) {
            ChipMenu("Programme", f.programmeId, h.programmes.map { it.id to it.name }) {
                onChange(f.copy(programmeId = it, courseId = null))
            }
        }
        ChipMenu("Course", f.courseId, courses.map { it.id to "${it.code} · ${it.name}" }) { onChange(f.copy(courseId = it)) }
        ChipMenu("Type", f.documentTypeId, h.documentTypes.map { it.id to it.name }) { onChange(f.copy(documentTypeId = it)) }
        ChipMenu("Source", f.official, listOf(true to "Official", false to "Community")) { onChange(f.copy(official = it)) }
        ChipMenu("Year", f.yearOfStudy, (1..4).map { it to "Year $it" }) { onChange(f.copy(yearOfStudy = it)) }
        ChipMenu("Semester", f.semester, (1..3).map { it to "Semester $it" }) { onChange(f.copy(semester = it)) }
        if (h.academicYears.isNotEmpty()) {
            ChipMenu("Academic year", f.academicYear, h.academicYears.map { it to it }) { onChange(f.copy(academicYear = it)) }
        }
        if (!f.isEmpty) TextButton(onClick = { onChange(Filters()) }) { Text("Clear") }
    }
}

@Composable
private fun <T> ChipMenu(label: String, selected: T?, options: List<Pair<T, String>>, onSelect: (T?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = {
                Text(options.firstOrNull { it.first == selected }?.second ?: label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 0.dp))
            },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Any ${label.lowercase()}") }, onClick = { open = false; onSelect(null) })
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}
