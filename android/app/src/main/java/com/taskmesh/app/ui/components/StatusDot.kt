package com.taskmesh.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.taskmesh.app.data.JobStatus
import com.taskmesh.app.data.WorkerStatus
import com.taskmesh.app.model.StatusMapping
import com.taskmesh.app.ui.theme.color
import com.taskmesh.app.ui.theme.monoLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusDot(status: JobStatus, modifier: Modifier = Modifier) {
    PulsingDot(
        color = StatusMapping.tone(status).color,
        pulsing = status == JobStatus.RUNNING,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerStatusDot(status: WorkerStatus, modifier: Modifier = Modifier) {
    PulsingDot(
        color = StatusMapping.workerTone(status).color,
        pulsing = status == WorkerStatus.BUSY,
        modifier = modifier,
    )
}

/** Rounded pill with a small dot + monospace status label (jobs). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusChip(status: JobStatus, modifier: Modifier = Modifier) {
    val tone = StatusMapping.tone(status)
    StatusChipSurface(
        color = tone.color,
        label = StatusMapping.label(status),
        modifier = modifier,
    )
}

/** Rounded pill with a small dot + monospace status label (workers). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkerStatusChip(status: WorkerStatus, modifier: Modifier = Modifier) {
    val tone = StatusMapping.workerTone(status)
    StatusChipSurface(
        color = tone.color,
        label = StatusMapping.workerLabel(status),
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusChipSurface(color: Color, label: String, modifier: Modifier = Modifier) {
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.monoLabel)
        }
    }
}
