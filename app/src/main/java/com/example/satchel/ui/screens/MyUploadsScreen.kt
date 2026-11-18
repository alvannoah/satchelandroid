package com.example.satchel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.Document
import com.example.satchel.data.Status
import com.example.satchel.ui.DocumentRow
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.launch

class MyUploadsViewModel(private val api: ApiClient, var seenKey: Int) : ViewModel() {
    var items by mutableStateOf<List<Document>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        refreshing = items != null
        viewModelScope.launch {
            try {
                items = api.myUploads()
                error = null
            } catch (e: ApiException) {
                error = e.message
            } finally {
                refreshing = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyUploadsScreen(refreshKey: Int, onOpenDocument: (Int) -> Unit) {
    val vm = screenViewModel { MyUploadsViewModel(it.api, refreshKey) }
    // A new upload elsewhere bumps refreshKey.
    LaunchedEffect(refreshKey) {
        if (refreshKey != vm.seenKey) {
            vm.seenKey = refreshKey
            vm.refresh()
        }
    }

    val items = vm.items
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        when {
            items == null && vm.error != null -> EmptyState(vm.error!!, actionLabel = "Try again", onAction = vm::refresh)
            items == null -> Loading()
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Column {
                        Text("My uploads", style = MaterialTheme.typography.headlineSmall)
                        Text("Newest first, with each one’s review status.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                vm.error?.let { item { Notice(it, kind = "error") } }
                if (items.isEmpty()) item {
                    Text("You haven’t uploaded anything yet.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp))
                }
                items(items, key = { it.id }) { doc ->
                    Column {
                        DocumentRow(doc, onClick = { onOpenDocument(doc.id) }, showStatus = true)
                        if (doc.status == Status.REJECTED && doc.reviewNote != null) {
                            Text("Moderator: “${doc.reviewNote}”", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
