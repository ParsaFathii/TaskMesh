package com.taskmesh.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.JobDto
import com.taskmesh.app.model.StatusMapping
import com.taskmesh.app.model.TimeFormat
import com.taskmesh.app.ui.theme.TaskMeshRose
import com.taskmesh.app.ui.theme.TaskMeshTextSecondary
import com.taskmesh.app.ui.theme.color
import com.taskmesh.app.ui.theme.monoLabel

/** One job in the jobs list: type, status, priority, age, progress, last error. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobRow(job: JobDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status = job.status)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = job.type,
                    style = MaterialTheme.typography.monoLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = StatusMapping.label(job.status),
                    style = MaterialTheme.typography.monoLabel,
                    color = StatusMapping.tone(job.status).color,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShortId(id = job.id)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = StatusMapping.priorityLabel(job.priority),
                    style = MaterialTheme.typography.monoLabel,
                    color = TaskMeshTextSecondary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = TimeFormat.relative(job.createdAt),
                    style = MaterialTheme.typography.monoLabel,
                    color = TaskMeshTextSecondary,
                )
            }
            if (job.status == JobStatus.RUNNING) {
                val progress = job.progress
                if (progress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val lastError = job.lastError
            if (lastError != null && job.status == JobStatus.FAILED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = lastError.take(120),
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskMeshRose,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
