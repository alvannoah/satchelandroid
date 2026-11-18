package com.example.satchel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.Provider
import com.example.satchel.data.Session
import com.example.satchel.ui.Notice
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.launch

class LoginViewModel(private val session: Session, private val api: ApiClient) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var providers by mutableStateOf<List<Provider>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            providers = runCatching { api.providers() }.getOrDefault(emptyList())   // the buttons are optional
        }
    }

    fun submit(register: Boolean, email: String, name: String, password: String) {
        error = when {
            email.isBlank() || password.isEmpty() -> "Enter your email and password."
            register && name.isBlank() -> "Enter your full name."
            register && password.length < MIN_PASSWORD -> "Passwords need at least $MIN_PASSWORD characters."
            else -> null
        }
        if (error != null) return
        busy = true
        viewModelScope.launch {
            try {
                if (register) session.register(email, name, password) else session.logIn(email, password)
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    companion object {
        const val MIN_PASSWORD = 8   // the API's MIN_PASSWORD_LENGTH
    }
}

@Composable
fun LoginScreen(message: String?, onOpenBrowser: (String) -> Unit) {
    val vm = screenViewModel { LoginViewModel(it.session, it.api) }
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = { focus.clearFocus(); vm.submit(register, email, name, password) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
            Spacer(Modifier.height(32.dp))
            Text("Satchel", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
            Text("Lecture notes, slides and past papers for your courses, with related material and practice quizzes.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            SecondaryTabRow(selectedTabIndex = if (register) 1 else 0) {
                Tab(selected = !register, onClick = { register = false; vm.error = null }, text = { Text("Sign in") })
                Tab(selected = register, onClick = { register = true; vm.error = null }, text = { Text("Create account") })
            }
            Spacer(Modifier.height(16.dp))

            (vm.error ?: message)?.let {
                Notice(it, kind = "error")
                Spacer(Modifier.height(12.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (register) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Full name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                }
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, label = { Text("Email") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Password") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                    },
                    supportingText = if (register) ({ Text("At least ${LoginViewModel.MIN_PASSWORD} characters") }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Button(onClick = submit, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(if (register) "Create account" else "Sign in")
                }
            }

            if (vm.providers.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(24.dp))
                vm.providers.forEach { provider ->
                    OutlinedButton(
                        onClick = { onOpenBrowser(provider.id) }, enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 0.dp),
                    ) { Text("Continue with ${provider.name}") }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
