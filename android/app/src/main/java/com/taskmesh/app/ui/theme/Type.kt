package com.taskmesh.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/**
 * Default Material3 typography with monospace accessors: the product identity
 * renders ids, timestamps, statuses, types and payloads in monospace.
 */
val TaskMeshTypography: Typography = Typography()

/** Small monospace style — ids, statuses, chips, timestamps. */
val Typography.monoLabel: TextStyle
    get() = labelSmall.copy(fontFamily = FontFamily.Monospace)

/** Body-size monospace style — payloads, results, log lines. */
val Typography.monoBody: TextStyle
    get() = bodySmall.copy(fontFamily = FontFamily.Monospace)
