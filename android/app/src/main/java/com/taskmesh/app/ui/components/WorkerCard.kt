package com.taskmesh.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskmesh.app.data.WorkerDto
import com.taskmesh.app.model.TimeFormat
import com.taskmesh.app.ui.theme.TaskMeshRose
import com.taskmesh.app.ui.theme.TaskMeshTextSecondary
import com.taskmesh.app.ui.theme.monoBody
import com.taskmesh.app.ui.theme.monoLabel

/**
 * Worker health card: identity, capabilities, live status and heartbeat age.
 * `stale == true` is surfaced prominently (backend-computed, SPEC §8).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerCard(worker: WorkerDto, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkerStatusDot(status = worker.status)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = worker.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${worker.hostname} · v${worker.version}",
                style = MaterialTheme.typography.monoLabel,
                color = TaskMeshTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (worker.capabilities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = worker.capabilities.joinToString(" · "),
                    style = MaterialTheme.typography.monoLabel,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkerStatusChip(status = worker.status, modifier = Modifier.weight(1f))
                Text(
                    text = "hb " + TimeFormat.relative(worker.lastHeartbeat),
                    style = MaterialTheme.typography.monoLabel,
                    color = TaskMeshTextSecondary,
                )
            }
            if (worker.stale) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "STALE — heartbeat overdue",
                    style = MaterialTheme.typography.monoLabel,
                    color = TaskMeshRose,
                )
            }
            val currentJobId = worker.currentJobId
            if (currentJobId != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "job",
                        style = MaterialTheme.typography.monoLabel,
                        color = TaskMeshTextSecondary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ShortId(id = currentJobId)
                }
            }
        }
    }
}
