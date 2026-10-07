package com.taskmesh.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TaskMeshDarkColors = darkColorScheme(
    primary = TaskMeshAmber,
    onPrimary = TaskMeshBackground,
    primaryContainer = TaskMeshPanelRaised,
    onPrimaryContainer = TaskMeshTextPrimary,
    secondary = TaskMeshEmerald,
    onSecondary = Color(0xFF0B1418),
    secondaryContainer = TaskMeshPanelRaised,
    onSecondaryContainer = TaskMeshTextPrimary,
    tertiary = TaskMeshAmber,
    onTertiary = TaskMeshBackground,
    background = TaskMeshBackground,
    onBackground = TaskMeshTextPrimary,
    surface = TaskMeshBackground,
    onSurface = TaskMeshTextPrimary,
    surfaceVariant = TaskMeshPanel,
    onSurfaceVariant = TaskMeshTextSecondary,
    outline = TaskMeshOutline,
    outlineVariant = TaskMeshOutline,
    error = TaskMeshRose,
    onError = Color(0xFF1B0F0E),
)

/**
 * TaskMesh is a dark-only product identity (graphite console look),
 * so a single dark scheme is applied unconditionally.
 */
@Composable
fun TaskMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TaskMeshDarkColors,
        typography = TaskMeshTypography,
        content = content,
    )
}
