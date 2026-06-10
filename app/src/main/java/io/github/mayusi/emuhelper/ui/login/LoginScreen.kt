package io.github.mayusi.emuhelper.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.mayusi.emuhelper.data.source.RemoteSource
import io.github.mayusi.emuhelper.data.source.LoginResult
import io.github.mayusi.emuhelper.data.storage.AuthStore
import io.github.mayusi.emuhelper.ui.common.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val remoteSource: RemoteSource,
    private val authStore: AuthStore
) : ViewModel() {

    data class LoginState(
        val isLoggedIn: Boolean = false, val isLoading: Boolean = false,
        val error: String = "", val savedEmail: String = "", val rememberMe: Boolean = false
    )

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state

    init {
        viewModelScope.launch {
            try {
                val savedEmail = authStore.savedEmail.first()
                val remember = authStore.rememberMe.first()
                if (remoteSource.isLoggedIn()) {
                    _state.value = LoginState(isLoggedIn = true, savedEmail = savedEmail, rememberMe = remember)
                } else if (remember && savedEmail.isNotBlank()) {
                    val savedPassword = authStore.getSavedPassword()
                    if (savedPassword.isBlank()) {
                        _state.value = LoginState(savedEmail = savedEmail, rememberMe = true)
                        return@launch
                    }
                    _state.value = LoginState(isLoading = true, savedEmail = savedEmail, rememberMe = true)
                    when (val r = remoteSource.login(savedEmail, savedPassword)) {
                        is LoginResult.Success -> _state.value = LoginState(isLoggedIn = true, savedEmail = savedEmail, rememberMe = true)
                        is LoginResult.Failed -> _state.value = LoginState(savedEmail = savedEmail, rememberMe = true, error = "Auto-login failed: ${r.message}")
                    }
                } else {
                    _state.value = LoginState(savedEmail = savedEmail, rememberMe = remember)
                }
            } catch (e: Exception) {
                _state.value = LoginState(error = "Startup error: ${e.message}")
            }
        }
    }

    fun login(email: String, password: String, rememberMe: Boolean = false) {
        if (email.isBlank() || password.isBlank()) { _state.value = _state.value.copy(error = "Enter email and password"); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = "")
            try {
                when (val r = remoteSource.login(email, password)) {
                    is LoginResult.Success -> {
                        authStore.saveCredentials(email, password, rememberMe)
                        _state.value = _state.value.copy(isLoggedIn = true, isLoading = false, rememberMe = rememberMe)
                    }
                    is LoginResult.Failed -> _state.value = _state.value.copy(isLoading = false, error = r.message)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = "Login error: ${e.message}")
            }
        }
    }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onSkip: () -> Unit = onLoggedIn, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.isLoggedIn) { if (state.isLoggedIn) onLoggedIn() }

    var email by remember(state.savedEmail) { mutableStateOf(state.savedEmail) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var rememberMe by remember(state.rememberMe) { mutableStateOf(state.rememberMe) }

    Box(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).widthIn(max = 460.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Lock, null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("Account Sign-In", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text("Sign in to access the configured source", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email address") },
                    supportingText = { Text("Case-sensitive  —  matches your account exactly", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    shape = MaterialTheme.shapes.extraSmall
                )
                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    shape = MaterialTheme.shapes.extraSmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
                    Text("Remember me", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (state.error.isNotBlank()) {
                    Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.login(email, password, rememberMe) },
                    enabled = !state.isLoading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(Dimens.ButtonMinHeight),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Log In", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSkip) {
                    Text("Skip login (public access)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
