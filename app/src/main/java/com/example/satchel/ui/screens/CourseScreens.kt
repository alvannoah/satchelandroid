package com.example.satchel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.AppContainer
import com.example.satchel.data.ApiException
import com.example.satchel.data.Course
import com.example.satchel.data.Hierarchy
import com.example.satchel.data.MyCourses
import com.example.satchel.data.User
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.SectionTitle
import com.example.satchel.ui.plural
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.launch

/** After the student's courses change: fresh user, fresh course list, and lists that depend on them reload. */
private suspend fun AppContainer.coursesUpdated() {
    session.refreshUser()
    hierarchy.clear()
    coursesChanged.value++
}

// --------------------------------- setup ---------------------------------- //

/**
 * "What do you study?": the student picks programme(s) and a year, and is enrolled
 * in every course of those programmes in that year (POST /me/courses/setup).
 * Shown before anything else on first sign-in, and from My courses to start again.
 */
class CourseSetupViewModel(private val container: AppContainer) : ViewModel() {
    var hierarchy by mutableStateOf<Hierarchy?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    val programmes = mutableStateListOf<Int>()
    var year by mutableStateOf<Int?>(null)
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        loadError = null
        viewModelScope.launch {
            try {
                val h = container.hierarchy.get()
                hierarchy = h
                if (h.programmes.size == 1) programmes.add(h.programmes.single().id)   // nothing to choose
            } catch (e: ApiException) {
                loadError = e.message
            }
        }
    }

    fun toggle(programmeId: Int) {
        if (!programmes.remove(programmeId)) programmes.add(programmeId)
    }

    /** The courses the choice gives, for the preview. */
    fun preview(): List<Course> {
        val h = hierarchy ?: return emptyList()
        val y = year ?: return emptyList()
        return h.programmes.filter { it.id in programmes }.flatMap { p ->
            p.courses.filter { it.yearOfStudy == y }
                .map { Course(it.id, p.id, it.code, it.name, it.yearOfStudy, it.semester) }
        }
    }

    fun save(done: () -> Unit) {
        val y = year ?: return
        if (programmes.isEmpty()) return
        saving = true
        error = null
        viewModelScope.launch {
            try {
                container.api.setUpCourses(programmes.toList(), y)
                container.coursesUpdated()
                done()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseSetupScreen(user: User, again: Boolean, onDone: () -> Unit, onCancel: () -> Unit) {
    val vm = screenViewModel { CourseSetupViewModel(it) }
    if (vm.year == null && user.yearOfStudy != null) vm.year = user.yearOfStudy
    val h = vm.hierarchy

    when {
        vm.loadError != null -> EmptyState(vm.loadError!!, actionLabel = "Try again", onAction = vm::load)
        h == null -> Loading()
        else -> Column(
            Modifier
                .fillMaxSize()
                .then(if (again) Modifier else Modifier.safeDrawingPadding())
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!again) {
                    Text(h.institution ?: "Satchel", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (again) "Choose your courses again" else "Welcome, ${user.fullName.substringBefore(' ')}",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "Tell us what you study, and your library will show your courses first. " +
                        "You can still browse every course at any time.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (h.programmes.size > 1) {
                    Column {
                        SectionTitle("Your programmes")
                        h.programmes.forEach { p ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(p.id in vm.programmes, role = Role.Checkbox) { vm.toggle(p.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = p.id in vm.programmes, onCheckedChange = null)
                                Spacer(Modifier.size(12.dp))
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                Column {
                    SectionTitle("Your year of study")
                    val years = h.courses.map { it.yearOfStudy }.distinct().sorted()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        years.forEach { y ->
                            FilterChip(selected = vm.year == y, onClick = { vm.year = y }, label = { Text("Year $y") })
                        }
                    }
                }

                if (vm.year != null && vm.programmes.isNotEmpty()) {
                    val preview = vm.preview()
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(plural(preview.size, "course"), style = MaterialTheme.typography.titleSmall)
                            if (preview.isEmpty()) {
                                Text("No courses are set up for that year yet. You can pick courses yourself afterwards.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            preview.forEach { c ->
                                Text("${c.code} · ${c.name}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                vm.error?.let { Notice(it, kind = "error") }
                Button(
                    onClick = { vm.save(onDone) },
                    enabled = !vm.saving && vm.year != null && vm.programmes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (vm.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("Continue")
                }
                // On first sign-in nothing else is reachable, so offer a way out.
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (again) "Cancel" else "Sign out")
                }
            }
        }
    }
}

// ------------------------------- my courses -------------------------------- //

class MyCoursesViewModel(private val container: AppContainer) : ViewModel() {
    var mine by mutableStateOf<MyCourses?>(null)
        private set
    var hierarchy by mutableStateOf<Hierarchy?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The ticked course ids while adding or removing; null when not editing. */
    var editing by mutableStateOf<Set<Int>?>(null)
    var saving by mutableStateOf(false)
        private set

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            try {
                hierarchy = container.hierarchy.get()
                mine = container.api.myCourses()
            } catch (e: ApiException) {
                error = e.message
            }
        }
    }

    fun toggle(id: Int) {
        val now = editing ?: return
        editing = if (id in now) now - id else now + id
    }

    fun save() {
        val ids = editing ?: return
        saving = true
        error = null
        viewModelScope.launch {
            try {
                mine = container.api.chooseCourses(ids)
                editing = null
                container.coursesUpdated()
                hierarchy = container.hierarchy.get()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }
}

/** "Year 1 · Semester 2" groups, in order. */
private fun bySemester(courses: List<Course>): List<Pair<String, List<Course>>> =
    courses.sortedWith(compareBy({ it.yearOfStudy }, { it.semester }, { it.code }))
        .groupBy { "Year ${it.yearOfStudy} · Semester ${it.semester}" }.toList()

@Composable
fun MyCoursesScreen(onStartAgain: () -> Unit) {
    val vm = screenViewModel { MyCoursesViewModel(it) }
    val mine = vm.mine
    val h = vm.hierarchy
    val editing = vm.editing

    when {
        mine == null && vm.error != null -> EmptyState(vm.error!!, actionLabel = "Try again", onAction = vm::load)
        mine == null || h == null -> Loading()
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The library, search and related documents show these first. Every other course stays one tap away.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    vm.error?.let { Notice(it, kind = "error") }
                    if (editing == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.editing = mine.courses.map { it.id }.toSet() }) { Text("Add or remove") }
                            OutlinedButton(onClick = onStartAgain) { Text("Start again") }
                        }
                    } else {
                        Text("Tick the courses you take, then save. ${editing.size} selected.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::save, enabled = !vm.saving) { Text(if (vm.saving) "Saving…" else "Save") }
                            OutlinedButton(onClick = { vm.editing = null }, enabled = !vm.saving) { Text("Cancel") }
                        }
                        if (editing.isEmpty()) {
                            Notice("With no courses, the library shows every course.", kind = "warn")
                        }
                    }
                }
            }

            if (editing != null) {
                // Every course, by programme and semester, with a tick box each.
                h.programmes.forEach { p ->
                    item(key = "programme-${p.id}") {
                        SectionTitle("${p.name} (${p.courses.count { it.id in editing }} of ${p.courses.size})")
                    }
                    bySemester(p.courses.map { Course(it.id, p.id, it.code, it.name, it.yearOfStudy, it.semester) })
                        .forEach { (label, list) ->
                            item(key = "group-${p.id}-$label") {
                                Text(label, style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(list, key = { "pick-${it.id}" }) { c ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .toggleable(c.id in editing, role = Role.Checkbox) { vm.toggle(c.id) }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = c.id in editing, onCheckedChange = null)
                                    Spacer(Modifier.size(12.dp))
                                    Text("${c.code} · ${c.name}", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    item(key = "divider-${p.id}") { HorizontalDivider() }
                }
            } else if (mine.courses.isEmpty()) {
                item {
                    Text("You haven’t chosen any courses, so the library shows every course.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                }
            } else {
                val counts = h.courses.associate { it.id to it.publishedDocuments }
                bySemester(mine.courses).forEach { (label, list) ->
                    item(key = "mine-$label") { SectionTitle(label) }
                    items(list, key = { "mine-${it.id}" }) { c ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(c.code, style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(c.name, style = MaterialTheme.typography.titleSmall)
                                Text(plural(counts[c.id] ?: 0, "document"), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
