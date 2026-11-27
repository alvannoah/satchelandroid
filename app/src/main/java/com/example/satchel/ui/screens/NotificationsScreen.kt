package com.example.satchel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.AppNotification
import com.example.satchel.data.Inbox
import com.example.satchel.ui.EmptyState
import com.example.satchel.ui.Loading
import com.example.satchel.ui.Notice
import com.example.satchel.ui.formatAgo
import com.example.satchel.ui.screenViewModel
import com.example.satchel.ui.theme.Satchel
import kotlinx.coroutines.launch

class NotificationsViewModel(private val api: ApiClient, private val inbox: Inbox) : ViewModel() {
    var items by mutableStateOf<List<AppNotification>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init {
        load()
    }

    fun load() {
        refreshing = items != null
        viewModelScope.launch {
            try {
                val list = api.notifications()
                items = list.items
                inbox.set(list.unread)
                error = null
            } catch (e: ApiException) {
                error = e.message
            } finally {
                refreshing = false
            }
        }
    }

    /** Opening one marks it read, here at once and on the server in the background. */
    fun open(n: AppNotification) {
        if (n.read) return
        items = items?.map { if (it.id == n.id) it.copy(read = true) else it }
        inbox.set((inbox.unread.value - 1).coerceAtLeast(0))
        viewModelScope.launch { runCatching { api.markNotificationRead(n.id) } }
    }

    fun markAllRead() {
        val before = items
        items = items?.map { it.copy(read = true) }
        inbox.set(0)
        viewModelScope.launch {
            try {
                api.markAllNotificationsRead()
            } catch (e: ApiException) {
                items = before
                error = e.message
                inbox.refresh()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onOpenDocument: (Int) -> Unit) {
    val vm = screenViewModel { NotificationsViewModel(it.api, it.inbox) }
    val items = vm.items

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = vm::load, modifier = Modifier.fillMaxSize()) {
        when {
            items == null && vm.error != null -> EmptyState(vm.error!!, actionLabel = "Try again", onAction = vm::load)
            items == null -> Loading()
            items.isEmpty() -> EmptyState(
                "No notifications yet. You’ll hear here when documents are added to your courses, " +
                    "and when your uploads are reviewed.",
            )
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        val unread = items.count { !it.read }
                        Text(
                            if (unread == 0) "All caught up" else "$unread unread",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (unread > 0) TextButton(onClick = vm::markAllRead) { Text("Mark all as read") }
                    }
                    vm.error?.let { Notice(it, kind = "error", modifier = Modifier.padding(16.dp)) }
                }
                items(items, key = { it.id }) { n ->
                    NotificationRow(n) {
                        vm.open(n)
                        n.documentId?.let(onOpenDocument)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: AppNotification, onClick: () -> Unit) {
    val c = Satchel.colors
    val (label, color) = when (n.kind) {
        "upload_approved" -> "Approved" to c.ok
        "upload_rejected" -> "Rejected" to MaterialTheme.colorScheme.error
        "upload_waiting" -> "Waiting for review" to c.warn
        else -> "New document" to MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (n.read) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable(onClickLabel = "Open", role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // An unread dot, keeping rows aligned when read.
        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape)
            .background(if (n.read) Color.Transparent else MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = color, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)))
                Text(" · ${formatAgo(n.createdAt)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(n.title, style = MaterialTheme.typography.titleSmall,
                fontWeight = if (n.read) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            n.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
