package com.example.satchel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.satchel.BuildConfig
import com.example.satchel.data.ApiClient
import com.example.satchel.data.ApiException
import com.example.satchel.data.User
import com.example.satchel.ui.Notice
import com.example.satchel.ui.screenViewModel
import kotlinx.coroutines.launch

class AccountViewModel(private val api: ApiClient) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var changed by mutableStateOf(false)

    fun changePassword(current: String, new: String, repeat: String, done: () -> Unit) {
        error = when {
            current.isEmpty() -> "Enter your current password."
            new.length < LoginViewModel.MIN_PASSWORD -> "New passwords need at least ${LoginViewModel.MIN_PASSWORD} characters."
            new != repeat -> "The new passwords don’t match."
            else -> null
        }
        if (error != null) return
        busy = true
        viewModelScope.launch {
            try {
                api.changePassword(current, new)
                changed = true
                done()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun AccountScreen(user: User, onLogOut: () -> Unit, onMyCourses: () -> Unit) {
    val vm = screenViewModel { AccountViewModel(it.api) }
    var changing by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Account", style = MaterialTheme.typography.headlineSmall)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(user.fullName, style = MaterialTheme.typography.titleMedium)
                Text(user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(user.role.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (user.role != "student") {
            Notice("Moderation and administration are on the Satchel website.")
        }
        if (vm.changed) Notice("Your password has been changed.", kind = "ok")
        if (user.isStudent) {
            OutlinedButton(onClick = onMyCourses, modifier = Modifier.fillMaxWidth()) { Text("My courses") }
        }
        // Accounts that only sign in with Google have no Satchel password to change.
        if (user.hasPassword) {
            OutlinedButton(onClick = { vm.error = null; changing = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Change password")
            }
        }
        Button(onClick = onLogOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        Text("Server: ${BuildConfig.API_URL}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (changing) ChangePasswordDialog(vm, onDismiss = { changing = false })
}

@Composable
private fun ChangePasswordDialog(vm: AccountViewModel, onDismiss: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!vm.busy) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple("Current password", current) { v: String -> current = v },
                    Triple("New password", new) { v: String -> new = v },
                    Triple("Repeat new password", repeat) { v: String -> repeat = v },
                ).forEach { (label, value, set) ->
                    OutlinedTextField(
                        value = value, onValueChange = set, label = { Text(label) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                vm.error?.let { Notice(it, kind = "error") }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.changePassword(current, new, repeat, onDismiss) }, enabled = !vm.busy) {
                Text(if (vm.busy) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !vm.busy) { Text("Cancel") } },
    )
}
