@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.R
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.data.ApiErrors
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.ui.components.ErrorPanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val baseUrl: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    private val internal = MutableStateFlow(LoginUiState(baseUrl = session.baseUrl))
    val uiState: StateFlow<LoginUiState> = internal.asStateFlow()

    fun updateUsername(value: String) = internal.update { it.copy(username = value) }

    fun updatePassword(value: String) = internal.update { it.copy(password = value) }

    fun updateBaseUrl(value: String) = internal.update { it.copy(baseUrl = value) }

    fun login(onSuccess: () -> Unit) {
        val state = internal.value
        if (state.loading) {
            return
        }
        internal.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            session.login(
                username = state.username.trim(),
                password = state.password,
                baseUrlOverride = state.baseUrl.takeIf { it.isNotBlank() },
            ).fold(
                onSuccess = {
                    internal.update { it.copy(loading = false, error = null) }
                    onSuccess()
                },
                onFailure = { throwable ->
                    internal.update { it.copy(loading = false, error = ApiErrors.message(throwable)) }
                },
            )
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val screenViewModel: LoginViewModel = viewModel { LoginViewModel() }
    val state by screenViewModel.uiState.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_taskmesh_logo),
            contentDescription = "TaskMesh logo",
            modifier = Modifier.size(72.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "TaskMesh", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Distributed job processing & worker orchestration",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedTextField(
            value = state.username,
            onValueChange = screenViewModel::updateUsername,
            label = { Text(text = "Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = screenViewModel::updatePassword,
            label = { Text(text = "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = screenViewModel::updateBaseUrl,
            label = { Text(text = "Server URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        val error = state.error
        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            ErrorPanel(message = error)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { screenViewModel.login(onSuccess = onLoginSuccess) },
            enabled = !state.loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(text = "Sign in")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Default server: http://10.0.2.2:8080 (emulator host loopback)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
