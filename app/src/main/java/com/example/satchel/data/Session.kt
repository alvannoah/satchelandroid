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

    private val _state = MutableStateFlow<AuthState>(if (token == null) AuthState.LoggedOut() else AuthState.Checking)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /** Set by AppContainer, which builds the client with this session's token. */
    lateinit var api: ApiClient

    /** Confirms a saved login is still valid (it lasts 8 hours). */
    fun restore() {
        if (token == null) return
        _state.value = AuthState.Checking
        scope.launch {
            try {
                _state.value = AuthState.LoggedIn(api.me())
            } catch (e: ApiException) {
                if (e.status == 401) logOut("Your login has expired. Please sign in again.")
                else _state.value = AuthState.Unreachable(e.message ?: "Cannot reach the server.")
            }
        }
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
        store.put(TOKEN, null)
        _state.value = AuthState.LoggedOut(message)
    }

    /** Called by the API client on a 401 while logged in. */
    fun expired() {
        if (_state.value is AuthState.LoggedIn) logOut("Your login has expired. Please sign in again.")
    }

    private fun accept(response: TokenResponse) {
        token = response.accessToken
        store.put(TOKEN, response.accessToken)
        _state.value = AuthState.LoggedIn(response.user)
    }

    private companion object {
        const val TOKEN = "token"
        const val VERIFIER = "sso_verifier"
    }
}
