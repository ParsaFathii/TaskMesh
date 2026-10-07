@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.data.ApiErrors
import com.taskmesh.app.data.ProjectDto
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.model.TimeFormat
import com.taskmesh.app.ui.components.EmptyState
import com.taskmesh.app.ui.components.ErrorPanel
import com.taskmesh.app.ui.components.ShortId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectsUiState(
    val loading: Boolean = true,
    val projects: List<ProjectDto> = emptyList(),
    val error: String? = null,
    val creating: Boolean = false,
    val createError: String? = null,
)

class ProjectsViewModel(
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    private val internal = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = internal.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            internal.update { it.copy(loading = it.projects.isEmpty(), error = null) }
            session.projects(page = 0, size = 100).fold(
                onSuccess = { page ->
                    internal.update { it.copy(projects = page.items, loading = false, error = null) }
                },
                onFailure = { throwable ->
                    internal.update { it.copy(loading = false, error = ApiErrors.message(throwable)) }
                },
            )
        }
    }

    /** POST /api/v1/projects {name, description} (SPEC §8; max 200/2000 chars). */
    fun createProject(name: String, description: String, onCreated: () -> Unit) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || trimmedName.length > MAX_NAME_LENGTH) {
            internal.update { it.copy(createError = "Project name must be 1-$MAX_NAME_LENGTH characters") }
            return
        }
        if (description.length > MAX_DESCRIPTION_LENGTH) {
            internal.update { it.copy(createError = "Description too long (max $MAX_DESCRIPTION_LENGTH characters)") }
            return
        }
        viewModelScope.launch {
            internal.update { it.copy(creating = true, createError = null) }
            session.createProject(trimmedName, description.trim()).fold(
                onSuccess = {
                    internal.update { it.copy(creating = false, createError = null) }
                    load()
                    onCreated()
                },
                onFailure = { throwable ->
                    internal.update { it.copy(creating = false, createError = ApiErrors.message(throwable)) }
                },
            )
        }
    }

    fun clearCreateError() = internal.update { it.copy(createError = null) }

    companion object {
        const val MAX_NAME_LENGTH = 200
        const val MAX_DESCRIPTION_LENGTH = 2000
    }
}

@Composable
fun ProjectsScreen(onProjectClick: (String) -> Unit) {
    val screenViewModel: ProjectsViewModel = viewModel { ProjectsViewModel() }
    val state by screenViewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = "Projects",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "New project")
            }
            IconButton(onClick = screenViewModel::load) {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        val error = state.error
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            error != null && state.projects.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                ErrorPanel(message = error, onRetry = screenViewModel::load)
            }
            state.projects.isEmpty() -> EmptyState(
                title = "No projects",
                subtitle = "Projects group related jobs. The API filters them to the ones you own.",
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.projects, key = { project -> project.id }) { project ->
                    ProjectCard(project = project, onClick = { onProjectClick(project.id) })
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            state = state,
            onDismiss = {
                showCreateDialog = false
                screenViewModel.clearCreateError()
            },
            onCreate = { name, description ->
                screenViewModel.createProject(name, description) {
                    showCreateDialog = false
                }
            },
        )
    }
}

@Composable
private fun CreateProjectDialog(
    state: ProjectsUiState,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val createError = state.createError
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "New project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = "Name (1-200 characters)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(text = "Description (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (createError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = createError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description) },
                enabled = !state.creating && name.isNotBlank(),
            ) {
                if (state.creating) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(text = "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.creating) {
                Text(text = "Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCard(project: ProjectDto, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (project.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = project.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(text = "owner", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.padding(6.dp))
                ShortId(id = project.ownerId)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = TimeFormat.relative(project.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
