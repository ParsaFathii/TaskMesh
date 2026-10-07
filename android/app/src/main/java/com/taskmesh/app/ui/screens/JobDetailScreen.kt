@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.data.ApiErrors
import com.taskmesh.app.data.JobAttemptDto
import com.taskmesh.app.data.JobDto
import com.taskmesh.app.data.JobLogDto
import com.taskmesh.app.data.JobResultView
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.JsonConfig
import com.taskmesh.app.data.SessionRepository
import com.taskmesh.app.model.StatusMapping
import com.taskmesh.app.model.TimeFormat
import com.taskmesh.app.ui.components.EmptyState
import com.taskmesh.app.ui.components.ErrorPanel
import com.taskmesh.app.ui.components.LabeledValue
import com.taskmesh.app.ui.components.SectionHeader
import com.taskmesh.app.ui.components.ShortId
import com.taskmesh.app.ui.components.StatusChip
import com.taskmesh.app.ui.theme.TaskMeshEmerald
import com.taskmesh.app.ui.theme.TaskMeshRose
import com.taskmesh.app.ui.theme.TaskMeshTextSecondary
import com.taskmesh.app.ui.theme.color
import com.taskmesh.app.ui.theme.monoBody
import com.taskmesh.app.ui.theme.monoLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JobDetailUiState(
    val loading: Boolean = true,
    val job: JobDto? = null,
    val attempts: List<JobAttemptDto> = emptyList(),
    val logs: List<JobLogDto> = emptyList(),
    val result: JobResultView? = null,
    val error: String? = null,
    val actionMessage: String? = null,
    val actionBusy: Boolean = false,
)

class JobDetailViewModel(
    private val jobId: String,
    private val session: SessionRepository = ServiceLocator.session,
) : ViewModel() {

    private val internal = MutableStateFlow(JobDetailUiState())
    val uiState: StateFlow<JobDetailUiState> = internal.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            internal.update { it.copy(loading = it.job == null, error = null) }
            session.job(jobId).fold(
                onSuccess = { job ->
                    internal.update { it.copy(job = job, loading = false, error = null) }
                    loadExtras(job)
                },
                onFailure = { throwable ->
                    internal.update { it.copy(loading = false, error = ApiErrors.message(throwable)) }
                },
            )
        }
    }

    private suspend fun loadExtras(job: JobDto) {
        session.attempts(jobId).fold(
            onSuccess = { attempts -> internal.update { it.copy(attempts = attempts) } },
            onFailure = { /* attempt history is optional detail */ },
        )
        session.logs(jobId, limit = 50).fold(
            onSuccess = { logs -> internal.update { it.copy(logs = logs) } },
            onFailure = { /* logs are optional detail */ },
        )
        if (job.status == JobStatus.SUCCEEDED) {
            session.jobResult(jobId).fold(
                onSuccess = { result -> internal.update { it.copy(result = result) } },
                onFailure = { /* result may briefly lag the SUCCEEDED status */ },
            )
        }
    }

    fun cancel() = runAction("Cancel requested") { session.cancelJob(jobId) }

    fun retry() = runAction("Job re-queued") { session.retryJob(jobId) }

    private fun runAction(
        successMessage: String,
        action: suspend () -> Result<JobDto>,
    ) {
        if (internal.value.actionBusy) {
            return
        }
        viewModelScope.launch {
            internal.update { it.copy(actionBusy = true, actionMessage = null) }
            action().fold(
                onSuccess = {
                    internal.update { it.copy(actionBusy = false, actionMessage = successMessage) }
                    load()
                },
                onFailure = { throwable ->
                    internal.update {
                        it.copy(actionBusy = false, actionMessage = ApiErrors.message(throwable))
                    }
                },
            )
        }
    }
}

@Composable
fun JobDetailScreen(jobId: String, onBack: () -> Unit) {
    val screenViewModel: JobDetailViewModel = viewModel(key = "job-$jobId") {
        JobDetailViewModel(jobId)
    }
    val state by screenViewModel.uiState.collectAsState()
    val clipboard = LocalClipboardManager.current

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
            Text(
                text = state.job?.type ?: "Job",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val job = state.job
        val error = state.error
        when {
            state.loading -> Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            job == null && error != null -> ErrorPanel(message = error, onRetry = screenViewModel::load)
            job == null -> EmptyState(title = "Job not available")
            else -> JobDetailContent(
                state = state,
                job = job,
                onCancel = screenViewModel::cancel,
                onRetry = screenViewModel::retry,
                onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun JobDetailContent(
    state: JobDetailUiState,
    job: JobDto,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val payloadText = JsonConfig.renderPretty(job.payload)

    // Header: status chip + priority + short id
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusChip(status = job.status)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = StatusMapping.priorityLabel(job.priority),
            style = MaterialTheme.typography.monoLabel,
            color = TaskMeshTextSecondary,
        )
        Spacer(modifier = Modifier.weight(1f))
        ShortId(id = job.id)
    }
    if (job.status == JobStatus.RUNNING) {
        val progress = job.progress
        Spacer(modifier = Modifier.height(8.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
    if (job.cancelRequested && job.status == JobStatus.RUNNING) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Cancellation requested — waiting for the worker to stop",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    SectionHeader(title = "Timeline")
    Panel {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(label = "Created", value = TimeFormat.display(job.createdAt), mono = true, modifier = Modifier.weight(1f))
            LabeledValue(label = "Queued", value = TimeFormat.display(job.queuedAt), mono = true, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(label = "Started", value = TimeFormat.display(job.startedAt), mono = true, modifier = Modifier.weight(1f))
            LabeledValue(label = "Completed", value = TimeFormat.display(job.completedAt), mono = true, modifier = Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledValue(label = "Retries", value = "${job.retryCount} / ${job.maxRetries}", mono = true, modifier = Modifier.weight(1f))
            LabeledValue(label = "Timeout", value = "${job.timeoutSeconds}s", mono = true, modifier = Modifier.weight(1f))
        }
        val idempotencyKey = job.idempotencyKey
        if (idempotencyKey != null) {
            Spacer(modifier = Modifier.height(8.dp))
            LabeledValue(label = "Idempotency key", value = idempotencyKey, mono = true)
        }
        val lastError = job.lastError
        if (lastError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            LabeledValue(label = "Last error", value = lastError)
        }
    }

    SectionHeader(title = "Worker")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = job.worker?.name ?: "unassigned",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = job.worker?.hostname ?: "",
                style = MaterialTheme.typography.monoLabel,
                color = TaskMeshTextSecondary,
            )
        }
        val workerId = job.workerId
        if (workerId != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "worker", style = MaterialTheme.typography.monoLabel, color = TaskMeshTextSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                ShortId(id = workerId)
                val workerStatus = job.worker?.status
                if (workerStatus != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = StatusMapping.workerLabel(workerStatus),
                        style = MaterialTheme.typography.monoLabel,
                        color = StatusMapping.workerTone(workerStatus).color,
                    )
                }
            }
        }
    }

    SectionHeader(title = "Payload")
    Panel {
        Text(
            text = payloadText,
            style = MaterialTheme.typography.monoBody,
            modifier = Modifier.fillMaxWidth(),
        )
        CopyButton(text = payloadText, onCopy = onCopy)
    }

    if (state.attempts.isNotEmpty()) {
        SectionHeader(title = "Attempts")
        state.attempts.forEach { attempt ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${attempt.attemptNumber}",
                        style = MaterialTheme.typography.monoLabel,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = attempt.outcome ?: "running",
                        style = MaterialTheme.typography.monoLabel,
                        color = if (attempt.outcome == "SUCCEEDED") TaskMeshEmerald else TaskMeshRose,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = TimeFormat.display(attempt.startedAt),
                        style = MaterialTheme.typography.monoLabel,
                        color = TaskMeshTextSecondary,
                    )
                }
                val attemptError = attempt.error
                if (attemptError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = attemptError.take(160),
                        style = MaterialTheme.typography.bodySmall,
                        color = TaskMeshRose,
                    )
                }
            }
        }
    }

    if (state.logs.isNotEmpty()) {
        SectionHeader(title = "Logs (last ${state.logs.size})")
        Panel {
            state.logs.forEachIndexed { index, log ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Row {
                    Text(
                        text = log.level,
                        style = MaterialTheme.typography.monoLabel,
                        color = StatusMapping.logTone(log.level).color,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = log.message,
                        style = MaterialTheme.typography.monoBody,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (job.status == JobStatus.SUCCEEDED) {
        SectionHeader(title = "Result")
        val result = state.result
        when (result) {
            is JobResultView.Inline -> Panel {
                Text(
                    text = result.pretty,
                    style = MaterialTheme.typography.monoBody,
                    modifier = Modifier.fillMaxWidth(),
                )
                CopyButton(text = result.pretty, onCopy = onCopy)
            }
            is JobResultView.FileRef -> Panel {
                LabeledValue(label = "Kind", value = "file", mono = true)
                Spacer(modifier = Modifier.height(8.dp))
                LabeledValue(label = "SHA-256", value = result.sha256 ?: "—", mono = true)
                Spacer(modifier = Modifier.height(8.dp))
                LabeledValue(
                    label = "Size",
                    value = result.sizeBytes?.let { bytes -> "$bytes bytes" } ?: "unknown",
                    mono = true,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "File results are stored server-side; download them from the web client.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskMeshTextSecondary,
                )
            }
            null -> Text(
                text = "No result loaded",
                style = MaterialTheme.typography.bodySmall,
                color = TaskMeshTextSecondary,
            )
        }
    }

    SectionHeader(title = "Actions")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val cancellable = job.status == JobStatus.QUEUED ||
            job.status == JobStatus.RUNNING ||
            job.status == JobStatus.RETRYING
        val retryable = job.status == JobStatus.FAILED ||
            job.status == JobStatus.TIMED_OUT ||
            job.status == JobStatus.CANCELLED
        OutlinedButton(
            onClick = onCancel,
            enabled = cancellable && !state.actionBusy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TaskMeshRose),
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "Cancel")
        }
        Button(
            onClick = onRetry,
            enabled = retryable && !state.actionBusy,
            colors = ButtonDefaults.buttonColors(containerColor = TaskMeshEmerald),
            modifier = Modifier.weight(1f),
        ) {
            Text(text = "Retry")
        }
    }
    val actionMessage = state.actionMessage
    if (actionMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = actionMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CopyButton(text: String, onCopy: (String) -> Unit) {
    var copied by remember { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(8.dp))
    TextButton(onClick = {
        onCopy(text)
        copied = true
    }) {
        Text(text = if (copied) "Copied" else "Copy")
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            content = content,
        )
    }
}
