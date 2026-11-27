package com.example.satchel

import android.app.Application
import android.content.Context
import kotlinx.coroutines.withContext
import com.example.satchel.data.Institution
import com.example.satchel.data.ApiException
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.BitmapFactory
import com.example.satchel.data.ApiClient
import com.example.satchel.data.AuthState
import com.example.satchel.data.DocumentFiles
import com.example.satchel.data.ReadingSync
import com.example.satchel.data.Push
import com.example.satchel.data.Inbox
import com.example.satchel.data.Hierarchy
import com.example.satchel.data.SecureStore
import com.example.satchel.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The app's shared objects, made once. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = Session(SecureStore(context), scope)
    val api = ApiClient(BuildConfig.API_URL, token = { session.token }, onUnauthorized = session::expired)
    val hierarchy = HierarchyCache(api)
    val prefs = Prefs(context)
    val institution = InstitutionCache(api)

    /** Bumped when the student's courses change, so lists that depend on them reload. */
    val coursesChanged = MutableStateFlow(0)

    val files = DocumentFiles(context, api)
    val reading = ReadingSync(context, api, scope)
    val push = Push(context)
    val inbox = Inbox(api, scope)

    init {
        session.api = api
        files.owner = { (session.state.value as? AuthState.LoggedIn)?.user?.id }
        reading.owner = files.owner

        // Push: the server learns this phone's token after every sign-in and whenever it
        // changes, and forgets it on sign-out (docs/ANDROID_APP.md, "Push notifications").
        push.onToken = { registerPushToken() }
        scope.launch {
            session.state.map { (it as? AuthState.LoggedIn)?.user?.id }.distinctUntilChanged().collect { id ->
                if (id != null) registerPushToken()
            }
        }
        session.beforeLogOut = { bearer ->
            push.token?.let { pushToken -> scope.launch { runCatching { api.unregisterDevice(pushToken, bearer) } } }
        }
    }
}

/** Optional: offline, or a server without push, simply means no pushes until next time. */
private fun AppContainer.registerPushToken() {
    val pushToken = push.token ?: return            // arrives later through Push.onToken
    if (session.state.value !is AuthState.LoggedIn) return
    scope.launch { runCatching { api.registerDevice(pushToken) } }
}

/** Display choices remembered on this phone. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("satchel.prefs", Context.MODE_PRIVATE)

    var askedForNotifications: Boolean
        get() = prefs.getBoolean("notifications.asked", false)
        set(value) = prefs.edit().putBoolean("notifications.asked", value).apply()

    var libraryLayout: String?
        get() = prefs.getString("library.layout", null)
        set(value) = prefs.edit().putString("library.layout", value).apply()
}

/** The institution's name, rules and logo, fetched once; null from servers that have none. */
class InstitutionCache(private val api: ApiClient) {
    private val lock = Mutex()
    private var loaded = false
    private var value: Institution? = null
    private var logo: ImageBitmap? = null

    suspend fun get(): Institution? = lock.withLock {
        if (!loaded) {
            value = try {
                api.institution()
            } catch (e: ApiException) {
                if (e.status == 0) return@withLock null   // offline: try again next time
                null                                        // an older server without it
            }
            loaded = true
        }
        value
    }

    suspend fun logo(): ImageBitmap? {
        logo?.let { return it }
        val url = get()?.logoUrl ?: return null
        return runCatching {
            val bytes = api.image(url)
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }.getOrNull()?.also { logo = it }
    }
}

/** The course hierarchy changes rarely; fetch it once and reuse it across screens. */
class HierarchyCache(private val api: ApiClient) {
    private val lock = Mutex()
    private val _value = MutableStateFlow<Hierarchy?>(null)
    val value: StateFlow<Hierarchy?> = _value

    suspend fun get(refresh: Boolean = false): Hierarchy = lock.withLock {
        _value.value?.takeUnless { refresh } ?: api.hierarchy().also { _value.value = it }
    }

    fun clear() {
        _value.value = null
    }
}

class SatchelApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.session.restore()
        container.push.createChannel()
        container.push.refreshToken()
    }
}

val Context.container: AppContainer get() = (applicationContext as SatchelApplication).container
