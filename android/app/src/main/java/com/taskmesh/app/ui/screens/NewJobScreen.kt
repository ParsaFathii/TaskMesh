@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.data.ApiErrors
import com.taskmesh.app.data.JobCreateRequest
import com.taskmesh.app.data.JobPriority
import com.taskmesh.app.data.JsonConfig
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.ui.components.ErrorPanel
import com.taskmesh.app.ui.components.SectionHeader
import com.taskmesh.app.ui.theme.monoBody
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewJobUiState(
    val loading: Boolean = true,
    val projects: List<com.taskmesh.app.data.ProjectDto> = emptyList(),
    val types: List<String> = emptyList(),
    val typesFromCatalog: Boolean = false,
    val projectId: String? = null,
    val type: String? = null,
    val priority: JobPriority = JobPriority.NORMAL,
    val payload: String = "",
    val timeoutSeconds: String = "",
    val maxRetries: String = "",
    val idempotencyKey: String = "",
    val error: String? = null,
    val submitting: Boolean = false,
)

class NewJobViewModel(
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    private val internal = MutableStateFlow(NewJobUiState())
    val uiState: StateFlow<NewJobUiState> = internal.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val projects = session.projects(page = 0, size = 100)
                .fold(onSuccess = { it.items }, onFailure = { emptyList() })
            val catalog = session.jobTypes()
                .fold(onSuccess = { types -> types.mapNotNull { it.typeName.ifBlank { null } } }, onFailure = { emptyList() })
            internal.update { state ->
                state.copy(
                    loading = false,
                    projects = projects,
                    types = if (catalog.isNotEmpty()) catalog else FALLBACK_TYPES,
                    typesFromCatalog = catalog.isNotEmpty(),
                    projectId = state.projectId ?: projects.firstOrNull()?.id,
                )
            }
        }
    }

    fun setProject(projectId: String?) = internal.update { it.copy(projectId = projectId) }

    fun setType(type: String) = internal.update { it.copy(type = type) }

    fun setPriority(priority: JobPriority) = internal.update { it.copy(priority = priority) }

    fun updatePayload(value: String) = internal.update { it.copy(payload = value) }

    fun updateTimeout(value: String) = internal.update { it.copy(timeoutSeconds = value.filter { c -> c.isDigit() }) }

    fun updateMaxRetries(value: String) = internal.update { it.copy(maxRetries = value.filter { c -> c.isDigit() }) }

    fun updateIdempotencyKey(value: String) = internal.update { it.copy(idempotencyKey = value) }

    fun submit(onCreated: () -> Unit) {
        val state = internal.value
        if (state.submitting) {
            return
        }
        val project = state.projectId
        if (project == null) {
            internal.update { it.copy(error = "Select a project") }
            return
        }
        val type = state.type
        if (type == null) {
            internal.update { it.copy(error = "Select a job type") }
            return
        }
        val payload = JsonConfig.parseElement(state.payload)
        if (payload == null) {
            internal.update { it.copy(error = "Payload must be valid JSON") }
            return
        }
        val timeout = state.timeoutSeconds.trim().toIntOrNull()
        if (timeout != null && timeout !in 5..3600) {
            internal.update { it.copy(error = "Timeout must be between 5 and 3600 seconds") }
            return
        }
        val retries = state.maxRetries.trim().toIntOrNull()
        if (retries != null && retries !in 0..10) {
            internal.update { it.copy(error = "Max retries must be between 0 and 10") }
            return
        }
        internal.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            session.createJob(
                JobCreateRequest(
                    projectId = project,
                    type = type,
                    priority = state.priority,
                    payload = payload,
                    maxRetries = retries,
                    timeoutSeconds = timeout,
                    idempotencyKey = state.idempotencyKey.trim().ifBlank { null },
                ),
            ).fold(
                onSuccess = {
                    internal.update { it.copy(submitting = false) }
                    onCreated()
                },
                onFailure = { throwable ->
                    internal.update { it.copy(submitting = false, error = ApiErrors.message(throwable)) }
                },
            )
        }
    }

    companion object {
        /** SPEC §9 job type catalog, used when GET /api/v1/job-types is unavailable. */
        val FALLBACK_TYPES = listOf(
            "csv_analysis",
            "json_transform",
            "image_resize",
            "hash_sha256",
            "text_statistics",
            "archive_inspection",
            "cpu_benchmark",
        )
    }
}

@Composable
fun NewJobScreen(onBack: () -> Unit, onCreated: () -> Unit) {
    val screenViewModel: NewJobViewModel = viewModel { NewJobViewModel() }
    val state by screenViewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(text = "New job", style = MaterialTheme.typography.headlineSmall)
        }

        if (state.loading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        SectionHeader(title = "Project")
        if (state.projects.isEmpty()) {
            Text(
                text = "No projects available — create one in the web client first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ChipFlowRow(options = state.projects.map { it.id to it.name }, selectedKey = state.projectId) { key ->
                screenViewModel.setProject(key)
            }
        }

        SectionHeader(title = "Type")
        if (!state.typesFromCatalog) {
            Text(
                text = "Type catalog unavailable — using SPEC §9 defaults",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChipFlowRow(options = state.types.map { it to it }, selectedKey = state.type) { key ->
            screenViewModel.setType(key)
        }

        SectionHeader(title = "Priority")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JobPriority.entries.forEach { priority ->
                FilterChip(
                    selected = state.priority == priority,
                    onClick = { screenViewModel.setPriority(priority) },
                    label = { Text(text = priority.name) },
                )
            }
        }

        SectionHeader(title = "Payload (JSON)")
        OutlinedTextField(
            value = state.payload,
            onValueChange = screenViewModel::updatePayload,
            placeholder = { Text(text = """{"text": "hello taskmesh"}""") },
            textStyle = MaterialTheme.typography.monoBody,
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader(title = "Options")
        OutlinedTextField(
            value = state.timeoutSeconds,
            onValueChange = screenViewModel::updateTimeout,
            label = { Text(text = "Timeout seconds (5-3600)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.maxRetries,
            onValueChange = screenViewModel::updateMaxRetries,
            label = { Text(text = "Max retries (0-10)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.idempotencyKey,
            onValueChange = screenViewModel::updateIdempotencyKey,
            label = { Text(text = "Idempotency key (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val error = state.error
        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            ErrorPanel(message = error)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = { screenViewModel.submit(onCreated = onCreated) },
            enabled = !state.submitting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            if (state.submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(text = "Submit job")
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** Horizontally scrolling single-select chip row for (key, label) pairs. */
@Composable
private fun ChipFlowRow(
    options: List<Pair<String, String>>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { (key, label) ->
            FilterChip(
                selected = selectedKey == key,
                onClick = { onSelect(key) },
                label = { Text(text = label, maxLines = 1) },
            )
        }
    }
}
