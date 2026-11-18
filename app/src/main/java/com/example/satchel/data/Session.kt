package com.example.satchel.data

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AuthState {
    /** Checking a saved login with the server. */
    data object Checking : AuthState
    data class LoggedOut(val message: String? = null) : AuthState
    data class LoggedIn(val user: User) : AuthState

    /** A saved login could not be checked because the server is unreachable. It is kept for a retry. */
    data class Unreachable(val message: String) : AuthState
}

/** Who is logged in. The token lives in [SecureStore], encrypted, and in memory. */
class Session(private val store: SecureStore, private val scope: CoroutineScope) {
    @Volatile
    var token: String? = store.get(TOKEN)
        private set

    /** The last signed-in user, so the app (and its downloads) can open without the server. */
    private var savedUser: User? = store.get(USER)?.let { runCatching { SatchelJson.decodeFromString<User>(it) }.getOrNull() }

    private val _state = MutableStateFlow<AuthState>(
        when {
            token == null -> AuthState.LoggedOut()
            savedUser != null -> AuthState.LoggedIn(savedUser!!)   // checked with the server in restore()
            else -> AuthState.Checking
        },
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Set by AppContainer, which builds the client with this session's token. */
    lateinit var api: ApiClient

    /**
     * Confirms a saved login is still valid (it lasts 8 hours). Offline, a
     * remembered user stays signed in, so downloaded documents can be read.
     */
    fun restore() {
        if (token == null) return
        if (_state.value !is AuthState.LoggedIn) _state.value = AuthState.Checking
        scope.launch {
            try {
                remember(api.me())
            } catch (e: ApiException) {
                when {
                    e.status == 401 -> logOut("Your login has expired. Please sign in again.")
                    savedUser != null -> {}   // offline: carry on with the remembered user
                    else -> _state.value = AuthState.Unreachable(e.message ?: "Cannot reach the server.")
                }
            }
        }
    }

    private fun remember(user: User) {
        savedUser = user
        store.put(USER, SatchelJson.encodeToString(User.serializer(), user))
        _state.value = AuthState.LoggedIn(user)
    }

    suspend fun logIn(email: String, password: String) = accept(api.login(email.trim(), password))

    suspend fun register(email: String, fullName: String, password: String) =
        accept(api.register(email.trim(), fullName.trim(), password))

    /** Starts signing in with an outside provider: the URL to open in a Custom Tab. */
    fun startSso(providerId: String): String {
        val verifier = Pkce.newVerifier()
        // Kept on disk: Android may stop the app while the browser is open.
        store.put(VERIFIER, verifier)
        return api.ssoStartUrl(providerId, Pkce.challenge(verifier))
    }

    private val ssoLock = Mutex()

    /** Finishes a sign-in from satchel://auth/callback?code=... (or ?sso_error=...). */
    fun finishSso(uri: Uri) {
        scope.launch {
            ssoLock.withLock {
                val error = uri.getQueryParameter("sso_error")
                val code = uri.getQueryParameter("code")
                val verifier = store.get(VERIFIER)
                store.put(VERIFIER, null)   // one use only
                when {
                    error != null -> _state.value = AuthState.LoggedOut(error)
                    code == null || verifier == null ->
                        _state.value = AuthState.LoggedOut("The sign-in expired; please try again.")
                    else -> try {
                        accept(api.appToken(code, verifier))
                    } catch (e: ApiException) {
                        _state.value = AuthState.LoggedOut(e.message)
                    }
                }
            }
        }
    }

    fun logOut(message: String? = null) {
        token = null
        savedUser = null
        store.put(TOKEN, null)
        store.put(USER, null)
        _state.value = AuthState.LoggedOut(message)
    }

    /** Called by the API client on a 401 while logged in. */
    fun expired() {
        if (_state.value is AuthState.LoggedIn) logOut("Your login has expired. Please sign in again.")
    }

    private fun accept(response: TokenResponse) {
        token = response.accessToken
        store.put(TOKEN, response.accessToken)
        remember(response.user)
    }

    private companion object {
        const val TOKEN = "token"
        const val USER = "user"
        const val VERIFIER = "sso_verifier"
    }
}
