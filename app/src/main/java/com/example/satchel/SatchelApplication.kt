package com.example.satchel

import android.app.Application
import android.content.Context
import com.example.satchel.data.ApiClient
import com.example.satchel.data.AuthState
import com.example.satchel.data.DocumentFiles
import com.example.satchel.data.ReadingSync
import com.example.satchel.data.Hierarchy
import com.example.satchel.data.SecureStore
import com.example.satchel.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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

    val files = DocumentFiles(context, api)
    val reading = ReadingSync(context, api, scope)

    init {
        session.api = api
        files.owner = { (session.state.value as? AuthState.LoggedIn)?.user?.id }
        reading.owner = files.owner
    }
}

/** Display choices remembered on this phone. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("satchel.prefs", Context.MODE_PRIVATE)

    var libraryLayout: String?
        get() = prefs.getString("library.layout", null)
        set(value) = prefs.edit().putString("library.layout", value).apply()
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
    }
}

val Context.container: AppContainer get() = (applicationContext as SatchelApplication).container
