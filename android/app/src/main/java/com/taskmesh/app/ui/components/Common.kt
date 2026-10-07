@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmesh.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskmesh.app.ui.theme.TaskMeshRose
import com.taskmesh.app.ui.theme.TaskMeshTextSecondary
import com.taskmesh.app.ui.theme.monoBody
import com.taskmesh.app.ui.theme.monoLabel

private val DOT_SIZE = 10.dp

/** Uppercase amber section header used across detail screens. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

/** Compact monospace id: first 8 characters of a UUID. */
@Composable
fun ShortId(id: String?, modifier: Modifier = Modifier) {
    val short = when {
        id == null -> "—"
        id.length >= 8 -> id.take(8)
        else -> id
    }
    Text(
        text = short,
        style = MaterialTheme.typography.monoLabel,
        color = TaskMeshTextSecondary,
        modifier = modifier,
    )
}

/** Label + value pair; monospace value for ids/timestamps/enums. */
@Composable
fun LabeledValue(
    label: String,
    value: String,
    mono: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TaskMeshTextSecondary,
        )
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.monoBody else MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Centered empty state. */
@Composable
fun EmptyState(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TaskMeshTextSecondary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

/** Rose error panel with optional retry action. */
@Composable
fun ErrorPanel(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        color = TaskMeshRose.copy(alpha = 0.10f),
        contentColor = TaskMeshRose,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TaskMeshRose,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = TaskMeshRose),
                ) {
                    Text(text = "Retry")
                }
            }
        }
    }
}

/** Pulsing status dot; RUNNING jobs and BUSY workers animate. */
@Composable
fun PulsingDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val dotModifier = modifier.size(DOT_SIZE)
    if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulseAlpha",
        )
        Box(modifier = dotModifier.background(color.copy(alpha = alpha), CircleShape))
    } else {
        Box(modifier = dotModifier.background(color, CircleShape))
    }
}
