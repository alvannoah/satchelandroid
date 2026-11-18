package com.example.satchel.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reading progress: the page on screen is sent to the server a moment after the
 * reader stops turning pages. Pages read offline are kept, per account, and sent
 * when the server can be reached again (docs/ANDROID_APP.md). The last page read
 * on this phone is kept too, so a downloaded document reopens there offline.
 */
class ReadingSync(context: Context, private val api: ApiClient, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("satchel.reading", Context.MODE_PRIVATE)
    private val sending = mutableMapOf<Int, Job>()
    private val flushing = Mutex()

    /** The signed-in account's id, set by AppContainer. */
    var owner: () -> Int? = { null }

    private fun key(kind: String, documentId: Int): String? = owner()?.let { "$kind:$it:$documentId" }

    /** The last page read on this phone, if any. */
    fun localPage(documentId: Int): Int? = key("last", documentId)?.let { prefs.getInt(it, 0) }?.takeIf { it > 0 }

    /** A page read but not yet sent: it is newer than what the server knows. */
    fun pendingPage(documentId: Int): Int? = key("pending", documentId)?.let { prefs.getInt(it, 0) }?.takeIf { it > 0 }

    /** Called as pages turn: remembers the page, and sends it after a short pause. */
    fun record(documentId: Int, page: Int) {
        val last = key("last", documentId) ?: return
        val pending = key("pending", documentId) ?: return
        prefs.edit().putInt(last, page).putInt(pending, page).apply()
        sending.remove(documentId)?.cancel()
        sending[documentId] = scope.launch {
            delay(800)   // swiping through pages sends only where the reader stops
            send(documentId)
            sending.remove(documentId)
        }
    }

    /** For documents without a page copy: remember the page here only (the server cannot track it). */
    fun rememberLocally(documentId: Int, page: Int) {
        key("last", documentId)?.let { prefs.edit().putInt(it, page).apply() }
    }

    /** Sends every page read offline. Called when the app starts and when a document opens. */
    fun flush() {
        val uid = owner() ?: return
        scope.launch {
            flushing.withLock {
                prefs.all.keys.filter { it.startsWith("pending:$uid:") }
                    .mapNotNull { it.substringAfterLast(':').toIntOrNull() }
                    .forEach { send(it) }
            }
        }
    }

    private suspend fun send(documentId: Int) {
        val pending = key("pending", documentId) ?: return
        val page = prefs.getInt(pending, 0).takeIf { it > 0 } ?: return
        try {
            api.saveProgress(documentId, page)
            // Only clear it if no newer page arrived while sending.
            if (prefs.getInt(pending, 0) == page) prefs.edit().remove(pending).apply()
        } catch (e: ApiException) {
            // Offline (0) or a server error: keep it for later. Anything else (the
            // document is gone, or has no page copy) will never succeed: drop it.
            if (e.status != 0 && e.status < 500) prefs.edit().remove(pending).apply()
        }
    }
}
