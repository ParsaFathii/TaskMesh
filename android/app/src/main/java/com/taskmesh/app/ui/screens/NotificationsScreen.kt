@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskmesh.app.ServiceLocator
import com.taskmesh.app.model.TimeFormat
import com.taskmesh.app.notifications.AppEvent
import com.taskmesh.app.notifications.EventKind
import com.taskmesh.app.notifications.NotificationCenter
import com.taskmesh.app.ui.components.EmptyState
import com.taskmesh.app.ui.theme.TaskMeshEmerald
import com.taskmesh.app.ui.theme.TaskMeshRose
import com.taskmesh.app.ui.theme.TaskMeshTextSecondary
import com.taskmesh.app.ui.theme.monoLabel
import com.taskmesh.app.ui.theme.monoBody

class NotificationsViewModel(
    private val center: NotificationCenter = ServiceLocator.notifications,
) : ViewModel() {
    val events = center.events
    fun markAllRead() = center.markAllRead()
    fun clear() = center.clear()
}

@Composable
fun NotificationsScreen() {
    val screenViewModel: NotificationsViewModel = viewModel { NotificationsViewModel() }
    val events by screenViewModel.events.collectAsState()

    // Opening the screen reads everything (the badge resets).
    LaunchedEffect(events.size) {
        if (events.isNotEmpty() && events.any { event -> !event.read }) {
            screenViewModel.markAllRead()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = screenViewModel::markAllRead) {
                Icon(imageVector = Icons.Filled.Check, contentDescription = "Mark all read")
            }
            IconButton(onClick = screenViewModel::clear) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Clear")
            }
        }

        if (events.isEmpty()) {
            EmptyState(
                title = "No notifications",
                subtitle = "Events appear when jobs you own finish, fail, time out or cancel, " +
                    "and when workers go offline — detected by the 30s poll while the app is open.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events, key = { event -> event.id }) { event ->
                    NotificationRow(event = event)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationRow(event: AppEvent) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = event.kind.label,
                style = MaterialTheme.typography.monoLabel,
                color = when (event.kind) {
                    EventKind.JOB_COMPLETED -> TaskMeshEmerald
                    EventKind.JOB_FAILED, EventKind.JOB_TIMED_OUT, EventKind.WORKER_OFFLINE -> TaskMeshRose
                    EventKind.JOB_CANCELLED -> TaskMeshTextSecondary
                },
                modifier = Modifier.width(96.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.padding(top = 2.dp))
                Text(
                    text = event.message,
                    style = MaterialTheme.typography.monoBody,
                    color = TaskMeshTextSecondary,
                )
            }
            Text(
                text = TimeFormat.relativeTo(event.timestamp),
                style = MaterialTheme.typography.monoLabel,
                color = TaskMeshTextSecondary,
            )
        }
    }
}
