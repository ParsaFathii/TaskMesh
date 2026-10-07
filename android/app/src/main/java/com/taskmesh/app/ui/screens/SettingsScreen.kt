@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.data.SessionState
import com.taskmesh.app.ui.components.LabeledValue
import com.taskmesh.app.ui.components.SectionHeader
import com.taskmesh.app.ui.theme.TaskMeshEmerald
import com.taskmesh.app.ui.theme.TaskMeshRose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val baseUrl: String = "",
    val notificationsEnabled: Boolean = true,
    val message: String? = null,
)

class SettingsViewModel(
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = session.sessionState

    private val internal = MutableStateFlow(
        SettingsUiState(baseUrl = session.baseUrl, notificationsEnabled = session.notificationsEnabled),
    )
    val uiState: StateFlow<SettingsUiState> = internal.asStateFlow()

    fun updateBaseUrl(value: String) = internal.update { it.copy(baseUrl = value, message = null) }

    fun saveBaseUrl() {
        session.setBaseUrl(internal.value.baseUrl)
        internal.update { it.copy(baseUrl = session.baseUrl, message = "Server address saved: ${session.baseUrl}") }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        session.notificationsEnabled = enabled
        internal.update { it.copy(notificationsEnabled = enabled) }
    }

    fun logout() {
        session.logout()
    }
}

@Composable
fun SettingsScreen() {
    val screenViewModel: SettingsViewModel = viewModel { SettingsViewModel() }
    val state by screenViewModel.uiState.collectAsState()
    val sessionState by screenViewModel.sessionState.collectAsState()
    val user = (sessionState as? SessionState.LoggedIn)?.user

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )

        SectionHeader(title = "Server")
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = screenViewModel::updateBaseUrl,
            label = { Text(text = "API base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = screenViewModel::saveBaseUrl, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Save server address")
        }
        val message = state.message
        if (message != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TaskMeshEmerald,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Default: http://10.0.2.2:8080 (Android emulator → host loopback). " +
                "Release builds only allow HTTPS.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader(title = "Account")
        if (user != null) {
            LabeledValue(label = "Username", value = user.username)
            Spacer(modifier = Modifier.height(8.dp))
            LabeledValue(label = "Role", value = user.role, mono = true)
            Spacer(modifier = Modifier.height(8.dp))
            LabeledValue(label = "User ID", value = user.id, mono = true)
        } else {
            Text(
                text = "Not signed in",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionHeader(title = "Notifications")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "System notifications",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.notificationsEnabled,
                onCheckedChange = screenViewModel::setNotificationsEnabled,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Events are detected by polling the jobs you own every 30 seconds while " +
                "the app is open, and posted as system notifications. TaskMesh 0.1.0 ships " +
                "without a push service by design (no FCM); see README.md.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader(title = "Session")
        Button(
            onClick = screenViewModel::logout,
            colors = ButtonDefaults.buttonColors(containerColor = TaskMeshRose),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Log out")
        }

        SectionHeader(title = "About")
        LabeledValue(label = "Version", value = "0.1.0", mono = true)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "TaskMesh — distributed job processing & worker orchestration",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Copyright © 2026 Parsa Fathi · Apache-2.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(32.dp))
    }
}
