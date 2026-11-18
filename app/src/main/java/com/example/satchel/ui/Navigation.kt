package com.example.satchel.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import java.util.UUID

/** The app's screens. */
sealed interface Route {
    data object Library : Route
    data class Document(val id: Int) : Route
    data class Quiz(val documentId: Int) : Route
    data class Viewer(val documentId: Int, val title: String) : Route
    data object Upload : Route
    data object MyUploads : Route
    data object Account : Route
}

enum class Tab(val label: String, val icon: ImageVector, val root: Route) {
    Library("Library", Icons.Filled.Home, Route.Library),
    Upload("Upload", Icons.Filled.AddCircle, Route.Upload),
    MyUploads("My uploads", Icons.AutoMirrored.Filled.List, Route.MyUploads),
    Account("Account", Icons.Filled.AccountCircle, Route.Account),
}

/** One screen on a back stack, with its own ViewModels, cleared when it is popped. */
class Entry(val route: Route) : ViewModelStoreOwner {
    val key: String = UUID.randomUUID().toString()
    override val viewModelStore = ViewModelStore()
}

/**
 * A back stack per tab, like most Android apps: switching tabs keeps your place
 * in each one, and Back walks down the current tab, then returns to the Library.
 * Kept in a ViewModel so it survives rotation.
 */
class Navigator : ViewModel() {
    private val stacks = Tab.entries.associateWith { mutableStateListOf(Entry(it.root)) }

    var tab by mutableStateOf(Tab.Library)
        private set

    val current: Entry get() = stacks.getValue(tab).last()

    /** Told when an entry is gone for good, so its saved UI state can be dropped too. */
    var onDiscard: (Entry) -> Unit = {}

    fun select(target: Tab) {
        if (target == tab) popToRoot() else tab = target
    }

    fun push(route: Route) {
        stacks.getValue(tab).add(Entry(route))
    }

    /** False when there is nowhere to go back to, and the app should close. */
    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.size > 1) {
            discard(stack.removeAt(stack.lastIndex))
            return true
        }
        if (tab != Tab.Library) {
            tab = Tab.Library
            return true
        }
        return false
    }

    /** After logging out: nothing from the last person's session survives. */
    fun reset() {
        stacks.forEach { (t, stack) ->
            stack.forEach(::discard)
            stack.clear()
            stack.add(Entry(t.root))
        }
        tab = Tab.Library
    }

    private fun popToRoot() {
        val stack = stacks.getValue(tab)
        while (stack.size > 1) discard(stack.removeAt(stack.lastIndex))
    }

    private fun discard(entry: Entry) {
        entry.viewModelStore.clear()
        onDiscard(entry)
    }

    override fun onCleared() {
        stacks.values.flatten().forEach { it.viewModelStore.clear() }
    }
}
