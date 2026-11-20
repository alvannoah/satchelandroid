package com.example.satchel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.example.satchel.container
import com.example.satchel.data.AuthState
import com.example.satchel.data.User
import com.example.satchel.ui.screens.AccountScreen
import com.example.satchel.ui.screens.DocumentScreen
import com.example.satchel.ui.screens.CourseSetupScreen
import com.example.satchel.ui.screens.MyCoursesScreen
import com.example.satchel.ui.screens.DownloadsScreen
import com.example.satchel.ui.screens.LibraryScreen
import com.example.satchel.ui.screens.LoginScreen
import com.example.satchel.ui.screens.MyUploadsScreen
import com.example.satchel.ui.screens.QuizScreen
import com.example.satchel.ui.screens.UploadScreen
import com.example.satchel.ui.screens.ViewerScreen
import androidx.compose.ui.text.style.TextOverflow

/** Login when logged out; otherwise the four tabs. */
@Composable
fun SatchelApp(navigator: Navigator, onOpenBrowser: (String) -> Unit) {
    val container = LocalContext.current.container
    val auth by container.session.state.collectAsStateWithLifecycle()

    when (val state = auth) {
        AuthState.Checking -> Loading(message = "Signing you in…")
        // Offline at start-up: keep the login, and let the person retry rather than sign in again.
        is AuthState.Unreachable -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.message, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = container.session::restore) { Text("Try again") }
            TextButton(onClick = { container.session.logOut() }) { Text("Sign out") }
        }
        is AuthState.LoggedOut -> {
            // Nothing from the last person's session survives a logout.
            LaunchedEffect(Unit) {
                navigator.reset()
                container.hierarchy.clear()
                container.files.clearCache()
                container.files.forgetDownloads()
            }
            LoginScreen(state.message) { providerId -> onOpenBrowser(container.session.startSso(providerId)) }
        }
        is AuthState.LoggedIn -> {
            LaunchedEffect(state.user.id) {
                container.files.loadDownloads()
                container.reading.flush()   // pages read offline last time
            }
            if (state.user.needsCourseSetup) {
                // Before anything else, a new student says what they study. The screen gets its
                // own ViewModels, cleared when it closes, so the next person starts afresh.
                val owner = remember(state.user.id) { Entry(Route.CourseSetup(again = false)) }
                DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    CourseSetupScreen(state.user, again = false, onDone = {},   // the refreshed user lets the app in
                        onCancel = { container.session.logOut() })
                }
            } else {
                Main(state.user, navigator, onLogOut = { container.session.logOut() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Main(user: User, navigator: Navigator, onLogOut: () -> Unit) {
    val holder = rememberSaveableStateHolder()
    DisposableEffect(navigator) {
        navigator.onDiscard = { holder.removeState(it.key) }
        onDispose { navigator.onDiscard = {} }
    }
    var uploads by rememberSaveable { mutableIntStateOf(0) }   // bumped after each upload
    val entry = navigator.current
    val nested = entry.route !is Route.Library && Tab.entries.none { it.root == entry.route }

    BackHandler(enabled = nested || navigator.tab != Tab.Library) { navigator.back() }

    Scaffold(
        topBar = {
            if (nested) TopAppBar(
                title = {
                    Text(
                        when (val r = entry.route) {
                            is Route.Quiz -> "Practice quiz"
                            is Route.Viewer -> r.title
                            Route.MyCourses -> "My courses"
                            is Route.CourseSetup -> "Your courses"
                            else -> "Document"
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            // The viewer gets the whole screen for the page.
            if (entry.route !is Route.Viewer) NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = navigator.tab == tab,
                        onClick = { navigator.select(tab) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            holder.SaveableStateProvider(entry.key) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                    val open = { id: Int -> navigator.push(Route.Document(id)) }
                    when (val route = entry.route) {
                        Route.Library -> LibraryScreen(onOpenDocument = open,
                            onView = { id, title -> navigator.push(Route.Viewer(id, title)) })
                        is Route.Document -> DocumentScreen(route.id, onOpenDocument = open,
                            onOpenQuiz = { navigator.push(Route.Quiz(it)) },
                            onView = { id, title -> navigator.push(Route.Viewer(id, title)) })
                        is Route.Viewer -> ViewerScreen(route.documentId)
                        is Route.Quiz -> QuizScreen(route.documentId, onBackToDocument = { navigator.back() })
                        Route.Upload -> UploadScreen(user.isStudent, onOpenDocument = open, onUploaded = { uploads++ })
                        Route.MyUploads -> MyUploadsScreen(uploads, onOpenDocument = open)
                        Route.Downloads -> DownloadsScreen(onView = { id, title -> navigator.push(Route.Viewer(id, title)) })
                        Route.Account -> AccountScreen(user, onLogOut, onMyCourses = { navigator.push(Route.MyCourses) })
                        Route.MyCourses -> MyCoursesScreen(onStartAgain = { navigator.push(Route.CourseSetup(again = true)) })
                        is Route.CourseSetup -> CourseSetupScreen(user, again = true,
                            onDone = { navigator.back() }, onCancel = { navigator.back() })
                    }
                }
            }
        }
    }
}
