package com.example.satchel.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The bell's unread count, shared by the bell and the notifications screen. The
 * server is asked when the app comes to the foreground, every minute while it is
 * open, when a push arrives, and after reading (docs/ANDROID_APP.md, "Notifications").
 */
class Inbox(private val api: ApiClient, private val scope: CoroutineScope) {
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    /** False until the server has answered once: servers without notifications show no bell. */
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    fun refresh() {
        scope.launch {
            try {
                _unread.value = api.unreadCount()
                _available.value = true
            } catch (e: ApiException) {
                if (e.status == 404) _available.value = false   // an older server; offline keeps what it had
            }
        }
    }

    /** After the screen has loaded the list, or marked some read. */
    fun set(unread: Int) {
        _unread.value = unread
        _available.value = true
    }

    fun clear() {
        _unread.value = 0
        _available.value = false
    }
}
