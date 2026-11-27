package com.example.satchel.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.satchel.container
import kotlinx.coroutines.delay

/**
 * The bell with its unread count. Asks the server when the app comes to the foreground
 * and every minute while it is open; hidden on servers without notifications.
 */
@Composable
fun Bell(onClick: () -> Unit) {
    val inbox = LocalContext.current.container.inbox
    val unread by inbox.unread.collectAsStateWithLifecycle()
    val available by inbox.available.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                inbox.refresh()
                delay(60_000)
            }
        }
    }
    if (!available) return
    val label = when (unread) {
        0 -> "Notifications"
        1 -> "Notifications, 1 unread"
        else -> "Notifications, $unread unread"
    }
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        BadgedBox(badge = { if (unread > 0) Badge { androidx.compose.material3.Text(if (unread > 99) "99+" else "$unread") } }) {
            Icon(Icons.Filled.Notifications, contentDescription = null)
        }
    }
}
